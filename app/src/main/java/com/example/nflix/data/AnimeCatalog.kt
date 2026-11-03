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
 * - Drop donghua SERIES that already live in the Donghua tab — decided
 *   by slug against the HHKungfu catalog (Cobe records carry no country
 *   field, so the Donghua catalog itself is the reference set).
 * - Dedupe the pool's duplicate records (one-piece vs dao-hai-tac: same
 *   show, two slugs, both at the live episode count).
 *
 * Country law (user, 2026-11-01 19:04 — REPLACES the "movies of any
 * origin" rule): NO western animation at all. Keep only (a) Japanese
 * anime SERIES and (b) Asian animated MOVIES (JP/KR/CN/TW). Cobe records
 * have no country field, so classification intersects the pool with
 * Cobe's OWN country-page slug pools (measured 11/11 on samples,
 * dossier ANIME-LOC-AU-MY): series must be in the nhat-ban pool, movies
 * in the union of the four Asian pools, everything else is denied by
 * default — a title whose pool has not loaded yet stays hidden and
 * appears once the harvest lands, never the other way around.
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

    /**
     * Apply the tab law to raw pool records: donghua-series exclusion,
     * the country gate, then dedupe. The country gate is default-deny:
     * series must sit in the Japan pool, movies in the Asian union —
     * while a pool is still harvesting, unclassified titles stay hidden.
     */
    fun visible(items: List<TitleItem>): List<TitleItem> {
        val kept = items.filter { item ->
            if (isSeriesRecord(item)) {
                item.slug !in donghuaSlugs && item.slug in jpSlugs
            } else {
                item.slug in asiaSlugs
            }
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
    // Country pools (country law, 2026-11-01 19:04): slug sets harvested
    // from Cobe's own country pages (/quoc-gia/<slug>, 32 records/page).
    // First run sweeps every page of the four Asian catalogs (453 pages
    // total, ~13k slugs); the sets persist in DataStore, so later runs
    // only refresh the head pages (lists are updatedAt-desc: stop as
    // soon as a whole page is already known). Japan is harvested FIRST
    // — it alone unlocks the series gate, so the tab starts filling
    // while the movie pools are still sweeping.
    // ------------------------------------------------------------------

    /** Published snapshots the (non-suspend) visible() gate reads. */
    @Volatile
    private var jpSlugs: Set<String> = emptySet()

    @Volatile
    private var asiaSlugs: Set<String> = emptySet()

    /** Working sets + per-country full-sweep flags, guarded by countryMutex. */
    private val countrySets = HashMap<String, MutableSet<String>>()
    private val countryDone = HashMap<String, Boolean>()
    private val countryMutex = Mutex()

    @Volatile
    private var countryCacheLoaded = false

    @Volatile
    private var countryWarming = false

    /** True once the persisted pools have been read into memory. */
    fun countryPoolsReady(): Boolean = countryCacheLoaded

    private fun publishCountrySnapshotsLocked() {
        jpSlugs = countrySets[COUNTRY_JP]?.toSet().orEmpty()
        val union = HashSet<String>()
        for (c in COUNTRIES) countrySets[c]?.let { union.addAll(it) }
        asiaSlugs = union
    }

    /** Load the persisted pools once (fast; no network). */
    suspend fun ensureCountryPoolsLoaded() {
        if (countryCacheLoaded) return
        countryMutex.withLock {
            if (countryCacheLoaded) return
            val json = runCatching { settings.animeCountryPoolsJson.first() }
                .getOrNull().orEmpty()
            if (json.isNotBlank()) {
                runCatching {
                    val root = JSONObject(json)
                    for (country in COUNTRIES) {
                        val entry = root.optJSONObject(country) ?: continue
                        val set = entry.optString("slugs")
                            .split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.toMutableSet()
                        countrySets[country] = set
                        countryDone[country] = entry.optBoolean("done", false)
                    }
                }
            }
            publishCountrySnapshotsLocked()
            countryCacheLoaded = true
        }
    }

    private suspend fun persistCountryPools() {
        val snapshot = countryMutex.withLock {
            val root = JSONObject()
            for (country in COUNTRIES) {
                val set = countrySets[country] ?: continue
                val entry = JSONObject()
                entry.put("done", countryDone[country] == true)
                entry.put("slugs", set.joinToString(","))
                root.put(country, entry)
            }
            root.toString()
        }
        runCatching { settings.setAnimeCountryPools(snapshot) }
    }

    /**
     * Full warm: persisted pools first (instant), then per country —
     * a full sweep when its persisted sweep never completed, otherwise
     * a head refresh that stops at the first fully-known page.
     * [onGrew] fires whenever a published snapshot actually grows, so
     * surfaces can re-publish and newly-classified titles appear live.
     * Concurrent calls collapse into one run.
     */
    suspend fun warmCountryPools(onGrew: () -> Unit) {
        ensureCountryPoolsLoaded()
        if (countryWarming) return
        countryWarming = true
        try {
            for (country in COUNTRIES) {
                runCatching {
                    val done = countryMutex.withLock { countryDone[country] == true }
                    if (done) refreshCountryHeads(country, onGrew)
                    else sweepCountry(country, onGrew)
                }
            }
        } finally {
            countryWarming = false
        }
    }

    /** First-run full sweep of one country catalog, batched parallel. */
    private suspend fun sweepCountry(country: String, onGrew: () -> Unit) {
        val (firstSlugs, totalPages) = cobe.countryPoolPage(country, 1)
        mergeCountrySlugs(country, firstSlugs, onGrew)
        var page = 2
        while (page <= totalPages) {
            val batch = (page until minOf(page + COUNTRY_SWEEP_PARALLEL, totalPages + 1)).toList()
            val results = coroutineScope {
                batch.map { p ->
                    async { runCatching { cobe.countryPoolPage(country, p).first }.getOrNull() }
                }.awaitAll()
            }
            for (slugs in results) {
                if (slugs != null) mergeCountrySlugs(country, slugs, onGrew)
            }
            page += COUNTRY_SWEEP_PARALLEL
        }
        countryMutex.withLock { countryDone[country] = true }
        persistCountryPools()
    }

    /** Later-run refresh: head pages only, stop at a fully-known page. */
    private suspend fun refreshCountryHeads(country: String, onGrew: () -> Unit) {
        var changed = false
        for (page in 1..COUNTRY_REFRESH_PAGES) {
            val (slugs, _) = try {
                cobe.countryPoolPage(country, page)
            } catch (e: Exception) {
                break
            }
            val grew = mergeCountrySlugs(country, slugs, onGrew)
            if (grew) changed = true else break
        }
        if (changed) persistCountryPools()
    }

    /** Merge slugs into a country's set; true when the set grew. */
    private suspend fun mergeCountrySlugs(
        country: String,
        slugs: List<String>,
        onGrew: () -> Unit
    ): Boolean {
        val grew = countryMutex.withLock {
            val set = countrySets.getOrPut(country) { mutableSetOf() }
            val before = set.size
            set.addAll(slugs)
            if (set.size > before) {
                publishCountrySnapshotsLocked()
                true
            } else {
                false
            }
        }
        if (grew) onGrew()
        return grew
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
            isThin(it) && !genreCache.containsKey(it.slug) && it.slug !in genreAttempted
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

    // ------------------------------------------------------------------
    // Poster fallback (country-law fix, 2026-11-01): some Cobe covers
    // are dead AT THE SOURCE — naruto-shippuden's poster and thumb
    // 404 in the listing payload, in the by-slug record and on Cobe's
    // own site. Until 1.3.3 the fallback cover was fetched from the
    // KKPhim record under the same slug into a persistent per-slug
    // cache. 1.3.4: KKPhim is GONE (user order) — the fetcher is
    // removed; covers already sitting in the persisted cache still
    // apply (they are plain image URLs the app stored earlier), but
    // no new lookup ever happens. A Cobe cover dead at the source now
    // simply shows the placeholder — the honest price of Cobe-only.
    // The fallback only ever fills TitleItem's posterFallbackUrl —
    // the item's own Cobe art stays primary, always.
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

    /** 1.3.4: no poster lookups remain — the enrichment loop must not spin. */
    fun hasUnattemptedPoster(items: List<TitleItem>): Boolean = false

    /**
     * 1.3.4: the KK poster fetcher is gone with its source. The pass
     * only warms the persisted cache into memory now and never gains
     * entries, so it always reports "no change".
     */
    suspend fun enrichPosterFallbacks(items: List<TitleItem>): Boolean {
        ensurePosterCacheLoaded()
        return false
    }

    /** The cached fallback poster URL for a Cobe item ("" when none known). */
    fun posterFallbackOf(item: TitleItem): String =
        if (item.sourceId == CobeSource.SOURCE_ID) posterCache[item.slug].orEmpty() else ""

    /** Attach the cached fallback poster as the item's last-resort cover. */
    fun withPosterFallback(item: TitleItem): TitleItem {
        val fb = posterFallbackOf(item)
        return if (fb.isBlank() || fb == item.posterUrl || item.posterFallbackUrl == fb) {
            item
        } else {
            item.copy(posterFallbackUrl = fb)
        }
    }

    /**
     * withPosterFallback for a single foreground item (Detail): cache
     * only since 1.3.4 — a miss stays a miss (no KK lookup exists).
     */
    suspend fun withPosterFallbackLoaded(item: TitleItem): TitleItem {
        if (item.sourceId != CobeSource.SOURCE_ID) return item
        ensurePosterCacheLoaded()
        return withPosterFallback(item)
    }

    companion object {
        /** The umbrella category Cobe's animation listing embeds — never a chip. */
        const val UMBRELLA_SLUG = "hoat-hinh"

        /** Max by-slug enrichment calls in flight at once. */
        const val GENRE_ENRICH_PARALLEL = 6

        /** Country catalog slugs of Cobe's /quoc-gia pages (Asian set). */
        const val COUNTRY_JP = "nhat-ban"
        const val COUNTRY_KR = "han-quoc"
        const val COUNTRY_CN = "trung-quoc"
        const val COUNTRY_TW = "dai-loan"

        /** Harvest order matters: Japan first — it unlocks the series gate. */
        val COUNTRIES = listOf(COUNTRY_JP, COUNTRY_CN, COUNTRY_KR, COUNTRY_TW)

        /** Pages fetched in parallel during a first-run country sweep. */
        const val COUNTRY_SWEEP_PARALLEL = 8

        /** Head pages re-checked per country on later runs. */
        const val COUNTRY_REFRESH_PAGES = 10

        /** Max KKPhim poster lookups in flight at once. */
        const val POSTER_LOOKUP_PARALLEL = 6

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
