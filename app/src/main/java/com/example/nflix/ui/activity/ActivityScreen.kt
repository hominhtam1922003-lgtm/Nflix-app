package com.example.nflix.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGreen
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FILTER_LABELS = listOf("Tất cả", "Phim", "Donghua", "Truyện")
private val VN_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

private fun dayLabel(epochSec: Long): String {
    val date = Instant.ofEpochSecond(epochSec).atZone(VN_ZONE).toLocalDate()
    val today = LocalDate.now(VN_ZONE)
    return when {
        date == today -> "HÔM NAY"
        date == today.minusDays(1) -> "HÔM QUA"
        date.isAfter(today.minusDays(7)) -> when (date.dayOfWeek.value) {
            1 -> "THỨ 2"
            2 -> "THỨ 3"
            3 -> "THỨ 4"
            4 -> "THỨ 5"
            5 -> "THỨ 6"
            6 -> "THỨ 7"
            else -> "CHỦ NHẬT"
        }
        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}

private fun timeLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(VN_ZONE)
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun matchesFilter(family: Family, filterIndex: Int): Boolean = when (filterIndex) {
    0 -> true
    1 -> family == Family.PHIM
    2 -> family == Family.DONGHUA
    3 -> family == Family.TRUYEN || family == Family.MANHWA
    else -> true
}

@Composable
fun ActivityScreen(
    onOpenTitle: (TitleItem) -> Unit,
    onResume: (TitleItem, UserStateEntity) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember { ActivityViewModel(app) }
    val entries by viewModel.entries.collectAsState()
    var filterIndex by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = entries.filter { matchesFilter(it.item.family, filterIndex) }
    val groups = filtered.groupBy { dayLabel(it.state.lastTouchedEpochSec) }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Hoạt động",
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
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSearch)
                        .padding(2.dp)
                )
                Spacer(Modifier.width(14.dp))
                Box {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "Thêm",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                            .clip(CircleShape)
                            .clickable { showMenu = true }
                            .padding(2.dp)
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(NflixSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Xoá toàn bộ lịch sử", color = Color.White) },
                            onClick = { showMenu = false; confirmClear = true }
                        )
                    }
                }
            }
            FilterChips(
                labels = FILTER_LABELS,
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it }
            )
            Spacer(Modifier.height(6.dp))
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Chưa có hoạt động nào", color = NflixTextMuted, fontSize = 14.sp)
                }
            }
        }

        groups.forEach { (label, groupEntries) ->
            item(key = "h-$label") {
                Text(
                    label,
                    color = NflixTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            items(groupEntries.size, key = { i -> groupEntries[i].item.id }) { i ->
                ActivityRow(
                    entry = groupEntries[i],
                    onClick = { onOpenTitle(groupEntries[i].item) },
                    onResume = { onResume(groupEntries[i].item, groupEntries[i].state) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = NflixSurface,
            title = { Text("Xoá toàn bộ lịch sử?", color = Color.White) },
            text = {
                Text(
                    "Toàn bộ lịch sử xem/đọc sẽ bị xoá. Yêu thích và Xem sau vẫn giữ nguyên.",
                    color = NflixTextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Xoá", color = Color(0xFFE5484D)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Huỷ", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun ActivityRow(
    entry: ActivityEntry,
    onClick: () -> Unit,
    onResume: () -> Unit
) {
    val item = entry.item
    val st = entry.state
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            if (st.finished) {
                Text(
                    "Đã xem xong ${st.lastUnitLabel.lowercase()}",
                    color = NflixGreen,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            } else {
                val pct = (st.progressFraction * 100).toInt()
                val sub = when (item.family) {
                    Family.TRUYEN, Family.MANHWA ->
                        "${st.lastUnitLabel} · trang ${st.pageIndex}/${st.pageCount}"
                    else -> "${st.lastUnitLabel} · đã xem $pct%"
                }
                Text(sub, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF3A3A46))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(st.progressFraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(NflixViolet, RoundedCornerShape(50))
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(timeLabel(st.lastTouchedEpochSec), color = NflixTextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (st.finished) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_green),
                    contentDescription = "Đã xong",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(32.dp)
                        .background(NflixViolet, CircleShape)
                        .clickable(onClick = onResume),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = "Xem tiếp",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
