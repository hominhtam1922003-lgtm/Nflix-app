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
    val scheduleWeek: List<Pair<String, List<TitleItem>>> = emptyList(),
    val phimFilter: PhimFilter = PhimFilter(),
    /** Anime filter: kind/country are fixed (hoat-hinh + Nhật Bản); category/year vary. */
    val animeFilter: PhimFilter = PhimFilter(kind = "hoat-hinh", country = "nhat-ban"),
    /** Donghua genre menu (label to slug), parsed from the source's home page nav. */
    val donghuaGenres: List<Pair<String, String>> = emptyList(),
    /** Donghua genre filter: non-null = the tab is browsing that category. */
    val donghuaGenre: Pair<String, String>? = null,
    /** A4: "Sắp chiếu" shelf of the Phim tab (KKPhim phim-sap-chieu list). */
    val comingSoon: List<TitleItem> = emptyList(),
    /** A5: "Trọn bộ" (completed-only) filter of the Manhwa tab (NetTruyen status=2). */
    val manhwaCompleted: Boolean = false,
    /** A5: "Trọn bộ" filter of the Truyện tab (TruyenQQ /truyen-full, standalone list). */
    val truyenCompleted: Boolean = false,
    /** A5: Donghua browse mode for the /hoan-thanh listing (replaces the day layout). */
    val donghuaCompleted: Boolean = false,
    /** A8: "Mới tinh" shelf (brand-new series) of the Manhwa / Truyện tabs. */
    val freshShelf: List<TitleItem> = emptyList(),
    /** C3: Truyện source transparency — host actually serving + last load ms. */
    val truyenHostLabel: String = "",
    val truyenOnFallback: Boolean = false,
    val truyenLastLoadMs: Long = -1L,
    /** C3: the Truyện tab's main load FAILED (distinct from genuinely empty). */
    val truyenError: Boolean = false,
    /** C3: the Truyện "Mới tinh" shelf load failed. */
    val truyenFreshError: Boolean = false,
    /** C3: appending the next Truyện rows page failed (retry keeps hasMore). */
    val truyenRowsError: Boolean = false
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FamilyViewModel(
    private val app: NflixApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    /** B2: ids of everything the user tracks — powers the schedule filter. */
    private val _trackedIds = MutableStateFlow<Set<String>>(emptySet())
    val trackedIds: StateFlow<Set<String>> = _trackedIds.asStateFlow()

    init {
        viewModelScope.launch {
            app.database.userStateDao().tracked().collect { states ->
                _trackedIds.value = states.map { it.titleId }.toSet()
            }
        }
    }

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
            Family.ANIME -> applyAnimeFilter(PhimFilter(kind = "hoat-hinh", country = "nhat-ban"))
            else -> selectContext(0)
        }
        if (family == Family.DONGHUA) {
            loadDonghuaTrending()
            loadDonghuaRows()
            loadDonghuaGenres()
        }
        if (family == Family.PHIM) {
            loadComingSoon()
        }
        if (family == Family.MANHWA || family == Family.TRUYEN) {
            loadFreshShelf()
        }
    }

    /** A8: "Mới tinh" shelf — series that just launched, not new chapters. */
    private fun loadFreshShelf() {
        viewModelScope.launch {
            try {
                val list = when (family) {
                    Family.MANHWA -> app.netTruyen.newest(1)
                    Family.TRUYEN -> app.truyenQq.newest(1)
                    else -> emptyList()
                }
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    if (family == Family.TRUYEN) {
                        it.copy(freshShelf = list, truyenFreshError = false)
                            .withTruyenStatus()
                    } else {
                        it.copy(freshShelf = list)
                    }
                }
            } catch (e: Exception) {
                // C3: a failed Truyện shelf is an ERROR, not an empty shelf.
                if (family == Family.TRUYEN) {
                    _state.update { it.copy(truyenFreshError = true).withTruyenStatus() }
                }
            }
        }
    }

    /** C3: snapshot the Truyện source's live host state into the UI state. */
    private fun FamilyUiState.withTruyenStatus(): FamilyUiState = copy(
        truyenHostLabel = app.truyenQq.statusHostLabel(),
        truyenOnFallback = app.truyenQq.statusOnFallback(),
        truyenLastLoadMs = app.truyenQq.statusLastLoadMs()
    )

    /** C3: retry the Truyện tab's failed loads (main list + "Mới tinh" shelf). */
    fun retryTruyen() {
        if (family != Family.TRUYEN) return
        _state.update { it.copy(truyenError = false, truyenFreshError = false) }
        selectContext(_state.value.contextIndex)
        loadFreshShelf()
    }

    /** C3: retry appending the next Truyện rows page after a load-more error. */
    fun retryTruyenRows() {
        if (family != Family.TRUYEN) return
        _state.update { it.copy(truyenRowsError = false) }
        loadMoreRows()
    }

    /** A4: the "Sắp chiếu" shelf — page 1 of KKPhim's coming-soon list is enough for a shelf. */
    private fun loadComingSoon() {
        viewModelScope.launch {
            try {
                val list = app.kkPhim.comingSoon(1)
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update { it.copy(comingSoon = list) }
            } catch (e: Exception) { /* shelf stays hidden */ }
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
        // Picking a Donghua day chip exits genre/completed browse mode; the
        // rows then belong to loadDonghuaRows again.
        val exitedBrowse = family == Family.DONGHUA &&
            (_state.value.donghuaGenre != null || _state.value.donghuaCompleted)
        _state.update {
            it.copy(
                contextIndex = index,
                loading = true,
                donghuaGenre = if (family == Family.DONGHUA) null else it.donghuaGenre,
                donghuaCompleted = if (family == Family.DONGHUA) false else it.donghuaCompleted
            )
        }
        if (exitedBrowse) loadDonghuaRows()
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
                        val list = fetchAnime(_state.value.animeFilter, 1)
                        list to list
                    }
                    Family.DONGHUA -> {
                        // Day chips only change the schedule shelf; the
                        // "Mới cập nhật" rows are loaded separately.
                        val list = app.hhKungfu.schedule(dayKey(index))
                        list to _state.value.rows
                    }
                    Family.MANHWA -> {
                        if (_state.value.manhwaCompleted) {
                            val list = fetchManhwa(index, completed = true, page = 1)
                            list to list
                        } else if (index == 0) {
                            app.netTruyen.hot() to app.netTruyen.latest()
                        } else {
                            val list = app.netTruyen.byGenre(MANHWA_GENRES[index].second)
                            list to list
                        }
                    }
                    Family.TRUYEN -> {
                        if (_state.value.truyenCompleted) {
                            // /truyen-full is one standalone list for the tab.
                            val list = app.truyenQq.completed(1)
                            list to list
                        } else {
                            val slug = TRUYEN_GENRES[index].second
                            if (slug.isEmpty()) app.truyenQq.home()
                            else {
                                val list = app.truyenQq.byGenre(slug)
                                list to list
                            }
                        }
                    }
                }
                app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                _state.update {
                    // Donghua rows are owned by loadDonghuaRows/loadMoreRows —
                    // a day-chip switch must not clobber them with a stale copy.
                    if (family == Family.DONGHUA) {
                        it.copy(loading = false, posters = posters)
                    } else if (family == Family.TRUYEN) {
                        // C3: a loaded Truyện tab clears its error state and
                        // publishes which host served it + how long it took.
                        it.copy(
                            loading = false,
                            posters = posters,
                            rows = rows,
                            rowsPage = 1,
                            rowsHasMore = rowsHasMoreFor(index),
                            truyenError = false,
                            truyenRowsError = false
                        ).withTruyenStatus()
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
                _state.update {
                    // C3: for Truyện an exception means "load failed", never
                    // silently an empty tab (K4).
                    if (family == Family.TRUYEN) {
                        it.copy(loading = false, truyenError = true).withTruyenStatus()
                    } else {
                        it.copy(loading = false)
                    }
                }
            }
        }
    }

    /** Whether the current rows source has further pages to append. */
    private fun rowsHasMoreFor(contextIndex: Int): Boolean = when (family) {
        Family.TRUYEN -> _state.value.truyenCompleted ||
            TRUYEN_GENRES.getOrNull(contextIndex)?.second?.isNotEmpty() == true
        else -> true
    }

    /** One Manhwa page with the "Trọn bộ" flag applied (status=2 overall or per genre). */
    private suspend fun fetchManhwa(idx: Int, completed: Boolean, page: Int): List<TitleItem> {
        val slug = MANHWA_GENRES[idx].second
        return when {
            completed && idx == 0 -> app.netTruyen.completed(page)
            completed -> app.netTruyen.completedByGenre(slug, page)
            idx == 0 -> app.netTruyen.hot(page)
            else -> app.netTruyen.byGenre(slug, page)
        }
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
            Family.ANIME -> fetchAnime(s.animeFilter, page)
            Family.DONGHUA -> {
                val genre = s.donghuaGenre
                if (genre != null) app.hhKungfu.byCategory(genre.second, page)
                else if (s.donghuaCompleted) app.hhKungfu.completed(page)
                else app.hhKungfu.latest(page)
            }
            Family.MANHWA -> {
                val idx = s.contextIndex
                if (s.manhwaCompleted) fetchManhwa(idx, completed = true, page = page)
                else if (idx == 0) app.netTruyen.latest(page)
                else app.netTruyen.byGenre(MANHWA_GENRES[idx].second, page)
            }
            Family.TRUYEN -> {
                if (s.truyenCompleted) {
                    app.truyenQq.completed(page)
                } else {
                    val slug = TRUYEN_GENRES.getOrNull(s.contextIndex)?.second.orEmpty()
                    if (slug.isEmpty()) emptyList() else app.truyenQq.byGenre(slug, page)
                }
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
            var failed = false
            val more = try {
                fetchRows(nextPage)
            } catch (e: Exception) {
                failed = true
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
                    // C3: a failed page fetch is NOT the end of the list —
                    // for Truyện keep hasMore and raise the rows error state
                    // instead of swallowing the failure as "no more rows".
                    rowsHasMore = if (failed && family == Family.TRUYEN) {
                        it.rowsHasMore
                    } else {
                        more.isNotEmpty()
                    },
                    truyenRowsError = if (family == Family.TRUYEN) failed else it.truyenRowsError
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

    /** One page of Anime (Nhật Bản only): plain feed when unfiltered, else the filtered list. */
    private suspend fun fetchAnime(filter: PhimFilter, page: Int): List<TitleItem> =
        if (filter.category.isEmpty() && filter.year == 0) {
            app.kkPhim.anime(page)
        } else {
            app.kkPhim.listFiltered(
                kind = "hoat-hinh",
                category = filter.category,
                country = "nhat-ban",
                year = filter.year,
                page = page
            )
        }

    /** Anime tab: apply a new filter (from the "Thể loại" sheet) and reload. */
    fun applyAnimeFilter(filter: PhimFilter) {
        _state.update { it.copy(animeFilter = filter, loading = true, rowsPage = 1) }
        viewModelScope.launch {
            try {
                val list = fetchAnime(filter, 1)
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
                _state.update {
                    // Browse modes (genre / completed) own the rows — do not clobber them.
                    if (it.donghuaGenre == null && !it.donghuaCompleted) {
                        it.copy(rows = rows, rowsPage = 1, rowsHasMore = true)
                    } else {
                        it
                    }
                }
            } catch (e: Exception) { /* shelf stays hidden */ }
        }
    }

    /** Donghua genre menu for the "Thể loại" sheet, from the source's home page nav. */
    private fun loadDonghuaGenres() {
        viewModelScope.launch {
            try {
                val genres = app.hhKungfu.genreLinks()
                if (genres.isNotEmpty()) {
                    _state.update { it.copy(donghuaGenres = genres) }
                }
            } catch (e: Exception) { /* sheet keeps only "Tất cả" */ }
        }
    }

    /**
     * Donghua "Thể loại" sheet result: a genre switches the tab to browsing
     * that category (posters + paged rows); null restores the default layout
     * (day-chip schedule shelf + latest rows).
     */
    fun applyDonghuaGenre(genre: Pair<String, String>?) {
        if (family != Family.DONGHUA) return
        if (genre == null) {
            _state.update { it.copy(donghuaGenre = null) }
            selectContext(_state.value.contextIndex)
            loadDonghuaRows()
            return
        }
        _state.update {
            it.copy(donghuaGenre = genre, donghuaCompleted = false, loading = true, rowsPage = 1)
        }
        viewModelScope.launch {
            try {
                val list = app.hhKungfu.byCategory(genre.second, 1)
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    it.copy(
                        loading = false,
                        posters = list,
                        rows = list,
                        rowsPage = 1,
                        rowsHasMore = list.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /**
     * A5 "Trọn bộ" toggle from the filter sheets. Manhwa/Truyện: a flag over
     * the current genre context (NetTruyen status=2 per genre; TruyenQQ's
     * /truyen-full is one standalone list covering the whole tab). Donghua:
     * a browse mode that replaces the day-chip layout with /hoan-thanh.
     */
    fun setCompletedFilter(on: Boolean) {
        when (family) {
            Family.MANHWA -> {
                if (_state.value.manhwaCompleted == on) return
                _state.update { it.copy(manhwaCompleted = on) }
                selectContext(_state.value.contextIndex)
            }
            Family.TRUYEN -> {
                if (_state.value.truyenCompleted == on) return
                _state.update { it.copy(truyenCompleted = on) }
                selectContext(_state.value.contextIndex)
            }
            Family.DONGHUA -> setDonghuaCompleted(on)
            else -> Unit
        }
    }

    private fun setDonghuaCompleted(on: Boolean) {
        if (_state.value.donghuaCompleted == on) return
        if (!on) {
            _state.update { it.copy(donghuaCompleted = false) }
            selectContext(_state.value.contextIndex)
            loadDonghuaRows()
            return
        }
        _state.update {
            it.copy(donghuaCompleted = true, donghuaGenre = null, loading = true, rowsPage = 1)
        }
        viewModelScope.launch {
            try {
                val list = app.hhKungfu.completed(1)
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    it.copy(
                        loading = false,
                        posters = list,
                        rows = list,
                        rowsPage = 1,
                        rowsHasMore = list.isNotEmpty()
                    )
                }
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
