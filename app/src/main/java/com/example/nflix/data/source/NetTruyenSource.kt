package com.example.nflix.data.source

import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * NetTruyen (nettruyen.gg) — source for the Manhwa family.
 * Server-rendered HTML; verified live 2026-11-01 (research/manhwa.md).
 * Chapter images live on image1.kcgsbok.com and REQUIRE
 * `Referer: https://nettruyen.gg/` — the app-wide Coil loader adds it.
 */
class NetTruyenSource(
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://nettruyen.gg"
) {
    companion object {
        const val SOURCE_ID = "nettruyen"
        const val MANHWA_GENRE = "manhwa-11400"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                        .build()
                )
            }
            .build()
    }

    private suspend fun getDoc(path: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("NetTruyen HTTP ${resp.code}")
            Jsoup.parse(resp.body?.string().orEmpty(), base)
        }
    }

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("NetTruyen HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun chapNumber(text: String): Float =
        Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

    private fun fmtNum(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()

    /**
     * 1.3.2: [family] is a parameter (was hardcoded MANHWA) so the
     * whole-site lists can re-home their items to TRUYEN when they
     * feed the Truyện tab as its fallback. The sourceId stays
     * NETTRUYEN either way — Detail/Reader route by sourceId, so a
     * re-homed item still opens through this source.
     */
    private fun parseItem(el: Element, family: Family = Family.MANHWA): TitleItem? {
        val link = el.selectFirst("a.jtip") ?: el.selectFirst("a[href*=/truyen-tranh/]") ?: return null
        val href = link.attr("href")
        val slug = href.substringAfter("/truyen-tranh/").trimEnd('/').substringBefore('/').substringBefore('?')
        if (slug.isBlank()) return null
        val name = link.text().trim()
            .ifBlank { el.selectFirst("h3, .title")?.text()?.trim().orEmpty() }
        if (name.isBlank()) return null
        val img = el.selectFirst("img.lazy, img.image-thumb, img")
        val poster = img?.let {
            it.attr("data-original").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }.orEmpty()
        val firstChap = el.selectFirst("li.chapter a, .chapter a")
        val chapText = firstChap?.text()?.trim().orEmpty()
        val chapNum = if (chapText.isNotBlank()) chapNumber(chapText) else -1f
        val timeText = el.selectFirst("li.chapter .time, .chapter .time")?.text()?.trim().orEmpty()
        return TitleItem(
            id = "${family.name}:$SOURCE_ID:$slug",
            family = family,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = poster,
            thumbUrl = poster,
            latestLabel = if (chapNum >= 0) "Chap ${fmtNum(chapNum)}" else "",
            episodeCount = if (chapNum >= 0) chapNum.toInt() else 0,
            updatedLabel = timeText
        )
    }

    private fun parseItems(doc: Document, family: Family = Family.MANHWA): List<TitleItem> =
        doc.select("div.item, .item").mapNotNull { parseItem(it, family) }.distinctBy { it.slug }

    // ------------------------------------------------------------------
    // 1.3.2: whole-site lists — the Truyện tab's FALLBACK shelves for
    // when both TruyenQQ hosts fail together (they are one operation;
    // measured dead-together on the user's device 2026-11-03 while
    // NetTruyen answered in <1s). Endpoints measured live 2026-11-03
    // (dossier truyen-fallback): /tim-truyen = latest site-wide,
    // sort=15 = brand-new series, sort=13 = Top ngày — 36 titles each.
    // The home page "/" is a landing page and parses ZERO titles —
    // never use it. Items default to family TRUYEN (re-homed); the
    // sourceId stays NETTRUYEN so Detail/Reader route here.
    // ------------------------------------------------------------------

    /** Whole-site latest updates — fallback for Truyện "Mới cập nhật". */
    suspend fun siteLatest(page: Int = 1, family: Family = Family.TRUYEN): List<TitleItem> =
        parseItems(getDoc("/tim-truyen?page=$page"), family)

    /** Whole-site brand-new series (sort=15) — fallback for Truyện "Mới tinh". */
    suspend fun siteNewest(page: Int = 1, family: Family = Family.TRUYEN): List<TitleItem> =
        parseItems(getDoc("/tim-truyen?sort=15&page=$page"), family)

    /** Whole-site Top ngày (sort=13) — fallback for the Truyện hot shelf. */
    suspend fun siteTopDay(page: Int = 1, family: Family = Family.TRUYEN): List<TitleItem> =
        parseItems(getDoc("/tim-truyen?sort=13&page=$page"), family)

    /** "Đang hot" for the Manhwa tab: genre list sorted by Top ngày (sort=13). */
    suspend fun hot(page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$MANHWA_GENRE?sort=13&page=$page"))

    /** Top tuần (sort=12) — the only real weekly chart among all sources. */
    suspend fun topWeek(page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$MANHWA_GENRE?sort=12&page=$page"))

    /** Newest-updated manhwa (default sort on the genre list). */
    suspend fun latest(page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$MANHWA_GENRE?page=$page"))

    suspend fun byGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$genreSlug?page=$page"))

    /**
     * Genre list with an explicit sort code (all verified live 2026-11-01,
     * each returns a genuinely different order): 13 = Top ngày, 12 = Top
     * tuần, 11 = Top tháng, 19 = Top Follow, 15 = Truyện mới ra mắt.
     */
    suspend fun byGenreSorted(genreSlug: String, sort: Int, page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$genreSlug?sort=$sort&page=$page"))

    /** Top tháng (sort=11) — real monthly chart for the Top tab. */
    suspend fun topMonth(page: Int = 1): List<TitleItem> =
        byGenreSorted(MANHWA_GENRE, 11, page)

    /** Top Follow (sort=19) — most-followed manhwa. */
    suspend fun topFollow(page: Int = 1): List<TitleItem> =
        byGenreSorted(MANHWA_GENRE, 19, page)

    /** "Mới tinh" (sort=15): brand-new series, not new chapters of old ones. */
    suspend fun newest(page: Int = 1): List<TitleItem> =
        byGenreSorted(MANHWA_GENRE, 15, page)

    /**
     * Genre list filtered by completion status (status: 1 = đang tiến hành,
     * 2 = hoàn thành — verified two-sided against detail pages 2026-11-01).
     */
    suspend fun byGenreStatus(genreSlug: String, status: Int, page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$genreSlug?status=$status&page=$page"))

    /** "Trọn bộ": the whole Manhwa category, completed only. */
    suspend fun completed(page: Int = 1): List<TitleItem> =
        byGenreStatus(MANHWA_GENRE, 2, page)

    /** "Trọn bộ" within one genre. */
    suspend fun completedByGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        byGenreStatus(genreSlug, 2, page)

    suspend fun search(keyword: String): List<TitleItem> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        return parseItems(getDoc("/tim-truyen?keyword=$q"))
    }

    suspend fun detail(slug: String): TitleDetail {
        val doc = getDoc("/truyen-tranh/$slug")
        val name = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" | ")?.substringBefore(" - NetTruyen")?.trim()
            ?: doc.selectFirst("h1.title-detail, h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val bodyText = doc.body().text()
        val status = when {
            bodyText.contains("Hoàn thành") -> "Hoàn thành"
            bodyText.contains("Đang tiến hành") -> "Đang tiến hành"
            else -> ""
        }

        // Full chapter list via the site's own JSON service (verified):
        // GET /Comic/Services/ComicService.asmx/ProcessChapterList?comicId=<id>
        val comicId = Regex("""comicId["' :=]+(\d+)""").find(doc.html())?.groupValues?.get(1)
        var chapters: List<ChapterItem> = emptyList()
        if (comicId != null) {
            try {
                val json = JSONObject(getText("/Comic/Services/ComicService.asmx/ProcessChapterList?comicId=$comicId"))
                val arr = json.optJSONArray("chapters")
                if (arr != null) {
                    val list = mutableListOf<ChapterItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val url = o.optString("url")
                        val num = chapNumber(o.optString("name"))
                        if (url.isBlank() || num < 0) continue
                        list.add(
                            ChapterItem(
                                label = "Chap ${fmtNum(num)}",
                                number = num,
                                id = url
                            )
                        )
                    }
                    chapters = list.sortedBy { it.number }
                }
            } catch (e: Exception) { /* fall back to embedded rows below */ }
        }
        if (chapters.isEmpty()) {
            val rows = doc.select("ul#chapter_list a[href*=/chuong-]")
            chapters = rows.mapNotNull { a ->
                val href = a.attr("href")
                val num = chapNumber(a.text()).takeIf { it >= 0 } ?: chapNumber(href)
                if (num < 0) return@mapNotNull null
                val time = a.parent()?.parent()?.selectFirst(".time, td:nth-child(2)")?.text()?.trim().orEmpty()
                ChapterItem(label = "Chap ${fmtNum(num)}", number = num, id = href, updatedLabel = time)
            }.distinctBy { it.number }.sortedBy { it.number }
        }

        val total = chapters.maxOfOrNull { it.number }?.toInt() ?: 0
        // Genres: the "Thể loại" row of the detail info block (ul.list-info
        // li.kind) — scoped there so the site-wide genre menu is not picked
        // up (verified live 2026-11-01).
        val genreLinks = mutableListOf<Pair<String, String>>()
        for (a in doc.select("ul.list-info li.kind a[href*=/tim-truyen/]")) {
            val href = a.attr("href")
            val gSlug = href.substringAfter("/tim-truyen/").trimEnd('/')
                .substringBefore('/').substringBefore('?')
            val gLabel = a.text().trim()
            if (gSlug.isNotBlank() && gLabel.isNotBlank() && genreLinks.none { it.second == gSlug }) {
                genreLinks.add(gLabel to gSlug)
            }
        }
        val item = TitleItem(
            id = "${Family.MANHWA.name}:$SOURCE_ID:$slug",
            family = Family.MANHWA,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = poster,
            thumbUrl = poster,
            latestLabel = if (total > 0) "Chap $total" else "",
            episodeCount = total,
            genres = genreLinks.map { it.first }
        )
        return TitleDetail(
            item = item,
            description = desc,
            status = status,
            totalCount = total,
            chapters = chapters,
            genreLinks = genreLinks
        )
    }

    /** [chapterPath] is the full path from the chapter list, e.g. /truyen-tranh/<slug>/chuong-228. */
    suspend fun chapterPages(chapterPath: String): List<String> {
        val doc = getDoc(chapterPath)
        val imgs = doc.select(".reading-detail img.lozad, .page-chapter img, img.lozad")
        return imgs.mapNotNull { img ->
            img.attr("data-src").ifBlank { img.attr("data-sv1") }.ifBlank { null }
        }.filter { it.contains("kcgsbok") || it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
    }
}
