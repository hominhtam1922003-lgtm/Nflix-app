package com.example.nflix.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflix.R
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.components.UpdatedRow
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

/**
 * Full-screen list — "Xem tất cả" for shelves (Mới nhất, lịch phát sóng).
 * Default stays the row list; [gridMode] switches the body to the
 * approved 3-column poster grid (list-shelf design 2026-11-01) for the
 * surfaces the user picked — same data, same click routing, same
 * load-more, only the presentation changes.
 *
 * 1.3.8: load-more is driven by SCROLL POSITION (a snapshotFlow over
 * the lazy state fires when the last visible item is within 6 of
 * the end). The pre-1.3.8 trigger — a zero-height sentinel item
 * whose LaunchedEffect ran if the layout happened to compose it —
 * was unreliable, and several "Xem tất cả" grids shipped without
 * any onLoadMore at all, so lists simply ended (user verdict 1.3.7).
 */
@Composable
fun TitleListScreen(
    title: String,
    items: List<TitleItem>,
    onBack: () -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: (() -> Unit)? = null,
    gridMode: Boolean = false
) {
    BackHandler(onBack = onBack)
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
                title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chưa có gì ở đây", color = NflixTextMuted, fontSize = 14.sp)
            }
        } else if (gridMode) {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState, onLoadMore) {
                if (onLoadMore == null) return@LaunchedEffect
                snapshotFlow {
                    val info = gridState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to
                        info.totalItemsCount
                }.collect { (lastVisible, total) ->
                    if (total > 0 && lastVisible >= total - 6) onLoadMore()
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(items, key = { it.id }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onOpenTitle(item) },
                        coverMeta = true
                    )
                }
                // 1.3.11: a manual paging path at the end of the
                // grid — auto load-on-scroll stays, but the user can
                // always pull the next page by hand (and can SEE the
                // catalog continues; the 1.3.9 verdict video showed a
                // grid that simply stopped at a dozen covers).
                if (onLoadMore != null) {
                    item(
                        key = "load_more",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        LoadMoreButton(onClick = onLoadMore)
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, onLoadMore) {
                if (onLoadMore == null) return@LaunchedEffect
                snapshotFlow {
                    val info = listState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to
                        info.totalItemsCount
                }.collect { (lastVisible, total) ->
                    if (total > 0 && lastVisible >= total - 6) onLoadMore()
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    UpdatedRow(
                        item = item,
                        onClick = { onOpenTitle(item) }
                    )
                }
                // 1.3.11: manual paging path, same as the grid mode.
                if (onLoadMore != null) {
                    item(key = "load_more") {
                        LoadMoreButton(onClick = onLoadMore)
                    }
                }
            }
        }
    }
}

/** 1.3.11: the end-of-list "load more" tap target (see call sites). */
@Composable
private fun LoadMoreButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Tải thêm",
            color = NflixViolet,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
