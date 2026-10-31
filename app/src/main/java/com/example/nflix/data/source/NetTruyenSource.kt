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

    private fun parseItem(el: Element): TitleItem? {
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
            id = "${Family.MANHWA.name}:$SOURCE_ID:$slug",
            family = Family.MANHWA,
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

    private fun parseItems(doc: Document): List<TitleItem> =
        doc.select("div.item, .item").mapNotNull { parseItem(it) }.distinctBy { it.slug }

    /** "Đang hot" for the Manhwa tab: genre list sorted by Top ngày (sort=13). */
    suspend fun hot(page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$MANHWA_GENRE?sort=13&page=$page"))

    /** Newest-updated manhwa (default sort on the genre list). */
    suspend fun latest(page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$MANHWA_GENRE?page=$page"))

    suspend fun byGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        parseItems(getDoc("/tim-truyen/$genreSlug?page=$page"))

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
        val item = TitleItem(
            id = "${Family.MANHWA.name}:$SOURCE_ID:$slug",
            family = Family.MANHWA,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = poster,
            thumbUrl = poster,
            latestLabel = if (total > 0) "Chap $total" else "",
            episodeCount = total
        )
        return TitleDetail(
            item = item,
            description = desc,
            status = status,
            totalCount = total,
            chapters = chapters
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
