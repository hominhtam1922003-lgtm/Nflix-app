package com.example.nflix.data.source

import com.example.nflix.data.SettingsStore
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
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

        /** API host measured live 2026-11-01; rediscovery replaces it when it dies. */
        private const val FALLBACK_API_BASE =
            "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
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
        private val JS_CHUNK = Regex("""/_next/static/chunks/[\w.\-]+\.js""")
    }

    @Volatile
    private var apiBaseCache: String? = null

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
            val found = discoverApiBase()
            if (found != null && found != base) {
                apiBaseCache = found
                runCatching { settings?.setCobeApiHost(found) }
                return getText(found + path)
            }
            throw e
        }
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

    private fun parseItem(m: JSONObject, family: Family): TitleItem {
        val slug = m.optString("slug")
        val type = m.optString("type")
        val epCurrent = m.optString("episode_current").trim()
        val epNum = Regex("\\d+").find(epCurrent)?.value?.toIntOrNull() ?: 0
        val genres = mutableListOf<String>()
        val cats = m.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            cats.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
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
            updatedEpochSec = parseUpdatedAt(m)
        )
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
     * payload (no country field), so results default to PHIM — the merge
     * dedupe still lets the KKPhim copy of an anime win by slug.
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 24): List<TitleItem> {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val root = getApiJson("/movies/search?keyword=$kw&page=$page&limit=$limit")
        val arr = root.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseItem(it, Family.PHIM) }
        }
    }

    suspend fun detail(slug: String, family: Family = Family.PHIM): TitleDetail {
        val root = getApiJson("/movies/by-slug/$slug")
        val m = root.optJSONObject("movie") ?: throw IllegalStateException("Cobe: no movie")
        val movieId = m.optLong("id")
        val base = parseItem(m, family)
        val groups = try {
            fetchGroups(slug, movieId)
        } catch (e: Exception) {
            emptyList()
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
        // Genre links double as "Cùng thể loại" keys: prefer the KKPhim
        // slug for the same genre name (the two catalogs share the slug
        // system), so the shelf can be served by the KKPhim category API.
        val genreLinks = mutableListOf<Pair<String, String>>()
        val cats = m.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val name = c.optString("name")
            val ownSlug = c.optString("slug")
            if (name.isBlank()) continue
            val kkSlug = KkPhimSource.CATEGORIES
                .firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
            val linkSlug = kkSlug ?: ownSlug
            if (linkSlug.isNotBlank()) genreLinks.add(name to linkSlug)
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
            status = when (m.optString("status")) {
                "ongoing" -> "Đang tiến hành"
                "completed" -> "Hoàn thành"
                else -> ""
            },
            totalCount = total,
            durationLabel = m.optString("episode_time"),
            actors = peopleNames(m.optJSONArray("actors")),
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
    suspend fun resolvePlay(locator: String): CobePlay {
        val (movieSlug, episodeId) = parseLocator(locator)
            ?: throw IllegalStateException("Cobe: bad locator")
        val watchUrl = "$webBase/phim/$movieSlug/tap-$episodeId"
        val html = try {
            getText(watchUrl)
        } catch (e: Exception) {
            return CobePlay.Embed(watchUrl)
        }
        val match = EPISODE_RSC.find(html) ?: EPISODE_RSC_LOOSE.find(html)
            ?: return CobePlay.Embed(watchUrl)
        // Both patterns capture (type, link) in groups 1 and 2.
        val type = match.groupValues[1]
        val link = match.groupValues[2].replace("\\/", "/")
        if (link.isBlank()) return CobePlay.Embed(watchUrl)
        val sm = STREAMVSMOV.find(link)
        if (sm != null) {
            return CobePlay.Hls("${sm.groupValues[1]}/stream/${sm.groupValues[2]}/master.m3u8")
        }
        return if (type.equals("m3u8", ignoreCase = true) || link.endsWith(".m3u8")) {
            CobePlay.Hls(link)
        } else {
            CobePlay.Embed(link)
        }
    }
}
