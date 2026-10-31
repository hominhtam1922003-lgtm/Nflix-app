package com.example.nflix.data.source

import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * KKPhim API (phimapi.com) — primary source for the Phim family.
 * Spec verified live 2026-11-01: see ~/workspace/nflix/research/phim.md.
 */
class KkPhimSource(
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        const val SOURCE_ID = "kkphim"
        private const val BASE = "https://phimapi.com"
        private const val CDN = "https://phimimg.com"

        /** Category (label to slug) for the Phim filter sheet — KKPhim /v1/api/the-loai, verified live 2026-11-01. */
        val CATEGORIES = listOf(
            "Hành động" to "hanh-dong",
            "Tình cảm" to "tinh-cam",
            "Hài hước" to "hai-huoc",
            "Tâm lý" to "tam-ly",
            "Hình sự" to "hinh-su",
            "Chiến tranh" to "chien-tranh",
            "Phiêu lưu" to "phieu-luu",
            "Võ thuật" to "vo-thuat",
            "Cổ trang" to "co-trang",
            "Kinh dị" to "kinh-di",
            "Viễn tưởng" to "vien-tuong",
            "Khoa học" to "khoa-hoc",
            "Thần thoại" to "than-thoai",
            "Học đường" to "hoc-duong",
            "Gia đình" to "gia-dinh",
            "Bí ẩn" to "bi-an",
            "Kinh điển" to "kinh-dien",
            "Chính kịch" to "chinh-kich",
            "Lịch sử" to "lich-su",
            "Miền Tây" to "mien-tay",
            "Thể thao" to "the-thao",
            "Trẻ em" to "tre-em",
            "Âm nhạc" to "am-nhac",
            "Tài liệu" to "tai-lieu",
            "Phim ngắn" to "phim-ngan",
            "Phim 18+" to "phim-18"
        )

        /** Country (label to slug) for the Phim filter sheet — KKPhim /v1/api/quoc-gia, verified live 2026-11-01. */
        val COUNTRIES = listOf(
            "Hàn Quốc" to "han-quoc",
            "Trung Quốc" to "trung-quoc",
            "Nhật Bản" to "nhat-ban",
            "Âu Mỹ" to "au-my",
            "Việt Nam" to "viet-nam",
            "Thái Lan" to "thai-lan",
            "Đài Loan" to "dai-loan",
            "Hồng Kông" to "hong-kong",
            "Anh" to "anh",
            "Pháp" to "phap",
            "Đức" to "duc",
            "Ấn Độ" to "an-do",
            "Indonesia" to "indonesia",
            "Philippines" to "philippines",
            "Malaysia" to "malaysia",
            "Canada" to "canada",
            "Úc" to "uc",
            "Tây Ban Nha" to "tay-ban-nha",
            "Ý" to "y",
            "Nga" to "nga",
            "Brazil" to "brazil",
            "Mexico" to "mexico",
            "Thổ Nhĩ Kỳ" to "tho-nhi-ky",
            "Hà Lan" to "ha-lan",
            "Quốc gia khác" to "quoc-gia-khac"
        )

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
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("KKPhim HTTP ${resp.code}")
            JSONObject(body)
        }
    }

    private fun imageUrl(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("http") -> raw
        // TMDB image fragments (t/p/w780/...) live on image.tmdb.org, not phimimg.
        raw.startsWith("t/p/") -> "https://image.tmdb.org/${raw.trimStart('/')}"
        else -> "$CDN/${raw.trimStart('/')}"
    }

    /** KKPhim modified.time is VN wall time mislabeled as Z: normalize to a true epoch (-7h). */
    private fun parseModified(item: JSONObject): Long {
        val t = item.optJSONObject("modified")?.optString("time").orEmpty()
        if (t.isBlank()) return 0
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            ((fmt.parse(t)?.time ?: 0) / 1000) - 7 * 3600
        } catch (e: Exception) {
            0
        }
    }

    private fun versionBadge(item: JSONObject): String {
        val keys = item.optJSONArray("lang_key") ?: return ""
        val set = (0 until keys.length()).map { keys.optString(it) }.toSet()
        return when {
            "tm" in set -> "TM"   // design default priority: Thuyết minh first
            "vs" in set -> "VS"
            else -> ""
        }
    }

    /**
     * Anime = KKPhim type "hoathinh" AND Japanese origin (verified live:
     * the type field is reliable, the category list is not — some animation
     * titles carry no hoat-hinh category). Chinese animation (donghua)
     * stays PHIM here — the Donghua tab is served by HHKungfu.
     */
    private fun familyOf(item: JSONObject): Family {
        if (item.optString("type") != "hoathinh") return Family.PHIM
        val countries = item.optJSONArray("country") ?: return Family.PHIM
        for (i in 0 until countries.length()) {
            if (countries.optJSONObject(i)?.optString("slug") == "nhat-ban") return Family.ANIME
        }
        return Family.PHIM
    }

    private fun parseItem(item: JSONObject): TitleItem {
        val slug = item.optString("slug")
        val family = familyOf(item)
        val epCurrent = item.optString("episode_current")
        val epNum = Regex("\\d+").find(epCurrent)?.value?.toIntOrNull() ?: 0
        val genres = mutableListOf<String>()
        val cats = item.optJSONArray("category") ?: JSONArray()
        for (i in 0 until cats.length()) cats.optJSONObject(i)?.optString("name")?.let { genres.add(it) }
        // Cross-fallback: ~3% of titles ship an empty thumb (or poster) — a
        // blank field must never reach the UI when the sibling image exists.
        val poster = imageUrl(item.optString("poster_url"))
        val thumb = imageUrl(item.optString("thumb_url"))
        return TitleItem(
            id = "${family.name}:$SOURCE_ID:$slug",
            family = family,
            sourceId = SOURCE_ID,
            slug = slug,
            name = item.optString("name"),
            originName = item.optString("origin_name"),
            posterUrl = poster.ifBlank { thumb },
            thumbUrl = thumb.ifBlank { poster },
            quality = item.optString("quality"),
            versionBadge = versionBadge(item),
            latestLabel = epCurrent,
            episodeCount = epNum,
            year = item.optInt("year"),
            genres = genres,
            updatedEpochSec = parseModified(item)
        )
    }

    private fun parseList(root: JSONObject): List<TitleItem> {
        val data = root.optJSONObject("data")
        val items = data?.optJSONArray("items") ?: root.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { parseItem(it) }
        }
    }

    /** Newest-updated list (verified legacy endpoint: flat items, absolute image URLs). */
    suspend fun latest(page: Int = 1): List<TitleItem> {
        val root = getJson("$BASE/danh-sach/phim-moi-cap-nhat?page=$page")
        return parseList(root)
    }

    /** kind: "phim-bo" | "phim-le" | "hoat-hinh" */
    suspend fun listByKind(kind: String, page: Int = 1, limit: Int = 24): List<TitleItem> {
        val root = getJson("$BASE/v1/api/danh-sach/$kind?page=$page&limit=$limit")
        return parseList(root)
    }

    /**
     * Filtered listing for the Phim tab's "Thể loại" sheet. Empty category /
     * country and year 0 mean "all". "chieu-rap" is a flag on phim-le, so it
     * maps to the phim-le list (the flag itself is applied by cinema()).
     */
    suspend fun listFiltered(
        kind: String,
        category: String = "",
        country: String = "",
        year: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): List<TitleItem> {
        val effectiveKind = if (kind == "chieu-rap") "phim-le" else kind
        val params = StringBuilder("?page=$page&limit=$limit")
        if (category.isNotEmpty()) params.append("&category=$category")
        if (country.isNotEmpty()) params.append("&country=$country")
        if (year > 0) params.append("&year=$year")
        val root = getJson("$BASE/v1/api/danh-sach/$effectiveKind$params")
        return parseList(root)
    }

    /**
     * Anime tab: Japanese animation only, filtered SERVER-side via the
     * country param on the hoat-hinh list (verified live: 1,700 JP titles;
     * Chinese titles stay out — donghua lives in the Donghua tab).
     */
    suspend fun anime(page: Int = 1): List<TitleItem> =
        listFiltered(kind = "hoat-hinh", country = "nhat-ban", page = page)

    suspend fun cinema(page: Int = 1, limit: Int = 24): List<TitleItem> {
        // Chiếu rạp has no dedicated endpoint: filter phim-le by the chieurap flag.
        val root = getJson("$BASE/v1/api/danh-sach/phim-le?page=$page&limit=48")
        val data = root.optJSONObject("data")
        val items = data?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.takeIf { it.optBoolean("chieurap") }?.let { parseItem(it) }
        }.take(limit)
    }

    suspend fun search(keyword: String, page: Int = 1, limit: Int = 24): List<TitleItem> {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val root = getJson("$BASE/v1/api/tim-kiem?keyword=$kw&page=$page&limit=$limit")
        return parseList(root)
    }

    suspend fun detail(slug: String): TitleDetail {
        val root = getJson("$BASE/v1/api/phim/$slug")
        val data = root.optJSONObject("data") ?: throw IllegalStateException("KKPhim: empty detail")
        val itemJson = data.optJSONObject("item") ?: throw IllegalStateException("KKPhim: no item")
        val base = parseItem(itemJson)
        val servers = mutableListOf<ServerGroup>()
        val eps = itemJson.optJSONArray("episodes") ?: JSONArray()
        for (s in 0 until eps.length()) {
            val srv = eps.optJSONObject(s) ?: continue
            val serverName = srv.optString("server_name")
            val langKey = when {
                serverName.startsWith("Thuyết Minh", true) -> "tm"
                serverName.startsWith("Vietsub", true) -> "vs"
                serverName.startsWith("Lồng Tiếng", true) -> "lt"
                else -> ""
            }
            val dataArr = srv.optJSONArray("server_data") ?: JSONArray()
            val episodes = (0 until dataArr.length()).mapNotNull { e ->
                val ep = dataArr.optJSONObject(e) ?: return@mapNotNull null
                val label = ep.optString("name")
                EpisodeItem(
                    label = label,
                    number = Regex("\\d+").find(label)?.value?.toFloatOrNull() ?: (e + 1).toFloat(),
                    slug = ep.optString("slug"),
                    streamUrl = ep.optString("link_m3u8"),
                    embedUrl = ep.optString("link_embed")
                )
            }
            if (episodes.isNotEmpty()) servers.add(ServerGroup(serverName, langKey, episodes))
        }
        val total = itemJson.optInt("episode_total")
        return TitleDetail(
            item = base.copy(episodeCount = if (total > 0) total else base.episodeCount),
            description = itemJson.optString("content").trim(),
            status = when (itemJson.optString("status")) {
                "ongoing" -> "Đang tiến hành"
                "completed" -> "Hoàn thành"
                else -> ""
            },
            totalCount = total,
            durationLabel = itemJson.optString("time"),
            actors = jsonStringList(itemJson.optJSONArray("actor")),
            directors = jsonStringList(itemJson.optJSONArray("director")),
            servers = servers
        )
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
