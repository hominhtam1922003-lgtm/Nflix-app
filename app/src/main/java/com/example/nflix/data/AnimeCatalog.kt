package com.example.nflix.data

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.CobeSource
import com.example.nflix.data.source.HhKungfuSource
import kotlinx.coroutines.flow.first
import java.text.Normalizer

/**
 * 1.2.0 (Khối B): the Anime tab's catalog rules over Cobe's animation
 * pool, shared by the family tab, the Top chart and Detail's related
 * shelf so every surface applies the SAME law the user set:
 *
 * - Keep animated MOVIES of any origin (incl. Chinese theatrical) and
 *   anime series.
 * - Drop donghua SERIES that already live in the Donghua tab — decided
 *   by slug against the HHKungfu catalog (Cobe records carry no country
 *   field, so the Donghua catalog itself is the reference set).
 * - Dedupe the pool's duplicate records (one-piece vs dao-hai-tac: same
 *   show, two slugs, both at the live episode count).
 */
class AnimeCatalog(
    private val cobe: CobeSource,
    private val hhKungfu: HhKungfuSource,
    private val settings: SettingsStore
) {
    @Volatile
    var donghuaSlugs: Set<String> = emptySet()
        private set

    @Volatile
    private var poolLoaded = false

    /** True once the exclusion set is at least warm (cache or fresh). */
    fun poolReady(): Boolean = poolLoaded

    /**
     * Load/refresh the Donghua slug pool: the persisted cache warms it
     * instantly, then a fresh union of the Donghua tab's own lists
     * (latest + completed + trending) replaces it and is re-persisted.
     */
    suspend fun warmDonghuaSlugs(): Set<String> {
        if (donghuaSlugs.isEmpty()) {
            val cached = runCatching { settings.donghuaSlugs.first() }.getOrNull().orEmpty()
            if (cached.isNotBlank()) {
                donghuaSlugs = cached.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (donghuaSlugs.isNotEmpty()) poolLoaded = true
            }
        }
        val fresh = mutableSetOf<String>()
        suspend fun harvest(block: suspend () -> List<TitleItem>) {
            runCatching { block() }.getOrNull()?.forEach { fresh.add(it.slug) }
        }
        for (p in 1..8) harvest { hhKungfu.latest(p) }
        for (p in 1..3) harvest { hhKungfu.completed(p) }
        harvest { hhKungfu.trending() }
        if (fresh.isNotEmpty()) {
            donghuaSlugs = fresh
            poolLoaded = true
            runCatching { settings.setDonghuaSlugs(fresh) }
        }
        return donghuaSlugs
    }

    /** One raw pool page from Cobe (32 records + total page count). */
    suspend fun poolPage(page: Int): Pair<List<TitleItem>, Int> = cobe.animePoolPage(page)

    /** Apply the tab law to raw pool records: exclude donghua series, dedupe. */
    fun visible(items: List<TitleItem>): List<TitleItem> {
        val kept = items.filter { item ->
            // Only series can be donghua-tab duplicates; animated movies
            // are kept whatever their origin (user rule).
            !(isSeriesRecord(item) && item.slug in donghuaSlugs)
        }
        return dedupeTitles(kept)
    }

    private fun isSeriesRecord(item: TitleItem): Boolean =
        // Cobe movies carry episodeCount 0 and a "Full" latest label.
        !(item.episodeCount == 0 && item.latestLabel.equals("Full", true))

    /**
     * Pool dedupe: same show under two slugs (one-piece / dao-hai-tac).
     * Key = normalized origin name (falls back to the display name);
     * the fuller / fresher record wins.
     */
    fun dedupeTitles(items: List<TitleItem>): List<TitleItem> {
        val byKey = LinkedHashMap<String, TitleItem>()
        for (item in items) {
            val key = normalizeTitle(item.originName.ifBlank { item.name })
            if (key.isBlank()) {
                byKey[item.id] = item
                continue
            }
            val cur = byKey[key]
            if (cur == null ||
                item.episodeCount > cur.episodeCount ||
                (item.episodeCount == cur.episodeCount && item.updatedEpochSec > cur.updatedEpochSec)
            ) {
                byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    companion object {
        /** Lowercase, diacritic-stripped, punctuation-collapsed title key. */
        fun normalizeTitle(raw: String): String {
            val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
            val noMarks = nfd.replace(Regex("\\p{Mn}+"), "")
            return noMarks.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }
}

/** Family check used by pool consumers (records arrive as ANIME already). */
fun TitleItem.isAnimePoolRecord(): Boolean = family == Family.ANIME
