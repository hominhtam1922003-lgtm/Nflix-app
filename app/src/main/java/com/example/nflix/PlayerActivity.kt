package com.example.nflix

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Rational
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.example.nflix.data.AdBlock
import com.example.nflix.data.AdCutPlaylistParserFactory
import com.example.nflix.data.AppSettings
import com.example.nflix.data.SettingsStore
import com.example.nflix.player.PlaybackKind
import com.example.nflix.player.PlayerUiState
import com.example.nflix.player.PlayerViewModel
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGreen
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixTheme
import com.example.nflix.ui.theme.NflixViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Video player screen (landscape, PiP-capable) — Màn 10. */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE_ID = "nflix.extra.TITLE_ID"
        const val EXTRA_EPISODE_SLUG = "nflix.extra.EPISODE_SLUG"
        const val EXTRA_EPISODE_LABEL = "nflix.extra.EPISODE_LABEL"
        const val EXTRA_EPISODE_NUMBER = "nflix.extra.EPISODE_NUMBER"
    }

    // Updated by the player composable; read by onUserLeaveHint for auto-PiP.
    var pipEligible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        val episodeSlug = intent.getStringExtra(EXTRA_EPISODE_SLUG).orEmpty()
        val episodeNumber = intent.getFloatExtra(EXTRA_EPISODE_NUMBER, 0f)
        setContent {
            NflixTheme {
                PlayerScreen(
                    titleId = titleId,
                    episodeSlug = episodeSlug,
                    episodeNumber = episodeNumber,
                    onBack = { finish() },
                    onPipEligible = { pipEligible = it }
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible) enterPip()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** True fullscreen: no status/navigation bars eating the top of the video. */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Synced with MainActivity: draw into the cutout area too, so the
        // video is not letterboxed by a punch-hole camera in landscape.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    fun enterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) { /* device without PiP */ }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 1.3.7: the persisted display-size setting (Netflix-style) mapped
 * to a PlayerView resize mode. FIT keeps the source aspect (black
 * bars possible), FULL zooms to fill (crops the edges), STRETCH
 * distorts to fill.
 */
private fun resizeModeFor(mode: String): Int = when (mode) {
    "FULL" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "STRETCH" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun qualityLabelForHeight(height: Int): String = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "FHD"
    height >= 700 -> "HD"
    height > 0 -> "${height}p"
    else -> "FHD"
}

@Composable
private fun PlayerScreen(
    titleId: String,
    episodeSlug: String,
    episodeNumber: Float = 0f,
    onBack: () -> Unit,
    onPipEligible: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as PlayerActivity
    val app = context.applicationContext as NflixApp
    val viewModel = remember(titleId, episodeSlug) {
        PlayerViewModel(app, titleId, episodeSlug, episodeNumber)
    }
    val state by viewModel.state.collectAsState()
    val settings by app.settings.flow.collectAsState(initial = AppSettings())

    if (state.kind == PlaybackKind.EMBED) {
        EmbedPlayer(state = state, viewModel = viewModel, onBack = onBack)
        return
    }

    val exo = remember {
        ExoPlayer.Builder(context).build().also { activity.pipEligible = false }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0) }
    var durationMs by remember { mutableLongStateOf(0) }
    var bufferedMs by remember { mutableLongStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var heights by remember { mutableStateOf<List<Int>>(emptyList()) }
    var qualityCap by remember { mutableIntStateOf(-1) } // -1 = no cap (Cao nhất)
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var showServers by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showQuickSettings by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    /** 1.3.4: the REAL technical error under the friendly headline. */
    var playbackErrorDetail by remember { mutableStateOf<String?>(null) }
    var reportSaved by remember { mutableStateOf(false) }
    val playerScope = androidx.compose.runtime.rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    // 1.3.4: auto-switch bookkeeping — which episode the tried-set
    // belongs to, and which server indices it already burned.
    var autoSwitchEpisode by remember { mutableStateOf<String?>(null) }
    var triedServers by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 1.3.12 fix1: Media3 1.4 has no ExoPlayer.addTextOutput —
    // cues reach the screen-anchored overlay below through
    // Player.Listener.onCues, the same channel PlayerView itself
    // uses to feed its own subtitle view.
    var subtitleOverlay by remember { mutableStateOf<SubtitleView?>(null) }
    // 1.3.13: display aspect of the running video (width × pixel
    // ratio ÷ height), fed by the listener's onVideoSizeChanged.
    // The FULLSUB surface below sizes itself from this; 0 means
    // "not known yet" and the surface falls back to 16:9.
    var videoAspect by remember { mutableFloatStateOf(0f) }

    fun applyQualityCap(cap: Int) {
        qualityCap = cap
        val params = exo.trackSelectionParameters.buildUpon()
        if (cap <= 0) params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        else params.setMaxVideoSize((cap * 16 + 8) / 9, cap)
        exo.trackSelectionParameters = params.build()
    }

    /**
     * 1.3.12: Netflix-style detached subtitles, part 2 — if the
     * stream carries text tracks but the selector picked none
     * (tagged neither Vietnamese nor undetermined), force-select
     * the best one (vi > undetermined > en > first). Converges:
     * applying the override re-fires onTracksChanged with a text
     * track selected, which returns early. Streams with subtitles
     * burned into the picture have no text groups — nothing here
     * can (or claims to) help those.
     */
    fun autoSelectTextTrack(tracks: Tracks) {
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty() || textGroups.any { it.isSelected }) return
        val best = textGroups.maxByOrNull { g ->
            val lang = g.getTrackFormat(0).language ?: ""
            when {
                lang.startsWith("vi") -> 3
                lang.isEmpty() || lang == "und" -> 2
                lang.startsWith("en") -> 1
                else -> 0
            }
        } ?: return
        var idx = -1
        for (i in 0 until best.length) {
            if (best.isTrackSupported(i)) {
                idx = i
                break
            }
        }
        if (idx < 0) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, listOf(idx)))
            .build()
    }

    // Load the episode's stream into ExoPlayer.
    LaunchedEffect(state.episode?.slug, state.serverIndex, state.kind, retryKey) {
        val ep = state.episode ?: return@LaunchedEffect
        if (state.kind != PlaybackKind.STREAM || ep.streamUrl.isBlank()) return@LaunchedEffect
        ended = false
        playbackError = null
        playbackErrorDetail = null
        reportSaved = false
        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://phimapi.com/"))
        // V2 (A6): stream through the shared write-through cache — the
        // exact source bytes land on disk as they play (no quality
        // downgrade anywhere), and the next-episode prefetcher warms
        // this same cache so episode switches start locally.
        val appRef = context.applicationContext as NflixApp
        val dataSource = CacheDataSource.Factory()
            .setCache(appRef.videoCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(appRef.videoCache)
            )
        // 1.3.3b: media playlists pass through the mid-roll ad
        // cutter (HlsAdFilter) on their way into the parser — spliced
        // gambling-ad blocks are removed before a timeline exists.
        // The filter is fail-open (any doubt → original playlist) and
        // reports only REAL removals back into the VM state, which
        // the controls surface as "Đã cắt quảng cáo: ~Xs" when > 0.
        val source = HlsMediaSource.Factory(dataSource)
            .setPlaylistParserFactory(
                AdCutPlaylistParserFactory { url, report ->
                    viewModel.recordAdCut(url, report)
                }
            )
            .createMediaSource(MediaItem.fromUri(ep.streamUrl))
        // 1.3.12: Netflix-style detached subtitles, part 1 — when
        // the stream carries a separate TEXT subtitle rendition,
        // prefer the Vietnamese one (undetermined-language tracks
        // count too). The onTracksChanged fallback below force-picks
        // a text track when the selector still leaves it unselected.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("vi")
            .setSelectUndeterminedTextLanguage(true)
            .build()
        exo.setMediaSource(source)
        exo.prepare()
        if (state.resumePositionMs > 0) exo.seekTo(state.resumePositionMs)
        // Default quality from settings until the user picks in-player.
        when (settings.qualityDefault) {
            "P1080" -> applyQualityCap(1080)
            "P720" -> applyQualityCap(720)
            "P480" -> applyQualityCap(480)
            else -> applyQualityCap(-1)
        }
        exo.playWhenReady = true
    }

    // Player events.
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.saveProgress(durationMs, durationMs)
                    val next = viewModel.nextEpisode()
                    if (settings.autoNextEpisode && next != null) {
                        viewModel.switchEpisode(next)
                    } else {
                        ended = true
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPipEligible(playing && settings.pipEnabled)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Never sit on a silent black screen (user report 2026-11-01).
                // 1.3.4: the silent rescue now walks EVERY untried
                // server in probe order (alive first) instead of one
                // blind (index + 1) hop — and when it runs out, the
                // card shows the REAL error under the friendly
                // headline (the network audit found the old mapping
                // destroyed the evidence: 403/5xx and the canned text
                // were indistinguishable afterwards).
                isBuffering = false
                val s = viewModel.state.value
                val slug = s.episode?.slug
                if (autoSwitchEpisode != slug) {
                    autoSwitchEpisode = slug
                    triedServers = emptySet()
                }
                // 1.3.6: this episode's own alternates first (the
                // other HLS variants, then the embeds the playback
                // API returned) — a route-gated HLS host must not
                // burn a whole server-group hop before the episode's
                // own embed gets its chance.
                if (viewModel.failoverPlay()) {
                    playbackError = null
                    playbackErrorDetail = null
                    return
                }
                val next = viewModel.nextAutoServer(triedServers + s.serverIndex)
                if (next != null) {
                    triedServers = triedServers + s.serverIndex
                    playbackError = null
                    playbackErrorDetail = null
                    viewModel.switchServer(next, exo.currentPosition)
                } else {
                    playbackError = when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            "Server báo không tìm thấy video này — link nguồn có thể đã chết. Hãy thử lại, đổi server hoặc xem bằng web."
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Không kết nối được tới server phát. Kiểm tra mạng rồi thử lại."
                        else ->
                            "Không phát được video từ server này. Hãy thử lại hoặc đổi server."
                    }
                    playbackErrorDetail = buildString {
                        append(error.errorCodeName)
                        error.message?.takeIf { it.isNotBlank() }
                            ?.let { append(" · ").append(it) }
                        error.cause?.let { cause ->
                            append(" · nguyên nhân: ")
                            append(cause.javaClass.simpleName)
                            cause.message?.takeIf { it.isNotBlank() }
                                ?.let { append(": ").append(it) }
                        }
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val found = sortedSetOf<Int>()
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        val h = group.getTrackFormat(i).height
                        if (h > 0) found.add(h)
                    }
                }
                heights = found.toList()
                autoSelectTextTrack(tracks)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 1.3.13: the FULLSUB (bottom-anchored zoom)
                // surface sizes itself from the real display
                // aspect of the stream, pixel ratio included.
                if (videoSize.height > 0) {
                    videoAspect =
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                // 1.3.12 fix1: feed the detached overlay (see
                // subtitleOverlay above) — the text is rendered
                // against the screen, never inside the video
                // frame, so Lấp đầy cropping cannot cut it.
                subtitleOverlay?.setCues(cueGroup.cues)
            }
        }
        exo.addListener(listener)
        onDispose {
            viewModel.saveProgress(exo.currentPosition, exo.duration)
            exo.removeListener(listener)
            exo.release()
            onPipEligible(false)
        }
    }

    // Progress polling + periodic progress save.
    LaunchedEffect(exo) {
        var ticks = 0
        while (true) {
            positionMs = exo.currentPosition.coerceAtLeast(0)
            durationMs = exo.duration.coerceAtLeast(0)
            // C5 (K11): real buffered position for the seek bar's middle layer.
            bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
            ticks++
            if (ticks % 10 == 0 && durationMs > 0) {
                viewModel.saveProgress(positionMs, durationMs)
            }
            // V2 (A6): past the halfway point, warm the next episode's
            // head in the shared cache (direct HLS only — the VM no-ops
            // for embeds and after one attempt per episode).
            if (durationMs > 0 && positionMs * 2 >= durationMs) {
                viewModel.prefetchNext()
            }
            delay(500)
        }
    }

    // Auto-hide controls after 3 s while playing.
    LaunchedEffect(controlsVisible, isPlaying, locked) {
        if (controlsVisible && isPlaying && !locked) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(700)
            seekFlash = null
        }
    }

    val currentQualityLabel = if (qualityCap <= 0) {
        if (heights.isNotEmpty()) qualityLabelForHeight(heights.max()) else "FHD"
    } else qualityLabelForHeight(qualityCap)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (!locked) controlsVisible = !controlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        if (offset.x < size.width / 2) {
                            exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                            seekFlash = "−10 giây"
                        } else {
                            exo.seekTo(exo.currentPosition + 10_000)
                            seekFlash = "+10 giây"
                        }
                        controlsVisible = true
                    }
                )
            }
    ) {
        if (settings.playerResize == "FULLSUB") {
            // 1.3.13: "Lấp đầy (giữ phụ đề)" — bottom-anchored
            // zoom for titles whose subtitles are burned into the
            // picture (no detached track for 1.3.12 to render).
            // AspectRatioFrameLayout can only crop around the
            // CENTRE, so this mode swaps the PlayerView for a
            // TextureView sized by layout instead: uniform scale
            // until the screen is filled, anchored to the BOTTOM
            // edge — the whole overflow is cropped from the top,
            // the bottom of the picture (where burned-in
            // subtitles live) is never cut, and nothing is
            // distorted. The other three modes keep the exact
            // PlayerView path they have always used.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                val containerAspect =
                    if (maxHeight > 0.dp) maxWidth / maxHeight else 0f
                val va = if (videoAspect > 0f) videoAspect else 16f / 9f
                val sized =
                    if (containerAspect > 0f && va >= containerAspect) {
                        // Video relatively wider than the screen:
                        // fill by height, overflow left/right.
                        Modifier.fillMaxHeight().aspectRatio(va)
                    } else {
                        // Fill by width, overflow at the top only.
                        Modifier.fillMaxWidth().aspectRatio(va)
                    }
                AndroidView(
                    factory = { ctx ->
                        android.view.TextureView(ctx).also { tv ->
                            // Media3 1.4: the texture-surface
                            // methods live on ExoPlayer's
                            // VideoComponent, not on ExoPlayer
                            // itself (verified against the real
                            // 1.4.1 jar before pushing).
                            exo.videoComponent.setVideoTextureView(tv)
                        }
                    },
                    update = { tv ->
                        // Re-assert after the old surface's detach
                        // has run, so a mode switch can never leave
                        // the player surfaceless (black picture).
                        exo.videoComponent.setVideoTextureView(tv)
                    },
                    onRelease = { tv ->
                        exo.videoComponent.clearVideoTextureView(tv)
                    },
                    modifier = sized.align(Alignment.BottomCenter)
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        resizeMode = resizeModeFor(settings.playerResize)
                        // 1.3.12: the stock subtitle view lives INSIDE
                        // the aspect-ratio frame, so under Lấp đầy (ZOOM)
                        // it is cropped along with the picture — the
                        // screen-anchored overlay below renders instead.
                        subtitleView?.visibility = android.view.View.GONE
                    }
                },
                update = { view ->
                    // 1.3.7: a display-size change in the settings sheet
                    // applies to the running player immediately.
                    view.resizeMode = resizeModeFor(settings.playerResize)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 1.3.12: Netflix-style detached subtitles, part 3 — the
        // subtitle layer is anchored to the SCREEN, outside the
        // video frame, so display-size cropping (Lấp đầy) can no
        // longer cut the text: the text is not part of the picture.
        AndroidView(
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    setBottomPaddingFraction(0.10f)
                    subtitleOverlay = this
                }
            },
            onRelease = {
                subtitleOverlay = null
            },
            modifier = Modifier.fillMaxSize()
        )

        if (state.loading || isBuffering) {
            CircularProgressIndicator(
                color = NflixViolet,
                modifier = Modifier.align(Alignment.Center).size(46.dp)
            )
        }

        if (state.error.isNotBlank() && state.episode == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.error, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Quay lại",
                    color = NflixViolet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onBack)
                )
            }
        }

        // Real stream error (ExoPlayer) — message + recovery actions.
        if (playbackError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(playbackError!!, color = Color.White, fontSize = 14.sp)
                // 1.3.4: the technical error, small and muted — one
                // screenshot now settles WHAT the server actually did.
                playbackErrorDetail?.let { detail ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        detail,
                        color = NflixTextMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Thử lại",
                        color = NflixViolet,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { retryKey++ }
                    )
                    if ((state.detail?.servers?.size ?: 0) > 1) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Đổi server",
                            color = NflixViolet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val count = state.detail?.servers?.size ?: 1
                                viewModel.switchServer(
                                    (state.serverIndex + 1) % count,
                                    exo.currentPosition
                                )
                            }
                        )
                    }
                    if (!state.episode?.embedUrl.isNullOrBlank()) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Xem bằng web",
                            color = NflixViolet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { viewModel.useEmbedFallback() }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 0.4: report THIS error for real — title, episode, server
                // and the exact message go to the local report store.
                Text(
                    if (reportSaved) "Đã ghi báo lỗi ✓" else "Báo lỗi này",
                    color = if (reportSaved) NflixGreen else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !reportSaved) {
                        val s = state
                        // 1.3.4: the report carries the technical
                        // detail too, not only the friendly headline.
                        val errorText = listOfNotNull(playbackError, playbackErrorDetail)
                            .joinToString("\n")
                        playerScope.launch {
                            withContext(Dispatchers.IO) {
                                com.example.nflix.data.ReportStore.add(
                                    context,
                                    com.example.nflix.data.ErrorReport(
                                        timeEpochSec = System.currentTimeMillis() / 1000,
                                        family = s.item?.family?.name ?: "",
                                        titleId = s.item?.id ?: titleId,
                                        titleName = s.item?.name ?: "",
                                        unitLabel = s.episode?.label ?: "",
                                        server = s.detail?.servers
                                            ?.getOrNull(s.serverIndex)?.name ?: "",
                                        message = errorText
                                    )
                                )
                            }
                            reportSaved = true
                        }
                    }
                )
            }
        }

        seekFlash?.let { flash ->
            Box(
                modifier = Modifier
                    .align(if (flash.startsWith("−")) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 90.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(flash, color = Color.White, fontSize = 13.sp)
            }
        }

        // Lock button — the only control left while locked.
        if (locked) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(42.dp)
                    .background(Color(0x80000000), CircleShape)
                    .clickable { locked = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = "Mở khoá điều khiển",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (controlsVisible && !locked) {
            PlayerControls(
                state = state,
                isPlaying = isPlaying,
                ended = ended,
                positionMs = if (scrubbing != null) (scrubbing!! * durationMs).toLong() else positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                speed = speed,
                qualityLabel = currentQualityLabel,
                statusText = when {
                    playbackError != null -> "Server lỗi"
                    state.loading || isBuffering -> "Đang tải…"
                    else -> "Server đang tốt"
                },
                statusDot = when {
                    playbackError != null -> NflixRed
                    state.loading || isBuffering -> NflixAmber
                    else -> NflixGreen
                },
                settings = settings,
                onBack = onBack,
                onPlayPause = {
                    if (ended) {
                        exo.seekTo(0)
                        ended = false
                    }
                    if (exo.isPlaying) exo.pause() else exo.play()
                },
                onSeekBack = { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)) },
                onSeekFwd = { exo.seekTo(exo.currentPosition + 10_000) },
                onLock = { locked = true },
                onScrub = { scrubbing = it },
                onScrubFinished = {
                    scrubbing?.let { exo.seekTo((it * durationMs).toLong()) }
                    scrubbing = null
                },
                onPrev = { viewModel.prevEpisode()?.let { viewModel.switchEpisode(it) } },
                onNext = { viewModel.nextEpisode()?.let { viewModel.switchEpisode(it) } },
                hasPrev = viewModel.prevEpisode() != null,
                hasNext = viewModel.nextEpisode() != null,
                nextLabel = viewModel.nextEpisode()?.label ?: "",
                onSkipIntro = {
                    val dur = exo.duration
                    if (exo.currentPosition < 85_000) exo.seekTo(85_000)
                    else if (dur > 0) exo.seekTo(dur)
                },
                onShowServers = { showServers = true },
                onShowSpeed = { showSpeed = true },
                onShowQuality = { showQuality = true },
                onShowEpisodes = { showEpisodes = true },
                onShowQuickSettings = { showQuickSettings = true },
                onPip = { if (settings.pipEnabled) activity.enterPip() },
                onFullscreen = {
                    portrait = !portrait
                    activity.requestedOrientation = if (portrait)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            )
        }
    }

    if (showServers) {
        ServerSheet(
            servers = state.detail?.servers ?: emptyList(),
            selectedIndex = state.serverIndex,
            onSelect = { idx ->
                showServers = false
                viewModel.switchServer(idx, exo.currentPosition)
            },
            onDismiss = { showServers = false },
            serverStatuses = state.serverStatuses
        )
    }
    if (showSpeed) {
        SpeedSheet(
            current = speed,
            onSelect = { s ->
                speed = s
                exo.setPlaybackSpeed(s)
                showSpeed = false
            },
            onDismiss = { showSpeed = false }
        )
    }
    if (showQuality) {
        QualitySheet(
            heights = heights,
            currentCap = qualityCap,
            onSelect = { cap ->
                applyQualityCap(cap)
                showQuality = false
            },
            onDismiss = { showQuality = false }
        )
    }
    if (showEpisodes) {
        EpisodesSheet(
            episodes = viewModel.episodes(),
            currentSlug = state.episode?.slug ?: "",
            isWatched = { viewModel.isWatched(it) },
            onSelect = { ep ->
                showEpisodes = false
                viewModel.switchEpisode(ep)
            },
            onDismiss = { showEpisodes = false }
        )
    }
    if (showQuickSettings) {
        QuickSettingsSheet(
            settings = settings,
            onShowQuality = {
                showQuickSettings = false
                showQuality = true
            },
            onShowSpeed = {
                showQuickSettings = false
                showSpeed = true
            },
            onShowResize = {
                showQuickSettings = false
                showResize = true
            },
            onDismiss = { showQuickSettings = false }
        )
    }
    if (showResize) {
        ResizeSheet(
            current = settings.playerResize,
            onPick = { mode ->
                // 1.3.10: written on the SCREEN's scope, which
                // outlives the sheet — see ResizeSheet's note.
                playerScope.launch {
                    app.settings.setString(SettingsStore.PLAYER_RESIZE, mode)
                }
            },
            onDismiss = { showResize = false }
        )
    }
}

