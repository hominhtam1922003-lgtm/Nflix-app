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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.example.nflix.data.AppSettings
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
    var portrait by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var reportSaved by remember { mutableStateOf(false) }
    val playerScope = androidx.compose.runtime.rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    var autoSwitchedSlug by remember { mutableStateOf<String?>(null) }

    fun applyQualityCap(cap: Int) {
        qualityCap = cap
        val params = exo.trackSelectionParameters.buildUpon()
        if (cap <= 0) params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        else params.setMaxVideoSize((cap * 16 + 8) / 9, cap)
        exo.trackSelectionParameters = params.build()
    }

    // Load the episode's stream into ExoPlayer.
    LaunchedEffect(state.episode?.slug, state.serverIndex, state.kind, retryKey) {
        val ep = state.episode ?: return@LaunchedEffect
        if (state.kind != PlaybackKind.STREAM || ep.streamUrl.isBlank()) return@LaunchedEffect
        ended = false
        playbackError = null
        reportSaved = false
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://phimapi.com/"))
        val source = HlsMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(ep.streamUrl))
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
                // Never sit on a silent black screen (user report 2026-11-01):
                // first try ONE silent switch to the next server; if that also
                // fails (or there is no other server), surface a real error.
                isBuffering = false
                val s = viewModel.state.value
                val servers = s.detail?.servers ?: emptyList()
                if (servers.size > 1 && autoSwitchedSlug != s.episode?.slug) {
                    autoSwitchedSlug = s.episode?.slug
                    viewModel.switchServer((s.serverIndex + 1) % servers.size, exo.currentPosition)
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
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
                        val errorText = playbackError ?: ""
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
            onDismiss = { showQuickSettings = false }
        )
    }
}

@Composable
private fun EmbedPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 1.1.1 (N5): a dead embed used to be a dead end — the page's own
    // "Setup error" (or a blank frame) just sat there. Main-frame load
    // failures now raise the same recovery overlay as the stream branch:
    // retry, switch server, report. Sub-resource errors (ad images, XHR
    // inside the page) must NOT trigger it — isForMainFrame guards that.
    var embedError by remember(state.embedUrl) { mutableStateOf<String?>(null) }
    var showServers by remember { mutableStateOf(false) }
    var reportSaved by remember(state.embedUrl) { mutableStateOf(false) }
    val embedScope = androidx.compose.runtime.rememberCoroutineScope()
    val servers = state.detail?.servers ?: emptyList()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (state.embedUrl.isNotBlank()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    embedError = "Không tải được trang phát của server này. Hãy thử lại hoặc đổi server."
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val code = errorResponse?.statusCode ?: 0
                                    embedError = "Server embed trả lỗi HTTP $code. Hãy thử lại hoặc đổi server."
                                }
                            }
                        }
                        webView = this
                        loadUrl(state.embedUrl)
                    }
                },
                update = { view ->
                    if (view.url != state.embedUrl) view.loadUrl(state.embedUrl)
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
