package com.example.nflix.ui.discover

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
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.common.TitleGridScreen
import com.example.nflix.ui.components.QualityBadge
import com.example.nflix.ui.components.SectionHeader
import com.example.nflix.ui.components.VersionBadge
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGold
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

@Composable
fun DiscoverScreen(
    onOpenTitle: (TitleItem) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember { DiscoverViewModel(app) }
    val state by viewModel.state.collectAsState()
    var genreRoute by remember { mutableStateOf<DiscoverViewModel.GenreDef?>(null) }
    var showRankingAll by remember { mutableStateOf(false) }

    if (showRankingAll) {
        TitleGridScreen(
            title = "Bảng xếp hạng tuần",
            items = state.ranking.take(20),
            onBack = { showRankingAll = false },
            onOpenTitle = onOpenTitle,
            modifier = modifier
        )
        return
    }
    genreRoute?.let { genre ->
        if (state.genreLoading) {
            Box(
                modifier = modifier.fillMaxSize().background(NflixBackground),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NflixViolet)
            }
        } else {
            TitleGridScreen(
                title = genre.label,
                items = state.genreItems,
                onBack = { genreRoute = null },
                onOpenTitle = onOpenTitle,
                modifier = modifier
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Khám phá",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Tìm kiếm",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                        .clickable(onClick = onOpenSearch)
                        .padding(2.dp)
                )
            }
            Text(
                "Thể loại",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DiscoverViewModel.GENRES.chunked(2).forEach { rowGenres ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowGenres.forEach { genre ->
                            Box(
                                modifier = Modifier.weight(1f)
                                    .aspectRatio(16f / 8.5f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(genre.colorHex))
                                    .clickable {
                                        genreRoute = genre
                                        viewModel.loadGenre(genre.slug)
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.align(Alignment.BottomStart)
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        genre.label,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        genre.countLabel,
                                        color = Color(0xDDFFFFFF),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        if (rowGenres.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.ranking.isNotEmpty()) {
            item {
                SectionHeader(
                    "Bảng xếp hạng tuần",
                    actionLabel = "Xem tất cả",
                    onAction = { showRankingAll = true }
                )
            }
            items(state.ranking.take(10).withIndex().toList(), key = { it.value.id }) { (index, item) ->
                RankRow(rank = index + 1, item = item, onClick = { onOpenTitle(item) })
            }
        }

        if (state.completed.isNotEmpty()) {
            item {
                SectionHeader("Hoàn thành — xem một lèo")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.completed, key = { it.id }) { item ->
                        WideCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RankRow(rank: Int, item: TitleItem, onClick: () -> Unit) {
    val rankColor = when (rank) {
        1 -> NflixGold
        2, 3 -> NflixViolet
        else -> NflixTextMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            color = rankColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )
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
            val sub = listOf(item.family.label, item.latestLabel)
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(sub, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun WideCard(item: TitleItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(230.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B1B24))
        ) {
            AsyncImage(
                model = item.thumbUrl.ifBlank { item.posterUrl },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            QualityBadge(item.quality, Modifier.align(Alignment.TopStart).padding(8.dp))
            VersionBadge(item.versionBadge, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (item.latestLabel.isNotBlank()) {
            Text(item.latestLabel, color = NflixTextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }
}
