package com.example.nflix.data.source

import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * HHKungFu (hhkungfu.ee) — source for the Donghua family.
 * WordPress/Halim theme, server-rendered HTML. Verified live 2026-11-01:
 * see ~/workspace/nflix/research/donghua.md.
 *
 * Playback note: streams are embed iframes (streamfree.vip / JW Player),
 * no direct m3u8 at the verified layer — episodes resolve to an embed URL
 * and the player shows them in an embed surface.
 */
class HhKungfuSource(
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://hhkungfu.ee"
) {
    companion object {
        const val SOURCE_ID = "hhkungfu"

        /**
         * V2 (A7): the watch page's own stream variants, in the page's
         * own order, with the SOURCE'S OWN labels verbatim — the page
         * ships four player buttons (data-type tiktik/pro/vip4k/
         * vip4kv2) labeled "1080P V1 · 1080P V2 · 4K V1 · 4K V2".
         * Measured 2026-11-01: player.php returns a DIFFERENT embed
         * per type. Re-measured 2026-11-03 (dossier donghua-embed):
         * the two pairs do NOT split by path — EACH pair mixes both:
         * V1 (tiktik / vip4k) lives under /embed/vt/, V2 (pro /
         * vip4kv2) under /embed/v/. The labels are the source's claim,
         * carried as-is — the app never invents a quality label of its own.
         * (key = player.php `type`, value = the source's button label)
         */
        val STREAM_VARIANTS = listOf(
            "tiktik" to "1080P V1",
            "pro" to "1080P V2",
            "vip4k" to "4K V1",
            "vip4kv2" to "4K V2"
        )

        /** Default variant when a group carries no explicit key. */
        const val DEFAULT_VARIANT = "pro"

        /**
         * 1.3.1: desktop-browser UA for the ONE retry in [detail] — the
         * site sits behind Cloudflare + LiteSpeed and the response a
         * device gets can differ from what the app UA receives (proven
         * live 2026-11-01: the app UA replays perfectly from the VM,
         * while the user's device got an episode-less detail).
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /**
         * Bare client for the desktop-UA retry ONLY. The shared client
         * stamps the app UA onto every request in its interceptor —
         * which is exactly what the retry must not carry.
         */
        private val desktopClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

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

    private suspend fun getDoc(path: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HHKungfu HTTP ${resp.code}")
            Jsoup.parse(resp.body?.string().orEmpty(), base)
        }
    }

    /** 1.3.1: the detail page once more, as a desktop browser (see DESKTOP_UA). */
    private suspend fun getDocDesktop(path: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).get()
            .header("User-Agent", DESKTOP_UA)
            .build()
        desktopClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HHKungfu HTTP ${resp.code}")
            Jsoup.parse(resp.body?.string().orEmpty(), base)
        }
    }

    private suspend fun postAjax(body: FormBody): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/wp-admin/admin-ajax.php")
            .post(body)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HHKungfu ajax HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun parseItem(el: Element): TitleItem? {
        val link = el.selectFirst("a.halim-thumb") ?: el.selectFirst("a[href]") ?: return null
        val href = link.attr("href")
        val slug = href.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug.contains("watch")) return null
        val img = el.selectFirst("img.film-poster-img") ?: el.selectFirst("img")
        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
        val name = el.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
        if (name.isBlank()) return null
        val origin = el.selectFirst("p.original_title")?.text()?.trim().orEmpty()
        val statusText = el.selectFirst("span.status")?.text()?.trim().orEmpty()
        val quality = when {
            statusText.contains("4K", true) -> "4K"
            statusText.contains("HD", true) -> "FHD"
            else -> statusText
        }
        val epText = el.selectFirst("span.episode")?.text()?.trim().orEmpty()
        val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return TitleItem(
            id = "${Family.DONGHUA.name}:$SOURCE_ID:$slug",
            family = Family.DONGHUA,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            originName = origin,
            posterUrl = poster,
            thumbUrl = poster,
            quality = quality,
            latestLabel = if (epNum > 0) "Tập $epNum" else epText,
            episodeCount = epNum
        )
    }

    private fun parseItems(doc: Document): List<TitleItem> =
        doc.select(".halim-item").mapNotNull { parseItem(it) }
            .distinctBy { it.slug }

    /** Home page shelves (hot / trending blocks render as .halim-item too). */
    suspend fun trending(): List<TitleItem> = parseItems(getDoc("/"))

    /** "Mới cập nhật" full listing, paginated /page/<n>. */
    suspend fun latest(page: Int = 1): List<TitleItem> =
        parseItems(getDoc(if (page <= 1) "/moi-cap-nhat" else "/moi-cap-nhat/page/$page"))

    /**
     * "Đã hoàn thành" listing (verified live 2026-11-01: /hoan-thanh,
     * 16 .halim-item per page, paginated /hoan-thanh/page/<n>).
     */
    suspend fun completed(page: Int = 1): List<TitleItem> =
        parseItems(getDoc(if (page <= 1) "/hoan-thanh" else "/hoan-thanh/page/$page"))

    /**
     * "Xem nhiều" chart (verified live 2026-11-01: /top-xem-nhieu, same
     * .halim-item grid) — a pure most-viewed list, unlike trending() which
     * mixes the whole home page.
     */
    suspend fun topViewed(page: Int = 1): List<TitleItem> =
        parseItems(getDoc(if (page <= 1) "/top-xem-nhieu" else "/top-xem-nhieu/page/$page"))

    /** Category listing — same .halim-item grid as the other listings. */
    suspend fun byCategory(slug: String, page: Int = 1): List<TitleItem> =
        parseItems(getDoc(if (page <= 1) "/category/$slug" else "/category/$slug/page/$page"))

    /**
     * Category menu (label to slug) parsed from the home page nav — the list
     * is whatever the site itself ships in its menu, not a hardcoded copy.
     */
    suspend fun genreLinks(): List<Pair<String, String>> {
        val doc = getDoc("/")
        val out = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a[href*=/category/]")) {
            val href = a.attr("href")
            val slug = href.trimEnd('/').substringAfterLast('/')
            val label = a.text().trim()
            if (slug.isNotBlank() && label.isNotBlank() && out.none { it.second == slug }) {
                out.add(label to slug)
            }
        }
        return out
    }

    suspend fun search(keyword: String): List<TitleItem> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        return parseItems(getDoc("/?s=$q"))
    }

    /**
     * Weekday schedule. [dateKey]: chu-nhat | thu-2 | ... | thu-7 (VN weekday,
     * mapped by the app — the site's own "today" follows server time).
     */
    suspend fun schedule(dateKey: String): List<TitleItem> {
        val html = postAjax(
            FormBody.Builder()
                .add("action", "dox_schedule_page")
                .add("date", dateKey)
                .add("type", "page")
                .build()
        )
        val frag = Jsoup.parseBodyFragment(html, base)
        val out = mutableListOf<TitleItem>()
        for (a in frag.select("a.schedule-item")) {
            val href = a.attr("href")
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isBlank()) continue
            val img = a.selectFirst("img")
            val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
            val name = a.selectFirst(".schedule-title, h3, .title")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
            if (name.isBlank()) continue
            val epText = a.text()
            val epNum = Regex("(\\d+)").findAll(epText).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add(
                TitleItem(
                    id = "${Family.DONGHUA.name}:$SOURCE_ID:$slug",
                    family = Family.DONGHUA,
                    sourceId = SOURCE_ID,
                    slug = slug,
                    name = name,
                    posterUrl = poster,
                    thumbUrl = poster,
                    latestLabel = if (epNum > 0) "Tập $epNum" else "",
                    episodeCount = epNum
                )
            )
        }
        return out.distinctBy { it.slug }
    }

    suspend fun detail(slug: String): TitleDetail {
        val doc = getDoc("/$slug")
        var parsed = parseDetailDoc(slug, doc)
        if (parsed.detail.servers.isEmpty()) {
            // 1.3.1: both parse paths found no episodes in the document
            // the app UA received. The site answers through Cloudflare +
            // LiteSpeed and the response a device actually gets can
            // differ (challenge/variant page) — retry ONCE as a desktop
            // browser and run both parse paths over the fresh document.
            val doc2 = runCatching { getDocDesktop("/$slug") }.getOrNull()
            if (doc2 != null) {
                val second = parseDetailDoc(slug, doc2)
                // Keep whichever pass found episodes; when both are
                // empty keep the SECOND — its counts describe the last
                // document the app saw, which the loadNote reports.
                if (second.detail.servers.isNotEmpty() || parsed.detail.servers.isEmpty()) {
                    parsed = second
                }
            }
        }
        // 1.3.1 (3b): the outcome is reported honestly on the detail —
        // the screen shows this note instead of a generic network line.
        val note = when {
            parsed.detail.servers.isEmpty() ->
                "Trang tải về nhưng không thấy tập nào " +
                    "(khối server: ${parsed.serverBlockCount}, neo tập: ${parsed.episodeAnchorCount})"
            parsed.fallbackUsed ->
                "Đã dùng đường dự phòng (khối server chuẩn rỗng)"
            else -> ""
        }
        return parsed.detail.copy(loadNote = note)
    }

    /** Outcome of one parse pass over a detail document. */
    private data class ParsedDetail(
        val detail: TitleDetail,
        val fallbackUsed: Boolean,
        val serverBlockCount: Int,
        val episodeAnchorCount: Int
    )

    /**
     * One full parse of a detail document: metadata + server groups.
     * Primary path = the standard #halim-list-server blocks; when they
     * yield no episodes at all, [parseLooseEpisodes] sweeps the whole
     * document (1.3.1). Both [detail] fetches (app UA, desktop UA) run
     * through here so the two passes can never drift apart.
     */
    private fun parseDetailDoc(slug: String, doc: Document): ParsedDetail {
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val name = doc.selectFirst("h1.entry-title")?.text()?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: ogTitle.substringBefore(" - ").trim()
        val statusText = doc.select(".status, .halim-item span.status").firstOrNull()?.text().orEmpty()
        val quality = when {
            statusText.contains("4K", true) -> "4K"
            statusText.contains("HD", true) -> "FHD"
            else -> ""
        }

        val servers = mutableListOf<ServerGroup>()
        val serverBlocks = doc.select("#halim-list-server .halim-server")
        for (block in serverBlocks) {
            servers += parseServerBlock(block)
        }
        var fallbackUsed = false
        if (servers.isEmpty()) {
            fallbackUsed = true
            servers += parseLooseEpisodes(doc)
        }

        val total = servers.maxOfOrNull { it.episodes.size } ?: 0
        val versionBadge = when {
            servers.any { it.langKey == "tm" } -> "TM"
            servers.any { it.langKey == "vs" } -> "VS"
            else -> ""
        }
        // Genres live in rel="tag" links on the detail page — only the ones
        // pointing at /category/<slug> are genres; the /tag/ ones are title
        // tags (verified live 2026-11-01, research A3/B2).
        val genreLinks = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a[rel=tag]")) {
            val href = a.attr("href")
            if (!href.contains("/category/")) continue
            val gSlug = href.trimEnd('/').substringAfterLast('/')
            val gLabel = a.text().trim()
            if (gSlug.isNotBlank() && gLabel.isNotBlank() && genreLinks.none { it.second == gSlug }) {
                genreLinks.add(gLabel to gSlug)
            }
        }
        val item = TitleItem(
            id = "${Family.DONGHUA.name}:$SOURCE_ID:$slug",
            family = Family.DONGHUA,
            sourceId = SOURCE_ID,
            slug = slug,
            name = name,
            posterUrl = ogImage,
            thumbUrl = ogImage,
            quality = quality,
            versionBadge = versionBadge,
            latestLabel = if (total > 0) "Tập $total" else "",
            episodeCount = total,
            genres = genreLinks.map { it.first }
        )
        val detail = TitleDetail(
            item = item,
            description = desc,
            totalCount = total,
            servers = servers,
            genreLinks = genreLinks
        )
        return ParsedDetail(
            detail = detail,
            fallbackUsed = fallbackUsed,
            serverBlockCount = serverBlocks.size,
            episodeAnchorCount = doc.select("a[data-ep]").size
        )
    }

    /** (display name, langKey) of one server block from its heading/body text. */
    private fun serverNameLang(heading: String, blockText: String): Pair<String, String> = when {
        heading.contains("Thuyết Minh", true) || blockText.contains("Thuyết Minh", true) ->
            "Thuyết Minh" to "tm"
        heading.contains("Vietsub", true) || blockText.contains("Vietsub", true) ->
            "Vietsub" to "vs"
        else -> heading.ifBlank { "Server" } to ""
    }

    /**
     * One episode anchor to its EpisodeItem, or null when the anchor
     * carries no readable episode number. The slug is the composite
     * locator "postId|tap-n|sv" that resolveEmbed() consumes.
     */
    private fun episodeOf(a: Element): EpisodeItem? {
        val epAttr = a.attr("data-ep") // "tap-<n>"
        val num = Regex("(\\d+(?:\\.\\d+)?)").find(epAttr)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("(\\d+)").find(a.text())?.groupValues?.get(1)?.toFloatOrNull()
            ?: return null
        val postId = a.attr("data-post-id")
        val sv = a.attr("data-sv")
        return EpisodeItem(
            label = "Tập ${if (num % 1f == 0f) num.toInt() else num}",
            number = num,
            slug = "$postId|$epAttr|$sv"
        )
    }

    /**
     * V2 (A7): ONE episode list, FOUR stream variants — the detail page
     * carries a single locator set; the variant is a player.php `type`
     * chosen at resolve time. Emit one group per variant, named with
     * the source's own button labels ("<lang> · 1080P V1" ... "<lang>
     * · 4K V2"), so the server sheet offers exactly what the source's
     * watch page offers.
     */
    private fun variantGroups(
        serverName: String,
        langKey: String,
        episodes: List<EpisodeItem>
    ): List<ServerGroup> = STREAM_VARIANTS.map { (variantKey, variantLabel) ->
        ServerGroup(
            name = "$serverName · $variantLabel",
            langKey = langKey,
            episodes = episodes,
            variantKey = variantKey
        )
    }

    /** Primary path: one standard .halim-server block to its variant groups. */
    private fun parseServerBlock(block: Element): List<ServerGroup> {
        val heading = block.selectFirst(".server-name, h3, strong")?.text().orEmpty()
        val (serverName, langKey) = serverNameLang(heading, block.text())
        val episodes = mutableListOf<EpisodeItem>()
        for (a in block.select("a[data-ep], a[href*=watch-]")) {
            val ep = episodeOf(a) ?: continue
            if (episodes.any { it.number == ep.number }) continue
            episodes.add(ep)
        }
        episodes.sortBy { it.number }
        if (episodes.isEmpty()) return emptyList()
        return variantGroups(serverName, langKey, episodes)
    }

    /** Accumulator for the fallback sweep (one raw server bucket). */
    private class LooseGroup(val name: String, val langKey: String) {
        val episodes = mutableListOf<EpisodeItem>()
    }

    /**
     * 1.3.1 fallback path: the standard blocks yielded nothing, so
     * sweep the WHOLE document for episode anchors. Anchors still
     * nested in a .halim-server ancestor group under that block (named
     * like the primary path); the rest group by their data-sv value,
     * named by the nearest server heading seen BEFORE them in document
     * order ("Server" when none). Deduped by number per group, then
     * expanded to the four stream variants like the primary path.
     */
    private fun parseLooseEpisodes(doc: Document): List<ServerGroup> {
        val blocks = doc.select(".halim-server")
        val groups = LinkedHashMap<String, LooseGroup>()
        var lastHeading = ""
        val sweep = doc.select(
            "a[data-ep], a[href*=watch-], .halim-server-name, .server-name"
        )
        for (el in sweep) {
            if (el.tagName() != "a") {
                // A server heading — only language-bearing ones can
                // name a fallback group.
                val t = el.text().trim()
                if (t.contains("Vietsub", true) || t.contains("Thuyết Minh", true) ||
                    t.contains("Lồng Tiếng", true)
                ) {
                    lastHeading = t
                }
                continue
            }
            val ep = episodeOf(el) ?: continue
            val ancestor = el.parents().firstOrNull { it.hasClass("halim-server") }
            val key: String
            val nameLang: Pair<String, String>
            if (ancestor != null) {
                key = "block:${blocks.indexOf(ancestor)}"
                val heading = ancestor.selectFirst(".server-name, h3, strong")
                    ?.text().orEmpty()
                nameLang = serverNameLang(heading, ancestor.text())
            } else {
                key = "sv:${el.attr("data-sv")}"
                nameLang = serverNameLang(lastHeading, lastHeading)
            }
            val group = groups.getOrPut(key) { LooseGroup(nameLang.first, nameLang.second) }
            if (group.episodes.any { it.number == ep.number }) continue
            group.episodes.add(ep)
        }
        val out = mutableListOf<ServerGroup>()
        for (group in groups.values) {
            group.episodes.sortBy { it.number }
            if (group.episodes.isNotEmpty()) {
                out += variantGroups(group.name, group.langKey, group.episodes)
            }
        }
        return out
    }

    /**
     * Resolve the embed player URL for one episode locator ("postId|tap-n|sv").
     *
     * V2 (A7) replaces the old prefer4k flag — a boolean the metadata
     * almost never turned on — with the explicit stream [variant]
     * (player.php `type`: tiktik/pro/vip4k/vip4kv2) of the server group
     * the user actually picked. Unknown/blank variants fall back to the
     * "pro" stream the app has always used.
     */
    suspend fun resolveEmbed(locator: String, variant: String = DEFAULT_VARIANT): String {
        val parts = locator.split("|")
        require(parts.size == 3) { "Bad donghua locator" }
        val (postId, chapterSt, sv) = parts
        val type = variant.takeIf { v -> STREAM_VARIANTS.any { it.first == v } }
            ?: DEFAULT_VARIANT
        val url = "$base/player/player.php?action=dox_ajax_player&post_id=$postId&chapter_st=$chapterSt&sv=$sv&type=$type"
        val html = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).get()
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HHKungfu player HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
        val doc = Jsoup.parse(html, base)
        val iframe = doc.selectFirst("iframe[src]")
        return iframe?.attr("src")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("HHKungfu: no embed iframe")
    }
}
