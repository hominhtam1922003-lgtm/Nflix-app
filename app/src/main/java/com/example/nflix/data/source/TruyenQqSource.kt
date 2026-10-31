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
        private val RESERVED_SLUGS = setOf(
            "tim-kiem", "the-loai", "dang-nhap", "dang-ky", "truyen-hot", "contact"
        )
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
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
     * Home page sections (markup re-verified live 2026-11-01 after the site
     * changed its cards): the hot slider lives in #owl-slide, the latest
     * updates in #contentstory. Both use div.item cards, but with different
     * name sources — slider cards carry the real title in .caption h3 while
     * their img alt is the SLUG; list cards carry it in h3.name a / img alt.
     * Cover paths are RELATIVE (/media/book/...) and must be absolutized.
     */
    suspend fun hot(): List<TitleItem> {
        val doc = getDoc("/")
        return parseCards(doc.selectFirst("#owl-slide") ?: doc)
    }

    suspend fun latest(): List<TitleItem> {
        val doc = getDoc("/")
        return parseCards(doc.selectFirst("#contentstory") ?: doc)
    }

    /** Both home sections from ONE fetch — the hosts are slow, never fetch "/" twice. */
    suspend fun home(): Pair<List<TitleItem>, List<TitleItem>> {
        val doc = getDoc("/")
        return parseCards(doc.selectFirst("#owl-slide") ?: doc) to
            parseCards(doc.selectFirst("#contentstory") ?: doc)
    }

    /** Genre listing — same card structure as the home page.
     *  The site only renders the listing when ?page= is present — even for page 1. */
    suspend fun byGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        parseCards(getDoc("/the-loai/$genreSlug?page=$page"))

    private fun parseCards(scope: org.jsoup.nodes.Element): List<TitleItem> {
        val seen = LinkedHashMap<String, TitleItem>()
        for (card in scope.select("div.item")) {
            val anchor = card.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.startsWith("/") && !href.contains("chapter") &&
                    href.trimEnd('/').count { it == '/' } == 1
            } ?: continue
            val slug = slugOf(anchor.attr("href"))
            if (slug.isBlank() || slug in RESERVED_SLUGS || seen.containsKey(slug)) continue
            val img = card.selectFirst("img") ?: continue
            // absUrl resolves the relative /media/book/... path against the host.
            val poster = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            if (!poster.contains("/media/book/")) continue
            val name = listOf(
                card.selectFirst(".info h3 a")?.text(),
                card.selectFirst("h3.name a")?.text(),
                card.selectFirst(".caption h3")?.text(),
                img.attr("alt").removeSuffix(" đọc online").trim(),
                anchor.attr("title")
            ).firstOrNull { !it.isNullOrBlank() && it != slug }?.trim().orEmpty()
            if (name.isBlank()) continue
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
                episodeCount = chapNum.toInt(),
                updatedLabel = card.selectFirst(".time")?.text()?.trim().orEmpty()
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
        val labelStatus = doc.selectFirst(".book-info .label-status")?.text()?.trim().orEmpty()
        val status = when {
            labelStatus.isNotBlank() -> labelStatus
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
            val lower = src.lowercase()
            val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
            if (src.contains("cc3t.net") && isImage && src !in urls) urls.add(src)
        }
        return urls
    }

    suspend fun search(keyword: String): List<TitleItem> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val doc = getDoc("/tim-kiem?s=$q")
        // Results live in .listing with the same div.item cards as the lists.
        return parseCards(doc.selectFirst(".listing") ?: doc)
    }

    private fun formatNum(n: Float): String =
        if (n % 1f == 0f) n.toInt().toString() else n.toString()
}