/**
 * Load an embed page into the WebView.
 *
 * No referer (every family except Donghua): the embed loads exactly
 * as it always has — top-level via loadUrl.
 *
 * With a referer (Donghua ONLY — PlayerViewModel fills it with the
 * episode's watch-page URL): 1.3.1 loaded the embed top-level with a
 * Referer header, which passed streamfree's SERVER-side gate but not
 * its CLIENT-side one — the embed's JS checks the frame context and,
 * finding itself top-level (window.self === window.top), renders
 * "video này chỉ có thể phát trong iframe (-2)". Reproduced both
 * ways in a real browser 2026-11-03 (dossier donghua-embed-iframe).
 *
 * 1.3.2: the embed now rides a wrapper page carrying a REAL <iframe>
 * whose markup copies player.php's own (referrerpolicy="unsafe-url",
 * allow="autoplay; fullscreen", allowfullscreen), loaded via
 * loadDataWithBaseURL with the watch page as baseURL — so the iframe
 * navigation carries an hhkungfu.ee Referer (server gate) AND the
 * embed really sits inside a frame (JS gate). Never enable
 * setWebContentsDebuggingEnabled on this WebView: the same host
 * answers DevTools-attached contexts with a "-3" refusal.
 */
private fun loadEmbed(view: WebView, url: String, referer: String?) {
    if (referer.isNullOrBlank()) {
        view.loadUrl(url)
        return
    }
    val src = url.replace("&", "&amp;").replace("\"", "&quot;")
    val wrapper = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%}iframe{border:0;width:100%;height:100%}</style>
        </head><body>
        <iframe src="$src" referrerpolicy="unsafe-url" allow="autoplay; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    view.loadDataWithBaseURL(referer, wrapper, "text/html", "UTF-8", null)
}

