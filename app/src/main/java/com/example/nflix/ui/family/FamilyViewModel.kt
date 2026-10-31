package com.example.nflix.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Phim-tab filter (Màn 6 redesign): kind + category + country + year. Empty/0 = all. */
data class PhimFilter(
    val kind: String = "phim-bo",
    val category: String = "",
    val country: String = "",
    val year: Int = 0
) {
    val isDefault: Boolean
        get() = kind == "phim-bo" && category.isEmpty() && country.isEmpty() && year == 0
}

data class FamilyUiState(
    val loading: Boolean = true,
    val contextIndex: Int = 0,
    val posters: List<TitleItem> = emptyList(),
    val rows: List<TitleItem> = emptyList(),
    val rowsPage: Int = 1,
    val rowsHasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val secondPosters: List<TitleItem> = emptyList(),
    val hasNew: Boolean = false,
    val newEntries: List<Pair<TitleItem, Int>> = emptyList(),
    val scheduleWeek: List<Pair<String, List<TitleItem>>> = emptyList(),
    val phimFilter: PhimFilter = PhimFilter()
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FamilyViewModel(
    private val app: NflixApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    companion object {
        /** Phim kinds — "Hoạt hình" removed per user order 2026-11-01 (Anime is its own tab now). */
        val PHIM_KINDS = listOf(
            "Phim bộ" to "phim-bo",
            "Phim lẻ" to "phim-le",
            "Chiếu rạp" to "chieu-rap"
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
            Family.ANIME -> emptyList()
            Family.DONGHUA -> DAY_LABELS
            Family.MANHWA -> MANHWA_GENRES.map { it.first }
            Family.TRUYEN -> TRUYEN_GENRES.map { it.first }
        }
    }

    init {
        when (family) {
            Family.PHIM -> applyPhimFilter(PhimFilter())
            Family.ANIME -> loadAnime()
            else -> selectContext(0)
        }
        if (family == Family.DONGHUA) {
            loadDonghuaTrending()
            loadDonghuaRows()
        }
        collectFollowingNew()
    }

    /** Followed titles with unseen new episodes/chapters — drives the header bell. */
    private fun collectFollowingNew() {
        viewModelScope.launch {
            app.database.userStateDao().following().flatMapLatest { states ->
                val withNew = states.filter { it.newCount > 0 }
                if (withNew.isEmpty()) flowOf(emptyList())
                else app.database.titleDao().observeByIds(withNew.map { it.titleId }).map { titles ->
                    val byId = titles.associateBy { it.id }
                    withNew.mapNotNull { st ->
                        byId[st.titleId]?.let { it.toItem() to st.newCount }
                    }
                }
            }.collect { entries ->
                _state.update { it.copy(hasNew = entries.isNotEmpty(), newEntries = entries) }
            }
        }
    }

    /** Whole-week Donghua broadcast schedule (7 day groups), loaded on demand. */
    fun loadScheduleWeek() {
        if (family != Family.DONGHUA || _state.value.scheduleWeek.isNotEmpty()) return
        viewModelScope.launch {
            val groups = DAY_KEYS.mapIndexed { i, key ->
                val items = try {
                    app.hhKungfu.schedule(key)
                } catch (e: Exception) {
                    emptyList()
                }
                DAY_LABELS[i + 1] to items
            }
            app.database.titleDao().upsertAll(
                groups.flatMap { it.second }.map { it.toEntity() }
            )
            _state.update { it.copy(scheduleWeek = groups) }
        }
    }

    fun selectContext(index: Int) {
        _state.update { it.copy(contextIndex = index, loading = true) }
        viewModelScope.launch {
            try {
                val (posters, rows) = when (family) {
                    Family.PHIM -> {
                        val kind = PHIM_KINDS.getOrNull(index)?.second ?: "phim-bo"
                        val list = if (kind == "chieu-rap") app.kkPhim.cinema()
                        else app.kkPhim.listByKind(kind)
                        list to list
                    }
                    Family.ANIME -> {
                        val list = app.kkPhim.anime(1)
                        list to list
                    }
                    Family.DONGHUA -> {
                        // Day chips only change the schedule shelf; the
                        // "Mới cập nhật" rows are loaded separately.
                        val list = app.hhKungfu.schedule(dayKey(index))
                        list to _state.value.rows
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
                        if (slug.isEmpty()) app.truyenQq.hot() to app.truyenQq.latest()
                        else {
                            val list = app.truyenQq.byGenre(slug)
                            list to list
                        }
                    }
                }
                app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                _state.update {
                    // Donghua rows are owned by loadDonghuaRows/loadMoreRows —
                    // a day-chip switch must not clobber them with a stale copy.
                    if (family == Family.DONGHUA) {
                        it.copy(loading = false, posters = posters)
                    } else {
                        it.copy(
                            loading = false,
                            posters = posters,
                            rows = rows,
                            rowsPage = 1,
                            rowsHasMore = rowsHasMoreFor(index)
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Whether the current rows source has further pages to append. */
    private fun rowsHasMoreFor(contextIndex: Int): Boolean = when (family) {
        Family.TRUYEN -> TRUYEN_GENRES.getOrNull(contextIndex)?.second?.isNotEmpty() == true
        else -> true
    }

    /** One page of the "Mới cập nhật" rows for the current context/filter. */
    private suspend fun fetchRows(page: Int): List<TitleItem> {
        val s = _state.value
        return when (family) {
            Family.PHIM -> {
                val f = s.phimFilter
                if (f.isDefault) app.kkPhim.latest(page)
                else fetchPhim(f, page)
            }
            Family.ANIME -> app.kkPhim.anime(page)
            Family.DONGHUA -> app.hhKungfu.latest(page)
            Family.MANHWA -> {
                val idx = s.contextIndex
                if (idx == 0) app.netTruyen.latest(page)
                else app.netTruyen.byGenre(MANHWA_GENRES[idx].second, page)
            }
            Family.TRUYEN -> {
                val slug = TRUYEN_GENRES.getOrNull(s.contextIndex)?.second.orEmpty()
                if (slug.isEmpty()) emptyList() else app.truyenQq.byGenre(slug, page)
            }
        }
    }

    /** Infinite scroll for the rows list: appends the next page when near the end. */
    fun loadMoreRows() {
        val s = _state.value
        if (s.loadingMore || !s.rowsHasMore || s.rows.isEmpty()) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val nextPage = s.rowsPage + 1
            val more = try {
                fetchRows(nextPage)
            } catch (e: Exception) {
                emptyList()
            }
            if (more.isNotEmpty()) {
                app.database.titleDao().upsertAll(more.map { it.toEntity() })
            }
            _state.update {
                it.copy(
                    loadingMore = false,
                    rows = (it.rows + more).distinctBy { d -> d.id },
                    rowsPage = if (more.isNotEmpty()) nextPage else it.rowsPage,
                    rowsHasMore = more.isNotEmpty()
                )
            }
        }
    }

    private suspend fun fetchPhim(filter: PhimFilter, page: Int): List<TitleItem> =
        if (filter.kind == "chieu-rap" && filter.category.isEmpty() &&
            filter.country.isEmpty() && filter.year == 0
        ) {
            app.kkPhim.cinema(page)
        } else {
            app.kkPhim.listFiltered(
                kind = filter.kind,
                category = filter.category,
                country = filter.country,
                year = filter.year,
                page = page
            )
        }

    /** Phim tab: apply a new filter (from the "Thể loại" sheet) and reload both shelves. */
    fun applyPhimFilter(filter: PhimFilter) {
        _state.update { it.copy(phimFilter = filter, loading = true, rowsPage = 1) }
        viewModelScope.launch {
            try {
                val posters = fetchPhim(filter, 1)
                val rows = if (filter.isDefault) app.kkPhim.latest(1) else posters
                app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                _state.update {
                    it.copy(
                        loading = false,
                        posters = posters,
                        rows = rows,
                        rowsPage = 1,
                        rowsHasMore = true
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun loadAnime() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val list = app.kkPhim.anime(1)
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    it.copy(loading = false, posters = list, rows = list, rowsPage = 1, rowsHasMore = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Donghua "Mới cập nhật" rows — independent of the day-chip schedule shelf. */
    private fun loadDonghuaRows() {
        viewModelScope.launch {
            try {
                val rows = app.hhKungfu.latest(1)
                app.database.titleDao().upsertAll(rows.map { it.toEntity() })
                _state.update { it.copy(rows = rows, rowsPage = 1, rowsHasMore = true) }
            } catch (e: Exception) { /* shelf stays hidden */ }
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
