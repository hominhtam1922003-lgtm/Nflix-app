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
import androidx.compose.material3.Text
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
import com.example.nflix.data.model.Family
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
import com.example.nflix.data.unitWord
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

/** Sentinel chip value in the Donghua sheet: "Trọn bộ" browse mode (A5). */
private const val DONGHUA_COMPLETED_SLUG = "__tron_bo"

private val FAMILY_CHIP_LABELS = listOf("Tất cả", "Phim", "Anime", "Donghua", "Manhwa", "Truyện")
private val FAMILY_CHIP_VALUES = listOf(null, Family.PHIM, Family.ANIME, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    family: Family,
    onFamilyChange: (Family?) -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    onGoSearch: () -> Unit,
    onOpenFirstNew: (TitleItem) -> Unit,
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
        Family.DONGHUA ->
            if (state.donghuaCompleted) "Trọn bộ"
            else if (donghuaGenre != null) donghuaGenre.first
            else if (state.contextIndex == 0) "Ra tập mới hôm nay"
            else "Ra tập mới ${FamilyViewModel.DAY_LABELS[state.contextIndex]}"
        Family.MANHWA ->
            if (state.manhwaCompleted) "Trọn bộ"
            else if (state.contextIndex == 0) "Đang hot"
            else FamilyViewModel.MANHWA_GENRES[state.contextIndex].first
        Family.TRUYEN ->
            if (state.truyenCompleted) "Trọn bộ"
            else if (state.contextIndex == 0) "Đang hot"
            else FamilyViewModel.TRUYEN_GENRES[state.contextIndex].first
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
            // same load-more pagination, "+N" pills fed by the same
            // newEntries/newRanges the bell list uses.
            TitleListScreen(
                title = "Mới cập nhật",
                items = state.rows,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier,
                newCountOf = { item ->
                    state.newEntries.firstOrNull { it.first.id == item.id }?.second ?: 0
                },
                unitOf = { it.family.unitWord() },
                newRangeOf = { item ->
                    state.newRanges[item.id]?.takeIf { it.first > 0 }
                },
                onOpenFirstNew = onOpenFirstNew,
                onLoadMore = { viewModel.loadMoreRows() },
                gridMode = true
            )
            return
        }
        "new" -> {
            TitleListScreen(
                title = "Có tập mới",
                items = state.newEntries.map { it.first },
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier,
                newCountOf = { item ->
                    state.newEntries.firstOrNull { it.first.id == item.id }?.second ?: 0
                },
                unitOf = { it.family.unitWord() },
                newRangeOf = { item ->
                    state.newRanges[item.id]?.takeIf { it.first > 0 }
                },
                onOpenFirstNew = onOpenFirstNew
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
                Spacer(Modifier.width(18.dp))
                Icon(
                    painter = painterResource(
                        if (state.hasNew) R.drawable.ic_bell_dot else R.drawable.ic_bell
                    ),
                    contentDescription = "Thông báo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                        .clickable { showAll = "new" }
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
        val showTruyenStatus = family == Family.TRUYEN && state.truyenHostLabel.isNotBlank() &&
            (state.truyenOnFallback || state.truyenLastLoadMs > 8000 ||
                state.truyenError || state.truyenFreshError)
        if (showTruyenStatus) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append("Nguồn: ")
                            append(state.truyenHostLabel)
                            if (state.truyenLastLoadMs >= 0) {
                                append(" · tải " + fmtLoadMs(state.truyenLastLoadMs))
                            }
                            if (state.truyenOnFallback) append(" · đang dùng host dự phòng")
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
                        "Cả hai host đều không phản hồi — kiểm tra mạng rồi thử lại.",
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

        // Shelf 1 — posters
        if (state.posters.isNotEmpty()) {
            item {
                PosterShelf(
                    title = shelfTitle,
                    items = state.posters,
                    onItemClick = onOpenTitle,
                    modifier = Modifier.padding(top = 6.dp),
                    actionLabel = if (family == Family.DONGHUA && state.donghuaGenre == null &&
                        !state.donghuaCompleted
                    ) {
                        "Lịch phát sóng"
                    } else {
                        "Xem tất cả"
                    },
                    onAction = {
                        if (family == Family.DONGHUA && state.donghuaGenre == null &&
                            !state.donghuaCompleted
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
            Family.MANHWA -> state.contextIndex == 0 && !state.manhwaCompleted
            Family.TRUYEN -> state.contextIndex == 0 && !state.truyenCompleted
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
                            coverMeta = true,
                            newCount = state.newEntries
                                .firstOrNull { it.first.id == rowItem.id }?.second ?: 0,
                            newSeen = state.newRanges[rowItem.id]?.first ?: 0,
                            newLatest = state.newRanges[rowItem.id]?.second ?: 0,
                            unit = rowItem.family.unitWord()
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
                current = state.animeFilter,
                onApply = { filter ->
                    showFilter = false
                    viewModel.applyAnimeFilter(filter)
                },
                onDismiss = { showFilter = false }
            )
            Family.MANHWA, Family.TRUYEN -> GenrePickSheet(
                labels = contextLabels,
                selectedIndex = state.contextIndex,
                completed = if (family == Family.MANHWA) state.manhwaCompleted else state.truyenCompleted,
                onPick = { index ->
                    showFilter = false
                    viewModel.selectContext(index)
                },
                onToggleCompleted = { on ->
                    showFilter = false
                    viewModel.setCompletedFilter(on)
                },
                onDismiss = { showFilter = false }
            )
            Family.DONGHUA -> DonghuaGenreSheet(
                genres = state.donghuaGenres,
                current = state.donghuaGenre,
                completed = state.donghuaCompleted,
                onApply = { genre, completed ->
                    showFilter = false
                    if (completed) viewModel.setCompletedFilter(true)
                    else viewModel.applyDonghuaGenre(genre)
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
        parts.joinToString(" · ")
    }
    Family.ANIME -> {
        val f = state.animeFilter
        val parts = mutableListOf<String>()
        KkPhimSource.CATEGORIES.firstOrNull { it.second == f.category }?.first?.let { parts += it }
        if (f.year > 0) parts += f.year.toString()
        parts.joinToString(" · ")
    }
    Family.MANHWA -> {
        val parts = mutableListOf<String>()
        FamilyViewModel.MANHWA_GENRES.getOrNull(state.contextIndex)?.first
            ?.takeIf { state.contextIndex != 0 }?.let { parts += it }
        if (state.manhwaCompleted) parts += "Trọn bộ"
        parts.joinToString(" · ")
    }
    Family.TRUYEN -> {
        val parts = mutableListOf<String>()
        FamilyViewModel.TRUYEN_GENRES.getOrNull(state.contextIndex)?.first
            ?.takeIf { state.contextIndex != 0 }?.let { parts += it }
        if (state.truyenCompleted) parts += "Trọn bộ"
        parts.joinToString(" · ")
    }
    Family.DONGHUA ->
        if (state.donghuaCompleted) "Trọn bộ" else state.donghuaGenre?.first ?: ""
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
    val years = listOf(0) + (2026 downTo 2015).toList()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        // Sections scroll; the Đặt lại / Lọc footer stays pinned and visible —
        // chips only take effect when the user taps "Lọc" (user report).
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Lọc phim", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            ) {
                FilterSection("Loại") {
                    FilterChipRow(
                        options = listOf("" to "Tất cả") + FamilyViewModel.PHIM_KINDS.map { it.second to it.first },
                        selected = kind,
                        onSelect = { kind = it }
                    )
                }
                FilterSection("Thể loại") {
                    FilterChipRow(
                        options = listOf("" to "Tất cả") + KkPhimSource.CATEGORIES.map { it.second to it.first },
                        selected = category,
                        onSelect = { category = it }
                    )
                }
                FilterSection("Quốc gia") {
                    FilterChipRow(
                        options = listOf("" to "Tất cả") + KkPhimSource.COUNTRIES.map { it.second to it.first },
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
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Button(
                    onClick = {
                        kind = "phim-bo"; category = ""; country = ""; year = 0
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixBackground),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) { Text("Đặt lại", color = Color.White) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        // "Tất cả" loại (kind = "") falls back to phim-bo listing.
                        onApply(PhimFilter(kind.ifEmpty { "phim-bo" }, category, country, year))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(2f)
                ) { Text("Lọc", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Anime filter sheet: Thể loại + Năm (loại/quốc gia are fixed: hoạt hình + Nhật Bản). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AnimeFilterSheet(
    current: PhimFilter,
    onApply: (PhimFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(current.category) }
    var year by remember { mutableIntStateOf(current.year) }
    val years = listOf(0) + (2026 downTo 2015).toList()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Lọc anime", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            FilterSection("Thể loại") {
                FilterChipRow(
                    options = listOf("" to "Tất cả") + KkPhimSource.CATEGORIES.map { it.second to it.first },
                    selected = category,
                    onSelect = { category = it }
                )
            }
            FilterSection("Năm phát hành") {
                FilterChipRow(
                    options = years.map { y -> y.toString() to if (y == 0) "Tất cả" else y.toString() },
                    selected = year.toString(),
                    onSelect = { year = it.toIntOrNull() ?: 0 }
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Button(
                    onClick = { category = ""; year = 0 },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixBackground),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) { Text("Đặt lại", color = Color.White) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        onApply(PhimFilter(kind = "hoat-hinh", category = category, country = "nhat-ban", year = year))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(2f)
                ) { Text("Lọc", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Donghua genre sheet (A3 + A5): Thể loại from the source's own menu, plus
 * a "Trọn bộ" chip that switches the tab to the /hoan-thanh listing.
 * onApply receives (genre, completed) — exactly one mode at a time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DonghuaGenreSheet(
    genres: List<Pair<String, String>>,
    current: Pair<String, String>?,
    completed: Boolean,
    onApply: (Pair<String, String>?, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var slug by remember {
        mutableStateOf(if (completed) DONGHUA_COMPLETED_SLUG else current?.second ?: "")
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Thể loại", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            FilterSection("Thể loại") {
                FilterChipRow(
                    options = listOf("" to "Tất cả", DONGHUA_COMPLETED_SLUG to "Trọn bộ") +
                        genres.map { it.second to it.first },
                    selected = slug,
                    onSelect = { slug = it }
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Button(
                    onClick = { slug = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixBackground),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) { Text("Đặt lại", color = Color.White) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        if (slug == DONGHUA_COMPLETED_SLUG) onApply(null, true)
                        else onApply(genres.firstOrNull { it.second == slug }, false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(2f)
                ) { Text("Lọc", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        color = NflixTextMuted,
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

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF2A2450) else NflixBackground)
            .then(
                if (selected) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) NflixViolet else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GenrePickSheet(
    labels: List<String>,
    selectedIndex: Int,
    completed: Boolean,
    onPick: (Int) -> Unit,
    onToggleCompleted: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Thể loại", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    PickChip(label, selected = index == selectedIndex) { onPick(index) }
                }
            }
            // A5: completion filter — "Trọn bộ" narrows the current genre
            // (NetTruyen status=2) or switches Truyện to its /truyen-full list.
            FilterSection("Trạng thái") {
                FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PickChip("Tất cả", selected = !completed) { onToggleCompleted(false) }
                    PickChip("Trọn bộ", selected = completed) { onToggleCompleted(true) }
                }
            }
            Spacer(Modifier.height(24.dp))
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
