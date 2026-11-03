package com.example.nflix.data.source

import android.util.Log
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * What one PhimCobe episode resolves to at play time (see
 * [PhimCobeSource.resolvePlay]). [fallbacks] carries every other
 * candidate the playback API returned for the SAME episode (the
 * remaining HLS variants, then the embeds) — 1.3.6: a dead primary
 * must advance through these before the player gives up, because
 * resolvePlay used to throw the embeds away whenever an HLS existed
 * and the HLS hosts are route-gated.
 */
sealed class PhimCobePlay {
    abstract val url: String
    abstract val fallbacks: List<String>

    data class Hls(
        override val url: String,
        override val fallbacks: List<String> = emptyList()
    ) : PhimCobePlay()

    data class Embed(
        override val url: String,
        override val fallbacks: List<String> = emptyList()
    ) : PhimCobePlay()
}

/**
 * PhimCobe (phimcobe.com) — the Phim/Anime source from 1.3.5, by user
 * order 2026-11-03 15:10 ("bỏ luôn cobe và kkphim đi" + the
 * phimcobe.com link). It replaces BOTH cobephim.fun (whose web
 * listings 404 from the user's route and whose API host rotates) and
 * the phimapi.com KKPhim adapter (dropped in 1.3.4).
 *
 * Measured live 2026-11-03 before a line was written:
 * - The site is a Next.js app on the Ophim catalog (slugs match the
 *   KKPhim/Ophim pool: one-piece, bac-si-x-phan-1, ...). It publishes
 *   a clean public JSON API on its own domain:
 *     GET /api/public/films?slug=<list>&page=N — paged listings with
 *       REAL pagination (phim-bo 13,904 · phim-le 18,368 ·
 *       hoat-hinh 6,502 items; 10 per page). List slugs are a closed
 *       set; an unknown slug falls back to the full catalog
 *       (32,786) newest-first — that fallback IS the latest feed.
 *     GET /api/public/search?keyword=&page= — same record shape.
 *     GET /api/public/playback?slug=&ep=&server= →
 *       {"status":"success","hls":[...],"embed":[...]} — the direct
 *       HLS and the embeds, separated. Measured on Bác Sĩ X tập 1:
 *       hls = a.kvp726.com/.../index.m3u8 (the KK pool's host — the
 *       streams are the pool's, only the storefront is new), embeds
 *       = player.phimapi.com + streamc.
 * - Detail records ride the /phim/<slug> page payload as a "film"
 *   object: full record (categories, countries, isAnime, quality,
 *   language, year, current_episode, total_episodes, casts,
 *   director) + episodes grouped by server (link fields empty in
 *   the payload — playback is resolved per episode via the API).
 * - Genre/country/BXH web pages embed the SAME item records as
 *   "items":[...] with "total_pages" (verified /the-loai/… ×25,
 *   /quoc-gia/han-quoc = 87 pages). /xep-hang embeds "initialFilms"
 *   (20 light records with REAL view counters, one single chart —
 *   the site publishes no day/week split).
 * - NO actor pages (measured: /dien-vien/<slug> = 404) — the actor
 *   filmography feature died with this source; cast names still
 *   show as plain text from the record's `casts` string.
 * - Records carry NO timestamps: updatedEpochSec stays 0 and the
 *   relative-time label simply hides (TimeText guards <= 0).
 *
 * Identity note: [SOURCE_ID] and [LOCATOR_PREFIX] deliberately keep
 * the "cobe" VALUES. They are internal keys, not display text: rows
 * an older build saved (library, history, progress, the poster
 * fallback cache) carry them, and the slug universe is the same
 * pool lineage, so saved titles keep resolving by slug. A legacy
 * locator's numeric Cobe episode id cannot be mapped onto this
 * source's episode slugs — resuming an old episode fails with the
 * honest error and the title's Detail (fresh locators) still opens.
 */
