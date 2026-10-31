package com.example.nflix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.data.TimeText
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixBorder
import com.example.nflix.ui.theme.NflixGold
import com.example.nflix.ui.theme.NflixOrange
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixSurfaceVariant
import com.example.nflix.ui.theme.NflixText
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixTextSecondary
import com.example.nflix.ui.theme.NflixViolet

private val BadgeShape = RoundedCornerShape(6.dp)
private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun QualityBadge(quality: String, modifier: Modifier = Modifier) {
    if (quality.isBlank()) return
    Box(
        modifier = modifier
            .background(Color(0xC20D0D13), BadgeShape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(quality, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun VersionBadge(version: String, modifier: Modifier = Modifier) {
    if (version.isBlank()) return
    val color = if (version == "TM") NflixGold else NflixOrange
    Box(
        modifier = modifier
            .background(Color(0xC20D0D13), BadgeShape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(version, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HotBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(NflixRed, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text("HOT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(NflixAmber, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text("MỚI", color = Color(0xFF221503), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Orange "+N tập / +N chap" pill. */
@Composable
fun PlusPill(count: Int, unit: String, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .background(NflixOrange, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("+$count $unit", color = Color(0xFF221503), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Poster card 2:3 with the design's badges; optional progress bar (Xem tiếp). */
@Composable
fun PosterCard(
    item: TitleItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null,
    progressLabel: String = ""
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(CardShape)
                .background(NflixSurfaceVariant)
        ) {
            AsyncImage(
                model = item.posterUrl.ifBlank { item.thumbUrl },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            QualityBadge(item.quality, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (item.isHot) {
                HotBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            } else {
                VersionBadge(item.versionBadge, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = NflixViolet,
                    trackColor = Color(0x55FFFFFF)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = NflixText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val sub = progressLabel.ifBlank { item.latestLabel }
        if (sub.isNotBlank()) {
            Text(sub, color = NflixTextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = NflixText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                color = NflixViolet,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

/** Horizontal poster shelf. */
@Composable
fun PosterShelf(
    title: String,
    items: List<TitleItem>,
    onItemClick: (TitleItem) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Int = 112
) {
    if (items.isEmpty()) return
    Column(modifier = modifier) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(item = item, onClick = { onItemClick(item) }, modifier = Modifier.width(cardWidth.dp))
            }
        }
    }
}

/** "Mới cập nhật" list row: thumb + name + latest + relative time + optional +N pill. */
@Composable
fun UpdatedRow(
    item: TitleItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    newCount: Int = 0,
    unit: String = "tập"
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 62.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NflixSurfaceVariant)
        ) {
            AsyncImage(
                model = item.posterUrl.ifBlank { item.thumbUrl },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = NflixText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val time = item.updatedLabel.ifBlank { TimeText.relative(item.updatedEpochSec) }
            val sub = listOf(item.latestLabel, time).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (item.isNew) NewBadge()
        if (item.isHot) HotBadge()
        if (newCount > 0) {
            Spacer(Modifier.width(8.dp))
            PlusPill(newCount, unit)
        }
    }
}

/** Two-level filter chips (family row / context row) exactly as designed. */
@Composable
fun FilterChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(labels.size) { i ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .background(
                        if (selected) NflixViolet else NflixSurface,
                        RoundedCornerShape(50)
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    labels[i],
                    color = if (selected) Color.White else NflixText,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Context chips (row 2): selected = violet-soft fill + violet border + violet text. */
@Composable
fun ContextChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(labels.size) { i ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .background(
                        if (selected) Color(0xFF2A2450) else NflixSurface,
                        RoundedCornerShape(50)
                    )
                    .then(
                        if (selected) Modifier.border(1.dp, NflixViolet, RoundedCornerShape(50))
                        else Modifier
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    labels[i],
                    color = if (selected) NflixViolet else NflixText,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Family tag chip used in search results (PHIM / DONGHUA / TRUYỆN). */
@Composable
fun FamilyTag(familyLabel: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(NflixSurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            familyLabel.uppercase(),
            color = NflixTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

val NflixCardBackground = NflixSurface
val NflixDivider = NflixBorder
val NflixPageBackground = NflixBackground