@Composable
private fun EmbedPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 1.3.2: the last embedUrl actually loaded into the WebView. The
    // reload guard compares against THIS, never view.url — with the
    // Donghua wrapper page, view.url is the watch-page baseURL, which
    // never equals the embed URL.
    var loadedEmbedUrl by remember { mutableStateOf<String?>(null) }
    // 1.1.1 (N5): a dead embed used to be a dead end — the page's own
    // "Setup error" (or a blank frame) just sat there. Main-frame load
    // failures now raise the same recovery overlay as the stream branch:
    // retry, switch server, report. Sub-resource errors (ad images, XHR
    // inside the page) must NOT trigger it — isForMainFrame guards that.
    var embedError by remember(state.embedUrl) { mutableStateOf<String?>(null) }
    // V2 (0.1): the ad-block setting, REALLY wired — sub-requests to
    // known ad/gambling domains are answered with an empty response
    // before they ever leave the WebView. Live value (a settings
    // change takes effect on the next embed open).
    val embedApp = context.applicationContext as NflixApp
    var adBlockOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        embedApp.settings.flow.collect { adBlockOn = it.adBlock }
    }
    // 1.3.7: display size for embeds — the same persisted setting as
    // the stream surface, applied as object-fit CSS on the page's
    // video element. Best effort: players rendering into a plain
    // <video> tag honour it; exotic renderers ignore it and keep
    // their own fit. The style element persists in the page, so a
    // video element created after injection is covered too.
    var embedResize by remember { mutableStateOf("FIT") }
    LaunchedEffect(Unit) {
        embedApp.settings.flow.collect { embedResize = it.playerResize }
    }
    LaunchedEffect(embedResize, loadedEmbedUrl) {
        val fit = when (embedResize) {
            "FULL" -> "cover"
            "STRETCH" -> "fill"
            // 1.3.13: bottom-anchored cover — same "Lấp đầy
            // (giữ phụ đề)" idea as the stream surface: the
            // crop lands on the top, the bottom (subtitles)
            // stays.
            "FULLSUB" -> "cover"
            else -> "contain"
        }
        val pos = if (embedResize == "FULLSUB") "center bottom" else "center center"
        webView?.evaluateJavascript(
            "(function(){var s=document.getElementById('nflix-fit');" +
                "if(!s){s=document.createElement('style');s.id='nflix-fit';" +
                "document.head.appendChild(s);}" +
                "s.textContent='video{object-fit:$fit !important;object-position:$pos !important}';})()",
            null
        )
    }
    var showServers by remember { mutableStateOf(false) }
    var reportSaved by remember(state.embedUrl) { mutableStateOf(false) }
    val embedScope = androidx.compose.runtime.rememberCoroutineScope()
    val servers = state.detail?.servers ?: emptyList()
    // 1.3.4: a main-frame embed failure first advances through the
    // untried candidates (other groups + same-group variants, real
    // streams first — see PlayerViewModel.failoverEmbed); only when
    // none are left does the error card appear, carrying the REAL
    // error. The candidate list is built in the background at apply
    // time, so a fast failure retries the call once after a short
    // wait before giving up.
    fun onEmbedFailed(realError: String) {
        // 1.3.6: this episode's own alternates first (see
        // failoverPlay), then the cross-group candidates.
        if (viewModel.failoverPlay()) return
        if (viewModel.failoverEmbed()) return
        embedScope.launch {
            kotlinx.coroutines.delay(1500)
            if (!viewModel.failoverPlay() && !viewModel.failoverEmbed()) {
                embedError = realError
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (state.embedUrl.isNotBlank()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                // V2 (0.1): known ad/gambling hosts get
                                // an empty body; everything else falls
                                // through to the real network untouched.
                                if (adBlockOn) {
                                    AdBlock.intercept(request)?.let { return it }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val desc = error?.description?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                    onEmbedFailed(
                                        "Không tải được trang phát của server này" +
                                            (desc?.let { " ($it)" } ?: "") +
                                            ". Hãy thử lại hoặc đổi server."
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val code = errorResponse?.statusCode ?: 0
                                    onEmbedFailed(
                                        "Server embed trả lỗi HTTP $code. Hãy thử lại hoặc đổi server."
                                    )
                                }
                            }
                        }
                        webView = this
                        loadedEmbedUrl = state.embedUrl
                        loadEmbed(this, state.embedUrl, state.embedReferer)
                    }
                },
                update = { view ->
                    if (loadedEmbedUrl != state.embedUrl) {
                        loadedEmbedUrl = state.embedUrl
                        loadEmbed(view, state.embedUrl, state.embedReferer)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (state.loading) {
            CircularProgressIndicator(
                color = NflixViolet,
                modifier = Modifier.align(Alignment.Center).size(46.dp)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(0x80000000), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.item?.name ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    state.episode?.label ?: "",
                    color = NflixTextMuted,
                    fontSize = 12.sp
                )
            }
            // Server escape hatch right in embed mode (N5): pages that
            // load "successfully" but show their own error text inside
            // raise no WebView error — the user still needs a way out.
            if (servers.size > 1) {
                Text(
                    "Đổi server",
                    color = NflixViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showServers = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        if (state.error.isNotBlank() && embedError == null) {
            Text(
                state.error,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }
        // Main-frame embed failure — same recovery shape as the stream
        // branch's error card (Thử lại · Đổi server · Báo lỗi này).
        if (embedError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(embedError!!, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Thử lại",
                        color = NflixViolet,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            embedError = null
                            webView?.reload()
                        }
                    )
                    if (servers.size > 1) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Đổi server",
                            color = NflixViolet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showServers = true }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (reportSaved) "Đã ghi báo lỗi ✓" else "Báo lỗi này",
                    color = if (reportSaved) NflixGreen else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !reportSaved) {
                        val errorText = embedError ?: ""
                        embedScope.launch {
                            withContext(Dispatchers.IO) {
                                com.example.nflix.data.ReportStore.add(
                                    context,
                                    com.example.nflix.data.ErrorReport(
                                        timeEpochSec = System.currentTimeMillis() / 1000,
                                        family = state.item?.family?.name ?: "",
                                        titleId = state.item?.id ?: "",
                                        titleName = state.item?.name ?: "",
                                        unitLabel = state.episode?.label ?: "",
                                        server = servers.getOrNull(state.serverIndex)?.name ?: "",
                                        message = errorText
                                    )
                                )
                            }
                            reportSaved = true
                        }
                    }
                )
            }
        }
    }
    if (showServers) {
        ServerSheet(
            servers = servers,
            selectedIndex = state.serverIndex,
            onSelect = { idx ->
                showServers = false
                embedError = null
                viewModel.switchServer(idx, 0)
            },
            onDismiss = { showServers = false },
            serverStatuses = state.serverStatuses
        )
    }
    DisposableEffect(Unit) {
        onDispose { /* WebView is destroyed with the view tree */ }
    }
}
