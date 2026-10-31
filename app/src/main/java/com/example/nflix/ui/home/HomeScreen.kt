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
import com.example.nflix.ui.components.UpdatedRow
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

private val FAMILY_CHIPS = listOf("Tất cả", "Phim", "Donghua", "Manhwa", "Truyện")
private val FAMILY_VALUES = listOf(null, Family.PHIM, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (TitleItem) -> Unit,
    onFamilyChip: (Family) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val continueList = state.continueList
    val newList = state.newList

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = NflixViolet)) { append("N") }
                        append("flix")
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
                )
                Spacer(Modifier.width(18.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_bell_dot),
                    contentDescription = "Thông báo",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        state.hero?.let { hero ->
            item {
                HeroCard(
                    item = hero,
                    onWatchNow = { onOpenTitle(hero) },
                    onWatchLater = { viewModel.toggleWatchLater(hero) }
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
                        SectionHeader("Xem tiếp")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(continueList, key = { it.item.id }) { entry ->
                                PosterCard(
                                    item = entry.item,
                                    onClick = { onOpenTitle(entry.item) },
                                    modifier = Modifier.width(140.dp),
                                    progressFraction = entry.progressFraction,
                                    progressLabel = entry.progressLabel
                                )
                            }
                        }
                    }
                }
                "new" -> if (newList.isNotEmpty()) {
                    Column {
                        SectionHeader("Có tập mới")
                        newList.forEach { entry ->
                            NewEpisodeCard(
                                entry = entry,
                                unit = viewModel.unitOf(entry.item),
                                onClick = { onOpenTitle(entry.item) }
                            )
                        }
                    }
                }
                "suggested" -> if (state.suggestedList.isNotEmpty()) {
                    com.example.nflix.ui.components.PosterShelf(
                        title = "Gợi ý cho bạn",
                        items = state.suggestedList,
                        onItemClick = onOpenTitle,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                "latest" -> if (state.latestList.isNotEmpty()) {
                    Column {
                        SectionHeader("Mới nhất")
                        state.latestList.forEach { item ->
                            UpdatedRow(item = item, onClick = { onOpenTitle(item) })
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NewEpisodeCard(entry: ShelfEntry, unit: String, onClick: () -> Unit) {
    val item = entry.item
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            modifier = Modifier
                .size(width = 46.dp, height = 62.dp)
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
            val time = com.example.nflix.data.TimeText.relative(item.updatedEpochSec)
            val countText = if (item.episodeCount > 0) "$unit mới nhất ${item.episodeCount}" else item.latestLabel
            val sub = listOf(item.family.label, countText, time).filter { it.isNotBlank() }.joinToString(" · ")
            Text(sub, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
        }
        com.example.nflix.ui.components.PlusPill(entry.newCount, unit)
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
        AsyncImage(
            model = item.thumbUrl.ifBlank { item.posterUrl },
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
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
                item.year.takeIf { it > 0 }?.toString().orEmpty(),
                item.family.label,
                item.quality,
                when (item.versionBadge) {
                    "TM" -> "Thuyết minh"
                    "VS" -> "Vietsub"
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
