package com.example.nflix.ui.top

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.FallbackImage
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.QualityBadge
import com.example.nflix.ui.components.VersionBadge
import com.example.nflix.ui.theme.NflixAmber
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixGold
import com.example.nflix.ui.theme.NflixText
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopUiState(
    val familyIndex: Int = 0,
    val period: Int = 0, // 0 = Ngày, 1 = Tuần (chỉ Manhwa có BXH thật theo kỳ)
    val loading: Boolean = true,
    val items: List<TitleItem> = emptyList(),
    val error: String = ""
)

/**
 * Tab "Top" (user chọn 2026-11-01 thay cho tab Tìm kiếm): bảng xếp hạng theo mục.
 * Manhwa có BXH thật từ NetTruyen (Top ngày sort=13 / Top tuần sort=12); các
 * nguồn còn lại không công bố lượt xem nên dùng danh sách thịnh hành của nguồn
 * và ghi nhãn "Đang thịnh hành" — không giả số liệu.
 */
class TopViewModel(private val app: NflixApp) : ViewModel() {
    private val _state = MutableStateFlow(TopUiState())
    val state: StateFlow<TopUiState> = _state.asStateFlow()
    private val cache = mutableMapOf<Pair<Int, Int>, List<TitleItem>>()

    val families = listOf(Family.PHIM, Family.ANIME, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

    init {
        load(0, 0)
    }

    fun selectFamily(index: Int) {
        if (index == _state.value.familyIndex) return
        _state.update { it.copy(familyIndex = index) }
        load(index, _state.value.period)
    }

    fun selectPeriod(period: Int) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        load(_state.value.familyIndex, period)
    }

    private fun load(familyIndex: Int, period: Int) {
        cache[familyIndex to period]?.let { cached ->
            _state.update { it.copy(loading = false, items = cached, error = "") }
            return
        }
        _state.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val items = when (families[familyIndex]) {
                    Family.PHIM -> app.kkPhim.latest(1)
                        .filter { it.family == Family.PHIM }.take(10)
                    Family.ANIME -> app.kkPhim.anime(1).take(10)
                    Family.DONGHUA -> app.hhKungfu.trending().take(10)
                    Family.MANHWA ->
                        if (period == 0) app.netTruyen.hot(1).take(10)
                        else app.netTruyen.topWeek(1).take(10)
                    Family.TRUYEN -> app.truyenQq.hot().take(10)
                }
                cache[familyIndex to period] = items
                // Only apply if the user is still on this family/period.
                if (_state.value.familyIndex == familyIndex && _state.value.period == period) {
                    _state.update { it.copy(loading = false, items = items) }
                }
            } catch (e: Exception) {
                if (_state.value.familyIndex == familyIndex && _state.value.period == period) {
                    _state.update {
                        it.copy(loading = false, error = "Không tải được bảng xếp hạng — kiểm tra mạng rồi thử lại.")
                    }
                }
            }
        }
    }

    fun retry() {
        val s = _state.value
        cache.remove(s.familyIndex to s.period)
        load(s.familyIndex, s.period)
    }
}

@Composable
fun TopScreen(onOpenTitle: (TitleItem) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = androidx.compose.runtime.remember { TopViewModel(app) }
    val state by viewModel.state.collectAsState()
    val family = viewModel.families[state.familyIndex]

    Column(
        modifier = Modifier.fillMaxSize().background(NflixBackground)
    ) {
        Text(
            "Top",
            color = NflixText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp)
        )
        FilterChips(
            labels = viewModel.families.map { it.label },
            selectedIndex = state.familyIndex,
            onSelect = { viewModel.selectFamily(it) }
        )
        Spacer(Modifier.height(8.dp))
        if (family == Family.MANHWA) {
            FilterChips(
                labels = listOf("Top ngày", "Top tuần"),
                selectedIndex = state.period,
                onSelect = { viewModel.selectPeriod(it) }
            )
        } else {
            Text(
                "Đang thịnh hành",
                color = NflixTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NflixViolet)
            }
            state.error.isNotBlank() -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.error, color = NflixTextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Thử lại",
                    color = NflixViolet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { viewModel.retry() }
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    RankRow(rank = index + 1, item = item, onClick = { onOpenTitle(item) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, item: TitleItem, onClick: () -> Unit) {
    val rankColor = when (rank) {
        1 -> NflixGold
        2 -> Color(0xFFC9C9D4)
        3 -> Color(0xFFE8A06A)
        else -> NflixTextMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = rankColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(38.dp)
        )
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 62.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1C26))
        ) {
            FallbackImage(
                primary = item.posterUrl,
                fallback = item.thumbUrl,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = NflixText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            val meta = buildString {
                if (item.latestLabel.isNotBlank()) append(item.latestLabel)
                if (item.year > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(item.year)
                }
                if (item.genres.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(item.genres.take(2).joinToString(", "))
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, color = NflixTextMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (item.quality.isNotBlank()) {
            QualityBadge(item.quality)
            Spacer(Modifier.width(6.dp))
        }
        if (item.versionBadge.isNotBlank()) {
            VersionBadge(item.versionBadge)
        }
    }
}
