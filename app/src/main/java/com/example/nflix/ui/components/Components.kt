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

/**
 * Image with a fallback chain: some KKPhim poster files fail
 * to load on-device while the sibling thumb works — try primary, then the
 * fallback URL, so a card never sits blank when either image is alive.
 */
@Composable
fun FallbackImage(
    primary: String,
    fallback: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var model by androidx.compose.runtime.remember(primary, fallback) {
        androidx.compose.runtime.mutableStateOf(primary.ifBlank { fallback })
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onError = {
            if (model != fallback && fallback.isNotBlank()) model = fallback
        }
    )
}

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

/** HOT pill on posters (design Màn 8/9): dark translucent bg, white text, top-start. */
@Composable
fun PosterHotBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xC20D0D13), BadgeShape)
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
            FallbackImage(
                primary = item.posterUrl,
                fallback = item.thumbUrl,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize()
            )
            if (item.isHot) {
                PosterHotBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            } else {
                QualityBadge(item.quality, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            VersionBadge(item.versionBadge, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (progressFraction != null) {
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = NflixViolet,
                trackColor = NflixSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = NflixText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val sub = progressLabel.ifBlank { item.latestLabel }
        if (sub.isNotBlank()) {
            Text(sub, color = NflixTextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    cardWidth: Int = 112,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (items.isEmpty()) return
    Column(modifier = modifier) {
        SectionHeader(title, actionLabel = actionLabel, onAction = onAction)
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
            FallbackImage(
                primary = item.posterUrl,
                fallback = item.thumbUrl,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    color = NflixText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.isNew) {
                    Spacer(Modifier.width(6.dp))
                    NewBadge()
                }
                if (item.isHot) {
                    Spacer(Modifier.width(6.dp))
                    HotBadge()
                }
            }
            if (item.latestLabel.isNotBlank()) {
                Text(item.latestLabel, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        val time = item.updatedLabel.ifBlank { TimeText.relative(item.updatedEpochSec) }
        if (newCount > 0 || time.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (newCount > 0) PlusPill(newCount, unit)
                if (time.isNotBlank()) {
                    Text(time, color = NflixTextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
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
