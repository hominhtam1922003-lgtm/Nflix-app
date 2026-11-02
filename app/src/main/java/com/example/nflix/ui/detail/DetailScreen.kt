package com.example.nflix.ui.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.ServerStatus
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PosterShelf
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixBlue
import com.example.nflix.ui.theme.NflixGreen
import com.example.nflix.ui.theme.NflixPink
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import com.example.nflix.ui.theme.NflixVioletSoft

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    item: TitleItem,
    onBack: () -> Unit,
    onPlay: (TitleItem, EpisodeItem, ServerGroup) -> Unit,
    onRead: (TitleItem, ChapterItem) -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier,
    /** V2 (A5): open an actor's page — only wired for actors carrying a Cobe slug. */
    onOpenActor: (com.example.nflix.data.model.ActorRef) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember(item.id) { DetailViewModel(app, item) }
    val state by viewModel.state.collectAsState()
    val detail = state.detail
    var showServerSheet by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }

    val dispItem = detail?.item ?: item
    val unitWord = if (viewModel.isComic) "chap" else "tập"
    val allUnits = viewModel.allUnits()
    val ranges = viewModel.ranges()
    val visible = viewModel.visibleUnits()
    // A4: "sắp chiếu" title — no playable episodes yet; the main button
    // follows the title instead and the episode UI is replaced by a note.
    val awaiting = viewModel.isAwaitingRelease()

    fun openUnit(unit: GridUnit) {
        if (viewModel.isComic) {
            unit.chapter?.let { onRead(item, it) }
        } else {
            val server = viewModel.server()
            if (unit.episode != null && server != null) onPlay(item, unit.episode, server)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                    com.example.nflix.ui.components.FallbackImage(
                        primary = dispItem.thumbUrl,
                        fallback = dispItem.posterUrl,
                        contentDescription = dispItem.name,
                        modifier = Modifier.fillMaxSize(),
                        title = dispItem.name,
                        extraFallback = dispItem.posterFallbackUrl
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color(0x33000000), Color.Transparent, NflixBackground),
                                startY = 0f
                            )
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        CircleButton(R.drawable.ic_back, "Quay lại") { onBack() }
                        Spacer(Modifier.weight(1f))
                        CircleButton(R.drawable.ic_share, "Chia sẻ") {
                            // B4: name + last opened unit (or newest) + the
                            // source's REAL public URL when one exists
                            // (KKPhim has none — name only, never invented).
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    com.example.nflix.data.ShareLinks.shareText(
                                        dispItem,
                                        state.userState?.lastUnitLabel.orEmpty()
                                    )
                                )
                            }
                            context.startActivity(Intent.createChooser(send, "Chia sẻ"))
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        dispItem.name,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(10.dp))
                    val meta = buildList {
                        if (dispItem.year > 0) add(dispItem.year.toString())
                        add(dispItem.family.label)
                        if (dispItem.quality.isNotBlank()) add(dispItem.quality)
                        when (dispItem.versionBadge) {
                            "TM" -> add("Thuyết minh")
                            "VS" -> add("Vietsub")
                        }
                        detail?.status?.takeIf { it.isNotBlank() }?.let { add(it) }
                        detail?.durationLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        meta.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, Color(0xFF3A3A46), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(chip, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (awaiting) viewModel.toggleBell()
                            else viewModel.resumeUnit()?.let { openUnit(it) }
                        },
                        enabled = allUnits.isNotEmpty() || awaiting,
                        colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(
                            painter = painterResource(if (awaiting) R.drawable.ic_bell else R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            viewModel.mainButtonLabel(),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // A1: Trailer button — KKPhim ships a YouTube trailer_url
                    // for nearly every title; shown only when the source
                    // actually provides one.
                    val trailerUrl = detail?.trailerUrl.orEmpty()
                    if (trailerUrl.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(trailerUrl)
                                )
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NflixSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_play),
                                contentDescription = null,
                                tint = NflixRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Xem trailer",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val st = state.userState
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToggleTile(
                            iconRes = R.drawable.ic_heart,
                            label = "Yêu thích",
                            active = st?.favorite == true,
                            activeAccent = NflixPink,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.toggleFavorite() }
                        ToggleTile(
                            iconRes = R.drawable.ic_bookmark,
                            label = "Xem sau",
                            active = st?.watchLater == true,
                            activeAccent = NflixBlue,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.toggleWatchLater() }
                        ToggleTile(
                            iconRes = if (st?.notifyOn == true) R.drawable.ic_bell_amber else R.drawable.ic_bell,
                            label = if (st?.notifyOn == true) "Đã bật báo" else "Báo tập mới",
                            active = st?.notifyOn == true,
                            iconTint = if (st?.notifyOn == true) Color.Unspecified else Color.White,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.toggleBell() }
                    }

                    Spacer(Modifier.height(14.dp))
                    if (viewModel.isComic) {
                        val sourceName = when (item.sourceId) {
                            "truyenqq" -> "TruyenQQ"
                            "nettruyen" -> "NetTruyen"
                            else -> item.sourceId
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NflixSurface)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Nguồn: ", color = NflixTextMuted, fontSize = 13.sp)
                            Text(sourceName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (!awaiting) {
                        val server = viewModel.server()
                        val selStatus = state.serverStatuses[state.serverIndex]
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NflixSurface)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp)
                                    .background(serverDotColor(selStatus), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                buildAnnotatedString {
                                    append("Server ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                        append(server?.name ?: "—")
                                    }
                                    // 0.3: quality only when the source states
                                    // it — never invent "FHD" for a blank field.
                                    if (dispItem.quality.isNotBlank()) {
                                        append(" · ${dispItem.quality}")
                                    }
                                    // 0.2: status comes from the last real
                                    // measurement of THIS server (0.1 probe).
                                    append(
                                        " · " + when {
                                            state.probingServers && selStatus == null -> "Đang kiểm tra…"
                                            selStatus != null -> selStatus.describe()
                                            else -> "Chưa kiểm tra"
                                        }
                                    )
                                },
                                color = NflixTextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Đổi",
                                color = NflixViolet,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { showServerSheet = true }
                            )
                        }
                        // 1.1.1 (N1): when the CobePhim source itself was
                        // unreachable, say so right under the server bar —
                        // the merged detail silently fell back to KKPhim
                        // only, and the user could not tell a whole source
                        // was missing. Informational only; nothing blocked.
                        if (detail?.cobeFailed == true) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Nguồn Cobe: không kết nối được",
                                color = NflixAmber,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    if (awaiting) {
                        Text(
                            "Phim chưa phát hành — bấm Theo dõi để nhận thông báo khi có tập đầu tiên.",
                            color = NflixTextMuted,
                            fontSize = 13.sp
                        )
                    }
                    if (!awaiting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (viewModel.isComic) "Danh sách chap" else "Danh sách tập",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (allUnits.isNotEmpty()) {
                                Text(
                                    " · ${allUnits.size} $unitWord",
                                    color = NflixTextMuted,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.sortDesc) "Mới nhất ▼" else "Cũ nhất ▼",
                                color = NflixViolet,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { viewModel.toggleSort() }
                            )
                        }
                    }
                }
            }

            if (ranges.size > 1) {
                item {
                    Spacer(Modifier.height(10.dp))
                    FilterChips(
                        labels = ranges.map { it.first },
                        selectedIndex = state.rangeIndex,
                        onSelect = { viewModel.selectRange(it) }
                    )
                }
            }

            // A4: coming-soon titles have no units to jump to — skip the row.
            if (!awaiting) {
                item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(NflixSurface)
                                .padding(horizontal = 12.dp, vertical = 11.dp)
                        ) {
                            BasicTextField(
                                value = jumpText,
                                onValueChange = { jumpText = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                cursorBrush = SolidColor(NflixViolet),
                                decorationBox = { inner ->
                                    if (jumpText.isEmpty()) {
                                        Text(
                                            if (viewModel.isComic) "Nhảy tới chap…" else "Nhảy tới tập…",
                                            color = NflixTextMuted,
                                            fontSize = 14.sp
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val unit = viewModel.findUnit(jumpText)
                                if (unit != null) {
                                    viewModel.setJumpError("")
                                    openUnit(unit)
                                } else {
                                    viewModel.setJumpError(
                                        if (viewModel.isComic) "Không có chap này" else "Không có tập này"
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Đi", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (state.jumpError.isNotBlank()) {
                        Text(
                            state.jumpError,
                            color = NflixRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                }
            }

            item {
                if (state.loading) {
                    Box(
                        Modifier.fillMaxWidth().padding(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = NflixViolet)
                    }
                } else if (awaiting) {
                    // A4: empty grid replaced by a clean note — no error frame.
                    Text(
                        "Chưa có tập nào. Trailer và thông tin phim đã có ở trên.",
                        color = NflixTextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                } else if (visible.isEmpty()) {
                    // 1.3.1: say WHAT actually happened — the real load
                    // error when the detail call failed, the source's
                    // own load note when it loaded abnormally (Donghua
                    // fallback/empty), the generic line only otherwise.
                    Text(
                        when {
                            state.detailError != null ->
                                "Không tải được chi tiết: ${state.detailError}"
                            state.loadNote.isNotBlank() -> state.loadNote
                            else -> "Chưa lấy được danh sách — kiểm tra mạng rồi mở lại."
                        },
                        color = NflixTextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                } else {
                    val rows = (visible.size + 4) / 5
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height((rows * 50 + (rows - 1) * 8).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false
                    ) {
                        items(visible, key = { it.number }) { unit ->
                            UnitCell(
                                unit = unit,
                                watched = viewModel.isWatched(unit.number),
                                current = viewModel.isCurrent(unit.number),
                                onClick = { openUnit(unit) }
                            )
                        }
                    }
                }
            }

            detail?.let { d ->
                if (d.description.isNotBlank() || d.actors.isNotEmpty() || d.directors.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Giới thiệu",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (d.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(d.description, color = NflixTextMuted, fontSize = 13.sp)
                            }
                            if (d.actors.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                // V2 (A5): actors the Cobe record gives
                                // a page for become tappable violet names
                                // opening their filmography; the rest stay
                                // plain text — never a dead link.
                                val refsByName = d.actorRefs.associateBy { it.name }
                                val tappable = d.actors.mapNotNull { name ->
                                    refsByName[name]?.takeIf { it.slug.isNotBlank() }
                                }
                                if (tappable.isEmpty()) {
                                    Text(
                                        "Diễn viên: ${d.actors.joinToString(", ")}",
                                        color = NflixTextMuted, fontSize = 13.sp
                                    )
                                } else {
                                    Text(
                                        "Diễn viên",
                                        color = NflixTextMuted, fontSize = 13.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        d.actors.forEach { name ->
                                            val ref = refsByName[name]
                                            if (ref != null && ref.slug.isNotBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(50))
                                                        .background(NflixVioletSoft)
                                                        .clickable { onOpenActor(ref) }
                                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                                ) {
                                                    Text(
                                                        name,
                                                        color = NflixViolet,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier.padding(
                                                        horizontal = 4.dp, vertical = 5.dp
                                                    )
                                                ) {
                                                    Text(
                                                        name,
                                                        color = NflixTextMuted,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (d.directors.isNotEmpty()) {
                                Text(
                                    "Đạo diễn: ${d.directors.joinToString(", ")}",
                                    color = NflixTextMuted, fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                    }
                }
            }

            // A2: "Cùng thể loại" — same-genre shelf closing the page,
            // loaded by the ViewModel from the title's first genre.
            if (state.related.isNotEmpty()) {
                item {
                    PosterShelf(
                        title = "Cùng thể loại",
                        items = state.related,
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(bottom = 28.dp)
                    )
                }
            }
        }

        if (showServerSheet) {
            val servers = detail?.servers ?: emptyList()
            ModalBottomSheet(
                onDismissRequest = { showServerSheet = false },
                containerColor = NflixSurface
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Chọn server",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    servers.forEachIndexed { index, server ->
                        val srvStatus = state.serverStatuses[index]
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (index == state.serverIndex) NflixVioletSoft else Color.Transparent)
                                .clickable {
                                    viewModel.selectServer(index)
                                    showServerSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp)
                                    .background(serverDotColor(srvStatus), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(server.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${server.episodes.size} tập · " +
                                        (srvStatus?.describe() ?: "Chưa kiểm tra"),
                                    color = NflixTextMuted, fontSize = 12.sp
                                )
                                // V2 (0.2, sheet frame 7): mark the group
                                // the settings' version priority defaults
                                // to — the chip names the real rule.
                                if (index == state.preferredServerIndex) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "Mặc định theo ưu tiên của bạn",
                                        color = NflixViolet,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            if (index == state.serverIndex) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "Đang chọn",
                                    tint = NflixViolet,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    // Manual re-measure — the way to get real statuses when
                    // the auto-fastest setting is off.
                    Text(
                        if (state.probingServers) "Đang đo tốc độ server…" else "Đo tốc độ server",
                        color = if (state.probingServers) NflixTextMuted else NflixViolet,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(enabled = !state.probingServers) { viewModel.measureServers() }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/** Dot color for a server's last real status — grey until actually measured. */
private fun serverDotColor(status: ServerStatus?): Color = when (status?.state) {
    ServerStatus.State.OK -> NflixGreen
    ServerStatus.State.SLOW -> NflixAmber
    ServerStatus.State.ERROR -> NflixRed
    else -> NflixTextMuted
}

@Composable
private fun CircleButton(iconRes: Int, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
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
private fun ToggleTile(
    iconRes: Int,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeAccent: Color = NflixViolet,
    iconTint: Color = if (active) activeAccent else Color.White,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) activeAccent.copy(alpha = 0.20f) else NflixSurface)
            .then(
                if (active) Modifier.border(1.dp, activeAccent, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun UnitCell(unit: GridUnit, watched: Boolean, current: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) NflixViolet else NflixSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            unit.label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        if (watched) {
            Icon(
                painter = painterResource(R.drawable.ic_check_green),
                contentDescription = "Đã xem",
                tint = Color.Unspecified,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(11.dp)
            )
        }
    }
}
