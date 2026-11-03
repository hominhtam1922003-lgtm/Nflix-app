package com.example.nflix.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.AdCutReport
import com.example.nflix.data.ProbeTarget
import com.example.nflix.data.ServerProbe
import com.example.nflix.data.ServerStatus
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.local.watchedSet
import com.example.nflix.data.local.withWatched
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.source.PhimCobePlay
import com.example.nflix.data.source.PhimCobeSource
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
import com.example.nflix.ui.detail.versionPriorityOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class PlaybackKind { STREAM, EMBED }

data class PlayerUiState(
    val loading: Boolean = true,
    val item: TitleItem? = null,
    val detail: TitleDetail? = null,
    val serverIndex: Int = 0,
    val episode: EpisodeItem? = null,
    val kind: PlaybackKind = PlaybackKind.STREAM,
    val embedUrl: String = "",
    val resumePositionMs: Long = 0,
    val userState: UserStateEntity? = null,
    val error: String = "",
    /** Last real per-server measurement for the server sheet (0.1/0.2). */
    val serverStatuses: Map<Int, ServerStatus> = emptyMap(),
    /**
     * 1.3.1: Referer header for the embed WebView — Donghua ONLY (the
     * source watch-page URL of the episode being played, so the embed
     * host sees the same request shape the site's own page produces).
     * Null for every other family: their embed loading is untouched.
     */
    val embedReferer: String? = null,
    /**
     * 1.3.3b: REAL mid-roll ad cut measured by HlsAdFilter on the
     * current stream's media playlist — segments/seconds ACTUALLY
     * removed before playback. 0 = nothing was cut (the player shows
     * the "Đã cắt quảng cáo" line only when this is > 0).
     */
    val adCutSegments: Int = 0,
    val adCutSeconds: Int = 0
)

