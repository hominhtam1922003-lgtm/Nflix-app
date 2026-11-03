package com.example.nflix.data.source

import android.util.Log
import com.example.nflix.data.SettingsStore
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** What one cobe episode resolves to at play time (see [CobeSource.resolvePlay]). */
sealed class CobePlay {
    data class Hls(val url: String) : CobePlay()
    data class Embed(val url: String) : CobePlay()
}

/** V2 (A3): one editorial collection from Cobe's homepageLists. */
data class CobeCollection(
    val name: String,
    val filter: String,
    val items: List<TitleItem>
)

/** V2 (A5): one Cobe actor page (/dien-vien/<slug>) distilled. */
data class CobeActorPage(
    val name: String,
    val thumb: String,
    val items: List<TitleItem>
)

/**
 * CobePhim (cobephim.fun) — second source for the Phim/Anime families
 * (đợt 4, user-approved merge 2026-11-01). Measured live 2026-11-01, see
 * the COBE- reports in ~/workspace/nflix/research-features:
 *
 * - Public JSON API on a rotating darkbytes.xyz host: search, by-slug
 *   detail, and a bare-array episode list per movie. NO stream links in
 *   the API — the play link lives in the watch page's embedded JSON and
 *   is resolved per episode by [resolvePlay].
 * - Slugs match KKPhim 1:1, which is what makes the slug merge cheap.
 * - Declared counts are NOT trusted (search meta.total is fabricated,
 *   the language field under-reports): released episodes are counted
 *   from the real episode list, exactly like the KKPhim adapter.
 */