class PhimCobeSource(
    private val client: OkHttpClient = defaultClient(),
    private val webBase: String = "https://www.phimcobe.com"
) {
    companion object {
        const val SOURCE_ID = "cobe"
        const val LOCATOR_PREFIX = "cobe:"
        private const val TAG = "PhimCobeSource"

        /** How long a parsed resolvePlay / detail payload stays reusable. */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        /**
         * The Phim sheet's genre table — the site's OWN categories,
         * every slug's /the-loai/<slug> listing verified live
         * (HTTP 200 + embedded items) on 2026-11-03. "Hoạt Hình" is
         * deliberately absent: Anime is its own tab (user order).
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

        /** Genre slugs a record's categories are filtered against (junk
         * pseudo-categories the pool mixes in — "phim-bo", "2026",
         * truncated slugs like "hai" — never become UI chips). */
        val GENRE_SLUGS: Set<String> = CATEGORIES.map { it.second }.toSet()

        /**
         * The Phim sheet's country table — every slug's /quoc-gia
         * listing verified live 2026-11-03 (200 + items); display
         * names are the site's own (read off its film records).
         */
        val COUNTRIES: List<Pair<String, String>> = listOf(
            "Hàn Quốc" to "han-quoc",
            "Trung Quốc" to "trung-quoc",
            "Nhật Bản" to "nhat-ban",
            "Đài Loan" to "dai-loan",
            "Âu Mỹ" to "au-my",
            "Anh" to "anh",
            "Pháp" to "phap",
            "Đức" to "duc",
            "Thái Lan" to "thai-lan",
            "Ấn Độ" to "an-do",
            "Việt Nam" to "viet-nam",
            "Hồng Kông" to "hong-kong",
            "Úc" to "uc",
            "Canada" to "canada",
            "Nga" to "nga",
            "Ý" to "y",
            "Tây Ban Nha" to "tay-ban-nha",
            "Indonesia" to "indonesia",
            "Malaysia" to "malaysia",
            "Philippines" to "philippines",
            "Singapore" to "singapore"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Hard per-request ceiling (1.3.2 law): readTimeout alone
            // is an inactivity timeout and slow-drip responses reset
            // it forever.
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                        .build()
                )
            }
            .build()

        /** Episode slugs of this source are locators, not plain slugs. */
        fun isLocator(slug: String): Boolean = slug.startsWith(LOCATOR_PREFIX)

        /**
         * Locator = "cobe:<movieSlug>:<episodeSlug>:<serverIndex>".
         * The server index points into the detail payload's episodes
         * array — the playback API needs the server NAME, which only
         * the detail record carries.
         */
        fun locator(movieSlug: String, episodeSlug: String, serverIndex: Int): String =
            "$LOCATOR_PREFIX$movieSlug:$episodeSlug:$serverIndex"

        data class LocatorParts(
            val movieSlug: String,
            val episodeSlug: String,
            val serverIndex: Int
        )

        /**
         * Parse a locator back. Legacy Cobe locators
         * ("cobe:<slug>:<numericEpisodeId>", written by ≤1.3.4 into
         * saved progress) have only two segments and a numeric tail:
         * they parse as episodeSlug = "" so resolvePlay can fail
         * honestly instead of guessing an episode.
         */
        fun parseLocator(locator: String): LocatorParts? {
            if (!locator.startsWith(LOCATOR_PREFIX)) return null
            val rest = locator.removePrefix(LOCATOR_PREFIX)
            val parts = rest.split(":")
            return when {
                parts.size >= 3 -> LocatorParts(
                    movieSlug = parts[0],
                    episodeSlug = parts[1],
                    serverIndex = parts[2].toIntOrNull() ?: 0
                )
                parts.size == 2 -> LocatorParts(parts[0], "", -1)
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP + payload plumbing
    // ------------------------------------------------------------------

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("PhimCobe HTTP ${resp.code} ($url)")
            }
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun getApiJson(path: String): JSONObject =
        JSONObject(getText(webBase + path))

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun absolutize(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("/") -> webBase + url
        else -> "$webBase/$url"
    }

    /**
     * 1.3.6: the films/search API hands out poster URLs through the
     * site's own image proxy (/api/public/img/u.<base64 of the origin
     * URL>), but the proxy REJECTS the origin host those records
     * actually use — measured 2026-11-03: every latest-list record
     * sampled (20/20) points at img.nguonc.com and the proxy answers
     * 403 "Host not allowed" for it in every client, browsers
     * included, while the phimimg.com origins the site's web pages
     * use pass with 200. For a nguonc origin the app therefore loads
     * the ORIGIN directly (the Coil loader sends Referer:
     * nguonc.com for that host); the proxy form stays for origins
     * the proxy does allow.
     */
    private fun imageUrl(raw: String): String {
        val abs = absolutize(raw)
        val marker = "/api/public/img/u."
        val idx = abs.indexOf(marker)
        if (idx < 0) return abs
        val payload = abs.substring(idx + marker.length).substringBefore('?')
        var decoded = ""
        for (flags in intArrayOf(
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            android.util.Base64.NO_WRAP
        )) {
            decoded = try {
                String(android.util.Base64.decode(payload, flags), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            if (decoded.startsWith("http")) break
        }
        return if (decoded.startsWith("http") && decoded.contains("nguonc.com")) {
            decoded
        } else {
            abs
        }
    }

    /**
     * Single-pass decode of the RSC JS-string escape layer — the same
     * proven decoder the Cobe adapter used (the naive replace corrupted
     * ~10–20% of records per page; this pass consumes each escape once).
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

    /** String-aware balanced {...} capture starting at [start] (must be '{'). */
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

    /** String-aware balanced [...] capture starting at [start] (must be '['). */
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

    /** Objects of the first "<key>":[...] array in already-decoded RSC text. */
    private fun extractArrayObjects(decoded: String, key: String): List<JSONObject> {
        val marker = "\"$key\":["
        val arrStart = decoded.indexOf(marker)
        if (arrStart < 0) return emptyList()
        val arr = captureArray(decoded, arrStart + marker.length - 1) ?: return emptyList()
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

    // ------------------------------------------------------------------
    // Record parsing (films API items, search items, "film" objects —
    // one shared shape; detail adds "episodes"/"casts"/"director")
    // ------------------------------------------------------------------

    private fun badgeOf(language: String): String = when {
        language.contains("Thuyết Minh", true) -> "TM"
        language.contains("Vietsub", true) -> "VS"
        else -> ""
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

    /** (current, total) out of a current_episode string: "Tập 14",
     * "Hoàn tất (24/24)", "Tập 36 / 40" all occur in the pool. */
    private fun episodeCountsOf(m: JSONObject): Pair<Int, Int> {
        val cur = m.optString("current_episode").trim()
        val pair = Regex("\\((\\d+)\\s*/\\s*(\\d+)\\)").find(cur)
        val declaredTotal = m.optInt("total_episodes")
        val total = pair?.groupValues?.get(2)?.toIntOrNull()
            ?: declaredTotal.takeIf { it > 0 }
            ?: Regex("\\d+").findAll(cur).lastOrNull()?.value?.toIntOrNull()
            ?: 0
        val current = pair?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\d+").find(cur)?.value?.toIntOrNull()
            ?: 0
        return current to total
    }

    private fun isCompletedRecord(m: JSONObject): Boolean {
        if (m.optString("type") == "movie") return true
        if (m.optString("current_episode").contains("Hoàn tất", true)) return true
        val (current, total) = episodeCountsOf(m)
        return total > 0 && current >= total
    }

    private fun parseItem(m: JSONObject, family: Family): TitleItem {
        val slug = m.optString("slug")
        val type = m.optString("type")
        val isMovie = type == "movie"
        val (current, _) = episodeCountsOf(m)
        val genres = mutableListOf<String>()
        val genreSlugs = mutableListOf<String>()
        var rawHoatHinh = false
        val cats = m.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val s = c.optString("slug")
            // 1.3.7: the hoat-hinh umbrella is tracked even though it
            // is not a display genre — it is the fallback signal for
            // the Phim tab's animation filter when a record's
            // isAnime flag is missing.
            if (s == "hoat-hinh") rawHoatHinh = true
            if (s in GENRE_SLUGS) {
                genreSlugs.add(s)
                genres.add(c.optString("name").ifBlank { s })
            }
        }
        val countrySlugs = mutableListOf<String>()
        val countries = m.optJSONArray("countries") ?: JSONArray()
        for (i in 0 until countries.length()) {
            countries.optJSONObject(i)?.optString("slug")
                ?.takeIf { it.isNotBlank() }?.let { countrySlugs.add(it) }
        }
        val poster = imageUrl(m.optString("poster_url"))
        val thumb = imageUrl(m.optString("thumb_url"))
        val language = m.optString("language")
        return TitleItem(
            id = "${family.name}:$SOURCE_ID:$slug",
            family = family,
            sourceId = SOURCE_ID,
            slug = slug,
            name = m.optString("name"),
            originName = m.optString("original_name"),
            posterUrl = poster.ifBlank { thumb },
            thumbUrl = thumb.ifBlank { poster },
            quality = m.optString("quality"),
            versionBadge = badgeOf(language),
            languages = language,
            latestLabel = when {
                isMovie -> "Full"
                current > 0 -> "Tập $current"
                else -> m.optString("current_episode")
            },
            episodeCount = if (isMovie) 0 else current,
            year = m.optInt("year"),
            genres = genres,
            genreSlugs = genreSlugs,
            completed = isCompletedRecord(m),
            // The source publishes no timestamps — 0 hides the
            // relative-time label rather than inventing one.
            updatedEpochSec = 0,
            countrySlugs = countrySlugs,
            isAnime = m.optBoolean("isAnime") || rawHoatHinh
        )
    }

    // ------------------------------------------------------------------
    // Listings
    // ------------------------------------------------------------------

    /** One /api/public/films page for a KNOWN list slug + total pages. */
    private suspend fun apiFilms(
        listSlug: String,
        page: Int,
        family: Family
    ): Pair<List<TitleItem>, Int> {
        val root = getApiJson("/api/public/films?slug=${enc(listSlug)}&page=$page")
        if (root.optString("status") != "success") {
            throw IllegalStateException(
                "PhimCobe: " + root.optString("message").ifBlank { "danh sách lỗi" }
            )
        }
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseItem(it, family) }
        }.distinctBy { it.slug }
        val totalPages = root.optJSONObject("pagination")?.optInt("total_pages", page) ?: page
        return items to totalPages
    }

    /**
     * 1.3.7: [count] consecutive API pages folded into one UI page.
     * The API serves only 10 records per page (the app's shelves and
     * grids were built for ~24-record pages), so a single API page
     * read as "a handful of titles" on device (user verdict 1.3.6).
     * UI page N maps to API pages (N-1)*count+1 .. N*count.
     */
    private suspend fun apiFilmsMulti(
        listSlug: String,
        uiPage: Int,
        count: Int,
        family: Family
    ): Pair<List<TitleItem>, Int> {
        val all = mutableListOf<TitleItem>()
        var totalPages = uiPage
        val first = (uiPage - 1) * count + 1
        for (p in first until first + count) {
            val (items, total) = apiFilms(listSlug, p, family)
            totalPages = total
            // 1.3.11: an empty page mid-fold must NOT truncate the
            // fold — from the user's network a page can come back
            // empty once and the old break turned that into "the
            // catalog ends here". Only the source's own page count
            // ends a fold.
            if (p >= total) break
            all += items
        }
        return all.distinctBy { it.slug } to totalPages
    }

    /** Newest-updates feed, [count] API pages per UI page. */
    suspend fun latestMulti(page: Int, count: Int): Pair<List<TitleItem>, Int> =
        apiFilmsMulti("phim-moi", page, count, Family.PHIM)

    /** Kind listing, [count] API pages per UI page. */
    suspend fun kindMulti(
        kind: String,
        page: Int,
        count: Int
    ): Pair<List<TitleItem>, Int> = when (kind) {
        "phim-bo", "phim-le" -> apiFilmsMulti(kind, page, count, Family.PHIM)
        else -> latestMulti(page, count)
    }

    /**
     * One web listing page (/the-loai/<slug>, /quoc-gia/<slug>): the
     * RSC payload embeds the same records as "items" + "total_pages".
     * [kind] filters on the records' OWN type ("phim-le" keeps only
     * movies, "phim-bo" keeps only non-movies), "" keeps both.
     */
    suspend fun webListPage(
        path: String,
        page: Int,
        family: Family,
        kind: String = ""
    ): Pair<List<TitleItem>, Int> {
        val html = getText("$webBase$path?page=$page")
        val decoded = decodeRscLayer(html)
        val totalPages = Regex("""\"total_pages\":(\d+)""").find(decoded)
            ?.groupValues?.get(1)?.toIntOrNull() ?: page
        val items = extractArrayObjects(decoded, "items")
            .filter { it.optString("slug").isNotBlank() }
            .filter {
                when (kind) {
                    "phim-le" -> it.optString("type") == "movie"
                    "phim-bo" -> it.optString("type") != "movie"
                    else -> true
                }
            }
            .map { parseItem(it, family) }
        return items.distinctBy { it.slug } to totalPages
    }

    /**
     * The newest-updates feed. The films API's list slugs are a
     * closed set — any other slug (this one included) falls back to
     * the full catalog ordered newest-first (measured: its head
     * matches the site's own "mới nhất" surface item for item).
     */
    suspend fun latestPage(page: Int): Pair<List<TitleItem>, Int> =
        apiFilms("phim-moi", page, Family.PHIM)

    /** Series / movie listing pages of the catalog. */
    suspend fun kindPage(kind: String, page: Int): Pair<List<TitleItem>, Int> = when (kind) {
        "phim-bo", "phim-le" -> apiFilms(kind, page, Family.PHIM)
        else -> latestPage(page)
    }

    /** The animation pool page (tab Anime's catalog). */
    suspend fun animePoolPage(page: Int): Pair<List<TitleItem>, Int> =
        apiFilms("hoat-hinh", page, Family.ANIME)

    /** One /the-loai/<slug> page, kind facet applied on raw type. */
    suspend fun categoryPage(
        categorySlug: String,
        page: Int,
        kind: String,
        family: Family = Family.PHIM
    ): Pair<List<TitleItem>, Int> =
        webListPage("/the-loai/$categorySlug", page, family, kind)

    /** One /quoc-gia/<slug> page, kind facet applied on raw type. */
    suspend fun countryPage(
        countrySlug: String,
        page: Int,
        kind: String,
        family: Family = Family.PHIM
    ): Pair<List<TitleItem>, Int> =
        webListPage("/quoc-gia/$countrySlug", page, family, kind)

    // ------------------------------------------------------------------
    // Search + view chart
    // ------------------------------------------------------------------

    /**
     * Search via the site's own API. Items carry the source's isAnime
     * flag, so results are familied by the record itself (the Cobe
     * adapter could not tell and defaulted everything to PHIM).
     */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 24): List<TitleItem> {
        val root = getApiJson("/api/public/search?keyword=${enc(keyword)}&page=$page")
        if (root.optString("status") != "success") return emptyList()
        val arr = root.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            parseItem(m, if (m.optBoolean("isAnime")) Family.ANIME else Family.PHIM)
        }
    }

    /**
     * The site's single view chart (/xep-hang → "initialFilms", 20
     * light records with REAL view counters). The site publishes no
     * day/week split — [weekly] is accepted for signature parity with
     * the retired Cobe adapter and ignored; the Top UI no longer
     * offers a fake period toggle for this chart.
     *
     * With [animeOnly], each chart entry is classified through its
     * own detail record (bounded parallel): the light chart records
     * carry no isAnime/country data, and an unclassifiable entry is
     * dropped rather than guessed. The caller still applies the
     * Anime tab law (country gate, donghua exclusion) on top.
     */
    suspend fun searchByViews(
        weekly: Boolean,
        limit: Int = 24,
        family: Family = Family.PHIM,
        animeOnly: Boolean = false
    ): List<TitleItem> {
        val html = getText("$webBase/xep-hang")
        val decoded = decodeRscLayer(html)
        val films = extractArrayObjects(decoded, "initialFilms")
        if (!animeOnly) {
            return films.mapNotNull { f ->
                val slug = f.optString("slug")
                if (slug.isBlank()) return@mapNotNull null
                val (current, total) = episodeCountsOf(f)
                val poster = imageUrl(f.optString("poster_url"))
                val thumb = imageUrl(f.optString("thumb_url"))
                TitleItem(
                    id = "${family.name}:$SOURCE_ID:$slug",
                    family = family,
                    sourceId = SOURCE_ID,
                    slug = slug,
                    name = f.optString("name"),
                    originName = f.optString("original_name"),
                    posterUrl = poster.ifBlank { thumb },
                    thumbUrl = thumb.ifBlank { poster },
                    viewCount = f.optLong("views"),
                    latestLabel = when {
                        total > 0 && current >= total -> "Full"
                        current > 0 -> "Tập $current"
                        else -> f.optString("current_episode")
                    },
                    episodeCount = current,
                    completed = total > 0 && current >= total
                )
            }.take(limit)
        }
        val semaphore = Semaphore(6)
        val classified = coroutineScope {
            films.map { f ->
                async {
                    val slug = f.optString("slug")
                    if (slug.isBlank()) return@async null
                    semaphore.withPermit {
                        runCatching { fetchFilm(slug) }.getOrNull()
                            ?.takeIf { it.optBoolean("isAnime") }
                            ?.let { film ->
                                parseItem(film, Family.ANIME)
                                    .copy(viewCount = f.optLong("views"))
                            }
                    }
                }
            }.awaitAll()
        }
        return classified.filterNotNull().take(limit)
    }

    // ------------------------------------------------------------------
    // Detail + playback
    // ------------------------------------------------------------------

    private data class CachedFilm(val film: JSONObject, val atMs: Long)

    private val filmCache = java.util.concurrent.ConcurrentHashMap<String, CachedFilm>()

    /**
     * The full "film" object of /phim/<slug> (record + episodes).
     * Cached briefly: Detail, the Top-chart classifier and
     * resolvePlay all read the same payload within minutes.
     */
    suspend fun fetchFilm(slug: String): JSONObject {
        filmCache[slug]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMs < CACHE_TTL_MS) {
                return cached.film
            }
        }
        val html = getText("$webBase/phim/$slug")
        val decoded = decodeRscLayer(html)
        val marker = "\"film\":{"
        val idx = decoded.indexOf(marker)
        if (idx < 0) throw IllegalStateException("PhimCobe: không thấy dữ liệu phim ($slug)")
        val objText = captureObject(decoded, idx + marker.length - 1)
            ?: throw IllegalStateException("PhimCobe: dữ liệu phim hỏng ($slug)")
        val film = JSONObject(objText)
        filmCache[slug] = CachedFilm(film, System.currentTimeMillis())
        return film
    }

    /** Full (label to slug) real categories of one title, from its detail record. */
    suspend fun categoriesOf(slug: String): List<Pair<String, String>> {
        val film = fetchFilm(slug)
        val item = parseItem(film, Family.ANIME)
        return item.genres.zip(item.genreSlugs)
    }

    suspend fun detail(slug: String, family: Family = Family.PHIM): TitleDetail {
        val film = fetchFilm(slug)
        val base = parseItem(film, family)
        val groups = parseGroups(film, slug)
        // Released truth = the highest episode number really listed
        // on a server — the declared current_episode lags behind it
        // (measured: One Piece declares Tập 1174, lists 1186+).
        val released = groups.maxOfOrNull { g ->
            g.episodes.maxOfOrNull { it.number } ?: 0f
        }?.toInt() ?: 0
        // Completion comes from the record's own counts, NOT from
        // `released`: episode numbering can reach the declared total
        // while a show is still airing (gaps in the listed numbers),
        // which would stamp One Piece "Hoàn thành" falsely.
        val completed = isCompletedRecord(film)
        val badge = when {
            groups.any { it.langKey == "tm" } -> "TM"
            groups.any { it.langKey == "vs" } -> "VS"
            groups.any { it.langKey == "lt" } -> "LT"
            else -> base.versionBadge
        }
        val description = film.optString("description").let { html ->
            if (html.isBlank()) "" else Jsoup.parseBodyFragment(html).text()
        }
        val casts = film.optString("casts")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val director = film.optString("director").trim()
        return TitleDetail(
            item = base.copy(
                episodeCount = if (released > 0) released else base.episodeCount,
                versionBadge = badge,
                completed = completed,
                latestLabel = if (released > 0 && film.optString("type") != "movie") {
                    "Tập $released"
                } else {
                    base.latestLabel
                }
            ),
            description = description,
            status = if (completed) "Hoàn thành" else "Đang tiến hành",
            totalCount = released,
            durationLabel = film.optString("time"),
            actors = casts,
            // No actor pages exist on this source (measured 404) —
            // cast stays plain text, never a dead link.
            actorRefs = emptyList(),
            directors = if (director.isBlank() || director == "Đang cập nhật") {
                emptyList()
            } else {
                director.split(",").map { it.trim() }.filter { it.isNotBlank() }
            },
            servers = groups,
            trailerUrl = "",
            genreLinks = base.genres.zip(base.genreSlugs)
        )
    }

    /** Episodes of the detail record, grouped by the source's server
     * labels (kept verbatim — e.g. "Vietsub (KKPhim VIP)" is the
     * pool's own name for its feed). Fuller groups first. */
    private fun parseGroups(film: JSONObject, movieSlug: String): List<ServerGroup> {
        val servers = film.optJSONArray("episodes") ?: JSONArray()
        val groups = mutableListOf<ServerGroup>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val serverName = s.optString("server_name").ifBlank { "Server ${i + 1}" }
            val data = s.optJSONArray("server_data") ?: JSONArray()
            val episodes = (0 until data.length()).mapNotNull { j ->
                val ep = data.optJSONObject(j) ?: return@mapNotNull null
                val epSlug = ep.optString("slug")
                if (epSlug.isBlank()) return@mapNotNull null
                val number = Regex("(\\d+(?:\\.\\d+)?)")
                    .find(epSlug)?.value?.toFloatOrNull()
                    ?: Regex("(\\d+(?:\\.\\d+)?)")
                        .find(ep.optString("name"))?.value?.toFloatOrNull()
                    ?: (j + 1).toFloat()
                EpisodeItem(
                    label = ep.optString("name").ifBlank { "Tập ${number.toInt()}" },
                    number = number,
                    slug = locator(movieSlug, epSlug, i),
                    streamUrl = "",
                    embedUrl = ""
                )
            }.sortedBy { it.number }
            if (episodes.isNotEmpty()) {
                groups.add(
                    ServerGroup(
                        name = serverName,
                        langKey = langKeyOf(serverName),
                        episodes = episodes
                    )
                )
            }
        }
        return groups.sortedWith(
            compareByDescending<ServerGroup> { g -> g.episodes.maxOfOrNull { it.number } ?: 0f }
                .thenBy { it.name }
        )
    }

    private data class CachedPlay(val play: PhimCobePlay, val atMs: Long)

    private val playCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlay>()

    /**
     * Resolve one episode locator through the playback API. The API
     * separates the direct HLS list from the embeds: a direct link
     * always wins (ExoPlayer-grade playback, no WebView); among
     * embeds, non-streamc hosts go first (streamc is Cloudflare-
     * gated on many routes — measured repeatedly on the user's
     * emulator). Only real parsed results are cached.
     */
    suspend fun resolvePlay(locator: String): PhimCobePlay {
        playCache[locator]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMs < CACHE_TTL_MS) {
                return cached.play
            }
        }
        val parts = parseLocator(locator)
            ?: throw IllegalStateException("PhimCobe: locator không hợp lệ")
        if (parts.episodeSlug.isBlank()) {
            // A ≤1.3.4 locator: its numeric Cobe episode id has no
            // meaning on this source. Fail honestly — reopening the
            // title's Detail mints fresh locators.
            throw IllegalStateException(
                "Tập này thuộc nguồn cũ — mở lại trang chi tiết của phim để lấy link phát mới"
            )
        }
        val serverName = try {
            val film = fetchFilm(parts.movieSlug)
            val servers = film.optJSONArray("episodes")
            if (parts.serverIndex in 0 until (servers?.length() ?: 0)) {
                servers?.optJSONObject(parts.serverIndex)?.optString("server_name")
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val play = playback(parts.movieSlug, parts.episodeSlug, serverName)
            ?: if (!serverName.isNullOrBlank()) {
                playback(parts.movieSlug, parts.episodeSlug, null)
            } else {
                null
            }
            ?: throw IllegalStateException(
                "PhimCobe: server chưa trả link phát cho tập này"
            )
        playCache[locator] = CachedPlay(play, System.currentTimeMillis())
        return play
    }

    /** One playback API call; null when the API reports no playable link. */
    private suspend fun playback(
        slug: String,
        epSlug: String,
        serverName: String?
    ): PhimCobePlay? {
        val serverParam = if (serverName.isNullOrBlank()) {
            ""
        } else {
            "&server=${enc(serverName)}"
        }
        val root = try {
            getApiJson(
                "/api/public/playback?slug=${enc(slug)}&ep=${enc(epSlug)}$serverParam"
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "playback failed ($slug/$epSlug): ${e.message}")
            return null
        }
        if (root.optString("status") != "success") return null
        // 1.3.6: keep EVERY candidate. Order: all direct HLS first
        // (ExoPlayer-grade, in the API's own order), then embeds —
        // non-streamc first, streamc last (Cloudflare-gated on many
        // routes). The first candidate is the primary; the rest ride
        // along as fallbacks the player walks on failure.
        val hlsUrls = mutableListOf<String>()
        val hls = root.optJSONArray("hls")
        if (hls != null) {
            for (i in 0 until hls.length()) {
                hls.optString(i).takeIf { it.isNotBlank() }?.let { hlsUrls.add(it) }
            }
        }
        val embedUrls = mutableListOf<String>()
        val embeds = root.optJSONArray("embed")
        if (embeds != null) {
            for (i in 0 until embeds.length()) {
                embeds.optString(i).takeIf { it.isNotBlank() }?.let { embedUrls.add(it) }
            }
        }
        val orderedEmbeds = embedUrls.filter { !it.contains("streamc") } +
            embedUrls.filter { it.contains("streamc") }
        val candidates = hlsUrls.map { it to true } + orderedEmbeds.map { it to false }
        val deduped = candidates.distinctBy { it.first }
        if (deduped.isEmpty()) return null
        val (primary, primaryIsHls) = deduped.first()
        val rest = deduped.drop(1).map { it.first }
        return if (primaryIsHls) {
            PhimCobePlay.Hls(primary, rest)
        } else {
            PhimCobePlay.Embed(primary, rest)
        }
    }
}
