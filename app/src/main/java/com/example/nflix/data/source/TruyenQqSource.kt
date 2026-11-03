package com.example.nflix.data.source

import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.CancellationException
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
        // 1.3.2: timeouts aligned with NetTruyenSource (connect 15s /
        // read 25s). The old 8s connect budget killed BOTH hosts on
        // slow VN routes while the source itself was alive (measured
        // 2026-11-03: 10/10 HTTP 200 from the VM in the same minutes
        // the user's device reported "both hosts not responding").
        // 1.3.3: + callTimeout 30s — readTimeout is an INACTIVITY
        // timeout, so a slow-dripping host (steady trickle under the
        // read budget) never tripped it and a page load could hang
        // forever (dossier truyen-hang-133 §1). callTimeout bounds the
        // WHOLE request, drip included. Same fix as CobeSource (1.3.2).
        // 1.3.4: ceiling tightened 30s → 10s per host — sequential
        // twin-host failover meant a dripping primary could burn a
        // minute per call chain (Lý Thấm's detail dossier).
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
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

    // C3 (K4): the twin-host fallback used to be silent — the tab now shows
    // which host is actually serving and how long the last page took.
    @Volatile
    private var lastLoadMs: Long = -1L

    /** Display host (no scheme) that served the most recent successful page. */
    fun statusHostLabel(): String = activeHost
        .removePrefix("https://").removePrefix("http://").trimEnd('/')

    /** True when the twin mirror is serving because the primary failed. */
    fun statusOnFallback(): Boolean = activeHost != hosts.first()

    /** Duration of the most recent successful page fetch, -1 when none yet. */
    fun statusLastLoadMs(): Long = lastLoadMs

    private suspend fun getDoc(path: String): Document = withContext(Dispatchers.IO) {
        val candidates = listOf(activeHost) + hosts.filter { it != activeHost }
        var lastError: Exception? = null
        // 1.3.3: keep EVERY host's error, not just the last one — the
        // Truyện error screen prints this text ("TruyenQQ (2 host):
        // <lỗi>"), and with two hosts the second failure used to bury
        // the first one's diagnosis (dossier truyen-hang-133 §6.5).
        val hostErrors = mutableListOf<String>()
        for (host in candidates) {
            try {
                val req = Request.Builder().url(host + path).get().build()
                val started = System.nanoTime()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val html = resp.body?.string().orEmpty()
                    activeHost = host
                    lastLoadMs = (System.nanoTime() - started) / 1_000_000
                    return@withContext Jsoup.parse(html, host)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                hostErrors += host.removePrefix("https://").removePrefix("http://") +
                    ": " + (e.message ?: e.javaClass.simpleName)
            }
        }
        throw IllegalStateException(
            "TruyenQQ: cả hai host đều lỗi — " + hostErrors.joinToString(" · "),
            lastError
        )
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

    /**
     * Genre listing — same card structure as the home page. The site only
     * renders the listing when ?page= is present — even for page 1.
     *
     * 1.2.0 parser fix: the parse is SCOPED to the listing region. Every
     * genre page also carries the "TRUYỆN PHỔ BIẾN" carousel (#owl-slide,
     * ~14 cards) — parsing the whole document used to pass those off as
     * the genre's own results, so dead genres (tien-hiep / kiem-hiep /
     * quan-truong, whose listings are genuinely EMPTY) showed a full fake
     * page. Prefer the .listing block; when absent, parse the document
     * with the carousel removed. A page past the end (the source caps
     * listings at 11 pages; page 12 is a 404) throws from getDoc and the
     * caller treats it as end-of-list.
     */
    suspend fun byGenre(genreSlug: String, page: Int = 1): List<TitleItem> =
        parseListing(getDoc("/the-loai/$genreSlug?page=$page"))

    /** Manhwa shelf (tab Manhwa's genre index base) — same listing, items re-homed to MANHWA. */
    suspend fun manhwaShelf(page: Int = 1): List<TitleItem> =
        parseListing(getDoc("/the-loai/manhwa?page=$page"), Family.MANHWA)

    /** Scoped listing parse: .listing when present, else document minus #owl-slide. */
    private suspend fun parseListing(doc: Document, family: Family = Family.TRUYEN): List<TitleItem> {
        doc.selectFirst(".listing")?.let { return parseCards(it, family) }
        val cleaned = doc.clone()
        cleaned.select("#owl-slide").remove()
        return parseCards(cleaned, family)
    }

    /**
     * Slugs of the completed catalog (/truyen-full), fetched page by page
     * until an empty page or [maxPages]. Powers the Truyện "Đang ra tiếp"
     * status (the source has no ongoing listing: ongoing = a genre/home
     * listing MINUS this set).
     */
    suspend fun completedSlugs(maxPages: Int = 11): Set<String> {
        val out = mutableSetOf<String>()
        for (p in 1..maxPages) {
            val items = try {
                completed(p)
            } catch (e: CancellationException) {
                // 1.3.3: cancellation (the Truyện tab's total budget)
                // must abort the harvest, not degrade it to a partial
                // set that then filters the list wrongly.
                throw e
            } catch (e: Exception) {
                break
            }
            if (items.isEmpty()) break
            items.forEach { out.add(it.slug) }
        }
        return out
    }

    /**
     * "Truyện Full đã hoàn thành" listing (verified live 2026-11-01:
     * /truyen-full, cards inside .listing, ?page= paging; page 1 redirects
     * to the bare path, which OkHttp follows).
     */
    suspend fun completed(page: Int = 1): List<TitleItem> {
        val doc = getDoc(if (page <= 1) "/truyen-full" else "/truyen-full?page=$page")
        return parseCards(doc.selectFirst(".listing") ?: doc)
    }

    /**
     * "Truyện mới ra mắt" listing (verified live 2026-11-01: /truyen-moi,
     * 34 cards inside .listing) — new series, distinct from "Mới cập nhật".
     */
    suspend fun newest(page: Int = 1): List<TitleItem> {
        val doc = getDoc(if (page <= 1) "/truyen-moi" else "/truyen-moi?page=$page")
        return parseCards(doc.selectFirst(".listing") ?: doc)
    }

    /**
     * Widget "TOP TRUYỆN ĐỌC NHIỀU" on the home page sidebar — the Truyện
     * family's only real readership chart (verified live 2026-11-01).
     * Items carry name + slug ONLY (no covers); callers cross-fill covers
     * from other lists by slug. Rank = document order (deduped — the
     * widget itself repeats one entry).
     */
    suspend fun topRead(): List<TitleItem> {
        val doc = getDoc("/")
        val out = mutableListOf<TitleItem>()
        for (a in doc.select(".widget.suggest .item a[href]")) {
            val href = a.attr("href")
            if (!href.startsWith("/") || href.contains("the-loai")) continue
            val slug = slugOf(href)
            if (slug.isBlank() || slug in RESERVED_SLUGS || out.any { it.slug == slug }) continue
            val name = a.attr("title").ifBlank { a.text() }.trim()
            if (name.isBlank()) continue
            out.add(
                TitleItem(
                    id = "${Family.TRUYEN.name}:$SOURCE_ID:$slug",
                    family = Family.TRUYEN,
                    sourceId = SOURCE_ID,
                    slug = slug,
                    name = name
                )
            )
        }
        return out
    }

    // 1.3.4: card extraction runs on Default, not the caller's Main
    // dispatcher (Dương Tử S1/S4 family of fixes).
    private suspend fun parseCards(
        scope: org.jsoup.nodes.Element,
        family: Family = Family.TRUYEN
    ): List<TitleItem> = withContext(Dispatchers.Default) {
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
                id = "${family.name}:$SOURCE_ID:$slug",
                family = family,
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
        seen.values.toList()
    }

    /**
     * Detail. [family] re-homes the item: manhwa titles browsed from the
     * TruyenQQ manhwa shelf carry MANHWA ids so their Detail/Reader state
     * lives with the Manhwa family (same record, different shelf).
     */
    // 1.3.4: the whole extraction runs on Default (Dương Tử S4) —
    // it used to run on the caller's Main dispatcher, and its
    // chapter dedupe below was O(k²) in the chapter count.
    suspend fun detail(slug: String, family: Family = Family.TRUYEN): TitleDetail =
        withContext(Dispatchers.Default) {
        val doc = getDoc("/$slug")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val name = ogTitle.substringBefore(" [Tới").substringBefore(" - ").trim()
            .ifBlank { doc.title().substringBefore(" |").trim() }
        val chapters = mutableListOf<ChapterItem>()
        // 1.3.4: HashSet dedupe — the old chapters.any{} scan inside
        // this loop was O(k²) (a 1,500-chapter title = ~10⁶ compares
        // in one Main-thread stall, Dương Tử S4).
        val seenNums = HashSet<Float>()
        val chapterRegex = Regex("/${Regex.escape(slug)}/chapter-(\\d+(?:\\.\\d+)?)")
        for (a in doc.select("a[href]")) {
            val m = chapterRegex.find(a.attr("href")) ?: continue
            val num = m.groupValues[1].toFloatOrNull() ?: continue
            if (!seenNums.add(num)) continue
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
        // Genres: the "Thể loại:" row inside .book-info (scoped there — the
        // header menu carries /the-loai/ links for every genre on the site).
        val genreLinks = mutableListOf<Pair<String, String>>()
        val infoEl = doc.selectFirst(".book-info")
        if (infoEl != null) {
            for (a in infoEl.select("a[href*=/the-loai/]")) {
                val href = a.attr("href")
                val gSlug = href.substringAfter("/the-loai/").trimEnd('/')
                    .substringBefore('/').substringBefore('?')
                val gLabel = a.text().trim()
                if (gSlug.isNotBlank() && gLabel.isNotBlank() && genreLinks.none { it.second == gSlug }) {
                    genreLinks.add(gLabel to gSlug)
                }
            }
        }
        val item = TitleItem(
            id = "${family.name}:$SOURCE_ID:$slug",
            family = family,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = ogImage,
            thumbUrl = ogImage,
            latestLabel = if (total > 0) "Chap $total" else "",
            episodeCount = total,
            genres = genreLinks.map { it.first }
        )
        val labelStatus = doc.selectFirst(".book-info .label-status")?.text()?.trim().orEmpty()
        val status = when {
            labelStatus.isNotBlank() -> labelStatus
            desc.contains("Đang tiến hành", true) -> "Đang tiến hành"
            desc.contains("Hoàn thành", true) -> "Hoàn thành"
            else -> ""
        }
        TitleDetail(
            item = item,
            description = desc,
            status = status,
            totalCount = total,
            chapters = chapters,
            genreLinks = genreLinks
        )
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