class CobeSource(
    private val client: OkHttpClient = defaultClient(),
    private val settings: SettingsStore? = null,
    private val webBase: String = "https://cobephim.fun"
) {
    companion object {
        const val SOURCE_ID = "cobe"
        const val LOCATOR_PREFIX = "cobe:"
        private const val TAG = "CobeSource"

        /**
         * 1.1.1 (N2): host rediscovery is expensive (home page + up to 40
         * JS chunks). Its result — success OR failure — is cached for
         * this long so a dead API host cannot make every Detail/Player/
         * search pay the full scan again.
         */
        private const val DISCOVERY_TTL_MS = 10 * 60 * 1000L

        /** 1.3.4: how long a parsed resolvePlay result stays reusable. */
        private const val PLAY_CACHE_TTL_MS = 5 * 60 * 1000L

        /** API host measured live 2026-11-01; rediscovery replaces it when it dies. */
        private const val FALLBACK_API_BASE =
            "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1"

        /**
         * 1.3.4: the Phim sheet's genre table — Cobe's OWN categories,
         * measured 2026-11-03 from real catalog records (288 sampled
         * via the search API; every slug's /the-loai/<slug> listing
         * verified HTTP 200 the same day), ordered by sampled
         * frequency. "Hoạt Hình" is deliberately absent: Anime is its
         * own tab (user order) — it stays reachable there. This list
         * replaces KKPhim's 26, which died with that source.
         */
        val CATEGORIES: List<Pair<String, String>> = listOf(
            "Chính Kịch" to "chinh-kich",
            "Hành Động" to "hanh-dong",
            "Tình Cảm" to "tinh-cam",
            "Tâm Lý" to "tam-ly",
            "Hài Hước" to "hai-huoc",
            "Phiêu Lưu" to "phieu-luu",
            "Giả Tưởng" to "gia-tuong",
            "Kinh Dị" to "kinh-di",
            "Hình Sự" to "hinh-su",
            "Bí Ẩn" to "bi-an",
            "Gia Đình" to "gia-dinh",
            "Cổ Trang" to "co-trang",
            "Khoa Học Viễn Tưởng" to "khoa-hoc-vien-tuong",
            "Chiến Tranh" to "chien-tranh",
            "Tội Phạm" to "toi-pham",
            "Võ Thuật" to "vo-thuat",
            "Lịch Sử" to "lich-su",
            "Lãng Mạn" to "lang-man",
            "Học Đường" to "hoc-duong",
            "Âm Nhạc" to "am-nhac",
            "Miền Tây" to "mien-tay",
            "Tài Liệu" to "tai-lieu",
            "Thể Thao" to "the-thao",
            "Thần Thoại" to "than-thoai"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // 1.3.2: hard per-request ceiling. readTimeout alone is an
            // INACTIVITY timeout — a slow-dripping response (measured
            // on the dead cobephim.fun homepage: 478KB in 30s, still
            // unfinished) resets it forever, which is what hung the
            // Player with no total budget. callTimeout bounds the
            // whole request, drip included.
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                        .build()
                )
            }
            .build()

        /** Episode slugs of this source are locators, not KKPhim-style slugs. */
        fun isLocator(slug: String): Boolean = slug.startsWith(LOCATOR_PREFIX)

        fun locator(movieSlug: String, episodeId: Long): String =
            "$LOCATOR_PREFIX$movieSlug:$episodeId"

        /** "cobe:<movieSlug>:<episodeId>" back to its parts (slugs contain no ':'). */
        fun parseLocator(locator: String): Pair<String, Long>? {
            if (!locator.startsWith(LOCATOR_PREFIX)) return null
            val rest = locator.removePrefix(LOCATOR_PREFIX)
            val idx = rest.lastIndexOf(':')
            if (idx <= 0) return null
            val id = rest.substring(idx + 1).toLongOrNull() ?: return null
            return rest.substring(0, idx) to id
        }

        // The watch page embeds the episode as escaped JSON inside its RSC
        // payload: episode":{...,"type":"...","link":"..."} with every
        // quote backslash-escaped. Shape verified live 2026-11-01 on
        // Bác Sĩ X (streamvsmov embed + m3u8) and Thượng Công Chúa (streamc).
        private val EPISODE_RSC = Regex(
            """episode\\":\{\\"id\\":\d+,\\"name\\":\\"[^"\\]*\\",\\"server\\":\\"[^"\\]*\\",\\"slug\\":\\"[^"\\]*\\",\\"type\\":\\"([^"\\]*)\\",\\"link\\":\\"([^"\\]*)\\""" + "\""
        )
        private val EPISODE_RSC_LOOSE = Regex(
            """\\"type\\":\\"(m3u8|embed)\\",\\"link\\":\\"([^"\\]*)\\""" + "\""
        )
        private val STREAMVSMOV = Regex("""(https?://[\w.\-]*streamvsmov\.com)/video/([\w\-]+)""")
        private val DARKBYTES_HOST = Regex("""([A-Za-z0-9]+)\.darkbytes\.xyz""")

        /** Status code out of getText's "Cobe HTTP <code> (<url>)" message. */
        private val COBE_HTTP_CODE = Regex("""Cobe HTTP (\d{3})""")
        private val JS_CHUNK = Regex("""/_next/static/chunks/[\w.\-]+\.js""")
    }

    @Volatile
    private var apiBaseCache: String? = null

    // Discovery cache (N2): the last discoverApiBase() outcome and when it
    // was produced. A cached FAILURE is honored until the TTL expires too.
    @Volatile
    private var discoveryCachedAtMs: Long = 0L

    @Volatile
    private var discoveryCachedResult: String? = null

    @Volatile
    private var discoveryCached: Boolean = false

    // 1.3.3: the last host that got a FORCED fresh discovery after an
    // HTTP failure, and when — bounds forced scans (see getApiText).
    @Volatile
    private var forcedHealHost: String? = null

    @Volatile
    private var forcedHealAtMs: Long = 0L

    private suspend fun apiBase(): String {
        apiBaseCache?.let { return it }
        val stored = try {
            settings?.cobeApiHost?.first()
        } catch (e: Exception) {
            null
        }
        val base = stored?.takeIf { it.isNotBlank() } ?: FALLBACK_API_BASE
        apiBaseCache = base
        return base
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Cobe HTTP ${resp.code} ($url)")
            resp.body?.string().orEmpty()
        }
    }

    /**
     * One API call with a single self-heal: when the current host fails,
     * rediscover the host from the site's own JS bundles, cache it to
     * settings, and retry once. Any later failure propagates.
     */
    private suspend fun getApiText(path: String): String {
        val base = apiBase()
        try {
            return getText(base + path)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Cobe API call failed ($path via $base): ${e.message}")
            // 1.3.3: ANY HTTP error status from the current host — not
            // just the 502 of the 2026-11-03 outage that 1.3.2 handled —
            // counts as "this host may be dead or rotated": forget it
            // (drop the in-memory cache AND the saved host) and
            // rediscover FRESH, bypassing the 10-minute discovery cache
            // (which may be holding a null from the outage). A rotated
            // host answering 404 for everything (the user's device,
            // 2026-11-03: every film detail "Cobe HTTP 404") previously
            // stuck forever: the saved host survives restarts and was
            // only ever forgotten on 502. Non-HTTP failures (DNS,
            // timeouts) keep the old cached-discovery heal. Still at
            // most ONE retry, exactly as before.
            val httpCode = COBE_HTTP_CODE.find(e.message ?: "")
                ?.groupValues?.get(1)?.toIntOrNull()
            val found = if (httpCode != null && httpCode in 400..599) {
                if (apiBaseCache == base) apiBaseCache = null
                runCatching { settings?.setCobeApiHost("") }
                discoverAfterHostDeath(base)
            } else {
                discoverApiBaseCached()
            }
            if (found != null && found != base) {
                apiBaseCache = found
                runCatching { settings?.setCobeApiHost(found) }
                return getText(found + path)
            }
            throw e
        }
    }

    /**
     * 1.3.3: fresh discovery after an HTTP host failure, bypassing the
     * TTL cache — but at most once per failed host per TTL window, so
     * a healthy host with a genuinely missing title (a title-level
     * 404) cannot make every later call pay a full rescan. The outcome
     * always refreshes the regular discovery cache.
     */
    private suspend fun discoverAfterHostDeath(failedBase: String): String? {
        val now = System.currentTimeMillis()
        if (forcedHealHost == failedBase && now - forcedHealAtMs < DISCOVERY_TTL_MS) {
            return discoveryCachedResult
        }
        forcedHealHost = failedBase
        forcedHealAtMs = now
        val found = discoverApiBase()
        discoveryCachedResult = found
        discoveryCachedAtMs = System.currentTimeMillis()
        discoveryCached = true
        return found
    }

    /** discoverApiBase() behind the TTL cache — see DISCOVERY_TTL_MS (N2). */
    private suspend fun discoverApiBaseCached(): String? {
        val now = System.currentTimeMillis()
        if (discoveryCached && now - discoveryCachedAtMs < DISCOVERY_TTL_MS) {
            return discoveryCachedResult
        }
        val found = discoverApiBase()
        discoveryCachedResult = found
        discoveryCachedAtMs = System.currentTimeMillis()
        discoveryCached = true
        return found
    }

    private suspend fun getApiJson(path: String): JSONObject = JSONObject(getApiText(path))

    /**
     * The API host is compiled into the site's Next.js chunks (measured:
     * chunk 1000-*.js carries it). Scan the home page, then its chunk
     * files, for a darkbytes.xyz subdomain that is not the image CDN.
     */
    private suspend fun discoverApiBase(): String? {
        return try {
            val home = getText("$webBase/")
            findApiHost(home)?.let { return it }
            val chunks = JS_CHUNK.findAll(home).map { it.value }.distinct().take(40).toList()
            for (chunk in chunks) {
                val js = runCatching { getText(webBase + chunk) }.getOrNull() ?: continue
                findApiHost(js)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Cobe host discovery failed: ${e.message}")
            null
        }
    }

    private fun findApiHost(text: String): String? {
        val sub = DARKBYTES_HOST.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it != "icdn" && it != "www" }
            ?: return null
        return "https://$sub.darkbytes.xyz/api/v1"
    }

    /** The API host's own last-resort embed page for one episode (measured 200). */
    private suspend fun apiEmbedUrl(episodeId: Long): String =
        apiBase().removeSuffix("/api/v1") + "/embed/" + episodeId

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Cobe's updatedAt is true UTC (unlike KKPhim's mislabeled VN time). */
    private fun parseUpdatedAt(m: JSONObject): Long {
        val t = m.optString("updatedAt")
        if (t.isBlank()) return 0
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return (fmt.parse(t)?.time ?: 0) / 1000
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return 0
    }

    private fun badgeOf(language: String): String = when {
        language.contains("Thuyết Minh", true) -> "TM"
        language.contains("Vietsub", true) -> "VS"
        else -> ""
    }

    /**
     * 1.2.0: completion from EPISODE COUNTS only. The raw `status` string
     * lies (One Piece is "completed" while airing; JoJo SBR is "completed"
     * at 8/12) — but an "(8/12)" pair or a released count below the total
     * never does. Movies are complete by definition.
     */
    private fun deriveCompleted(m: JSONObject): Boolean {
        if (m.optString("type") == "movie") return true
        val epCurrent = m.optString("episode_current").trim()
        if (epCurrent.equals("Full", true)) return true
        val pair = Regex("\\((\\d+)\\s*/\\s*(\\d+)\\)").find(epCurrent)
        val total = pair?.groupValues?.get(2)?.toIntOrNull()
            ?: m.optInt("episode_total").takeIf { it > 0 }
            ?: return false
        val current = pair?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\d+").find(epCurrent)?.value?.toIntOrNull()
            ?: return false
        return total > 0 && current >= total
    }

    private fun parseItem(m: JSONObject, family: Family): TitleItem {
        val slug = m.optString("slug")
        val type = m.optString("type")
        val epCurrent = m.optString("episode_current").trim()
        val epNum = Regex("\\d+").find(epCurrent)?.value?.toIntOrNull() ?: 0
        val genres = mutableListOf<String>()
        val genreSlugs = mutableListOf<String>()
        val cats = m.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            c.optString("name").takeIf { it.isNotBlank() }?.let { genres.add(it) }
            c.optString("slug").takeIf { it.isNotBlank() }?.let { genreSlugs.add(it) }
        }
        val poster = m.optString("poster")
        val thumb = m.optString("thumbnail")
        return TitleItem(
            id = "${family.name}:$SOURCE_ID:$slug",
            family = family,
            sourceId = SOURCE_ID,
            slug = slug,
            name = m.optString("name"),
            originName = m.optString("origin_name"),
            posterUrl = poster.ifBlank { thumb },
            thumbUrl = thumb.ifBlank { poster },
            quality = m.optString("quality"),
            versionBadge = badgeOf(m.optString("language")),
            // V2 (A1): the raw language string feeds the local
            // PHIÊN BẢN sheet filter.
            languages = m.optString("language"),
            // Movies read "Full" like KKPhim's phim-le; series count from
            // episode_current, which the episode list later corrects.
            latestLabel = when {
                type == "movie" -> if (epCurrent.isBlank()) "" else "Full"
                epNum > 0 -> "Tập $epNum"
                else -> epCurrent
            },
            episodeCount = if (type == "movie") 0 else epNum,
            year = m.optInt("publish_year"),
            genres = genres,
            genreSlugs = genreSlugs,
            completed = deriveCompleted(m),
            updatedEpochSec = parseUpdatedAt(m)
        )
    }

    /**
     * V2 (A5): actor objects of a by-slug record ({id, name, slug,
     * thumbnail} — thumbnail is a TMDB image). Only actors WITH a slug
     * can open an actor page; the rest stay plain names in [peopleNames].
     */
    private fun actorRefsOf(arr: JSONArray?): List<com.example.nflix.data.model.ActorRef> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            com.example.nflix.data.model.ActorRef(
                name = name,
                slug = o.optString("slug").trim(),
                thumb = o.optString("thumbnail").trim()
            )
        }
    }

    /** People arrays hold objects with a name field (plain strings tolerated). */
    private fun peopleNames(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val el = arr.opt(i)) {
                is JSONObject -> el.optString("name").trim().ifBlank { null }
                is String -> el.trim().ifBlank { null }
                else -> null
            }
        }
    }

    private fun langKeyOf(serverName: String): String {
        val n = serverName.lowercase()
        return when {
            n.contains("thuyết minh") -> "tm"
            n.contains("lồng tiếng") -> "lt"
            n.contains("vietsub") -> "vs"
            n.contains("phụ đề") -> "vs"
            n.contains("song ngữ") -> "vs"
            else -> ""
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Search always needs an explicit limit (without one the API returns
     * only ~5 per page). Anime cannot be told apart reliably from this
     * payload (no country field), so results default to PHIM.
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 24): List<TitleItem> {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val root = getApiJson("/movies/search?keyword=$kw&page=$page&limit=$limit")
        val arr = root.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseItem(it, Family.PHIM) }
        }
    }

    /**
     * V2 (A2): the view charts KKPhim does not have. Cobe's search
     * accepts sort=view_day / sort=view_week and returns a really
     * decreasing order (measured 2026-11-01: day 1577→191 over 10).
     * view_month is inconsistent in samples — never used. Each item's
     * viewCount is the counter OF THE SORTED FIELD, read from the same
     * record (a week chart shows week numbers, not day numbers).
     * [animeOnly] adds the hoat-hinh category id the measurement used
     * (genres=24); the Anime country gate still applies on top.
     */
    suspend fun searchByViews(
        weekly: Boolean,
        limit: Int = 24,
        family: Family = Family.PHIM,
        animeOnly: Boolean = false
    ): List<TitleItem> {
        val sort = if (weekly) "view_week" else "view_day"
        val genreParam = if (animeOnly) "&genres=24" else ""
        val root = getApiJson("/movies/search?sort=$sort&limit=$limit$genreParam")
        val arr = root.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            parseItem(m, family).copy(viewCount = m.optLong(sort))
        }
    }

    /**
     * V2 (A3): Cobe's editorial homepage collections (measured
     * 2026-11-01: GET /lists/homepageLists → result.collections, 15
     * lists with 10–30 movies embedded). Callers pick the lists that do
     * not duplicate shelves the app already has and rename them in the
     * app's voice — the list CONTENT stays the source's.
     */
    suspend fun homepageLists(): List<CobeCollection> {
        val root = getApiJson("/lists/homepageLists?page=1&limit=15")
        val cols = root.optJSONObject("result")?.optJSONArray("collections")
            ?: root.optJSONArray("collections")
            ?: JSONArray()
        return (0 until cols.length()).mapNotNull { i ->
            val c = cols.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val moviesArr = c.optJSONArray("movies")
                ?: c.optJSONArray("items")
                ?: c.optJSONArray("result")
                ?: c.optJSONArray("data")
                ?: JSONArray()
            val items = (0 until moviesArr.length()).mapNotNull { j ->
                moviesArr.optJSONObject(j)?.let { parseItem(it, Family.PHIM) }
            }.distinctBy { it.slug }
            CobeCollection(
                name = name,
                filter = c.optString("filter"),
                items = items
            )
        }
    }

    /**
     * V2 (A5): an actor's filmography from the Cobe WEB actor page
     * (/dien-vien/<slug>). The pure API person endpoints are dead
     * (404/401), so the web page is the only path. A slug the site
     * does not know 404s out of getText — the caller shows an honest
     * empty/error state, never a dead link.
     *
     * 1.3.1 fix: the page does NOT embed listing-style "item" records
     * (measured live 2026-11-01: the whole page carries two "item"
     * strings), so the old extractItemJson path returned ZERO titles
     * and every actor page rendered empty. The filmography actually
     * rides the person object itself as a "movies":[...] array of
     * FULL by-slug records (poster/thumbnail/categories/publish_year
     * all present) — [extractPersonMovies] reads that array and
     * [parseItem] consumes the records unchanged. The "item"
     * extraction survives only as a fallback if the payload shape
     * ever changes back.
     */
    suspend fun actorPage(slug: String): CobeActorPage {
        val html = getText("$webBase/dien-vien/$slug")
        val decoded = decodeRscLayer(html)
        val personMovies = extractPersonMovies(decoded)
        val items = if (personMovies.isNotEmpty()) {
            personMovies
                .filter {
                    it.optString("slug").isNotBlank() && it.optString("name").isNotBlank()
                }
                .map { parseItem(it, Family.PHIM) }
                .distinctBy { it.slug }
        } else {
            extractItemJson(html)
                .filter { it.optString("slug").isNotBlank() }
                .map { parseItem(it, Family.PHIM) }
                .distinctBy { it.slug }
        }
        // The person object rides the same payload ({name, slug,
        // thumbnail} in some order); recover name/thumb when present,
        // else humanize the slug — the filmography is the point.
        val name = findPersonField(decoded, slug, "name")
            ?: slug.split("-").filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val thumb = findPersonField(decoded, slug, "thumbnail") ?: ""
        return CobeActorPage(name = name, thumb = thumb, items = items)
    }

    /** One string field of the person object that carries [slug], either field order. */
    private fun findPersonField(decoded: String, slug: String, field: String): String? {
        val esc = Regex.escape(slug)
        val patterns = listOf(
            Regex("\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"[^}]{0,200}?\"slug\":\"$esc\""),
            Regex("\"slug\":\"$esc\"[^}]{0,200}?\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        )
        for (p in patterns) {
            val v = p.find(decoded)?.groupValues?.get(1)?.trim()
            if (!v.isNullOrBlank()) {
                return v.replace("\\/", "/").replace("\\\"", "\"")
            }
        }
        return null
    }

    /**
     * Full (label to slug) categories of ONE title, from its by-slug
     * record. This is the only Cobe surface whose categories are
     * complete: the web LISTING payloads embed only the listing page's
     * own category per record (the /the-loai/hoat-hinh pool carries
     * just the "hoat-hinh" umbrella), which is what emptied the Anime
     * genre sheet in 1.2.0. The umbrella is included in the result —
     * callers filter it out.
     */
    suspend fun categoriesOf(slug: String): List<Pair<String, String>> {
        val root = getApiJson("/movies/by-slug/$slug")
        val m = root.optJSONObject("movie") ?: return emptyList()
        val item = parseItem(m, Family.ANIME)
        return item.genres.zip(item.genreSlugs)
    }

    // ------------------------------------------------------------------
    // Web listings (1.2.0) — the API's list endpoints are token-locked
    // (401), but the Next.js pages embed the SAME full movie records in
    // their RSC payload. Paths verified live 2026-11-01 (dossier
    // COBE-ANIME-LIETKE): /the-loai/hoat-hinh (130 pages),
    // /phim-bo (436), /phim-le (612), 32 records per page, updatedAt desc.
    // ------------------------------------------------------------------

    /**
     * One web listing page: the items plus the source's total page count.
     * Records are the full by-slug shape, so no per-title detail call is
     * needed for lists. Item extraction goes through [extractItemJson] —
     * see its note on the payload's TWO escape layers.
     */
    suspend fun webListPage(path: String, page: Int, family: Family): Pair<List<TitleItem>, Int> {
        val html = getText("$webBase$path?page=$page")
        val totalPages = totalPagesOf(html) ?: page
        val items = extractItemJson(html)
            .filter { it.optString("slug").isNotBlank() }
            .map { parseItem(it, family) }
        return items.distinctBy { it.slug } to totalPages
    }

    /**
     * Slugs only from one web listing page (plus total page count) — the
     * country pools are intersected with the Anime pool by slug, so the
     * full records would be wasted work on 453 harvest pages.
     */
    suspend fun webListSlugs(path: String, page: Int): Pair<List<String>, Int> {
        val html = getText("$webBase$path?page=$page")
        val totalPages = totalPagesOf(html) ?: page
        val slugs = extractItemJson(html)
            .map { it.optString("slug") }
            .filter { it.isNotBlank() }
            .distinct()
        return slugs to totalPages
    }

    /** One page of a Cobe country catalog (/quoc-gia/<slug>) — slugs only. */
    suspend fun countryPoolPage(countrySlug: String, page: Int): Pair<List<String>, Int> =
        webListSlugs("/quoc-gia/$countrySlug", page)

    private fun totalPagesOf(html: String): Int? =
        Regex("""\\"totalPages\\":(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Extract every embedded `"item":{...}` object from an RSC page.
     *
     * The flight payload carries TWO escape layers: the records are JSON
     * text inside a JS string literal, so a quote inside a record value
     * is `\\\"` on the wire. The old extraction (replace `\"` -> `"`,
     * then brace-match) corrupted exactly those sequences into a bogus
     * string end and silently dropped ~10–20% of each page — measured
     * live 2026-11-01: 29/32 items on /the-loai/hoat-hinh and
     * /quoc-gia/nhat-ban, 28/32 on /phim-bo. [decodeRscLayer] is a proper
     * single-pass decode of the JS layer; after it, the text is plain
     * JSON-level text where the string-aware brace scan is exact.
     * Verified 32/32 on all three page types plus deep country pages.
     */
    private fun extractItemJson(html: String): List<JSONObject> {
        val decoded = decodeRscLayer(html)
        val out = mutableListOf<JSONObject>()
        var from = 0
        while (true) {
            val idx = decoded.indexOf("\"item\":{", from)
            if (idx < 0) break
            val start = idx + "\"item\":".length
            val obj = captureObject(decoded, start) ?: break
            from = start + obj.length
            runCatching { JSONObject(obj) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * Single-pass decode of the JS-string escape layer: `\\` -> `\`,
     * `\"` -> `"`, `\/` -> `/`, `\n`/`\r`/`\t`/`\b`/`\f`, `\uXXXX`.
     * Anything else after a backslash passes through as the char itself.
     * Left-to-right, each escape consumed exactly once — the property
     * the naive replace lacked.
     */
    private fun decodeRscLayer(text: String): String {
        if (!text.contains('\\')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        val code = if (i + 6 <= text.length) {
                            text.substring(i + 2, i + 6).toIntOrNull(16)
                        } else {
                            null
                        }
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(n)
                            i += 2
                        }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** String-aware balanced {...} capture starting at [start] (which must be '{'). */
    private fun captureObject(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * 1.3.1: the elements of the person object's "movies":[...] array
     * on a /dien-vien/<slug> page, from ALREADY [decodeRscLayer]-
     * decoded text. The person object is anchored by its "other_name"
     * field and the page carries exactly one such array (measured
     * 2026-11-01), so the first "movies" array in the payload is the
     * filmography. Each element is captured with the same string-aware
     * balance scan as [captureObject]; a malformed element is skipped,
     * never fatal.
     */
    private fun extractPersonMovies(decoded: String): List<JSONObject> {
        val arrStart = decoded.indexOf("\"movies\":[")
        if (arrStart < 0) return emptyList()
        val start = arrStart + "\"movies\":".length
        val arr = captureArray(decoded, start) ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        var from = 0
        while (true) {
            val idx = arr.indexOf('{', from)
            if (idx < 0) break
            val obj = captureObject(arr, idx) ?: break
            from = idx + obj.length
            runCatching { JSONObject(obj) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** String-aware balanced [...] capture starting at [start] (which must be '['). */
    private fun captureArray(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '[') return null
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** The animation pool page (tab Anime's catalog) — raw, unfiltered. */
    suspend fun animePoolPage(page: Int): Pair<List<TitleItem>, Int> =
        webListPage("/the-loai/hoat-hinh", page, Family.ANIME)

    /** Series / movie listing pages for the merged Phim catalog. */
    suspend fun kindPage(kind: String, page: Int): Pair<List<TitleItem>, Int> = when (kind) {
        "phim-bo" -> webListPage("/phim-bo", page, Family.PHIM)
        "phim-le" -> webListPage("/phim-le", page, Family.PHIM)
        else -> emptyList<TitleItem>() to page
    }

    /** 1.3.4: the site's newest-updates listing (/phim-moi, measured 200). */
    suspend fun latestPage(page: Int): Pair<List<TitleItem>, Int> =
        webListPage("/phim-moi", page, Family.PHIM)

    /**
     * 1.3.4: one /the-loai/<slug> page with the kind facet applied on
     * the records' OWN `type` field (series vs movie) — parseItem
     * drops `type`, so the filter must run on the raw payload here.
     * [kind] "" keeps both. Genre listings mix types by design; this
     * is what lets the Phim sheet combine Thể loại + Loại exactly.
     */
    suspend fun categoryPage(
        categorySlug: String,
        page: Int,
        kind: String,
        family: Family = Family.PHIM
    ): Pair<List<TitleItem>, Int> {
        val html = getText("$webBase/the-loai/$categorySlug?page=$page")
        val totalPages = totalPagesOf(html) ?: page
        val items = extractItemJson(html)
            .filter { it.optString("slug").isNotBlank() }
            .filter {
                kind.isEmpty() ||
                    (kind == "phim-le") == (it.optString("type") == "movie")
            }
            .map { parseItem(it, family) }
        return items.distinctBy { it.slug } to totalPages
    }

    suspend fun detail(slug: String, family: Family = Family.PHIM): TitleDetail {
        val root = getApiJson("/movies/by-slug/$slug")
        val m = root.optJSONObject("movie") ?: throw IllegalStateException("Cobe: no movie")
        val movieId = m.optLong("id")
        val base = parseItem(m, family)
        val groups = try {
            fetchGroups(slug, movieId)
        } catch (e: Exception) {
            // 1.3.2: an episode-list failure used to be SWALLOWED into
            // an empty "successful" detail (servers=[], no error flag)
            // — the Player then died with a phantom "Không thấy tập
            // này" dressed as a network error (dossier phim-0-tap §2
            // branch C3). Fail loudly: the detail call surfaces this
            // real message to the screen.
            Log.w(TAG, "Cobe episode list failed for slug=$slug: ${e.message}")
            throw IllegalStateException("Cobe: không lấy được danh sách tập cho tựa này", e)
        }
        // Released count = highest episode number really present on a
        // server — never the declared episode_total.
        val released = groups.maxOfOrNull { g ->
            g.episodes.maxOfOrNull { it.number } ?: 0f
        }?.toInt() ?: 0
        val total = if (groups.isEmpty()) 0 else released
        // The declared language field under-reports (it can miss TM
        // entirely); when episodes loaded, derive the badge from the
        // server labels that actually exist.
        val badge = when {
            groups.any { it.langKey == "tm" } -> "TM"
            groups.any { it.langKey == "vs" } -> "VS"
            else -> base.versionBadge
        }
        // Genre links double as "Cùng thể loại" keys — Cobe's OWN
        // slugs (1.3.4: KKPhim is gone; the shelf is served by Cobe's
        // /the-loai/<slug> listings, so the keys must be Cobe's).
        val genreLinks = mutableListOf<Pair<String, String>>()
        val cats = m.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val name = c.optString("name")
            val ownSlug = c.optString("slug")
            if (name.isBlank()) continue
            if (ownSlug.isNotBlank()) genreLinks.add(name to ownSlug)
        }
        val description = m.optString("description").let { html ->
            if (html.isBlank()) "" else Jsoup.parseBodyFragment(html).text()
        }
        return TitleDetail(
            item = base.copy(
                episodeCount = if (total > 0) total else base.episodeCount,
                versionBadge = badge,
                latestLabel = if (total > 0 && m.optString("type") != "movie") {
                    "Tập $total"
                } else {
                    base.latestLabel
                }
            ),
            description = description,
            // Status also derives from counts (see deriveCompleted) — the
            // raw field marks airing shows as completed.
            status = if (deriveCompleted(m)) "Hoàn thành" else "Đang tiến hành",
            totalCount = total,
            durationLabel = m.optString("episode_time"),
            actors = peopleNames(m.optJSONArray("actors")),
            actorRefs = actorRefsOf(m.optJSONArray("actors")),
            directors = peopleNames(m.optJSONArray("directors")),
            servers = groups,
            trailerUrl = m.optString("trailer_url"),
            genreLinks = genreLinks
        )
    }

    /**
     * Episode list: one flat record per (episode x server). Grouped by
     * the raw server label, normalized with a " · Cobe" suffix so merged
     * UI can tell the source apart. Fuller groups first — the plain
     * "Thuyết Minh" variant is a stale feed next to "Thuyết minh #1",
     * and picking the fuller one is the whole point of this source.
     */
    private suspend fun fetchGroups(movieSlug: String, movieId: Long): List<ServerGroup> {
        val arr = JSONArray(getApiText("/episodes/by-idMovie/$movieId"))
        val byServer = LinkedHashMap<String, MutableList<EpisodeItem>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val episodeId = r.optLong("id")
            if (episodeId <= 0) continue
            val server = r.optString("server").ifBlank { "Cobe" }
            val rawName = r.optString("name").ifBlank { r.optString("slug") }
            val number = Regex("(\\d+(?:\\.\\d+)?)").find(rawName)?.value?.toFloatOrNull()
            val label = when {
                rawName.isBlank() -> ""
                number != null && rawName.all { it.isDigit() || it == '.' } -> "Tập $rawName"
                else -> rawName
            }
            val list = byServer.getOrPut(server) { mutableListOf() }
            list.add(
                EpisodeItem(
                    label = label,
                    number = number ?: (list.size + 1).toFloat(),
                    slug = locator(movieSlug, episodeId),
                    streamUrl = "",
                    embedUrl = apiEmbedUrl(episodeId)
                )
            )
        }
        return byServer.entries.map { (server, episodes) ->
            ServerGroup(
                name = "$server · Cobe",
                langKey = langKeyOf(server),
                episodes = episodes.sortedBy { it.number }
            )
        }.sortedWith(
            compareByDescending<ServerGroup> { g -> g.episodes.maxOfOrNull { it.number } ?: 0f }
                .thenBy { it.name }
        )
    }

    /**
     * Resolve one episode locator to a playable target by reading the
     * watch page's embedded episode JSON (the API's per-episode endpoint
     * requires a token — the page is the only open path).
     *
     * - streamvsmov embed pages derive a direct HLS playlist by a fixed
     *   pattern (measured 200 today) — ExoPlayer-grade playback.
     * - type "m3u8" links are direct HLS already.
     * - Everything else (streamc...) stays an embed for the WebView
     *   surface. When the page cannot be parsed, the watch page itself
     *   is the embed fallback.
     */
    /** 1.3.4: one parsed resolvePlay result, kept for PLAY_CACHE_TTL_MS. */
    private data class CachedPlay(val play: CobePlay, val atMs: Long)

    private val playCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlay>()

    suspend fun resolvePlay(locator: String): CobePlay {
        // 1.3.4: short-term cache — Detail's probe, the player's init
        // ranking and embed failover all resolve the SAME locators
        // within minutes of each other, and the watch-page fetch is
        // the slowest call in the Cobe path. Only results parsed from
        // a real page are cached; the failure fallbacks below (watch
        // page unreachable / unparseable) are re-tried next time.
        playCache[locator]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMs < PLAY_CACHE_TTL_MS) {
                return cached.play
            }
        }
        val (movieSlug, episodeId) = parseLocator(locator)
            ?: throw IllegalStateException("Cobe: bad locator")
        val watchUrl = "$webBase/phim/$movieSlug/tap-$episodeId"
        val html = try {
            getText(watchUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Cobe watch page failed ($watchUrl): ${e.message}")
            return CobePlay.Embed(watchUrl)
        }
        val match = EPISODE_RSC.find(html) ?: EPISODE_RSC_LOOSE.find(html)
            ?: return CobePlay.Embed(watchUrl)
        // Both patterns capture (type, link) in groups 1 and 2.
        val type = match.groupValues[1]
        val link = match.groupValues[2].replace("\\/", "/")
        if (link.isBlank()) return CobePlay.Embed(watchUrl)
        val sm = STREAMVSMOV.find(link)
        val play = if (sm != null) {
            CobePlay.Hls("${sm.groupValues[1]}/stream/${sm.groupValues[2]}/master.m3u8")
        } else if (type.equals("m3u8", ignoreCase = true) || link.endsWith(".m3u8")) {
            CobePlay.Hls(link)
        } else {
            CobePlay.Embed(link)
        }
        playCache[locator] = CachedPlay(play, System.currentTimeMillis())
        return play
    }
}
