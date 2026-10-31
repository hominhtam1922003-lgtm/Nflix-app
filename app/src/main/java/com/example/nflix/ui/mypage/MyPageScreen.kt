package com.example.nflix.ui.mypage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.unitWord
import com.example.nflix.ui.common.TitleGridScreen
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PlusPill
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.components.SectionHeader
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import kotlinx.coroutines.launch

private val FILTER_LABELS = listOf("Tất cả", "Phim", "Donghua", "Truyện")

private fun matchesFilter(family: Family, filterIndex: Int): Boolean = when (filterIndex) {
    0 -> true
    1 -> family == Family.PHIM
    2 -> family == Family.DONGHUA
    3 -> family == Family.TRUYEN || family == Family.MANHWA
    else -> true
}

@Composable
fun MyPageScreen(
    onOpenTitle: (TitleItem) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember { MyPageViewModel(app) }
    val state by viewModel.state.collectAsState()
    var filterIndex by remember { mutableIntStateOf(0) }
    var showWatchLater by remember { mutableStateOf(false) }
    var showFavoritesAll by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (showWatchLater) {
        TitleGridScreen(
            title = "Xem sau",
            items = state.watchLater.map { it.item },
            onBack = { showWatchLater = false },
            onOpenTitle = onOpenTitle,
            modifier = modifier
        )
        return
    }

    val favorites = state.favorites.filter { matchesFilter(it.item.family, filterIndex) }

    if (showFavoritesAll) {
        TitleGridScreen(
            title = "Yêu thích",
            items = favorites.map { it.item },
            onBack = { showFavoritesAll = false },
            onOpenTitle = onOpenTitle,
            modifier = modifier
        )
        return
    }
    val followingHeaderIndex = 1
    val favoritesHeaderIndex = followingHeaderIndex +
        (if (state.following.isNotEmpty()) 1 + state.following.size else 0)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(NflixBackground)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Của tôi",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_gear),
                    contentDescription = "Cài đặt",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                        .clickable(onClick = onOpenSettings)
                        .padding(2.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    count = state.followingCount,
                    label = "Đang theo dõi",
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.following.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(followingHeaderIndex) }
                    }
                }
                StatCard(
                    count = state.favoriteCount,
                    label = "Yêu thích",
                    modifier = Modifier.weight(1f)
                ) {
                    scope.launch { listState.animateScrollToItem(favoritesHeaderIndex) }
                }
                StatCard(
                    count = state.watchLaterCount,
                    label = "Xem sau",
                    modifier = Modifier.weight(1f)
                ) { showWatchLater = true }
            }
        }

        if (state.following.isNotEmpty()) {
            item {
                SectionHeader("Đang theo dõi — có mới lên đầu")
            }
            items(state.following.size, key = { i -> state.following[i].item.id }) { i ->
                val entry = state.following[i]
                FollowingRow(entry = entry, onClick = { onOpenTitle(entry.item) })
            }
        }

        item {
            SectionHeader(
                "Yêu thích",
                actionLabel = "Xem tất cả",
                onAction = { showFavoritesAll = true }
            )
            FilterChips(
                labels = FILTER_LABELS,
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it }
            )
            Spacer(Modifier.height(12.dp))
        }

        item {
            if (favorites.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Chưa có bộ nào", color = NflixTextMuted, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    favorites.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { entry ->
                                PosterCard(
                                    item = entry.item,
                                    onClick = { onOpenTitle(entry.item) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StatCard(count: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NflixSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$count", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = NflixTextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun FollowingRow(entry: LibraryEntry, onClick: () -> Unit) {
    val item = entry.item
    val st = entry.state
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NflixSurface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.posterUrl.ifBlank { item.thumbUrl },
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 46.dp, height = 62.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val progressText = when {
                st.lastUnitNumber <= 0f -> "Chưa xem"
                st.progressFraction > 0f && !st.finished ->
                    "đang xem dở ${st.lastUnitLabel.lowercase()}"
                else -> "đã xem tới ${st.lastUnitLabel.lowercase()}"
            }
            Text(
                "${item.family.label} · $progressText",
                color = NflixTextMuted,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        if (st.newCount > 0) {
            PlusPill(st.newCount, item.family.unitWord())
        }
    }
}
