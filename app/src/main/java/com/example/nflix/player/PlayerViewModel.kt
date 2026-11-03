package com.example.nflix.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.ProbeTarget
import com.example.nflix.data.ServerProbe
import com.example.nflix.data.ServerStatus
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.local.watchedSet
import com.example.nflix.data.local.withWatched
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.source.CobePlay
import com.example.nflix.data.source.CobeSource
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    val embedReferer: String? = null
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
            // Đợt 4: same merged front as the Detail screen, so
            // the server pool (KKPhim + Cobe groups) matches what
            // the user picked from; cobe-only titles stay cobe.
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
        val episode = server?.episodes?.firstOrNull { it.slug == episodeSlug }
            ?: server?.episodes?.firstOrNull { it.number == episodeNumber }
            ?: server?.episodes?.firstOrNull()
            ?: throw IllegalStateException("Không thấy tập này")

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
        applyEpisode(detail.item, episode)
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
                            CobeSource.isLocator(ep.slug) ->
                                try {
                                    when (
                                        val target = app.cobe.resolvePlay(ep.slug)
                                    ) {
                                        is CobePlay.Hls ->
                                            ProbeTarget.Stream(target.url)
                                        is CobePlay.Embed ->
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
        when {
            // Đợt 4: cobe episodes carry only a locator — the real link is
            // resolved per episode. Derived/direct HLS feeds ExoPlayer
            // like any KKPhim stream (the episode keeps its API embed URL,
            // so "Xem bằng web" still works after an HLS failure); other
            // links go to the embed surface.
            CobeSource.isLocator(episode.slug) -> {
                try {
                    when (val target = app.cobe.resolvePlay(episode.slug)) {
                        is CobePlay.Hls -> _state.update {
                            it.copy(
                                loading = false,
                                episode = episode.copy(streamUrl = target.url),
                                kind = PlaybackKind.STREAM,
                                embedUrl = "",
                                embedReferer = null
                            )
                        }
                        is CobePlay.Embed -> _state.update {
                            it.copy(
                                loading = false,
                                episode = episode.copy(embedUrl = target.url),
                                kind = PlaybackKind.EMBED,
                                embedUrl = target.url,
                                embedReferer = null
                            )
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
                                error = "Không lấy được link phát từ server Cobe."
                            )
                        }
                    }
                }
            }
            episode.streamUrl.isNotBlank() -> _state.update {
                it.copy(
                    loading = false, episode = episode, kind = PlaybackKind.STREAM,
                    embedUrl = "", embedReferer = null
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
                CobeSource.isLocator(next.slug) -> try {
                    (app.cobe.resolvePlay(next.slug) as? CobePlay.Hls)?.url
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
