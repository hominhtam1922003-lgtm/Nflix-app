package com.example.nflix.data

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.CobeSource
import com.example.nflix.data.source.HhKungfuSource
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

    // ------------------------------------------------------------------
    // Genre enrichment (1.2.0 fix): the /the-loai/hoat-hinh LISTING
    // payload embeds ONLY the "hoat-hinh" umbrella in each record's
    // categories — the page IS that category, so its serializer embeds
    // nothing else — which left the Anime filter sheet with no genre
    // chips at all. The by-slug API record is the only surface with a
    // title's FULL categories, so thin pool records are enriched from
    // it in the background:
    // - bounded parallelism (GENRE_ENRICH_PARALLEL calls in flight),
    // - a PERSISTENT per-slug cache (DataStore): each title is fetched
    //   at most once, ever, across app runs,
    // - failures are NOT cached: the slug stays thin for this process
    //   run and is retried on a later app run,
    // - the pool listing itself is never blocked or slowed by this.
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
     * Effective categories of a pool record: the enriched by-slug list
     * when cached, else whatever the listing payload itself carried
     * (usually just the umbrella — honest, never invented).
     */
    fun genresOf(item: TitleItem): List<Pair<String, String>> =
        genreCache[item.slug] ?: item.genres.zip(item.genreSlugs)

    /** A record is "thin" when the listing gave it no real genre. */
    private fun isThin(item: TitleItem): Boolean =
        item.genreSlugs.none { it != UMBRELLA_SLUG }

    /** Pool records still owed an enrichment attempt (drives re-kicks). */
    fun hasUnattemptedThin(items: List<TitleItem>): Boolean =
        items.any {
            isThin(it) && it.slug !in genreCache && it.slug !in genreAttempted
        }

    /**
     * One enrichment pass over [items]: fetch full categories for every
     * thin, uncached, not-yet-attempted slug. Returns true when the
     * cache gained entries — callers re-publish so chips and filter
     * results update live as enrichment lands.
     */
    suspend fun enrichThinGenres(items: List<TitleItem>): Boolean {
        ensureGenreCacheLoaded()
        val todo = items.asSequence()
            .filter { isThin(it) && it.slug !in genreCache && it.slug !in genreAttempted }
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
                        runCatching { cobe.categoriesOf(slug) }.getOrNull()
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

    companion object {
        /** The umbrella category Cobe's animation listing embeds — never a chip. */
        const val UMBRELLA_SLUG = "hoat-hinh"

        /** Max by-slug enrichment calls in flight at once. */
        const val GENRE_ENRICH_PARALLEL = 6

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
