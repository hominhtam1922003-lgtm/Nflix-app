package com.example.nflix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflix.data.AppSettings
import com.example.nflix.data.SettingsStore
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.player.PlayerUiState
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.detail.fmtNum
import com.example.nflix.data.ServerStatus
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGreen
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import com.example.nflix.ui.theme.NflixVioletSoft
import kotlinx.coroutines.launch

private fun fmtT(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun qLabel(height: Int): String = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "FHD"
    height >= 700 -> "HD"
    height > 0 -> "${height}p"
    else -> "FHD"
}

/**
 * C5 (K11): custom seek bar with the design's 3 layers — remaining (dark
 * grey) at the back, buffered (light grey) in the middle, played (violet)
 * on top, plus the thumb. A tap or drag scrubs: onScrub fires while the
 * finger moves, onScrubFinished once on release (same contract the old
 * Material Slider gave the activity). Without buffered data the middle
 * layer is simply empty and the bar behaves exactly as before.
 */
@Composable
private fun BufferedSeekBar(
    fraction: Float,
    bufferedFraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrub((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() }
                )
            }
    ) {
        val trackShape = RoundedCornerShape(50)
        // Remaining.
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp)
                .align(Alignment.Center)
                .clip(trackShape)
                .background(Color(0xFF4A4A58))
        )
        // Buffered (never behind the played head visually — it starts at 0
        // and the played layer paints over its own span).
        if (bufferedFraction > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(bufferedFraction).height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(trackShape)
                    .background(Color(0xFF9A9AA8))
            )
        }
        // Played.
        if (fraction > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction).height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(trackShape)
                    .background(NflixViolet)
            )
        }
        // Thumb.
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - 14.dp) * fraction)
                .size(14.dp)
                .align(Alignment.CenterStart)
                .background(NflixViolet, CircleShape)
        )
    }
}

