package com.example.nflix.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class FamilyUiState(
    val loading: Boolean = true,
    val contextIndex: Int = 0,
    val posters: List<TitleItem> = emptyList(),
    val rows: List<TitleItem> = emptyList(),
    val secondPosters: List<TitleItem> = emptyList()
)

class FamilyViewModel(
    private val app: NflixApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    companion object {
        val PHIM_KINDS = listOf(
            "Phim bộ" to "phim-bo",
            "Phim lẻ" to "phim-le",
            "Chiếu rạp" to "chieu-rap",
            "Hoạt hình" to "hoat-hinh"
        )
        /** label to NetTruyen genre slug; first entry = the whole Manhwa category. */
        val MANHWA_GENRES = listOf(
            "Tất cả" to "manhwa-11400",
            "Action" to "action-95",
            "Romance" to "romance-121",
            "Fantasy" to "fantasy-105",
            "Tu tiên" to "tu-tien",
            "Ngôn tình" to "ngon-tinh",
            "Chuyển sinh" to "chuyen-sinh-2130",
            "Xuyên không" to "xuyen-khong-205",
            "Cổ đại" to "co-dai-207",
            "Martial Arts" to "martial-arts",
            "Adventure" to "adventure",
            "Comedy" to "comedy-99",
            "Drama" to "drama-103"
        )
        /** label to TruyenQQ genre slug (slugs verified live 2026-11-01). */
        val TRUYEN_GENRES = listOf(
            "Tất cả" to "",
            "Tiên hiệp" to "tien-hiep",
            "Kiếm hiệp" to "kiem-hiep",
            "Ngôn tình" to "ngon-tinh",
            "Huyền huyễn" to "huyen-huyen",
            "Học đường" to "hoc-duong",
            "Hành động" to "hanh-dong",
            "Xuyên không" to "xuyen-khong",
            "Trọng sinh" to "trong-sinh",
            "Quan trường" to "quan-truong"
        )
        val DAY_LABELS = listOf("Hôm nay", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật")
        private val DAY_KEYS = listOf("thu-2", "thu-3", "thu-4", "thu-5", "thu-6", "thu-7", "chu-nhat")

        /** date param for a day-chip index: 0 = today (VN), 1..7 = Thứ 2..Chủ nhật. */
        fun dayKey(index: Int): String {
            if (index == 0) {
                val dow = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).dayOfWeek.value // 1=Mon..7=Sun
                return DAY_KEYS[dow - 1]
            }
            return DAY_KEYS[index - 1]
        }

        fun contextLabels(family: Family): List<String> = when (family) {
            Family.PHIM -> PHIM_KINDS.map { it.first }
            Family.DONGHUA -> DAY_LABELS
            Family.MANHWA -> MANHWA_GENRES.map { it.first }
            Family.TRUYEN -> TRUYEN_GENRES.map { it.first }
        }
    }

    init {
        selectContext(0)
        if (family == Family.DONGHUA) loadDonghuaTrending()
    }

    fun selectContext(index: Int) {
        _state.update { it.copy(contextIndex = index, loading = true) }
        viewModelScope.launch {
            try {
                val (posters, rows) = when (family) {
                    Family.PHIM -> {
                        val kind = PHIM_KINDS[index].second
                        val list = if (kind == "chieu-rap") app.kkPhim.cinema()
                        else app.kkPhim.listByKind(kind)
                        list to list
                    }
                    Family.DONGHUA -> {
                        val list = app.hhKungfu.schedule(dayKey(index))
                        list to emptyList()
                    }
                    Family.MANHWA -> {
                        val slug = MANHWA_GENRES[index].second
                        if (index == 0) app.netTruyen.hot() to app.netTruyen.latest()
                        else {
                            val list = app.netTruyen.byGenre(slug)
                            list to list
                        }
                    }
                    Family.TRUYEN -> {
                        val slug = TRUYEN_GENRES[index].second
                        val list = if (slug.isEmpty()) app.truyenQq.latest() else app.truyenQq.byGenre(slug)
                        list to list
                    }
                }
                app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                _state.update { it.copy(loading = false, posters = posters, rows = rows) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun loadDonghuaTrending() {
        viewModelScope.launch {
            try {
                val trending = app.hhKungfu.trending()
                app.database.titleDao().upsertAll(trending.map { it.toEntity() })
                _state.update { it.copy(secondPosters = trending) }
            } catch (e: Exception) { /* shelf stays hidden */ }
        }
    }
}