class PlayerViewModel(
    private val app: NflixApp,
    private val titleId: String,
    private val episodeSlug: String,
    private val episodeNumber: Float = 0f
) : ViewModel() {

    companion object {
        /** 1.3.2: total ceiling for the player init — see init/loadInitial. */
        private const val INIT_TIMEOUT_MS = 60_000L
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // 1.3.2: ONE total ceiling over the whole init. The
                // cobe-only detail path used to have no budget at all,
                // so a dead Cobe (502 / slow-drip rediscovery) hung
                // init forever: spinner + "Đang tải…" + an episode
                // sheet stuck at 0 (dossier phim-0-tap branch D).
                withTimeout(INIT_TIMEOUT_MS) { loadInitial() }
            } catch (e: TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Tải quá lâu — nguồn phản hồi chậm hoặc đã chết. Hãy thử lại."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 1.3.2: keep the REAL failure (dead source / episode
                // not found / no detail from either source) instead of
                // flattening every error into one generic network
                // line — the user is told what actually broke.
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Không tải được — kiểm tra mạng rồi thử lại."
                    )
                }
            }
        }
    }

    private suspend fun loadInitial() {
        val entity = app.database.titleDao().observe(titleId).first()
        val item = entity?.toItem()
            ?: throw IllegalStateException("Không thấy thông tin bộ này")
        val detail = when (item.sourceId) {
            "hhkungfu" -> app.hhKungfu.detail(item.slug)
            // Same routing front as the Detail screen, so the
            // server pool matches what the user picked from
            // (Cobe-only since 1.3.4).
            else -> app.mergedPhim.detailFor(item)
        }
        app.database.titleDao().upsert(detail.item.toEntity())
        val st = app.database.userStateDao().get(titleId)

        var serverIdx = detail.servers.indexOfFirst { s ->
            s.episodes.any { it.slug == episodeSlug }
        }
        if (serverIdx < 0 && episodeNumber > 0f) {
            serverIdx = detail.servers.indexOfFirst { s ->
                s.episodes.any { it.number == episodeNumber }
            }
        }
        if (serverIdx < 0) serverIdx = 0
        val server = detail.servers.getOrNull(serverIdx)
        var episode = server?.episodes?.firstOrNull { it.slug == episodeSlug }
            ?: server?.episodes?.firstOrNull { it.number == episodeNumber }
            ?: server?.episodes?.firstOrNull()
            ?: throw IllegalStateException("Không thấy tập này")

        // 1.3.4 (Triệu Lệ Dĩnh's server dossier): with more than one
        // group, the handed-in group is NOT trusted blindly — the
        // same episode number is resolved across ALL groups in
        // parallel and ranked: probed-alive HLS > other HLS > Embed;
        // inside a tier, non-KK-infra hosts first (their streams
        // carry the gambling ads the user ordered dropped); the
        // language priority only breaks ties. The old flow opened
        // whatever group contained the slug — measured opening a
        // Cloudflare-blocked streamc embed while a direct m3u8 of
        // the same episode sat in another group.
        val ranked = if (detail.servers.size > 1) {
            pickInitialRanked(detail, detail.item, episode)
        } else {
            null
        }
        if (ranked != null) {
            serverIdx = ranked.groupIndex
            episode = ranked.episode
        }

        val resume = if (st != null && st.lastUnitNumber == episode.number && !st.finished)
            st.positionMs else 0L

        _state.update {
            it.copy(
                item = detail.item,
                detail = detail,
                serverIndex = serverIdx,
                userState = st,
                resumePositionMs = resume
            )
        }
        if (ranked != null) {
            applyResolved(detail.item, ranked)
            if (ranked.kind == PlaybackKind.EMBED) {
                prepareEmbedCandidates(episode.number, episode.slug)
            }
        } else {
            applyEpisode(detail.item, episode)
        }
        // 0.1: with the auto-fastest setting on, measure the other
        // servers in the background so the server sheet shows real
        // statuses/ms instead of a hardcoded claim. Playback itself
        // is untouched — error-driven auto-switch already exists.
        if (detail.servers.size > 1) {
            viewModelScope.launch {
                val auto = try {
                    app.settings.flow.first().autoFastestServer
                } catch (e: Exception) {
                    true
                }
                if (auto) {
                    // 1.1.1 (N3): typed targets like Detail — embed
                    // pages are reported unmeasurable, never timed.
                    val statuses = ServerProbe.probeAll(
                        detail.servers, episode.number
                    ) { ep ->
                        when {
                            PhimCobeSource.isLocator(ep.slug) ->
                                try {
                                    when (
                                        val target = app.phimcobe.resolvePlay(ep.slug)
                                    ) {
                                        is PhimCobePlay.Hls ->
                                            ProbeTarget.Stream(target.url)
                                        is PhimCobePlay.Embed ->
                                            ProbeTarget.Embed(target.url)
                                    }
                                } catch (e: Exception) {
                                    ep.embedUrl.takeIf { it.isNotBlank() }
                                        ?.let { ProbeTarget.Embed(it) }
                                        ?: ProbeTarget.None
                                }
                            item.sourceId == "hhkungfu" -> try {
                                // V2 (A7): resolve through the
                                // variant of the group this
                                // episode belongs to.
                                val variant = detail.servers
                                    .firstOrNull { g ->
                                        g.episodes.any { it.slug == ep.slug }
                                    }?.variantKey.orEmpty()
                                ProbeTarget.Embed(
                                    app.hhKungfu.resolveEmbed(ep.slug, variant)
                                )
                            } catch (e: Exception) {
                                ProbeTarget.None
                            }
                            ep.streamUrl.isNotBlank() ->
                                ProbeTarget.Stream(ep.streamUrl)
                            ep.embedUrl.isNotBlank() ->
                                ProbeTarget.Embed(ep.embedUrl)
                            else -> ProbeTarget.None
                        }
                    }
                    _state.update { it.copy(serverStatuses = statuses) }
                }
            }
        }
    }

    private suspend fun applyEpisode(item: TitleItem, episode: EpisodeItem) {
        // 1.3.6: a fresh episode starts with no same-episode
        // alternates; the PhimCobe branch below fills the list when
        // the playback API returns more than one candidate.
        playFallbacks = emptyList()
        when {
            // Cobe episodes carry only a locator — the real link is
            // resolved per episode. Derived/direct HLS feeds ExoPlayer
            // like any direct stream (the episode keeps its API embed
            // URL, so "Xem bằng web" still works after an HLS failure);
            // other links go to the embed surface.
            PhimCobeSource.isLocator(episode.slug) -> {
                try {
                    when (val target = app.phimcobe.resolvePlay(episode.slug)) {
                        is PhimCobePlay.Hls -> {
                            playFallbacks = target.fallbacks
                            _state.update {
                                it.copy(
                                    loading = false,
                                    episode = episode.copy(
                                        streamUrl = target.url,
                                        embedUrl = target.fallbacks
                                            .firstOrNull { fb -> !fb.contains(".m3u8") }
                                            ?: episode.embedUrl
                                    ),
                                    kind = PlaybackKind.STREAM,
                                    embedUrl = "",
                                    embedReferer = null,
                                    adCutSegments = 0,
                                    adCutSeconds = 0
                                )
                            }
                        }
                        is PhimCobePlay.Embed -> {
                            playFallbacks = target.fallbacks
                            _state.update {
                                it.copy(
                                    loading = false,
                                    episode = episode.copy(embedUrl = target.url),
                                    kind = PlaybackKind.EMBED,
                                    embedUrl = target.url,
                                    embedReferer = null
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (episode.embedUrl.isNotBlank()) {
                        _state.update {
                            it.copy(
                                loading = false,
                                episode = episode,
                                kind = PlaybackKind.EMBED,
                                embedUrl = episode.embedUrl,
                                embedReferer = null
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                episode = episode,
                                error = "Không lấy được link phát từ server PhimCobe."
                            )
                        }
                    }
                }
            }
            episode.streamUrl.isNotBlank() -> _state.update {
                it.copy(
                    loading = false, episode = episode, kind = PlaybackKind.STREAM,
                    embedUrl = "", embedReferer = null,
                    adCutSegments = 0, adCutSeconds = 0
                )
            }
            item.sourceId == "hhkungfu" -> {
                try {
                    // V2 (A7): the variant of the group the user is on —
                    // state.serverIndex is set before applyEpisode runs.
                    val variant = server()?.variantKey.orEmpty()
                    val embed = app.hhKungfu.resolveEmbed(episode.slug, variant)
                    _state.update {
                        it.copy(
                            loading = false, episode = episode, kind = PlaybackKind.EMBED,
                            embedUrl = embed,
                            // 1.3.1: the embed loads with the source's
                            // own watch page as Referer.
                            embedReferer = donghuaWatchReferer(item.slug, episode.slug)
                        )
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(loading = false, episode = episode, error = "Không lấy được link phát từ server này.")
                    }
                }
            }
            episode.embedUrl.isNotBlank() -> _state.update {
                it.copy(
                    loading = false, episode = episode, kind = PlaybackKind.EMBED,
                    embedUrl = episode.embedUrl, embedReferer = null
                )
            }
            else -> _state.update {
                it.copy(loading = false, episode = episode, error = "Tập này chưa có link phát.")
            }
        }
        // 1.3.4: whenever an episode lands on the embed surface, line
        // up its failover candidates in the background — a dead embed
        // then advances on its own (see failoverEmbed) instead of
        // sitting on its error card.
        val sNow = _state.value
        if (sNow.kind == PlaybackKind.EMBED && sNow.episode?.slug == episode.slug) {
            prepareEmbedCandidates(episode.number, episode.slug)
        }
    }

    /** One episode fully resolved to a playable target (1.3.4). */
    private data class ResolvedPlay(
        val groupIndex: Int,
        val episode: EpisodeItem,
        val kind: PlaybackKind,
        val url: String,
        /** 1.3.6: same-episode alternate URLs behind [url]. */
        val fallbacks: List<String> = emptyList()
    )

    /** 1.3.6: untried same-episode alternates for the current play. */
    private var playFallbacks: List<String> = emptyList()

    /**
     * Resolve one episode of one group to its playable target — the
     * same resolution applyEpisode performs for a single pick, reused
     * by the initial ranking and the embed failover.
     */
    private suspend fun resolveCandidate(
        item: TitleItem,
        groupIndex: Int,
        group: ServerGroup,
        episode: EpisodeItem
    ): ResolvedPlay? = when {
        PhimCobeSource.isLocator(episode.slug) -> try {
            when (val target = app.phimcobe.resolvePlay(episode.slug)) {
                is PhimCobePlay.Hls ->
                    ResolvedPlay(
                        groupIndex, episode, PlaybackKind.STREAM,
                        target.url, target.fallbacks
                    )
                is PhimCobePlay.Embed ->
                    ResolvedPlay(
                        groupIndex, episode, PlaybackKind.EMBED,
                        target.url, target.fallbacks
                    )
            }
        } catch (e: Exception) {
            episode.embedUrl.takeIf { it.isNotBlank() }
                ?.let { ResolvedPlay(groupIndex, episode, PlaybackKind.EMBED, it) }
        }
        item.sourceId == "hhkungfu" -> try {
            ResolvedPlay(
                groupIndex, episode, PlaybackKind.EMBED,
                app.hhKungfu.resolveEmbed(episode.slug, group.variantKey)
            )
        } catch (e: Exception) {
            null
        }
        episode.streamUrl.isNotBlank() ->
            ResolvedPlay(groupIndex, episode, PlaybackKind.STREAM, episode.streamUrl)
        episode.embedUrl.isNotBlank() ->
            ResolvedPlay(groupIndex, episode, PlaybackKind.EMBED, episode.embedUrl)
        else -> null
    }

    /** KK-infra hosts (their streams carry the spliced gambling ads). */
    private fun isKkInfra(url: String): Boolean {
        val host = try {
            java.net.URI(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        return host.contains("kkphim") || host.contains("kvp")
    }

    /** Put an already-resolved candidate on screen (no re-resolution). */
    private fun applyResolved(item: TitleItem, c: ResolvedPlay) {
        playFallbacks = c.fallbacks
        when (c.kind) {
            PlaybackKind.STREAM -> _state.update {
                it.copy(
                    loading = false,
                    serverIndex = c.groupIndex,
                    episode = c.episode.copy(
                        streamUrl = c.url,
                        // 1.3.6: keep the episode's first embed
                        // alternate on the episode itself, so the
                        // existing "Xem bằng web" / embed fallback
                        // actions have a real target (PhimCobe detail
                        // episodes carry no embed URL of their own).
                        embedUrl = c.fallbacks.firstOrNull { fb -> !fb.contains(".m3u8") }
                            ?: c.episode.embedUrl
                    ),
                    kind = PlaybackKind.STREAM,
                    embedUrl = "",
                    embedReferer = null,
                    adCutSegments = 0,
                    adCutSeconds = 0,
                    error = ""
                )
            }
            PlaybackKind.EMBED -> _state.update {
                it.copy(
                    loading = false,
                    serverIndex = c.groupIndex,
                    episode = c.episode.copy(embedUrl = c.url),
                    kind = PlaybackKind.EMBED,
                    embedUrl = c.url,
                    embedReferer = if (item.sourceId == "hhkungfu") {
                        donghuaWatchReferer(item.slug, c.episode.slug)
                    } else {
                        null
                    },
                    error = ""
                )
            }
        }
    }

    /**
     * 1.3.4: the initial pick for a multi-group title — resolve the
     * fallback episode's number across ALL groups in parallel, probe
     * the direct streams, and rank (see loadInitial). Null when
     * nothing resolves at all; the caller then keeps the old path,
     * whose error is the honest one.
     */
    private suspend fun pickInitialRanked(
        detail: TitleDetail,
        item: TitleItem,
        fallbackEpisode: EpisodeItem
    ): ResolvedPlay? {
        val number = fallbackEpisode.number
        val candidates = coroutineScope {
            detail.servers.flatMapIndexed { gi, group ->
                group.episodes.filter { it.number == number }
                    .map { ep -> async { resolveCandidate(item, gi, group, ep) } }
            }.awaitAll()
        }.filterNotNull().distinctBy { it.episode.slug }
        if (candidates.isEmpty()) return null
        val bySlug = candidates.associateBy { it.episode.slug }
        val statuses = ServerProbe.probeAll(detail.servers, number) { ep ->
            when (val c = bySlug[ep.slug]) {
                null -> ProbeTarget.None
                else -> if (c.kind == PlaybackKind.STREAM) {
                    ProbeTarget.Stream(c.url)
                } else {
                    ProbeTarget.Embed(c.url)
                }
            }
        }
        _state.update { it.copy(serverStatuses = statuses) }
        val priority = try {
            app.settings.flow.first().versionPriority
        } catch (e: Exception) {
            "TM"
        }
        val order = versionPriorityOrder(priority)
        fun langRank(groupIndex: Int): Int {
            val key = detail.servers.getOrNull(groupIndex)?.langKey ?: return order.size
            val i = order.indexOf(key)
            return if (i < 0) order.size else i
        }
        fun tier(c: ResolvedPlay): Int = when {
            c.kind == PlaybackKind.STREAM &&
                statuses[c.groupIndex]?.isAlive == true -> 0
            c.kind == PlaybackKind.STREAM -> 1
            else -> 2
        }
        return candidates.minWithOrNull(
            compareBy<ResolvedPlay>(
                { tier(it) },
                { if (it.kind == PlaybackKind.STREAM && isKkInfra(it.url)) 1 else 0 },
                { statuses[it.groupIndex]?.ms ?: Long.MAX_VALUE },
                { langRank(it.groupIndex) },
                { it.groupIndex }
            )
        )
    }

    // --- Embed failover (1.3.4) --------------------------------------

    /** Ledger of embed candidates already tried for this episode. */
    private val triedEmbedSlugs = mutableSetOf<String>()

    /** Ordered untried candidates for the current embed episode. */
    private var embedCandidates: List<ResolvedPlay> = emptyList()

    /**
     * Build the failover list for an embed episode: every same-number
     * episode in every group (including same-group variants the group
     * switcher cannot reach), resolved in parallel and ranked
     * STREAM-first, then Embed — a dead embed should land on a real
     * stream whenever one exists.
     */
    private fun prepareEmbedCandidates(number: Float, currentSlug: String) {
        val detail = _state.value.detail ?: return
        val item = _state.value.item ?: return
        triedEmbedSlugs.clear()
        triedEmbedSlugs += currentSlug
        embedCandidates = emptyList()
        viewModelScope.launch {
            val resolved = coroutineScope {
                detail.servers.flatMapIndexed { gi, group ->
                    group.episodes.filter { it.number == number && it.slug != currentSlug }
                        .map { ep -> async { resolveCandidate(item, gi, group, ep) } }
                }.awaitAll()
            }.filterNotNull()
            embedCandidates = resolved
                .filter { it.episode.slug !in triedEmbedSlugs }
                .distinctBy { it.episode.slug }
                .sortedWith(
                    compareBy<ResolvedPlay>(
                        { if (it.kind == PlaybackKind.STREAM) 0 else 1 },
                        { if (it.kind == PlaybackKind.STREAM && isKkInfra(it.url)) 1 else 0 },
                        { it.groupIndex }
                    )
                )
        }
    }

    /**
     * Advance a failed embed to the next untried candidate. Returns
     * false when nothing is left — the caller then shows the error
     * card with the real error. The candidate list is built in the
     * background at apply time; an early failure can race it, so the
     * UI retries this call once after a short wait before giving up.
     */
    fun failoverEmbed(): Boolean {
        val s = _state.value
        val item = s.item ?: return false
        val next = embedCandidates.firstOrNull { it.episode.slug !in triedEmbedSlugs }
            ?: return false
        triedEmbedSlugs += next.episode.slug
        embedCandidates = embedCandidates.filter { it.episode.slug != next.episode.slug }
        _state.update { it.copy(loading = true) }
        applyResolved(item, next)
        return true
    }

    /**
     * 1.3.6: advance to the next SAME-episode alternate URL the
     * playback API returned for this episode (the other HLS
     * variants, then the embeds). The cross-group mechanisms
     * (failoverEmbed, nextAutoServer) re-resolve the same gated
     * pool through other server groups; this episode's own
     * alternates used to be discarded inside resolvePlay, so a
     * route-gated HLS host ended playback even though a working
     * embed for the same episode existed. Returns false when
     * nothing is left — the caller then moves on to the existing
     * group-level rescue or the honest error card.
     */
    fun failoverPlay(): Boolean {
        val s = _state.value
        val ep = s.episode ?: return false
        val next = playFallbacks.firstOrNull() ?: return false
        playFallbacks = playFallbacks.drop(1)
        if (next.contains(".m3u8")) {
            _state.update {
                it.copy(
                    loading = false,
                    episode = ep.copy(streamUrl = next),
                    kind = PlaybackKind.STREAM,
                    embedUrl = "",
                    embedReferer = null,
                    error = ""
                )
            }
        } else {
            _state.update {
                it.copy(
                    loading = false,
                    episode = ep.copy(embedUrl = next),
                    kind = PlaybackKind.EMBED,
                    embedUrl = next,
                    embedReferer = null,
                    error = ""
                )
            }
        }
        return true
    }

    /**
     * 1.3.4: the STREAM auto-switch target — the next group by probe
     * ranking (alive first by measured ms, then unmeasured, then
     * embed-only, dead last), skipping everything in [tried]. The old
     * rule was a blind (index + 1) taken exactly once, which on a
     * one-host failure walked into the next dead group and stopped.
     */
    fun nextAutoServer(tried: Set<Int>): Int? {
        val s = _state.value
        val servers = s.detail?.servers ?: return null
        val statuses = s.serverStatuses
        fun tier(i: Int): Int {
            val st = statuses[i] ?: return 1
            return when {
                st.isAlive -> 0
                st.state == ServerStatus.State.UNKNOWN -> 1
                st.state == ServerStatus.State.EMBED -> 2
                else -> 3
            }
        }
        return servers.indices
            .filter { it != s.serverIndex && it !in tried }
            .minWithOrNull(
                compareBy<Int>(
                    { tier(it) },
                    { statuses[it]?.ms ?: Long.MAX_VALUE },
                    { it }
                )
            )
    }

    /**
     * 1.3.3b: HlsAdFilter (wired into the player's playlist parser in
     * PlayerActivity) reports what it ACTUALLY cut from a media
     * playlist. Guards keep the number honest: a report counts only
     * for the stream currently on screen — a stale parser finishing
     * an old episode/server load must not paint its numbers onto this
     * one — and when several renditions of the same episode get
     * parsed, the LARGEST report wins (each rendition carries roughly
     * the same ad breaks; the max is the least-understated measure).
     */
    fun recordAdCut(forUrl: String, report: AdCutReport) {
        if (report.cutSegments <= 0) return
        val s = _state.value
        if (s.kind != PlaybackKind.STREAM) return
        if (s.episode?.streamUrl != forUrl) return
        val seconds = report.cutSeconds.toInt()
        _state.update {
            if (seconds > it.adCutSeconds) {
                it.copy(
                    adCutSegments = report.cutSegments,
                    adCutSeconds = seconds
                )
            } else {
                it
            }
        }
    }

    /** Fallback for a dead direct stream: play this episode through its web embed. */
    fun useEmbedFallback() {
        val s = _state.value
        val ep = s.episode ?: return
        if (ep.embedUrl.isBlank()) return
        _state.update {
            it.copy(
                loading = false, kind = PlaybackKind.EMBED, embedUrl = ep.embedUrl,
                error = "", embedReferer = null
            )
        }
    }

    /**
     * 1.3.1: the source watch-page URL of one Donghua episode, built
     * from the title slug + the episode locator ("postId|tap-n|sv") —
     * "https://hhkungfu.ee/watch-<slug>/tap-n-sv<sv>.html", the exact
     * page the site itself plays the episode on. A malformed locator
     * degrades to the site root (still a same-host Referer).
     */
    private fun donghuaWatchReferer(titleSlug: String, locator: String): String {
        val parts = locator.split("|")
        if (parts.size != 3) return "https://hhkungfu.ee/"
        val epAttr = parts[1].trim()
        val sv = parts[2].trim()
        if (epAttr.isBlank() || sv.isBlank()) return "https://hhkungfu.ee/"
        return "https://hhkungfu.ee/watch-$titleSlug/$epAttr-sv$sv.html"
    }

    fun server(): ServerGroup? = _state.value.detail?.servers?.getOrNull(_state.value.serverIndex)

    fun episodes(): List<EpisodeItem> = server()?.episodes ?: emptyList()

    fun switchEpisode(episode: EpisodeItem) {
        val item = _state.value.item ?: return
        _state.update { it.copy(loading = true, resumePositionMs = 0) }
        viewModelScope.launch { applyEpisode(item, episode) }
    }

    /** Switch server, keeping the same episode NUMBER and (approximately) position. */
    fun switchServer(index: Int, keepPositionMs: Long) {
        val s = _state.value
        val detail = s.detail ?: return
        val item = s.item ?: return
        val currentNumber = s.episode?.number ?: return
        val target = detail.servers.getOrNull(index) ?: return
        val episode = target.episodes.firstOrNull { it.number == currentNumber }
            ?: target.episodes.firstOrNull() ?: return
        _state.update { it.copy(serverIndex = index, loading = true, resumePositionMs = keepPositionMs) }
        viewModelScope.launch { applyEpisode(item, episode) }
    }

    fun prevEpisode(): EpisodeItem? {
        val cur = _state.value.episode ?: return null
        return episodes().filter { it.number < cur.number }.maxByOrNull { it.number }
    }

    fun nextEpisode(): EpisodeItem? {
        val cur = _state.value.episode ?: return null
        return episodes().filter { it.number > cur.number }.minByOrNull { it.number }
    }

    /**
     * V2 (A6): warm the shared video cache with the head of the NEXT
     * episode (direct HLS only) once playback passes the halfway point.
     * The PlayerScreen's progress loop calls this repeatedly; the
     * attempted-set makes each episode warm at most once per session.
     * Embed episodes (incl. all Donghua) are skipped — a WebView stream
     * cannot be prefetched, and the UI promises nothing about it.
     */
    private val prefetchAttempted = mutableSetOf<String>()
    fun prefetchNext() {
        val s = _state.value
        if (s.kind != PlaybackKind.STREAM) return
        val next = nextEpisode() ?: return
        if (!prefetchAttempted.add(next.slug)) return
        viewModelScope.launch {
            val url = when {
                PhimCobeSource.isLocator(next.slug) -> try {
                    (app.phimcobe.resolvePlay(next.slug) as? PhimCobePlay.Hls)?.url
                } catch (e: Exception) {
                    null
                }
                next.streamUrl.isNotBlank() -> next.streamUrl
                else -> null
            } ?: return@launch
            com.example.nflix.data.PlayerPrefetch.prefetchHls(app.videoCache, url)
        }
    }

    /** Only episodes actually opened in the player count as watched. */
    fun isWatched(number: Float): Boolean {
        val st = _state.value.userState ?: return false
        return st.watchedSet().contains(number)
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val s = _state.value
        val episode = s.episode ?: return
        val item = s.item ?: return
        if (durationMs <= 0) return
        viewModelScope.launch {
            app.database.titleDao().upsert(item.toEntity())
            val current = app.database.userStateDao().get(item.id)
                ?: UserStateEntity(titleId = item.id)
            val finished = positionMs >= durationMs * 0.95
            val total = s.detail?.servers?.maxOfOrNull { srv -> srv.episodes.size }
                ?: current.latestCount
            val newLatest = maxOf(current.latestCount, total)
            // "Có tập mới" counts units the SOURCE added after tracking began.
            // First ever watch = baseline (everything out now). After that,
            // watching consumes only the units actually watched (<= this one),
            // so a newly added episode stays "new" until he really watches it.
            val consumed = episodes().count { it.number <= episode.number }
            val newSeen = when {
                current.seenCount == 0 && current.latestCount == 0 -> newLatest
                consumed > 0 -> maxOf(current.seenCount, minOf(consumed, newLatest))
                else -> current.seenCount
            }
            val next = current.withWatched(episode.number).copy(
                lastUnitLabel = episode.label,
                lastUnitNumber = episode.number,
                positionMs = if (finished) 0 else positionMs,
                durationMs = durationMs,
                progressFraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                finished = finished,
                seenCount = newSeen,
                latestCount = newLatest,
                pageIndex = 0,
                pageCount = 0,
                lastTouchedEpochSec = System.currentTimeMillis() / 1000
            )
            app.database.userStateDao().upsert(next)
            _state.update { it.copy(userState = next) }
        }
    }
}