@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    isPlaying: Boolean,
    ended: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0,
    speed: Float,
    qualityLabel: String,
    statusText: String,
    statusDot: Color,
    settings: AppSettings,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekFwd: () -> Unit,
    onLock: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    nextLabel: String,
    introEndMs: Long,
    onSkipIntro: () -> Unit,
    onShowServers: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowQuality: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowQuickSettings: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // 1.3.14: the intro boundary is per title (learned), not a
    // universal 85 s — see PlayerActivity's introEndMs.
    val showSkipIntro = settings.skipIntroOutro && durationMs > 360_000 && positionMs < introEndMs
    val showSkipOutro = settings.skipIntroOutro && durationMs > 0 &&
        durationMs - positionMs in 1..90_000 && positionMs >= introEndMs

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlCircle(R.drawable.ic_back, "Quay lại", onBack)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.item?.name ?: "",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val versionText = when (state.item?.versionBadge) {
                    "TM" -> "Thuyết minh"
                    "VS" -> "Vietsub"
                    else -> ""
                }
                Text(
                    buildAnnotatedString {
                        append("${state.episode?.label ?: ""}")
                        if (versionText.isNotBlank()) append(" · $versionText")
                        append(" · ")
                        withStyle(SpanStyle(color = statusDot)) { append("●") }
                        append(" $statusText")
                        // 1.3.3b: measured by HlsAdFilter on THIS
                        // stream's playlist — shown only when ad
                        // segments were really removed.
                        if (state.adCutSeconds > 0) {
                            append(" · ")
                            withStyle(SpanStyle(color = NflixGreen)) {
                                append("Đã cắt quảng cáo: ~${state.adCutSeconds}s")
                            }
                        }
                    },
                    color = NflixTextMuted,
                    fontSize = 12.sp
                )
            }
            Row(
                modifier = Modifier
                    .background(Color(0x80000000), RoundedCornerShape(50))
                    .clickable(onClick = onShowServers)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Đổi server", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            ControlCircle(R.drawable.ic_gear, "Cài đặt phát", onShowQuickSettings)
        }

        // Center cluster
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(42.dp)
                    .background(Color(0x80000000), CircleShape)
                    .clickable(onClick = onLock),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = "Khoá điều khiển",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(34.dp)
            ) {
                SeekButton(R.drawable.ic_seek_back, "Tua lùi", onSeekBack)
                Box(
                    modifier = Modifier.size(72.dp)
                        .background(NflixViolet, CircleShape)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying && !ended) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(width = 7.dp, height = 24.dp).background(Color.White, RoundedCornerShape(2.dp)))
                            Box(Modifier.size(width = 7.dp, height = 24.dp).background(Color.White, RoundedCornerShape(2.dp)))
                        }
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = if (ended) "Xem lại" else "Phát",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                SeekButton(R.drawable.ic_seek_fwd, "Tua tới", onSeekFwd)
            }
        }

        // Skip intro/outro pill
        Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            if (showSkipIntro || showSkipOutro) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .background(Color.White, RoundedCornerShape(50))
                        .clickable(onClick = onSkipIntro)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (showSkipIntro) "Bỏ qua mở đầu ⏩" else "Bỏ qua kết thúc ⏩",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Seek bar — C5 (K11): three layers per the design (Màn 10):
        // played violet · buffered light grey · remaining dark grey.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(fmtT(positionMs), color = Color.White, fontSize = 12.sp)
            BufferedSeekBar(
                fraction = fraction,
                bufferedFraction = if (durationMs > 0) {
                    (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Text(fmtT(durationMs), color = NflixTextMuted, fontSize = 12.sp)
        }

        // Bottom bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBtn(
                iconRes = R.drawable.ic_prev,
                text = "Tập trước",
                enabled = hasPrev,
                onClick = onPrev
            )
            Spacer(Modifier.width(6.dp))
            BottomBtn(
                iconRes = R.drawable.ic_next,
                text = "Tập sau",
                enabled = hasNext,
                onClick = onNext
            )
            if (hasNext) {
                Spacer(Modifier.width(8.dp))
                Text("Tiếp theo: $nextLabel", color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${speed}x".replace(".0x", ".0x"),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onShowSpeed)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            )
            Box(
                modifier = Modifier
                    .background(NflixViolet, RoundedCornerShape(8.dp))
                    .clickable(onClick = onShowQuality)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(qualityLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onShowEpisodes)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Tập", color = Color.White, fontSize = 13.sp)
            }
            if (settings.pipEnabled) {
                Text(
                    "PiP",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onPip)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_full),
                contentDescription = "Toàn màn hình",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onFullscreen)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun ControlCircle(iconRes: Int, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(38.dp)
            .background(Color(0x80000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = desc,
            tint = Color.White,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun SeekButton(iconRes: Int, desc: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(54.dp)
                .background(Color(0x80000000), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = desc,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Text("10 giây", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BottomBtn(iconRes: Int, text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) Color.White else NflixTextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            color = if (enabled) Color.White else NflixTextMuted,
            fontSize = 13.sp
        )
    }
}

// ---------- Sheets ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSheet(
    servers: List<ServerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    serverStatuses: Map<Int, ServerStatus> = emptyMap()
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Chọn server", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            servers.forEachIndexed { index, server ->
                val srvStatus = serverStatuses[index]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (index == selectedIndex) NflixVioletSoft else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            when (srvStatus?.state) {
                                ServerStatus.State.OK -> NflixGreen
                                ServerStatus.State.SLOW -> NflixAmber
                                ServerStatus.State.ERROR -> NflixRed
                                else -> NflixTextMuted
                            },
                            CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${server.episodes.size} tập · " +
                                (srvStatus?.describe() ?: "Chưa kiểm tra"),
                            color = NflixTextMuted, fontSize = 12.sp
                        )
                    }
                    if (index == selectedIndex) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Đang chọn",
                            tint = NflixViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Tốc độ phát", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (s == current) NflixVioletSoft else Color.Transparent)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${s}x", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (s == current) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Đang chọn",
                            tint = NflixViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualitySheet(
    heights: List<Int>,
    currentCap: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Chất lượng", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            QualityRow(
                label = "Cao nhất",
                sub = "Luôn lấy bản nét nhất nguồn có",
                selected = currentCap <= 0,
                onClick = { onSelect(-1) }
            )
            heights.sortedDescending().forEach { h ->
                QualityRow(
                    label = qLabel(h),
                    sub = "${h}p",
                    selected = currentCap == h,
                    onClick = { onSelect(h) }
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QualityRow(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) NflixVioletSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = NflixTextMuted, fontSize = 12.sp)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "Đang chọn",
                tint = NflixViolet,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodesSheet(
    episodes: List<EpisodeItem>,
    currentSlug: String,
    isWatched: (Float) -> Boolean,
    onSelect: (EpisodeItem) -> Unit,
    onDismiss: () -> Unit
) {
    var rangeIndex by remember { mutableIntStateOf(0) }
    val sorted = remember(episodes) { episodes.sortedBy { it.number } }
    val ranges = remember(sorted) {
        if (sorted.size <= 100) listOf("" to sorted)
        else sorted.chunked(100).map {
            "${fmtNum(it.first().number)}–${fmtNum(it.last().number)}" to it
        }
    }
    val visible = ranges.getOrNull(rangeIndex)?.second ?: sorted
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixBackground) {
        Column(modifier = Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text(
                "Danh sách tập · ${episodes.size} tập",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (ranges.size > 1) {
                Spacer(Modifier.height(10.dp))
                FilterChips(
                    labels = ranges.map { it.first },
                    selectedIndex = rangeIndex,
                    onSelect = { rangeIndex = it }
                )
            }
            val rows = (visible.size + 4) / 5
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.padding(top = 12.dp)
                    .height((rows * 50 + (rows - 1) * 8).dp.coerceAtMost(420.dp)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.slug }) { ep ->
                    val isCurrent = ep.slug == currentSlug
                    Box(
                        modifier = Modifier.height(50.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) NflixViolet else NflixSurface)
                            .clickable { onSelect(ep) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmtNum(ep.number),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        if (isWatched(ep.number)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_green),
                                contentDescription = "Đã xem",
                                tint = Color.Unspecified,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(11.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickSettingsSheet(
    settings: AppSettings,
    onShowQuality: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowResize: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val scope = rememberCoroutineScope()
    // 1.3.9: open FULLY expanded and let the content scroll. In
    // 1.3.8 the sheet opened partially in landscape and its plain
    // column overflowed the sheet's max height, so the last row
    // ("Kích thước hiển thị") sat half-clipped at the bottom edge —
    // unreachable and untappable (user verdict 1.3.8: "Vướng không
    // kéo lên được... ấn cái nào màn vẫn y nguyên").
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NflixSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Cài đặt phát", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            QuickRow("Chất lượng", "Theo nguồn đang phát") { onShowQuality() }
            QuickRow("Tốc độ", "Đổi tốc độ phát") { onShowSpeed() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bỏ qua mở đầu / kết thúc", color = Color.White, fontSize = 14.sp)
                    Text("Tự hiện nút bỏ qua ở đoạn intro, outro", color = NflixTextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.skipIntroOutro,
                    onCheckedChange = { on ->
                        scope.launch { app.settings.set(SettingsStore.SKIP_INTRO, on) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NflixViolet)
                )
            }
            // 1.3.8: display size is a chevron row like Quality/Speed
            // and opens its OWN sheet — in 1.3.7 the three options
            // were inline at the bottom of this sheet, whose column
            // does not scroll, so they sat below the fold and could
            // not be tapped (user verdict 1.3.7: "có chứ không dùng
            // được").
            QuickRow(
                "Kích thước hiển thị",
                when (settings.playerResize) {
                    "FULL" -> "Lấp đầy"
                    "FULLSUB" -> "Lấp đầy (giữ phụ đề)"
                    "STRETCH" -> "Kéo giãn"
                    else -> "Vừa màn hình"
                }
            ) { onShowResize() }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 1.3.8: the display-size picker as its own sheet (Netflix-style),
 * opened from the QuickSettings chevron row. Picking a size writes
 * the persisted setting — the player applies it live — and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResizeSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 1.3.10: the pick is reported to the HOST (PlayerActivity),
    // which writes the setting on the player screen's scope. The
    // 1.3.8/1.3.9 version launched the DataStore write on THIS
    // sheet's own rememberCoroutineScope and dismissed immediately —
    // disposing the sheet cancelled the scope mid-write, so picks
    // kept losing the race and the stored mode stayed whatever it
    // already was (user verdict 1.3.9: the picker "keeps defaulting
    // to Kéo giãn" no matter what he chose).
    fun pick(mode: String) {
        onPick(mode)
        onDismiss()
    }
    // 1.3.9: same reachability guard as QuickSettingsSheet — fully
    // expanded + scrollable content.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NflixSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Kích thước hiển thị", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            QualityRow("Vừa màn hình", "Giữ nguyên tỉ lệ gốc, có thể có viền đen", current == "FIT") {
                pick("FIT")
            }
            QualityRow("Lấp đầy", "Phóng to cho đầy màn hình, cắt bớt hai cạnh", current == "FULL") {
                pick("FULL")
            }
            QualityRow("Lấp đầy (giữ phụ đề)", "Phóng đều đầy màn hình, giữ nguyên đáy hình có phụ đề, cắt bớt phía trên", current == "FULLSUB") {
                pick("FULLSUB")
            }
            QualityRow("Kéo giãn", "Giãn hình cho đầy màn hình, hình bị méo", current == "STRETCH") {
                pick("STRETCH")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QuickRow(label: String, sub: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(sub, color = NflixTextMuted, fontSize = 12.sp)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chev_r),
            contentDescription = null,
            tint = NflixTextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}
