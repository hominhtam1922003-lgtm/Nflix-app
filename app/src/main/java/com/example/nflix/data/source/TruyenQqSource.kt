package com.example.nflix.data.source

import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * TruyenQQ mirror system — primary source for the Truyện family.
 * Verified live 2026-11-01: see ~/workspace/nflix/research/truyen.md.
 * Base host lives in remote config in production (mirrors rotate);
 * the twin mirror is the automatic fallback.
 */
class TruyenQqSource(
    private val client: OkHttpClient = defaultClient(),
    private val hosts: List<String> = listOf("https://nettruyenqq.org", "https://www.truyentranhqq.org")
) {
    companion object {
        const val SOURCE_ID = "truyenqq"
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

    @Volatile
    private var activeHost: String = hosts.first()

    private suspend fun getDoc(path: String): Document = withContext(Dispatchers.IO) {
        val candidates = listOf(activeHost) + hosts.filter { it != activeHost }
        var lastError: Exception? = null
        for (host in candidates) {
            try {
                val req = Request.Builder().url(host + path).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val html = resp.body?.string().orEmpty()
                    activeHost = host
                    return@withContext Jsoup.parse(html, host)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("TruyenQQ: all hosts failed")
    }

    /** Slug = last path segment of an <a> href pointing at a title page. */
    private fun slugOf(href: String): String =
        href.trimEnd('/').substringAfterLast('/')

    /**
     * Home page = latest updates. Each card links to /<slug> and shows the
     * newest chapters with relative times; structure verified in the spec.
     */
    suspend fun latest(): List<TitleItem> = parseCards(getDoc("/"))

    /** Genre listing — same card structure as the home page. */
    suspend fun byGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        parseCards(getDoc("/the-loai/$genreSlug" + if (page > 1) "?page=$page" else ""))

    private fun parseCards(doc: Document): List<TitleItem> {
        val seen = LinkedHashMap<String, TitleItem>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            // Title links look like /<slug> (single segment, no "chapter", no known prefixes)
            val slug = slugOf(href)
            if (!href.startsWith("/") || href.contains("chapter") || slug.isBlank() ||
                slug in setOf("tim-kiem", "the-loai", "dang-nhap", "dang-ky") || seen.containsKey(slug)
            ) continue
            val card = a.closest("li, .item, .list-item, div") ?: continue
            val img = card.selectFirst("img")
            val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
            val name = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.text().trim()
            if (name.isBlank() || !poster.contains("/media/book/")) continue
            val chapMatch = Regex("[Cc]hapter\\s+(\\d+(?:\\.\\d+)?)").find(card.text())
            val chapNum = chapMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            seen[slug] = TitleItem(
                id = "${Family.TRUYEN.name}:$SOURCE_ID:$slug",
                family = Family.TRUYEN,
                sourceId = SOURCE_ID,
                slug = slug,
                name = name,
                posterUrl = poster,
                thumbUrl = poster,
                latestLabel = if (chapNum > 0) "Chap ${formatNum(chapNum)}" else "",
                episodeCount = chapNum.toInt()
            )
        }
        return seen.values.toList()
    }

    suspend fun detail(slug: String): TitleDetail {
        val doc = getDoc("/$slug")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val name = ogTitle.substringBefore(" [Tới").substringBefore(" - ").trim()
            .ifBlank { doc.title().substringBefore(" |").trim() }
        val chapters = mutableListOf<ChapterItem>()
        val chapterRegex = Regex("/${Regex.escape(slug)}/chapter-(\\d+(?:\\.\\d+)?)")
        for (a in doc.select("a[href]")) {
            val m = chapterRegex.find(a.attr("href")) ?: continue
            val num = m.groupValues[1].toFloatOrNull() ?: continue
            if (chapters.any { it.number == num }) continue
            chapters.add(
                ChapterItem(
                    label = "Chap ${formatNum(num)}",
                    number = num,
                    id = "$slug/chapter-${m.groupValues[1]}",
                    updatedLabel = a.parent()?.text()?.trim().orEmpty()
                )
            )
        }
        chapters.sortByDescending { it.number }
        val total = chapters.maxOfOrNull { it.number }?.toInt() ?: chapters.size
        val item = TitleItem(
            id = "${Family.TRUYEN.name}:$SOURCE_ID:$slug",
            family = Family.TRUYEN,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = ogImage,
            thumbUrl = ogImage,
            latestLabel = if (total > 0) "Chap $total" else "",
            episodeCount = total
        )
        val status = when {
            desc.contains("Đang tiến hành", true) -> "Đang tiến hành"
            desc.contains("Hoàn thành", true) -> "Hoàn thành"
            else -> ""
        }
        return TitleDetail(item = item, description = desc, status = status, totalCount = total, chapters = chapters)
    }

    /** Page image URLs of one chapter, in reading order. */
    suspend fun chapterPages(chapterId: String): List<String> {
        val doc = getDoc("/$chapterId")
        val urls = mutableListOf<String>()
        for (img in doc.select("img[data-src]")) {
            val src = img.attr("data-src")
            if (src.contains("cc3t.net") && src.endsWith(".jpg") && src !in urls) urls.add(src)
        }
        return urls
    }

    suspend fun search(keyword: String): List<TitleItem> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val doc = getDoc("/tim-kiem?s=$q")
        val results = mutableListOf<TitleItem>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val slug = slugOf(href)
            if (!href.startsWith("/") || href.contains("chapter") || slug.isBlank() || slug in seen) continue
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: continue
            val poster = img.attr("data-src").ifBlank { img.attr("src") }
            if (!poster.contains("/media/book/")) continue
            val name = img.attr("alt").ifBlank { a.text().trim() }
            if (name.isBlank()) continue
            seen.add(slug)
            results.add(
                TitleItem(
                    id = "${Family.TRUYEN.name}:$SOURCE_ID:$slug",
                    family = Family.TRUYEN,
                    sourceId = SOURCE_ID,
                    slug = slug,
                    name = name,
                    posterUrl = poster,
                    thumbUrl = poster
                )
            )
        }
        return results
    }

    private fun formatNum(n: Float): String =
        if (n % 1f == 0f) n.toInt().toString() else n.toString()
}
