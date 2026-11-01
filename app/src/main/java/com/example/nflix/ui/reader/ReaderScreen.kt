package com.example.nflix.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.scrollBy
import coil.compose.SubcomposeAsyncImage
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.AppSettings
import com.example.nflix.data.SettingsStore
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.NewBadge
import com.example.nflix.ui.detail.fmtNum
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGreen
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixSurfaceVariant
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import com.example.nflix.ui.theme.NflixVioletSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    item: TitleItem,
    chapter: ChapterItem,
    onBack: () -> Unit,
    onOpenChapter: (ChapterItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    var attempt by remember { mutableIntStateOf(0) }
    val viewModel = remember(item.id, chapter.number, attempt) { ReaderViewModel(app, item, chapter) }
    val state by viewModel.state.collectAsState()
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    var showDrawer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var reportNote by remember { mutableStateOf(false) }
    var scrubPage by remember { mutableStateOf<Float?>(null) }
    var brightness by remember { mutableStateOf<Float?>(null) }

    // Reader chrome (top + bottom bars): hidden while reading when the
    // auto-hide setting is on; tapping a page toggles it back.
    var chromeShown by remember { mutableStateOf(false) }
    LaunchedEffect(settings.readerAutoHideChrome) {
        chromeShown = !settings.readerAutoHideChrome
    }
    LaunchedEffect(chromeShown, showDrawer, showSettings) {
        if (settings.readerAutoHideChrome && chromeShown && !showDrawer && !showSettings) {
            kotlinx.coroutines.delay(3000)
            chromeShown = false
        }
    }
    // B5: auto-scroll pause — tapping the page pauses/resumes the drift.
    var scrollPaused by remember { mutableStateOf(false) }
    val toggleChrome: () -> Unit = {
        if (settings.readerAutoHideChrome) chromeShown = !chromeShown
    }
    val tapToggle = Modifier.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
    ) {
        toggleChrome()
        if (settings.readerAutoScroll && settings.readerMode == "SCROLL") {
            scrollPaused = !scrollPaused
        }
    }

    val totalPages = state.pages.size
    val displayPage = (scrubPage?.toInt() ?: state.currentPage).coerceIn(1, maxOf(1, totalPages))

    // Keep screen on while reading (per setting).
    DisposableEffect(settings.readerKeepScreenOn) {
        val activity = context as? Activity
        if (settings.readerKeepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // Brightness override from the reading-settings sheet.
    DisposableEffect(brightness) {
        val activity = context as? Activity
        val b = brightness
        if (activity != null && b != null) {
            val attrs = activity.window.attributes
            attrs.screenBrightness = b
            activity.window.attributes = attrs
        }
        onDispose {
            if (activity != null && b != null) {
                val attrs = activity.window.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = attrs
            }
        }
    }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0) { maxOf(1, totalPages) }

    // Initial position: jump to the saved page once pages arrive.
    LaunchedEffect(state.pages, state.chapter?.number) {
        if (state.pages.isNotEmpty() && state.startPage > 1) {
            if (settings.readerMode == "PAGE") pagerState.scrollToPage(state.startPage - 1)
            else listState.scrollToItem(state.startPage - 1)
        }
    }
    // Track the visible page (scroll mode).
    LaunchedEffect(listState, state.pages) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (state.pages.isNotEmpty()) {
                viewModel.onPageVisible((idx + 1).coerceAtMost(state.pages.size))
            }
        }
    }
    // Track the visible page (pager mode).
    LaunchedEffect(pagerState, state.pages) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (state.pages.isNotEmpty()) viewModel.onPageVisible(page + 1)
        }
    }
    // C5 (K7): near the end of the chapter, warm the next chapter's pages —
    // SCROLL when 3 or fewer pages remain, PAGE at the penultimate page.
    LaunchedEffect(state.currentPage, state.pages.size, settings.readerMode) {
        val total = state.pages.size
        if (total <= 0) return@LaunchedEffect
        val remaining = total - state.currentPage
        val nearEnd = if (settings.readerMode == "PAGE") remaining <= 1 else remaining <= 3
        if (nearEnd) viewModel.prefetchNextChapter()
    }
    // B5: hands-free auto-scroll (SCROLL mode only). Dragging pauses via
    // isScrollInProgress; tapping a page toggles scrollPaused (see tapToggle).
    val autoScrollRunning = settings.readerAutoScroll &&
        settings.readerMode == "SCROLL" && !scrollPaused && state.pages.isNotEmpty()
    LaunchedEffect(autoScrollRunning, state.chapter?.number, settings.readerAutoScrollSpeed) {
        if (!autoScrollRunning) return@LaunchedEffect
        val pxPerTick = when (settings.readerAutoScrollSpeed) {
            "SLOW" -> 3f
            "FAST" -> 14f
            else -> 7f
        }
        while (true) {
            kotlinx.coroutines.delay(50)
            if (!listState.isScrollInProgress) listState.scrollBy(pxPerTick)
        }
    }

    fun jumpToPage(page: Int) {
        val target = page.coerceIn(1, maxOf(1, totalPages))
        scope.launch {
            if (settings.readerMode == "PAGE") pagerState.animateScrollToPage(target - 1)
            else listState.animateScrollToItem(target - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NflixViolet)
            }
            state.error.isNotBlank() -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.error, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { attempt++ },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50)
                ) { Text("Thử lại", color = Color.White) }
            }
            settings.readerMode == "PAGE" -> HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ReaderPageImage(
                    url = state.pages[page],
                    pageNumber = page + 1,
                    contentDescription = "Trang ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(tapToggle)
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(state.pages, key = { i, _ -> i }) { index, url ->
                    ReaderPageImage(
                        url = url,
                        pageNumber = index + 1,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF101016)).then(tapToggle)
                    )
                }
                viewModel.nextChapter()?.let { next ->
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NflixVioletSoft)
                                .border(1.dp, NflixViolet, RoundedCornerShape(12.dp))
                                .clickable { onOpenChapter(next) }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Đọc tiếp: ${next.label} ›",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // B5: warm eye-protection overlay (strength from reading settings).
        // A plain Box consumes no touches, so page taps still reach images.
        if (settings.readerEyeFilter > 0f) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFFFF9E45).copy(alpha = settings.readerEyeFilter))
            )
        }

        // Top bar (floating) — part of the toggleable chrome.
        if (chromeShown) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                )
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderCircleBtn(R.drawable.ic_back, "Quay lại") { onBack() }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        state.chapter?.label ?: chapter.label,
                        color = NflixTextMuted,
                        fontSize = 12.sp
                    )
                }
                ReaderCircleBtn(
                    R.drawable.ic_bookmark, "Đánh dấu trang",
                    tint = if (viewModel.isBookmarkedHere()) NflixAmber else Color.White
                ) { viewModel.toggleBookmark() }
                Spacer(Modifier.width(8.dp))
                Box {
                    ReaderCircleBtn(R.drawable.ic_more, "Thêm") { showMenu = true }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(NflixSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Danh sách chap", color = Color.White) },
                            onClick = { showMenu = false; showDrawer = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Cài đặt đọc", color = Color.White) },
                            onClick = { showMenu = false; showSettings = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Báo lỗi chap", color = Color.White) },
                            onClick = {
                                showMenu = false
                                // 0.4: REAL report now (was a fake toast) —
                                // title, chapter and source go to the store.
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.example.nflix.data.ReportStore.add(
                                            context,
                                            com.example.nflix.data.ErrorReport(
                                                timeEpochSec = System.currentTimeMillis() / 1000,
                                                family = item.family.name,
                                                titleId = item.id,
                                                titleName = item.name,
                                                unitLabel = state.chapter?.label ?: chapter.label,
                                                server = viewModel.sourceDisplayName(),
                                                message = "Người dùng báo lỗi chap này trong trình đọc"
                                            )
                                        )
                                    }
                                    reportNote = true
                                }
                            }
                        )
                    }
                }
            }
        }
        }

        // Bottom: page progress + chapter controls (toggleable chrome too).
        // The right-side page pill is gone — the bottom slider already counts pages.
        if (chromeShown) {
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        ) {
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trang $displayPage", color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = displayPage.toFloat(),
                        onValueChange = { scrubPage = it },
                        onValueChangeFinished = {
                            scrubPage?.let { jumpToPage(it.toInt()) }
                            scrubPage = null
                        },
                        valueRange = 1f..totalPages.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = NflixViolet,
                            activeTrackColor = NflixViolet,
                            inactiveTrackColor = Color(0xFF3A3A46)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    Text("$totalPages", color = NflixTextMuted, fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val prev = viewModel.prevChapter()
                val next = viewModel.nextChapter()
                val pillShape = RoundedCornerShape(50)
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(NflixSurfaceVariant)
                        .then(if (prev != null) Modifier.clickable { onOpenChapter(prev) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        "‹ Chap trước",
                        color = if (prev != null) Color.White else NflixTextMuted,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(NflixSurfaceVariant)
                        .clickable { showDrawer = true }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_list),
                        contentDescription = "Danh sách chap",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(NflixSurfaceVariant)
                        .clickable { showSettings = true }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gear),
                        contentDescription = "Cài đặt đọc",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (settings.readerMode == "SCROLL") {
                    // B5: quick auto-scroll toggle (speed lives in the sheet).
                    Box(
                        modifier = Modifier
                            .clip(pillShape)
                            .background(if (settings.readerAutoScroll) NflixViolet else NflixSurfaceVariant)
                            .clickable {
                                val next = !settings.readerAutoScroll
                                scrollPaused = false
                                scope.launch {
                                    app.settings.set(SettingsStore.READER_AUTOSCROLL, next)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text("Tự cuộn", color = Color.White, fontSize = 13.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(NflixSurfaceVariant)
                        .then(if (next != null) Modifier.clickable { onOpenChapter(next) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        "Chap sau ›",
                        color = if (next != null) Color.White else NflixTextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
        }

        if (reportNote) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1800)
                reportNote = false
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xDD14141B), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text("Đã ghi nhận báo lỗi chap này", color = Color.White, fontSize = 13.sp)
            }
        }
    }

    if (showDrawer) {
        ChapterSheet(
            viewModel = viewModel,
            item = item,
            onClose = { showDrawer = false },
            onPickChapter = { ch ->
                showDrawer = false
                if (viewModel.isCurrent(ch)) {
                    jumpToPage(state.userState?.pageIndex ?: 1)
                } else {
                    onOpenChapter(ch)
                }
            }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            brightness = brightness,
            onBrightness = { brightness = it },
            onClose = { showSettings = false }
        )
    }
}

/**
 * One reader page. A failed page is no longer a silent hole in the chapter
 * (K6): it shows a placeholder with the page number and a per-page retry.
 * Reloading bumps a key so Coil issues a fresh request for that page only.
 */
@Composable
private fun ReaderPageImage(
    url: String,
    pageNumber: Int,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    var reloadKey by remember(url) { mutableIntStateOf(0) }
    androidx.compose.runtime.key(url, reloadKey) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            error = {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                        .background(Color(0xFF14141B)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Trang $pageNumber chưa tải được",
                            color = NflixTextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(NflixViolet)
                                .clickable { reloadKey++ }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Tải lại trang này", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ReaderCircleBtn(
    iconRes: Int,
    desc: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(36.dp)
            .background(Color(0x80000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    viewModel: ReaderViewModel,
    item: TitleItem,
    onClose: () -> Unit,
    onPickChapter: (ChapterItem) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var sortDesc by remember { mutableStateOf(true) }
    var jumpText by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf("") }
    val chapters = if (sortDesc) state.chapters.reversed() else state.chapters
    val current = state.chapter
    val maxNumber = state.chapters.maxOfOrNull { it.number } ?: 0f

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = NflixBackground
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.88f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Danh sách chap — ${item.name}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier.size(30.dp)
                        .border(1.dp, Color(0xFF3A3A46), CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Đóng",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                "${state.chapters.size} chap · Nguồn: ${viewModel.sourceDisplayName()}",
                color = NflixTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Currently-reading banner
            if (current != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NflixVioletSoft)
                        .border(1.dp, NflixViolet, RoundedCornerShape(12.dp))
                        .clickable { onPickChapter(current) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Đang đọc: ", color = NflixViolet, fontSize = 13.sp)
                    Text(
                        "${current.label} · Trang ${state.currentPage}/${state.pages.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text("Đọc tiếp ›", color = NflixViolet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Toolbar: jump + sort
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NflixSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = jumpText,
                        onValueChange = { jumpText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(NflixViolet),
                        decorationBox = { inner ->
                            if (jumpText.isEmpty()) {
                                Text("Đi tới chap…", color = NflixTextMuted, fontSize = 14.sp)
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val n = jumpText.trim().toFloatOrNull()
                        val target = state.chapters.firstOrNull { it.number == n }
                        if (target != null) {
                            jumpError = ""
                            onPickChapter(target)
                        } else {
                            jumpError = "Không có chap này"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Đi", color = Color.White, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (sortDesc) "Mới nhất ▼" else "Cũ nhất ▼",
                    color = NflixViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { sortDesc = !sortDesc }
                )
            }
            if (jumpError.isNotBlank()) {
                Text(jumpError, color = NflixRed, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }

            LazyColumn(modifier = Modifier.padding(top = 10.dp)) {
                items(chapters, key = { it.number }) { ch ->
                    val isCurrent = viewModel.isCurrent(ch)
                    val isRead = viewModel.isRead(ch)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) NflixVioletSoft else Color.Transparent)
                            .clickable { onPickChapter(ch) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ch.label,
                            color = when {
                                isCurrent -> Color.White
                                isRead -> NflixTextMuted
                                else -> Color.White
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                        if (ch.number == maxNumber) {
                            Spacer(Modifier.width(8.dp))
                            NewBadge()
                        }
                        if (isCurrent) {
                            val pct = if (state.pages.isNotEmpty())
                                (state.currentPage * 100 / state.pages.size) else 0
                            Spacer(Modifier.width(8.dp))
                            Text("đang đọc $pct%", color = NflixViolet, fontSize = 12.sp)
                        } else if (isRead) {
                            Spacer(Modifier.width(8.dp))
                            Text("✓ đã đọc", color = NflixGreen, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        if (ch.updatedLabel.isNotBlank()) {
                            Text(ch.updatedLabel, color = NflixTextMuted, fontSize = 12.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: AppSettings,
    brightness: Float?,
    onBrightness: (Float?) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Cài đặt đọc", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Text("Chế độ đọc", color = NflixTextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("SCROLL" to "Cuộn dọc", "PAGE" to "Lật trang").forEach { (key, label) ->
                    val selected = settings.readerMode == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) NflixVioletSoft else NflixBackground)
                            .then(
                                if (selected) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                                else Modifier
                            )
                            .clickable {
                                scope.launch { app.settings.setString(SettingsStore.READER_MODE, key) }
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text(label, color = if (selected) NflixViolet else Color.White, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Độ sáng", color = NflixTextMuted, fontSize = 12.sp)
            Slider(
                value = brightness ?: 0.7f,
                onValueChange = { onBrightness(it) },
                valueRange = 0.1f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = NflixViolet,
                    activeTrackColor = NflixViolet,
                    inactiveTrackColor = Color(0xFF3A3A46)
                )
            )
            // B5: auto-scroll (SCROLL mode) + speed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tự cuộn trang", color = Color.White, fontSize = 14.sp)
                    Text("Chỉ ở chế độ Cuộn dọc — chạm màn hình để tạm dừng", color = NflixTextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.readerAutoScroll,
                    onCheckedChange = { on ->
                        scope.launch { app.settings.set(SettingsStore.READER_AUTOSCROLL, on) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NflixViolet)
                )
            }
            if (settings.readerAutoScroll) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("SLOW" to "Chậm", "NORMAL" to "Vừa", "FAST" to "Nhanh").forEach { (key, label) ->
                        val selected = settings.readerAutoScrollSpeed == key
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) NflixVioletSoft else NflixBackground)
                                .then(
                                    if (selected) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                                    else Modifier
                                )
                                .clickable {
                                    scope.launch {
                                        app.settings.setString(SettingsStore.READER_AUTOSCROLL_SPEED, key)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(label, color = if (selected) NflixViolet else Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // B5: warm eye-protection filter strength (0 = off).
            Text(
                "Lọc bảo vệ mắt" +
                    if (settings.readerEyeFilter <= 0f) " — đang tắt"
                    else " — ${(settings.readerEyeFilter * 100).toInt()}%",
                color = NflixTextMuted,
                fontSize = 12.sp
            )
            Slider(
                value = settings.readerEyeFilter,
                onValueChange = { v ->
                    scope.launch { app.settings.setFloat(SettingsStore.READER_EYE_FILTER, v) }
                },
                valueRange = 0f..0.45f,
                colors = SliderDefaults.colors(
                    thumbColor = NflixViolet,
                    activeTrackColor = NflixViolet,
                    inactiveTrackColor = Color(0xFF3A3A46)
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Giữ màn hình sáng", color = Color.White, fontSize = 14.sp)
                    Text("Không tắt màn hình khi đang đọc", color = NflixTextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.readerKeepScreenOn,
                    onCheckedChange = { on ->
                        scope.launch { app.settings.set(SettingsStore.READER_KEEP_ON, on) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NflixViolet)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tự ẩn thanh công cụ", color = Color.White, fontSize = 14.sp)
                    Text("Chạm vào màn hình để hiện lại", color = NflixTextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.readerAutoHideChrome,
                    onCheckedChange = { on ->
                        scope.launch { app.settings.set(SettingsStore.READER_AUTOHIDE, on) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NflixViolet)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
