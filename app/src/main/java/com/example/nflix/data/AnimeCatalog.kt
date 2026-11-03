package com.example.nflix.data

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.HhKungfuSource
import com.example.nflix.data.source.PhimCobeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The Anime tab's catalog rules, shared by the family tab, the Top
 * chart and Detail's related shelf so every surface applies the SAME
 * law the user set:
 *
 * - Drop donghua SERIES that already live in the Donghua tab — decided
 *   by slug against the HHKungfu catalog.
 * - Dedupe the pool's duplicate records (one-piece vs dao-hai-tac:
 *   same show, two slugs, both at the live episode count).
 *
 * Country law (user, 2026-11-01 19:04): NO western animation at all.
 * Keep only (a) Japanese anime SERIES and (b) Asian animated MOVIES
 * (JP/KR/CN/TW).
 *
 * 1.3.5: the source is PhimCobe, whose records CARRY their countries
 * (the Cobe records never did). The gate is therefore per-record —
 * the 453-page country slug-pool harvest of 1.2.x, its persistence
 * and its warming state are all gone. Default-deny stands: a record
 * whose countries are unknown fails the gate, exactly as an
 * unclassified record did under the pool regime.
 */
class AnimeCatalog(
    private val phimcobe: PhimCobeSource,
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

    /** One raw pool page from the source (10 records + total page count). */
    suspend fun poolPage(page: Int): Pair<List<TitleItem>, Int> = phimcobe.animePoolPage(page)

    /**
     * Apply the tab law to raw pool records: donghua-series exclusion,
     * the per-record country gate, then dedupe. Series must be
     * Japanese; movies must be from the Asian set; a record with no
     * countries on file fails both (default-deny).
     */
    fun visible(items: List<TitleItem>): List<TitleItem> {
        val kept = items.filter { item ->
            if (isSeriesRecord(item)) {
                item.slug !in donghuaSlugs && COUNTRY_JP in item.countrySlugs
            } else {
                item.countrySlugs.any { it in COUNTRIES }
            }
        }
        return dedupeTitles(kept)
    }

    private fun isSeriesRecord(item: TitleItem): Boolean =
        // Movies carry episodeCount 0 and a "Full" latest label.
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

    // ------------------------------------------------------------------
    // Country pools — RETIRED in 1.3.5. PhimCobe records carry their
    // own countries, so there is nothing to harvest. These entry
    // points remain as instant no-ops because the tab's first-run UI
    // still asks them whether the gate is ready; the honest answer
    // is now "immediately, per record".
    // ------------------------------------------------------------------

    /** True — the gate needs no pools anymore (kept for the tab's UI). */
    fun countryPoolsReady(): Boolean = true

    /** No-op since 1.3.5 (see the retirement note above). */
    suspend fun ensureCountryPoolsLoaded() = Unit

    /** No-op since 1.3.5: nothing warms, [onGrew] never fires. */
    suspend fun warmCountryPools(onGrew: () -> Unit) = Unit

    // ------------------------------------------------------------------
    // Genre enrichment: PhimCobe pool records arrive with their FULL
    // real categories (junk pseudo-categories are filtered at parse
    // time in the source), so records are practically never "thin"
    // anymore. The machinery stays for the rare record whose listing
    // entry carried no genre: its full categories are read from the
    // detail record, bounded-parallel, with the persistent per-slug
    // cache — same contract as 1.2.x.
    // ------------------------------------------------------------------

    private val genreCache = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val genreAttempted: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var genreCacheLoaded = false
    private val genreCacheMutex = Mutex()

    private suspend fun ensureGenreCacheLoaded() {
        if (genreCacheLoaded) return
        genreCacheMutex.withLock {
            if (genreCacheLoaded) return
            val json = runCatching { settings.animeGenreCacheJson.first() }
                .getOrNull().orEmpty()
            if (json.isNotBlank()) {
                runCatching {
                    val root = JSONObject(json)
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val slug = keys.next()
                        val arr = root.optJSONArray(slug) ?: continue
                        val pairs = (0 until arr.length()).mapNotNull { i ->
                            val p = arr.optJSONArray(i) ?: return@mapNotNull null
                            val label = p.optString(0)
                            val s = p.optString(1)
                            if (label.isBlank() || s.isBlank()) null else label to s
                        }
                        if (pairs.isNotEmpty()) genreCache[slug] = pairs
                    }
                }
            }
            genreCacheLoaded = true
        }
    }

    private suspend fun persistGenreCache() {
        runCatching {
            val root = JSONObject()
            for ((slug, pairs) in genreCache) {
                val arr = JSONArray()
                for ((label, s) in pairs) {
                    arr.put(JSONArray().put(label).put(s))
                }
                root.put(slug, arr)
            }
            settings.setAnimeGenreCache(root.toString())
        }
    }

    /**
     * Effective categories of a pool record: the enriched detail list
     * when cached, else whatever the record itself carried (its own
     * real genres — honest, never invented).
     */
    fun genresOf(item: TitleItem): List<Pair<String, String>> =
        genreCache[item.slug] ?: item.genres.zip(item.genreSlugs)

    /** A record is "thin" when it carries no real genre at all. */
    private fun isThin(item: TitleItem): Boolean = item.genreSlugs.isEmpty()

    /** Pool records still owed an enrichment attempt (drives re-kicks). */
    fun hasUnattemptedThin(items: List<TitleItem>): Boolean =
        items.any {
            isThin(it) && !genreCache.containsKey(it.slug) && it.slug !in genreAttempted
        }

    /**
     * One enrichment pass over [items]: fetch full categories for
     * every thin, uncached, not-yet-attempted slug. Returns true when
     * the cache gained entries — callers re-publish so chips and
     * filter results update live as enrichment lands.
     */
    suspend fun enrichThinGenres(items: List<TitleItem>): Boolean {
        ensureGenreCacheLoaded()
        val todo = items.asSequence()
            .filter { isThin(it) && !genreCache.containsKey(it.slug) && it.slug !in genreAttempted }
            .map { it.slug }
            .distinct()
            .toList()
        if (todo.isEmpty()) return false
        genreAttempted.addAll(todo)
        val semaphore = Semaphore(GENRE_ENRICH_PARALLEL)
        val results = coroutineScope {
            todo.map { slug ->
                async {
                    semaphore.withPermit {
                        runCatching { phimcobe.categoriesOf(slug) }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { cats ->
                                genreCache[slug] = cats
                                true
                            } ?: false
                    }
                }
            }.awaitAll()
        }
        val changed = results.any { it }
        if (changed) persistGenreCache()
        return changed
    }

    // ------------------------------------------------------------------
    // Poster fallback: some covers are dead AT THE SOURCE. Until
    // 1.3.3 the fallback cover was fetched from the KKPhim record
    // under the same slug into a persistent per-slug cache. Both KK
    // (1.3.4) and that fetcher are gone; covers already sitting in
    // the persisted cache still apply (they are plain image URLs the
    // app stored earlier), but no new lookup ever happens. PhimCobe
    // posters ride the site's own image proxy, which measured
    // healthy; a cover dead at the source shows the placeholder.
    // ------------------------------------------------------------------

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterAttempted: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var posterCacheLoaded = false
    private val posterCacheMutex = Mutex()

    private suspend fun ensurePosterCacheLoaded() {
        if (posterCacheLoaded) return
        posterCacheMutex.withLock {
            if (posterCacheLoaded) return
            val json = runCatching { settings.animePosterCacheJson.first() }
                .getOrNull().orEmpty()
            if (json.isNotBlank()) {
                runCatching {
                    val root = JSONObject(json)
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val slug = keys.next()
                        val url = root.optString(slug)
                        if (url.isNotBlank()) posterCache[slug] = url
                    }
                }
            }
            posterCacheLoaded = true
        }
    }

    private suspend fun persistPosterCache() {
        runCatching {
            val root = JSONObject()
            for ((slug, url) in posterCache) root.put(slug, url)
            settings.setAnimePosterCache(root.toString())
        }
    }

    /** No poster lookups remain — the enrichment loop must not spin. */
    fun hasUnattemptedPoster(items: List<TitleItem>): Boolean = false

    /**
     * The pass only warms the persisted cache into memory now and
     * never gains entries, so it always reports "no change".
     */
    suspend fun enrichPosterFallbacks(items: List<TitleItem>): Boolean {
        ensurePosterCacheLoaded()
        return false
    }

    /** The cached fallback poster URL for a pool item ("" when none known). */
    fun posterFallbackOf(item: TitleItem): String =
        if (item.sourceId == PhimCobeSource.SOURCE_ID) posterCache[item.slug].orEmpty() else ""

    /** Attach the cached fallback poster as the item's last-resort cover. */
    fun withPosterFallback(item: TitleItem): TitleItem {
        val fb = posterFallbackOf(item)
        return if (fb.isBlank() || fb == item.posterUrl || item.posterFallbackUrl == fb) {
            item
        } else {
            item.copy(posterFallbackUrl = fb)
        }
    }

    /** withPosterFallback for a single foreground item (Detail): cache only. */
    suspend fun withPosterFallbackLoaded(item: TitleItem): TitleItem {
        if (item.sourceId != PhimCobeSource.SOURCE_ID) return item
        ensurePosterCacheLoaded()
        return withPosterFallback(item)
    }

    companion object {
        /** The animation umbrella slug — never a genre chip. */
        const val UMBRELLA_SLUG = "hoat-hinh"

        /** Max detail enrichment calls in flight at once. */
        const val GENRE_ENRICH_PARALLEL = 6

        /** Country slugs of the Asian set (the country law). */
        const val COUNTRY_JP = "nhat-ban"
        const val COUNTRY_KR = "han-quoc"
        const val COUNTRY_CN = "trung-quoc"
        const val COUNTRY_TW = "dai-loan"

        val COUNTRIES = listOf(COUNTRY_JP, COUNTRY_CN, COUNTRY_KR, COUNTRY_TW)

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
