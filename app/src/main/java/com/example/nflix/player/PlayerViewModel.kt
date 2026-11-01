package com.example.nflix.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val serverStatuses: Map<Int, ServerStatus> = emptyMap()
)

class PlayerViewModel(
    private val app: NflixApp,
    private val titleId: String,
    private val episodeSlug: String,
    private val episodeNumber: Float = 0f
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
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
                                                    target.url
                                                is CobePlay.Embed ->
                                                    target.url
                                            }
                                        } catch (e: Exception) {
                                            ep.embedUrl.ifBlank { null }
                                        }
                                    item.sourceId == "hhkungfu" -> try {
                                        app.hhKungfu.resolveEmbed(
                                            ep.slug,
                                            prefer4k = item.quality.contains("4K")
                                        )
                                    } catch (e: Exception) {
                                        null
                                    }
                                    else -> ep.streamUrl.ifBlank { ep.embedUrl.ifBlank { null } }
                                }
                            }
                            _state.update { it.copy(serverStatuses = statuses) }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = "Không tải được — kiểm tra mạng rồi thử lại.")
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
                                embedUrl = ""
                            )
                        }
                        is CobePlay.Embed -> _state.update {
                            it.copy(
                                loading = false,
                                episode = episode.copy(embedUrl = target.url),
                                kind = PlaybackKind.EMBED,
                                embedUrl = target.url
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
                                embedUrl = episode.embedUrl
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
                it.copy(loading = false, episode = episode, kind = PlaybackKind.STREAM, embedUrl = "")
            }
            item.sourceId == "hhkungfu" -> {
                try {
                    val embed = app.hhKungfu.resolveEmbed(episode.slug, prefer4k = item.quality.contains("4K"))
                    _state.update {
                        it.copy(loading = false, episode = episode, kind = PlaybackKind.EMBED, embedUrl = embed)
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(loading = false, episode = episode, error = "Không lấy được link phát từ server này.")
                    }
                }
            }
            episode.embedUrl.isNotBlank() -> _state.update {
                it.copy(loading = false, episode = episode, kind = PlaybackKind.EMBED, embedUrl = episode.embedUrl)
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
            it.copy(loading = false, kind = PlaybackKind.EMBED, embedUrl = ep.embedUrl, error = "")
        }
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
