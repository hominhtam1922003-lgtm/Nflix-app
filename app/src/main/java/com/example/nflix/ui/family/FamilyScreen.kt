package com.example.nflix.ui.family

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.ManhwaIndex
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.STATUS_COMPLETED
import com.example.nflix.data.model.STATUS_ONGOING
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.KkPhimSource
import com.example.nflix.ui.components.ContextChips
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.components.PosterShelf
import com.example.nflix.ui.components.SectionHeader
import com.example.nflix.ui.components.UpdatedRow
import com.example.nflix.ui.common.TitleGridScreen
import com.example.nflix.ui.common.TitleListScreen
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixOrangeBright
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

private val FAMILY_CHIP_LABELS = listOf("Tất cả", "Phim", "Anime", "Donghua", "Manhwa", "Truyện")
private val FAMILY_CHIP_VALUES = listOf(null, Family.PHIM, Family.ANIME, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    family: Family,
    onFamilyChange: (Family?) -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    onGoSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember(family) { FamilyViewModel(app, family) }
    val state by viewModel.state.collectAsState()
    val contextLabels = remember(family) { FamilyViewModel.contextLabels(family) }
    var showAll by remember { mutableStateOf<String?>(null) }
    var showFilter by remember { mutableStateOf(false) }

    // Local copy: state is delegate-backed, so state.donghuaGenre cannot smart-cast.
    val donghuaGenre = state.donghuaGenre
    val shelfTitle = when (family) {
        Family.PHIM -> "Đang thịnh hành"
        Family.ANIME -> "Đang thịnh hành"
        Family.DONGHUA -> when {
            donghuaGenre != null -> donghuaGenre.first
            state.donghuaStatus == STATUS_COMPLETED -> "Trọn bộ"
            state.donghuaStatus == STATUS_ONGOING -> "Đang ra tiếp"
            state.contextIndex == 0 -> "Ra tập mới hôm nay"
            else -> "Ra tập mới ${FamilyViewModel.DAY_LABELS[state.contextIndex]}"
        }
        Family.MANHWA -> when {
            state.manhwaFilter.genreLabel.isNotEmpty() -> state.manhwaFilter.genreLabel
            state.manhwaFilter.audienceLabel.isNotEmpty() -> state.manhwaFilter.audienceLabel
            state.manhwaFilter.status == STATUS_COMPLETED -> "Trọn bộ"
            state.manhwaFilter.status == STATUS_ONGOING -> "Đang ra tiếp"
            else -> "Đang hot"
        }
        Family.TRUYEN -> when {
            state.truyenStatus == STATUS_COMPLETED -> "Trọn bộ"
            state.contextIndex == 0 && state.truyenStatus == STATUS_ONGOING -> "Đang ra tiếp"
            state.contextIndex == 0 -> "Đang hot"
            else -> FamilyViewModel.TRUYEN_GENRES[state.contextIndex].first
        }
    }

    when (showAll) {
        "posters" -> {
            TitleGridScreen(
                title = shelfTitle,
                items = state.posters,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "coming" -> {
            TitleGridScreen(
                title = "Sắp chiếu",
                items = state.comingSoon,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "second" -> {
            TitleGridScreen(
                title = "Đang thịnh hành",
                items = state.secondPosters,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "fresh" -> {
            TitleGridScreen(
                title = "Mới tinh",
                items = state.freshShelf,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "rows" -> {
            // List-shelf design (approved 2026-11-01): the families' main
            // browse list is a 3-column poster grid now — same rows data,
            // same load-more pagination.
            TitleListScreen(
                title = "Mới cập nhật",
                items = state.rows,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier,
                onLoadMore = { viewModel.loadMoreRows() },
                gridMode = true
            )
            return
        }
        "schedule" -> {
            val trackedIds by viewModel.trackedIds.collectAsState()
            ScheduleWeekScreen(
                groups = state.scheduleWeek,
                trackedIds = trackedIds,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        append("Nf")
                        withStyle(SpanStyle(color = NflixViolet)) { append("lix") }
                    },
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Tìm kiếm",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                        .clickable { onGoSearch() }
                        .padding(2.dp)
                )
            }
        }

        item {
            FilterChips(
                labels = FAMILY_CHIP_LABELS,
                selectedIndex = FAMILY_CHIP_VALUES.indexOf(family),
                onSelect = { onFamilyChange(FAMILY_CHIP_VALUES[it]) }
            )
            Spacer(Modifier.height(10.dp))
            // Second row per family (user redesign 2026-11-01): Donghua keeps
            // its day chips; Phim/Anime/Manhwa/Truyện get ONE "Thể loại" button
            // that opens the filter sheet (Anime filter added per user report).
            when (family) {
                Family.DONGHUA -> Column {
                    ContextChips(
                        labels = contextLabels,
                        selectedIndex = state.contextIndex,
                        onSelect = { viewModel.selectContext(it) }
                    )
                    Spacer(Modifier.height(8.dp))
                    // A3: Donghua gets the same "Thể loại" button as the
                    // other families, under its day chips.
                    GenreFilterButton(
                        summary = filterSummary(family, state),
                        active = filterActive(family, state),
                        onClick = { showFilter = true }
                    )
                }
                Family.PHIM, Family.ANIME, Family.MANHWA, Family.TRUYEN -> GenreFilterButton(
                    summary = filterSummary(family, state),
                    active = filterActive(family, state),
                    onClick = { showFilter = true }
                )
            }
        }

        // C3 (K4): Truyện host transparency — a small line under the header,
        // shown only when there is something worth telling: the fallback
        // host is serving, the last load was slow (>8s), or a load failed.
        // 1.3.2: the NetTruyen fallback (both TruyenQQ hosts down) is one
        // of those things — it is disclosed, never passed off silently.
        val showTruyenStatus = family == Family.TRUYEN &&
            (state.truyenHostLabel.isNotBlank() || state.truyenFallbackSource) &&
            (state.truyenOnFallback || state.truyenLastLoadMs > 8000 ||
                state.truyenError || state.truyenFreshError || state.truyenFallbackSource)
        if (showTruyenStatus) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            if (state.truyenFallbackSource) {
                                append("Nguồn: NetTruyen · đang dùng nguồn dự phòng")
                                append(" — TruyenQQ không phản hồi")
                            } else {
                                append("Nguồn: ")
                                append(state.truyenHostLabel)
                                if (state.truyenLastLoadMs >= 0) {
                                    append(" · tải " + fmtLoadMs(state.truyenLastLoadMs))
                                }
                                if (state.truyenOnFallback) append(" · đang dùng host dự phòng")
                            }
                            if (state.truyenError) append(" · lần tải gần nhất lỗi")
                            if (state.truyenFreshError) append(" · kệ Mới tinh lỗi")
                        },
                        color = NflixTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.truyenError || state.truyenFreshError) {
                        Text(
                            "Thử lại",
                            color = NflixViolet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { viewModel.retryTruyen() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (state.loading && state.posters.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NflixViolet)
                }
            }
        }

        // C3: the Truyện main load FAILED and nothing is on screen — an
        // explicit error + retry, never a silently empty tab.
        if (family == Family.TRUYEN && state.truyenError && !state.loading &&
            state.posters.isEmpty() && state.rows.isEmpty()
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Không tải được nguồn Truyện" +
                            if (state.truyenHostLabel.isNotBlank()) {
                                " (${state.truyenHostLabel})"
                            } else {
                                ""
                            },
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 1.3.3: the REAL error (which sources were
                        // tried + what each answered) instead of the
                        // canned line that hid it in 1.3.2.
                        state.truyenErrorDetail.ifBlank {
                            "Cả hai host đều không phản hồi — kiểm tra mạng rồi thử lại."
                        },
                        color = NflixTextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { viewModel.retryTruyen() },
                        colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                        shape = RoundedCornerShape(50)
                    ) { Text("Thử lại", color = Color.White) }
                }
            }
        }

        // V2 (0.4, sheet frame 6): the same honesty for Phim / Anime /
        // Donghua / Manhwa — a failed main load with nothing on screen
        // is an explicit error + retry, never a silently empty tab.
        if (family != Family.TRUYEN && state.familyError && !state.loading &&
            state.posters.isEmpty() && state.rows.isEmpty()
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Không tải được nội dung",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 1.3.3: real error text when the ViewModel
                        // kept one (see FamilyUiState.familyErrorDetail).
                        state.familyErrorDetail.ifBlank {
                            "Kiểm tra kết nối mạng rồi thử lại."
                        },
                        color = NflixTextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { viewModel.retryFamily() },
                        colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                        shape = RoundedCornerShape(50)
                    ) { Text("Thử lại", color = Color.White) }
                }
            }
        }

        // V2 (0.4): Anime first-run — the country pools are still
        // classifying, so an empty tab means "đang phân loại", not
        // "không có gì". Shown only while nothing has landed yet.
        if (family == Family.ANIME && state.animeClassifying && !state.loading &&
            !state.familyError && state.rows.isEmpty()
        ) {
            item {
                Text(
                    "Đang phân loại kho Anime theo quốc gia — danh sách hiện dần.",
                    color = NflixTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                )
            }
        }

        // Shelf 1 — posters
        if (state.posters.isNotEmpty()) {
            item {
                PosterShelf(
                    title = shelfTitle,
                    items = state.posters,
                    onItemClick = onOpenTitle,
                    modifier = Modifier.padding(top = 6.dp),
                    actionLabel = if (family == Family.DONGHUA && state.donghuaGenre == null &&
                        state.donghuaStatus == 0
                    ) {
                        "Lịch phát sóng"
                    } else {
                        "Xem tất cả"
                    },
                    onAction = {
                        if (family == Family.DONGHUA && state.donghuaGenre == null &&
                            state.donghuaStatus == 0
                        ) {
                            viewModel.loadScheduleWeek()
                            showAll = "schedule"
                        } else {
                            showAll = "posters"
                        }
                    }
                )
            }
        }

        // A4 — Phim "Sắp chiếu" shelf (trailer-only titles, followable)
        if (family == Family.PHIM && state.comingSoon.isNotEmpty()) {
            item {
                PosterShelf(
                    title = "Sắp chiếu",
                    items = state.comingSoon,
                    onItemClick = onOpenTitle,
                    actionLabel = "Xem tất cả",
                    onAction = { showAll = "coming" }
                )
            }
        }

        // Donghua shelf 2 — trending posters
        if (family == Family.DONGHUA && state.secondPosters.isNotEmpty()) {
            item {
                PosterShelf(
                    title = "Đang thịnh hành",
                    items = state.secondPosters,
                    onItemClick = onOpenTitle,
                    actionLabel = "Xem tất cả",
                    onAction = { showAll = "second" }
                )
            }
        }

        // A8 — "Mới tinh" shelf (brand-new series) for Manhwa / Truyện,
        // shown in the tabs' default context only.
        val showFresh = state.freshShelf.isNotEmpty() && when (family) {
            Family.MANHWA -> state.manhwaFilter.isDefault
            Family.TRUYEN -> state.contextIndex == 0 && state.truyenStatus == 0
            else -> false
        }
        if (showFresh) {
            item {
                PosterShelf(
                    title = "Mới tinh",
                    items = state.freshShelf,
                    onItemClick = onOpenTitle,
                    actionLabel = "Xem tất cả",
                    onAction = { showAll = "fresh" }
                )
            }
        }

        // "Mới cập nhật" preview — list-shelf design (approved 2026-11-01):
        // the vertical small-cover rows are gone; the preview is the same
        // 3-column poster grid as the full list, chunked into rows because
        // this tab's body is a LazyColumn. Same 8 items as before.
        if (state.rows.isNotEmpty()) {
            item {
                SectionHeader(
                    "Mới cập nhật",
                    actionLabel = "Xem tất cả",
                    onAction = { showAll = "rows" }
                )
            }
            items(state.rows.take(8).chunked(3), key = { chunk -> chunk.first().id }) { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    chunk.forEach { rowItem ->
                        PosterCard(
                            item = rowItem,
                            onClick = { onOpenTitle(rowItem) },
                            modifier = Modifier.weight(1f),
                            coverMeta = true
                        )
                    }
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // C3: appending the next Truyện rows page failed — per-shelf retry.
        if (family == Family.TRUYEN && state.truyenRowsError) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clickable { viewModel.retryTruyenRows() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Không tải thêm được danh sách",
                        color = NflixTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Thử lại",
                        color = NflixViolet,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showFilter) {
        when (family) {
            Family.PHIM -> PhimFilterSheet(
                current = state.phimFilter,
                onApply = { filter ->
                    showFilter = false
                    viewModel.applyPhimFilter(filter)
                },
                onDismiss = { showFilter = false }
            )
            Family.ANIME -> AnimeFilterSheet(
                categories = state.animeCategories,
                years = state.animeYears,
                current = state.animeFilter,
                onApply = { filter ->
                    showFilter = false
                    viewModel.applyAnimeFilter(filter)
                },
                onDismiss = { showFilter = false }
            )
            Family.MANHWA -> ManhwaFilterSheet(
                current = state.manhwaFilter,
                indexBuilding = state.manhwaIndexBuilding && !state.manhwaIndexReady,
                onApply = { filter ->
                    showFilter = false
                    viewModel.applyManhwaFilter(filter)
                },
                onDismiss = { showFilter = false }
            )
            Family.TRUYEN -> TruyenFilterSheet(
                genres = FamilyViewModel.TRUYEN_GENRES,
                currentIndex = state.contextIndex,
                currentStatus = state.truyenStatus,
                onApply = { index, status ->
                    showFilter = false
                    viewModel.applyTruyenFilter(index, status)
                },
                onDismiss = { showFilter = false }
            )
            Family.DONGHUA -> DonghuaFilterSheet(
                genres = state.donghuaGenres,
                current = state.donghuaGenre,
                currentStatus = state.donghuaStatus,
                onApply = { genre, status ->
                    showFilter = false
                    viewModel.applyDonghuaFilter(genre, status)
                },
                onDismiss = { showFilter = false }
            )
            else -> Unit
        }
    }
}

/** C3: human form of a page-load duration ("850 ms" / "1,2 s"). */
private fun fmtLoadMs(ms: Long): String = when {
    ms < 0 -> ""
    ms < 1000 -> "$ms ms"
    else -> "%.1f s".format(ms / 1000f).replace('.', ',')
}

/** Label of a status filter value (0 = all shows nothing). */
private fun statusLabel(status: Int): String = when (status) {
    STATUS_ONGOING -> "Đang ra tiếp"
    STATUS_COMPLETED -> "Trọn bộ"
    else -> ""
}

/** Short text shown after "Thể loại" on the button when a filter is active. */
private fun filterSummary(family: Family, state: FamilyUiState): String = when (family) {
    Family.PHIM -> {
        val f = state.phimFilter
        val parts = mutableListOf<String>()
        FamilyViewModel.PHIM_KINDS.firstOrNull { it.second == f.kind }?.first
            ?.takeIf { f.kind != "phim-bo" }?.let { parts += it }
        KkPhimSource.CATEGORIES.firstOrNull { it.second == f.category }?.first?.let { parts += it }
        KkPhimSource.COUNTRIES.firstOrNull { it.second == f.country }?.first?.let { parts += it }
        if (f.year > 0) parts += f.year.toString()
        com.example.nflix.data.VersionFilter.labelOf(f.version)
            .takeIf { it.isNotEmpty() && f.version.isNotEmpty() }?.let { parts += it }
        statusLabel(f.status).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts.joinToString(" · ")
    }
    Family.ANIME -> {
        val f = state.animeFilter
        val parts = mutableListOf<String>()
        state.animeCategories.firstOrNull { it.second == f.category }?.first?.let { parts += it }
        if (f.year > 0) parts += f.year.toString()
        com.example.nflix.data.VersionFilter.labelOf(f.version)
            .takeIf { it.isNotEmpty() && f.version.isNotEmpty() }?.let { parts += it }
        statusLabel(f.status).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts.joinToString(" · ")
    }
    Family.MANHWA -> {
        val f = state.manhwaFilter
        val parts = mutableListOf<String>()
        if (f.genreLabel.isNotEmpty()) parts += f.genreLabel
        if (f.audienceLabel.isNotEmpty()) parts += f.audienceLabel
        statusLabel(f.status).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts.joinToString(" · ")
    }
    Family.TRUYEN -> {
        val parts = mutableListOf<String>()
        FamilyViewModel.TRUYEN_GENRES.getOrNull(state.contextIndex)?.first
            ?.takeIf { state.contextIndex != 0 }?.let { parts += it }
        statusLabel(state.truyenStatus).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts.joinToString(" · ")
    }
    Family.DONGHUA -> {
        val parts = mutableListOf<String>()
        state.donghuaGenre?.first?.let { parts += it }
        statusLabel(state.donghuaStatus).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts.joinToString(" · ")
    }
    else -> ""
}

private fun filterActive(family: Family, state: FamilyUiState): Boolean =
    filterSummary(family, state).isNotEmpty()

@Composable
private fun GenreFilterButton(summary: String, active: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) Color(0xFF2A2450) else NflixSurface)
                .then(
                    if (active) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                    else Modifier
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Thể loại",
                color = if (active) NflixViolet else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (summary.isNotBlank()) {
                Text(
                    " · $summary",
                    color = NflixTextMuted,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_tri_down),
                contentDescription = null,
                tint = if (active) NflixViolet else NflixTextMuted,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/**
 * The unified filter-sheet frame (mockup approved 2026-11-01): title
 * "Lọc {mục}", scrolling body capped at 92% of the screen, and the
 * Đặt lại / Lọc footer PINNED at the bottom. Every sheet is
 * draft-then-apply: chips edit local state, nothing reaches the tab
 * until "Lọc" is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheetFrame(
    title: String,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            ) {
                content()
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = NflixBackground),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) { Text("Đặt lại", color = Color.White) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(2f)
                ) { Text("Lọc", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** In-sheet search (mockup: shown when a sheet holds more than 18 items). */
@Composable
private fun SheetSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Tìm thể loại...", color = NflixTextMuted, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = NflixBackground,
            unfocusedContainerColor = NflixBackground,
            focusedIndicatorColor = NflixViolet,
            unfocusedIndicatorColor = Color(0xFF2A2A35),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = NflixViolet
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    )
}

/**
 * The Trạng thái group (user ruling 2026-11-01): exactly the pair
 * "Đang ra tiếp" | "Trọn bộ", single-select — tapping the active chip
 * clears back to "all".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(status: Int, onSelect: (Int) -> Unit) {
    FilterChipRow(
        options = listOf(
            STATUS_ONGOING.toString() to "Đang ra tiếp",
            STATUS_COMPLETED.toString() to "Trọn bộ"
        ),
        selected = status.toString(),
        onSelect = { v ->
            val n = v.toIntOrNull() ?: 0
            onSelect(if (n == status) 0 else n)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PhimFilterSheet(
    current: PhimFilter,
    onApply: (PhimFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var kind by remember { mutableStateOf(current.kind) }
    var category by remember { mutableStateOf(current.category) }
    var country by remember { mutableStateOf(current.country) }
    var year by remember { mutableIntStateOf(current.year) }
    var status by remember { mutableIntStateOf(current.status) }
    var version by remember { mutableStateOf(current.version) }
    var query by remember { mutableStateOf("") }
    val years = listOf(0) + (2026 downTo 2015).toList()

    FilterSheetFrame(
        title = "Lọc phim",
        onReset = {
            kind = "phim-bo"; category = ""; country = ""; year = 0; status = 0
            version = ""; query = ""
        },
        onApply = {
            // "Tất cả" loại (kind = "") falls back to phim-bo listing.
            onApply(
                PhimFilter(
                    kind.ifEmpty { "phim-bo" }, category, country, year, status,
                    version = version
                )
            )
        },
        onDismiss = onDismiss
    ) {
        SheetSearchField(query) { query = it }
        FilterSection("Loại") {
            FilterChipRow(
                options = listOf("" to "Tất cả") + FamilyViewModel.PHIM_KINDS.map { it.second to it.first },
                selected = kind,
                onSelect = { kind = it }
            )
        }
        FilterSection("Thể loại") {
            FilterChipRow(
                options = (listOf("" to "Tất cả") + KkPhimSource.CATEGORIES.map { it.second to it.first })
                    .filter { it.first.isEmpty() || query.isBlank() || it.second.contains(query, ignoreCase = true) },
                selected = category,
                onSelect = { category = it }
            )
        }
        FilterSection("Quốc gia") {
            FilterChipRow(
                options = (listOf("" to "Tất cả") + KkPhimSource.COUNTRIES.map { it.second to it.first })
                    .filter { it.first.isEmpty() || query.isBlank() || it.second.contains(query, ignoreCase = true) },
                selected = country,
                onSelect = { country = it }
            )
        }
        FilterSection("Năm phát hành") {
            FilterChipRow(
                options = years.map { y -> y.toString() to if (y == 0) "Tất cả" else y.toString() },
                selected = year.toString(),
                onSelect = { year = it.toIntOrNull() ?: 0 }
            )
        }
        // V2 (A1, sheet frame 1): PHIÊN BẢN — local filter over the
        // sources' own language descriptors (the KKPhim API ignores
        // version=; Cobe's descriptor comes from its own records).
        FilterSection("Phiên bản") {
            FilterChipRow(
                options = com.example.nflix.data.VersionFilter.OPTIONS,
                selected = version,
                onSelect = { version = it }
            )
        }
        FilterSection("Trạng thái") {
            StatusChips(status) { status = it }
        }
    }
}

/**
 * Anime filter sheet (1.2.0, Khối B): Thể loại = the categories REALLY
 * present in the loaded Cobe pool, Năm = publish_year values really in
 * the pool, Trạng thái = the standard pair. All filtering is local.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AnimeFilterSheet(
    categories: List<Pair<String, String>>,
    years: List<Int>,
    current: AnimeFilter,
    onApply: (AnimeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(current.category) }
    var year by remember { mutableIntStateOf(current.year) }
    var status by remember { mutableIntStateOf(current.status) }
    var version by remember { mutableStateOf(current.version) }
    var query by remember { mutableStateOf("") }

    FilterSheetFrame(
        title = "Lọc anime",
        onReset = { category = ""; year = 0; status = 0; version = ""; query = "" },
        onApply = {
            onApply(
                AnimeFilter(
                    category = category, year = year, status = status,
                    version = version
                )
            )
        },
        onDismiss = onDismiss
    ) {
        if (categories.size > 18) {
            SheetSearchField(query) { query = it }
        }
        FilterSection("Thể loại") {
            FilterChipRow(
                options = (listOf("" to "Tất cả") + categories.map { it.second to it.first })
                    .filter { it.first.isEmpty() || query.isBlank() || it.second.contains(query, ignoreCase = true) },
                selected = category,
                onSelect = { category = it }
            )
        }
        FilterSection("Năm phát hành") {
            FilterChipRow(
                options = listOf("0" to "Tất cả") + years.map { y -> y.toString() to y.toString() },
                selected = year.toString(),
                onSelect = { year = it.toIntOrNull() ?: 0 }
            )
        }
        // V2 (A1, sheet frame 1): PHIÊN BẢN — local over the pool that
        // already passed the country gate.
        FilterSection("Phiên bản") {
            FilterChipRow(
                options = com.example.nflix.data.VersionFilter.OPTIONS,
                selected = version,
                onSelect = { version = it }
            )
        }
        FilterSection("Trạng thái") {
            StatusChips(status) { status = it }
        }
    }
}

/**
 * Donghua filter sheet: Thể loại from the source's own menu (8 genres)
 * plus the Trạng thái pair — genre and status COMBINE (the ViewModel
 * filters the genre listing by the completed set when both are set).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DonghuaFilterSheet(
    genres: List<Pair<String, String>>,
    current: Pair<String, String>?,
    currentStatus: Int,
    onApply: (Pair<String, String>?, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var slug by remember { mutableStateOf(current?.second ?: "") }
    var status by remember { mutableIntStateOf(currentStatus) }

    FilterSheetFrame(
        title = "Lọc donghua",
        onReset = { slug = ""; status = 0 },
        onApply = { onApply(genres.firstOrNull { it.second == slug }, status) },
        onDismiss = onDismiss
    ) {
        FilterSection("Thể loại") {
            FilterChipRow(
                options = listOf("" to "Tất cả") + genres.map { it.second to it.first },
                selected = slug,
                onSelect = { slug = it }
            )
        }
        FilterSection("Trạng thái") {
            StatusChips(status) { status = it }
        }
    }
}

/**
 * Manhwa filter sheet (1.2.0, Khối C): 31 content chips (Vietnamese
 * labels) + the Đối tượng group (Vietnamese display labels per the
 * user's ruling; filter keys stay the source slugs) + Trạng thái.
 * Genre/audience resolve LOCALLY over the TruyenQQ manhwa-shelf index.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ManhwaFilterSheet(
    current: ManhwaFilter,
    indexBuilding: Boolean,
    onApply: (ManhwaFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var genre by remember { mutableStateOf(current.genre) }
    var genreLabel by remember { mutableStateOf(current.genreLabel) }
    var audience by remember { mutableStateOf(current.audience) }
    var audienceLabel by remember { mutableStateOf(current.audienceLabel) }
    var status by remember { mutableIntStateOf(current.status) }
    var query by remember { mutableStateOf("") }

    FilterSheetFrame(
        title = "Lọc manhwa",
        onReset = {
            genre = null; genreLabel = ""
            audience = null; audienceLabel = ""
            status = 0; query = ""
        },
        onApply = {
            onApply(
                ManhwaFilter(
                    genre = genre, genreLabel = genreLabel,
                    audience = audience, audienceLabel = audienceLabel,
                    status = status
                )
            )
        },
        onDismiss = onDismiss
    ) {
        SheetSearchField(query) { query = it }
        if (indexBuilding) {
            Text(
                "Đang lập chỉ mục thể loại từ nguồn — lọc thể loại sẽ có sau ít phút.",
                color = NflixTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        FilterSection("Thể loại") {
            FilterChipRow(
                options = (listOf("" to "Tất cả") + ManhwaIndex.CONTENT_CHIPS.map { it.second to it.first })
                    .filter { it.first.isEmpty() || query.isBlank() || it.second.contains(query, ignoreCase = true) },
                selected = genre ?: "",
                onSelect = { slug ->
                    genre = slug.ifEmpty { null }
                    genreLabel = ManhwaIndex.CONTENT_CHIPS.firstOrNull { it.second == slug }?.first ?: ""
                }
            )
        }
        FilterSection("Đối tượng") {
            FilterChipRow(
                options = listOf("" to "Tất cả") + ManhwaIndex.AUDIENCE_CHIPS.map { it.second to it.first },
                selected = audience ?: "",
                onSelect = { slug ->
                    audience = slug.ifEmpty { null }
                    audienceLabel = ManhwaIndex.AUDIENCE_CHIPS.firstOrNull { it.second == slug }?.first ?: ""
                }
            )
        }
        FilterSection("Trạng thái") {
            StatusChips(status) { status = it }
        }
    }
}

/**
 * Truyện filter sheet: the verified 59-map subset (dead slugs removed),
 * draft-then-apply like every sheet, plus the Trạng thái pair.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TruyenFilterSheet(
    genres: List<Pair<String, String>>,
    currentIndex: Int,
    currentStatus: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var index by remember { mutableIntStateOf(currentIndex) }
    var status by remember { mutableIntStateOf(currentStatus) }
    var query by remember { mutableStateOf("") }

    FilterSheetFrame(
        title = "Lọc truyện",
        onReset = { index = 0; status = 0; query = "" },
        onApply = { onApply(index, status) },
        onDismiss = onDismiss
    ) {
        SheetSearchField(query) { query = it }
        FilterSection("Thể loại") {
            FilterChipRow(
                options = genres.mapIndexed { i, (label, _) -> i.toString() to label }
                    .filter { it.first == "0" || query.isBlank() || it.second.contains(query, ignoreCase = true) },
                selected = index.toString(),
                onSelect = { index = it.toIntOrNull() ?: 0 }
            )
        }
        FilterSection("Trạng thái") {
            StatusChips(status) { status = it }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    // Group labels are BRIGHT ORANGE and bold in every sheet (user's
    // pick on the approved mockup — violet was rejected).
    Text(
        title,
        color = NflixOrangeBright,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
    content()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) Color(0xFF2A2450) else NflixBackground)
                    .then(
                        if (isSel) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                        else Modifier
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    color = if (isSel) NflixViolet else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Whole-week Donghua broadcast schedule: 7 day groups of update rows. */
@Composable
private fun ScheduleWeekScreen(
    groups: List<Pair<String, List<TitleItem>>>,
    trackedIds: Set<String> = emptySet(),
    onBack: () -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    // B2: "Chỉ phim tôi theo dõi" — narrow the week to tracked titles.
    var onlyMine by remember { mutableStateOf(false) }
    val shownGroups = if (onlyMine) {
        groups.map { (label, dayItems) ->
            label to dayItems.filter { it.id in trackedIds }
        }
    } else {
        groups
    }
    Column(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(0xFF1B1B24), CircleShape)
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
            Text(
                "Lịch phát sóng",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Chỉ phim tôi theo dõi",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = onlyMine,
                onCheckedChange = { onlyMine = it },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = NflixViolet,
                    uncheckedTrackColor = Color(0xFF3A3A46),
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color.White
                )
            )
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NflixViolet)
            }
        } else if (shownGroups.all { it.second.isEmpty() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Chưa có phim theo dõi nào trong lịch tuần này.",
                    color = NflixTextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                shownGroups.forEach { (label, dayItems) ->
                    if (dayItems.isNotEmpty()) {
                        item { SectionHeader(label) }
                        items(dayItems, key = { "${label}_${it.id}" }) { item ->
                            UpdatedRow(item = item, onClick = { onOpenTitle(item) })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
