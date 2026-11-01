package com.example.nflix.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.R
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.components.SectionHeader
import com.example.nflix.ui.common.TitleGridScreen
import com.example.nflix.ui.common.TitleListScreen
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

private val FAMILY_CHIPS = listOf("Tất cả", "Phim", "Anime", "Donghua", "Manhwa", "Truyện")
private val FAMILY_VALUES = listOf(null, Family.PHIM, Family.ANIME, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (TitleItem) -> Unit,
    onFamilyChip: (Family) -> Unit,
    onPlayDirect: (TitleItem) -> Unit,
    onResume: (TitleItem) -> Unit,
    onGoSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val continueList = state.continueList
    var showAll by remember { mutableStateOf<String?>(null) }

    when (showAll) {
        "continue" -> {
            TitleGridScreen(
                title = "Xem tiếp",
                items = continueList.map { it.item },
                onBack = { showAll = null },
                onOpenTitle = { onResume(it) },
                modifier = modifier
            )
            return
        }
        "suggested" -> {
            TitleGridScreen(
                title = "Gợi ý cho bạn",
                items = state.suggestedList,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "latest" -> {
            // List-shelf design (approved 2026-11-01): the full "Mới nhất"
            // list opens as the 3-column poster grid, like the tabs.
            TitleListScreen(
                title = "Mới nhất",
                items = state.latestList,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier,
                gridMode = true
            )
            return
        }
        "today" -> {
            TitleGridScreen(
                title = "Hôm nay có gì",
                items = state.todayList,
                onBack = { showAll = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
            return
        }
        "foryou" -> {
            TitleGridScreen(
                title = state.forYouTitle,
                items = state.forYouList,
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

        if (state.heroList.isNotEmpty()) {
            item {
                HeroCarousel(
                    items = state.heroList,
                    onWatchNow = onPlayDirect,
                    onWatchLater = { viewModel.toggleWatchLater(it) }
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            FilterChips(
                labels = FAMILY_CHIPS,
                selectedIndex = 0,
                onSelect = { idx -> FAMILY_VALUES[idx]?.let(onFamilyChip) }
            )
        }

        if (state.loading && state.hero == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NflixViolet)
                }
            }
        }

        items(state.shelves, key = { it }) { shelfKey ->
            when (shelfKey) {
                "continue" -> if (continueList.isNotEmpty()) {
                    Column {
                        SectionHeader(
                            "Xem tiếp",
                            actionLabel = "Xem tất cả",
                            onAction = { showAll = "continue" }
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(continueList, key = { it.item.id }) { entry ->
                                PosterCard(
                                    item = entry.item,
                                    onClick = { onResume(entry.item) },
                                    modifier = Modifier.width(140.dp),
                                    progressFraction = entry.progressFraction,
                                    progressLabel = entry.progressLabel
                                )
                            }
                        }
                    }
                }
                "today" -> if (state.todayList.isNotEmpty()) {
                    com.example.nflix.ui.components.PosterShelf(
                        title = "Hôm nay có gì",
                        items = state.todayList,
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(top = 6.dp),
                        actionLabel = "Xem tất cả",
                        onAction = { showAll = "today" }
                    )
                }
                "foryou" -> if (state.forYouList.isNotEmpty()) {
                    com.example.nflix.ui.components.PosterShelf(
                        title = state.forYouTitle,
                        items = state.forYouList,
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(top = 6.dp),
                        actionLabel = "Xem tất cả",
                        onAction = { showAll = "foryou" }
                    )
                }
                "suggested" -> if (state.suggestedList.isNotEmpty()) {
                    com.example.nflix.ui.components.PosterShelf(
                        title = "Gợi ý cho bạn",
                        items = state.suggestedList,
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(top = 6.dp),
                        actionLabel = "Xem tất cả",
                        onAction = { showAll = "suggested" }
                    )
                }
                // List-shelf design (approved 2026-11-01): "Mới nhất" is a
                // poster shelf like every other Home shelf — the vertical
                // small-cover rows the user called out are gone.
                "latest" -> if (state.latestList.isNotEmpty()) {
                    com.example.nflix.ui.components.PosterShelf(
                        title = "Mới nhất",
                        items = state.latestList.take(8),
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(top = 6.dp),
                        actionLabel = "Xem tất cả",
                        onAction = { showAll = "latest" },
                        coverMeta = true
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * "Đang thịnh hành" hero carousel: swipes through the trending titles and
 * auto-advances every 3 s (pauses while the user is dragging).
 */
@Composable
private fun HeroCarousel(
    items: List<TitleItem>,
    onWatchNow: (TitleItem) -> Unit,
    onWatchLater: (TitleItem) -> Unit
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { items.size }
    androidx.compose.runtime.LaunchedEffect(items.size) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(3000)
            if (!pagerState.isScrollInProgress) {
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }
    Box {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val heroItem = items[page]
            HeroCard(
                item = heroItem,
                onWatchNow = { onWatchNow(heroItem) },
                onWatchLater = { onWatchLater(heroItem) }
            )
        }
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 30.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == pagerState.currentPage) 7.dp else 5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (i == pagerState.currentPage) NflixViolet
                                else Color(0x66FFFFFF)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(item: TitleItem, onWatchNow: () -> Unit, onWatchLater: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(18.dp))
            .background(NflixSurface)
    ) {
        com.example.nflix.ui.components.FallbackImage(
            primary = item.thumbUrl,
            fallback = item.posterUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            title = item.name,
            extraFallback = item.posterFallbackUrl
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE60B0B10)),
                        startY = 120f
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xC20D0D13), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("Đang thịnh hành", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                item.name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            val meta = listOf(
                item.family.label,
                item.latestLabel,
                when (item.versionBadge) {
                    "TM" -> "Thuyết minh"
                    "VS" -> "Vietsub"
                    "LT" -> "Lồng tiếng"
                    else -> ""
                }
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = Color(0xFFD8D8E0), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = onWatchNow,
                    colors = ButtonDefaults.buttonColors(containerColor = NflixViolet),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Xem ngay", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onWatchLater,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Xem sau", color = Color.White)
                }
            }
        }
    }
}
