package com.example.nflix.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.ManhwaIndex
import com.example.nflix.data.ManhwaIndexEntry
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.STATUS_COMPLETED
import com.example.nflix.data.model.STATUS_ONGOING
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.model.matchesStatus
import com.example.nflix.data.matchesVersion
import com.example.nflix.data.source.NetTruyenSource
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Phim-tab filter: kind + category + country + year + status + version. Empty/0 = all. */
data class PhimFilter(
    val kind: String = "phim-bo",
    val category: String = "",
    val country: String = "",
    val year: Int = 0,
    val status: Int = 0,
    /** V2 (A1): PHIÊN BẢN key (VersionFilter) — filtered LOCALLY. */
    val version: String = ""
) {
    val isDefault: Boolean
        get() = kind == "phim-bo" && category.isEmpty() && country.isEmpty() &&
            year == 0 && status == 0 && version.isEmpty()
}

/**
 * Anime-tab filter (1.2.0, Khối B): the tab reads Cobe's animation pool
 * and filters LOCALLY — category = a Cobe category slug really present
 * in the loaded pool, year = publish_year, status from episode counts.
 */
data class AnimeFilter(
    val category: String = "",
    val year: Int = 0,
    val status: Int = 0,
    /** V2 (A1): PHIÊN BẢN key (VersionFilter) — filtered LOCALLY on the gated pool. */
    val version: String = ""
) {
    val isDefault: Boolean
        get() = category.isEmpty() && year == 0 && status == 0 && version.isEmpty()
}

/**
 * Manhwa-tab filter (1.2.0, Khối C): genre/audience are canonical
 * TruyenQQ slugs resolved over the local manhwa-shelf index; a
 * status-only filter still rides NetTruyen's server-side status param.
 */
data class ManhwaFilter(
    val genre: String? = null,
    val genreLabel: String = "",
    val audience: String? = null,
    val audienceLabel: String = "",
    val status: Int = 0
) {
    val isDefault: Boolean
        get() = genre == null && audience == null && status == 0
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
    /** Anime filter over the Cobe pool (1.2.0). */
    val animeFilter: AnimeFilter = AnimeFilter(),
    /** (label to slug) categories really present in the loaded Anime pool. */
    val animeCategories: List<Pair<String, String>> = emptyList(),
    /** Years really present in the loaded Anime pool, newest first. */
    val animeYears: List<Int> = emptyList(),
    /** Manhwa sheet filter (genre / audience / status). */
    val manhwaFilter: ManhwaFilter = ManhwaFilter(),
    /** The local manhwa index is available (cache or freshly built). */
    val manhwaIndexReady: Boolean = false,
    /** The manhwa index is being (re)built in the background. */
    val manhwaIndexBuilding: Boolean = false,
    /** Truyện status filter: 0 all / 1 Đang ra tiếp / 2 Trọn bộ. */
    val truyenStatus: Int = 0,
    /** Donghua genre menu (label to slug), parsed from the source's home page nav. */
    val donghuaGenres: List<Pair<String, String>> = emptyList(),
    /** Donghua genre filter: non-null = the tab is browsing that category. */
    val donghuaGenre: Pair<String, String>? = null,
    /** Donghua status filter: 0 all / 1 Đang ra tiếp / 2 Trọn bộ. */
    val donghuaStatus: Int = 0,
    /** A4: "Sắp chiếu" shelf of the Phim tab (KKPhim phim-sap-chieu list). */
    val comingSoon: List<TitleItem> = emptyList(),
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
    val truyenRowsError: Boolean = false,
    /**
     * 1.3.2: the Truyện shelves are currently served by the NetTruyen
     * FALLBACK (both TruyenQQ hosts failed together). The status line
     * says so out loud — fallback content is never passed off as
     * TruyenQQ content.
     */
    val truyenFallbackSource: Boolean = false,
    /**
     * V2 (0.4): the tab's main load FAILED for the four families that
     * used to swallow errors (Phim/Anime/Donghua/Manhwa). FamilyScreen
     * shows the frame-6 error block + Thử lại while this is set and the
     * tab has nothing to show.
     */
    val familyError: Boolean = false,
    /**
     * V2 (0.4): Anime only — the country pools are still harvesting, so
     * the tab is classifying; the screen explains the gradual fill-in
     * instead of looking broken/empty.
     */
    val animeClassifying: Boolean = false
)

class FamilyViewModel(
    private val app: NflixApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    /** B2: ids of everything the user tracks — powers the schedule filter. */
    private val _trackedIds = MutableStateFlow<Set<String>>(emptySet())
    val trackedIds: StateFlow<Set<String>> = _trackedIds.asStateFlow()

    // --- Anime pool accumulation (Khối B) ---
    private val animePool = mutableListOf<TitleItem>()
    private var animePoolNextPage = 1
    private var animePoolTotalPages = Int.MAX_VALUE
    private var animePoolStarted = false
    private var animeEnrichRunning = false
    /** V2 (0.4): the pool's first page fetch failed (nothing to show). */
    private var animePoolFailed = false

    // --- Manhwa local index (Khối C) ---
    private var manhwaIndex: List<ManhwaIndexEntry>? = null
    private var manhwaWindow = MANHWA_WINDOW_STEP

    // --- Lazily harvested completed-slug sets (status filters) ---
    private var donghuaCompletedSlugs: Set<String>? = null
    private var truyenCompletedSlugs: Set<String>? = null

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

        /**
         * Truyện genres (label to TruyenQQ slug) — the VERIFIED menu map
         * of GENRE-DATA-DUONG-MICH.md. The old chips tien-hiep /
         * kiem-hiep / quan-truong were FAKE (0-title listings whose
         * "results" were the page's popular carousel) and are gone.
         */
        val TRUYEN_GENRES = listOf(
            "Tất cả" to "",
            "Hành động" to "action",
            "Ngôn tình" to "ngon-tinh",
            "Huyền huyễn" to "huyen-huyen",
            "Xuyên không" to "xuyen-khong",
            "Trọng sinh" to "trong-sinh",
            "Chuyển sinh" to "chuyen-sinh",
            "Cổ đại" to "co-dai",
            "Fantasy" to "fantasy",
            "Drama" to "drama",
            "Romance" to "romance",
            "Phiêu lưu" to "adventure",
            "Hài hước" to "comedy",
            "Kinh dị" to "horror",
            "Bí ẩn" to "mystery",
            "Học đường" to "school-life",
            "Võ thuật" to "martial-arts",
            "Siêu nhiên" to "supernatural",
            "Tâm lý" to "psychological",
            "Trinh thám" to "detective",
            "Hậu cung" to "harem",
            "Dị giới" to "isekai",
            "Lịch sử" to "historical",
            "Bi kịch" to "tragedy",
            "Đời thường" to "slice-of-life",
            "Thể thao" to "sports",
            "Phép thuật" to "magic",
            "Ẩm thực" to "cooking",
            "Mecha" to "mecha",
            "Hoán đổi giới tính" to "gender-bender",
            "Truyện ngắn" to "one-shot",
            "Ác quỷ" to "demons",
            "Khoa học viễn tưởng" to "sci-fi",
            "Seinen" to "seinen",
            "Shounen" to "shounen",
            "Shoujo" to "shoujo",
            "Josei" to "josei"
        )
        val DAY_LABELS = listOf("Hôm nay", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật")
        private val DAY_KEYS = listOf("thu-2", "thu-3", "thu-4", "thu-5", "thu-6", "thu-7", "chu-nhat")

        private const val MANHWA_WINDOW_STEP = 40

        /** V2 (A1): raw source pages scanned per version-filter round. */
        private const val PHIM_VERSION_SCAN_PAGES = 3

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
            else -> emptyList()
        }
    }

    init {
        when (family) {
            Family.PHIM -> applyPhimFilter(PhimFilter())
            Family.ANIME -> startAnimePool()
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
        if (family == Family.MANHWA) {
            loadFreshShelf()
            loadManhwaIndex()
        }
        if (family == Family.TRUYEN) {
            loadFreshShelf()
        }
    }

    /**
     * 1.3.2: the Truyện home shelves (hot + latest from ONE home fetch)
     * with the NetTruyen fallback. The two TruyenQQ hosts are a single
     * operation — when they fail together the tab used to die outright
     * (user report 2026-11-03). NetTruyen's whole-site lists then serve
     * the same shelves: Top ngày as the hot shelf, latest updates as
     * the rows. Items are re-homed to TRUYEN but keep sourceId
     * NETTRUYEN, so Detail/Reader route to NetTruyen by sourceId.
     * Returns (hot, latest, servedByFallback). Throws only when the
     * fallback fails too — the caller then raises the real error state.
     */
    private suspend fun truyenHomeShelves(): Triple<List<TitleItem>, List<TitleItem>, Boolean> =
        try {
            val (hot, latest) = app.truyenQq.home()
            Triple(hot, latest, false)
        } catch (e: Exception) {
            val hot = app.netTruyen.siteTopDay(1)
            val latest = app.netTruyen.siteLatest(1)
            Triple(hot, latest, true)
        }

    /** 1.3.2: "Mới tinh" with the same NetTruyen fallback (sort=15, whole site). */
    private suspend fun truyenNewestShelf(): Pair<List<TitleItem>, Boolean> =
        try {
            app.truyenQq.newest(1) to false
        } catch (e: Exception) {
            app.netTruyen.siteNewest(1) to true
        }

    /** A8: "Mới tinh" shelf — series that just launched, not new chapters. */
    private fun loadFreshShelf() {
        viewModelScope.launch {
            try {
                var freshFallback = false
                val list = when (family) {
                    Family.MANHWA -> app.netTruyen.newest(1)
                    Family.TRUYEN -> {
                        val (items, fb) = truyenNewestShelf()
                        freshFallback = fb
                        items
                    }
                    else -> emptyList()
                }
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    if (family == Family.TRUYEN) {
                        it.copy(
                            freshShelf = list,
                            truyenFreshError = false,
                            truyenFallbackSource = it.truyenFallbackSource || freshFallback
                        ).withTruyenStatus()
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
        _state.update {
            it.copy(truyenError = false, truyenFreshError = false, truyenFallbackSource = false)
        }
        selectContext(_state.value.contextIndex)
        loadFreshShelf()
    }

    /** C3: retry appending the next Truyện rows page after a load-more error. */
    fun retryTruyenRows() {
        if (family != Family.TRUYEN) return
        _state.update { it.copy(truyenRowsError = false) }
        loadMoreRows()
    }

    /**
     * V2 (0.4): the frame-6 "Thử lại" for the four families that used to
     * swallow load failures — reruns the family's own loaders.
     */
    fun retryFamily() {
        when (family) {
            Family.TRUYEN -> retryTruyen()
            Family.PHIM -> {
                _state.update { it.copy(familyError = false) }
                applyPhimFilter(_state.value.phimFilter)
                loadComingSoon()
            }
            Family.ANIME -> {
                animePoolStarted = false
                animePoolFailed = false
                _state.update { it.copy(familyError = false, loading = true) }
                startAnimePool()
            }
            Family.DONGHUA -> {
                _state.update { it.copy(familyError = false) }
                selectContext(_state.value.contextIndex)
                loadDonghuaRows()
                loadDonghuaGenres()
                loadDonghuaTrending()
            }
            Family.MANHWA -> {
                _state.update { it.copy(familyError = false) }
                loadManhwaLists()
                loadFreshShelf()
            }
        }
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
        if (family == Family.MANHWA) {
            // Manhwa genre picking lives in the sheet (local index) now;
            // a context reset just reloads the tab's current mode.
            _state.update { it.copy(contextIndex = index) }
            loadManhwaLists()
            return
        }
        // Picking a Donghua day chip exits genre/status browse mode; the
        // rows then belong to loadDonghuaRows again.
        val exitedBrowse = family == Family.DONGHUA &&
            (_state.value.donghuaGenre != null || _state.value.donghuaStatus != 0)
        _state.update {
            it.copy(
                contextIndex = index,
                loading = true,
                donghuaGenre = if (family == Family.DONGHUA) null else it.donghuaGenre,
                donghuaStatus = if (family == Family.DONGHUA) 0 else it.donghuaStatus
            )
        }
        if (exitedBrowse) loadDonghuaRows()
        viewModelScope.launch {
            try {
                // 1.3.2: set by the TRUYEN branch when the NetTruyen
                // fallback served this load (published to the UI state
                // below so the status line can disclose it).
                var truyenUsedFallback = false
                val (posters, rows) = when (family) {
                    Family.PHIM -> {
                        val kind = PHIM_KINDS.getOrNull(index)?.second ?: "phim-bo"
                        val list = app.mergedPhim.byKindMerged(kind, 1)
                        list to list
                    }
                    Family.ANIME -> {
                        val list = animeFiltered()
                        list to list
                    }
                    Family.DONGHUA -> {
                        // Day chips only change the schedule shelf; the
                        // "Mới cập nhật" rows are loaded separately.
                        val list = app.hhKungfu.schedule(dayKey(index))
                        list to _state.value.rows
                    }
                    Family.TRUYEN -> {
                        val status = _state.value.truyenStatus
                        when {
                            status == STATUS_COMPLETED -> {
                                val list = app.truyenQq.completed(1)
                                list to list
                            }
                            else -> {
                                val slug = TRUYEN_GENRES[index].second
                                // 1.3.2: the DEFAULT context's shelves
                                // fall back to NetTruyen when both
                                // TruyenQQ hosts fail. Genre browsing
                                // deliberately gets NO fallback — the
                                // 59-chip sheet is TruyenQQ's own slug
                                // system and NetTruyen's does not map
                                // onto it (dossier truyen-fallback §3).
                                val raw = if (slug.isEmpty()) {
                                    val (hot, latest, fb) = truyenHomeShelves()
                                    truyenUsedFallback = fb
                                    hot to latest
                                } else {
                                    val list = app.truyenQq.byGenre(slug)
                                    list to list
                                }
                                if (status == STATUS_ONGOING && !truyenUsedFallback) {
                                    // Skipped under fallback: the
                                    // completed set is a TruyenQQ
                                    // harvest — with the source down it
                                    // cannot be computed honestly.
                                    val done = ensureTruyenCompletedSlugs()
                                    raw.first.filter { it.slug !in done } to
                                        raw.second.filter { it.slug !in done }
                                } else {
                                    raw
                                }
                            }
                        }
                    }
                    else -> emptyList<TitleItem>() to emptyList()
                }
                app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                _state.update {
                    // Donghua rows are owned by loadDonghuaRows/loadMoreRows —
                    // a day-chip switch must not clobber them with a stale copy.
                    if (family == Family.DONGHUA) {
                        it.copy(loading = false, posters = posters, familyError = false)
                    } else if (family == Family.TRUYEN) {
                        // C3: a loaded Truyện tab clears its error state and
                        // publishes which host served it + how long it took.
                        // 1.3.2: also publish whether the NetTruyen fallback
                        // served this load (status line discloses it).
                        it.copy(
                            loading = false,
                            posters = posters,
                            rows = rows,
                            rowsPage = 1,
                            rowsHasMore = rowsHasMoreFor(index),
                            truyenError = false,
                            truyenRowsError = false,
                            truyenFallbackSource = truyenUsedFallback
                        ).withTruyenStatus()
                    } else {
                        it.copy(
                            loading = false,
                            posters = posters,
                            rows = rows,
                            rowsPage = 1,
                            rowsHasMore = rowsHasMoreFor(index),
                            familyError = false
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    // C3: for Truyện an exception means "load failed", never
                    // silently an empty tab (K4). V2 (0.4) extends the same
                    // honesty to the other families via familyError.
                    if (family == Family.TRUYEN) {
                        it.copy(loading = false, truyenError = true).withTruyenStatus()
                    } else {
                        it.copy(loading = false, familyError = true)
                    }
                }
            }
        }
    }

    /** Whether the current rows source has further pages to append. */
    private fun rowsHasMoreFor(contextIndex: Int): Boolean = when (family) {
        Family.TRUYEN -> _state.value.truyenStatus == STATUS_COMPLETED ||
            TRUYEN_GENRES.getOrNull(contextIndex)?.second?.isNotEmpty() == true
        else -> true
    }

    /** One page of the rows list for the current context/filter. */
    private suspend fun fetchRows(page: Int): List<TitleItem> {
        val s = _state.value
        return when (family) {
            Family.PHIM -> {
                val f = s.phimFilter
                if (f.isDefault) app.mergedPhim.latestMerged(page) else fetchPhim(f, page)
            }
            Family.ANIME -> animeFiltered()
            Family.DONGHUA -> {
                if (s.donghuaGenre != null || s.donghuaStatus != 0) {
                    fetchDonghuaBrowse(page)
                } else {
                    app.hhKungfu.latest(page)
                }
            }
            Family.MANHWA -> when {
                s.manhwaFilter.genre != null || s.manhwaFilter.audience != null -> emptyList()
                s.manhwaFilter.status == STATUS_COMPLETED -> app.netTruyen.completed(page)
                s.manhwaFilter.status == STATUS_ONGOING ->
                    app.netTruyen.byGenreStatus(NetTruyenSource.MANHWA_GENRE, 1, page)
                else -> app.netTruyen.latest(page)
            }
            Family.TRUYEN -> {
                if (s.truyenStatus == STATUS_COMPLETED) {
                    app.truyenQq.completed(page)
                } else {
                    val slug = TRUYEN_GENRES.getOrNull(s.contextIndex)?.second.orEmpty()
                    if (slug.isEmpty()) {
                        emptyList()
                    } else {
                        val raw = app.truyenQq.byGenre(slug, page)
                        if (s.truyenStatus == STATUS_ONGOING) {
                            val done = truyenCompletedSlugs ?: emptySet()
                            raw.filter { it.slug !in done }
                        } else {
                            raw
                        }
                    }
                }
            }
        }
    }

    /** Infinite scroll for the rows list: appends the next page when near the end. */
    fun loadMoreRows() {
        val s = _state.value
        if (s.loadingMore || !s.rowsHasMore) return
        // Anime "load more" = pull the next Cobe pool pages, then re-filter.
        if (family == Family.ANIME) {
            _state.update { it.copy(loadingMore = true) }
            viewModelScope.launch {
                loadAnimePoolPages(2)
                publishAnime()
                kickAnimeEnrichment()
                _state.update { it.copy(loadingMore = false) }
            }
            return
        }
        // Manhwa index mode paginates locally over the loaded index.
        // 1.3.2: only when an index exists — with the index missing the
        // rows came from the NetTruyen fallback and must not be wiped
        // by an empty local window.
        if (family == Family.MANHWA && manhwaIndex != null &&
            (s.manhwaFilter.genre != null || s.manhwaFilter.audience != null)
        ) {
            manhwaWindow += MANHWA_WINDOW_STEP
            val (window, hasMore) = manhwaIndexWindow()
            _state.update { it.copy(posters = window, rows = window, rowsHasMore = hasMore) }
            return
        }
        // V2 (A1): under a Phim version filter the first round can be
        // dry while later rounds still hold matches — paging must be
        // allowed to continue from an empty grid in that one case.
        if (s.rows.isEmpty() &&
            !(family == Family.PHIM && s.phimFilter.version.isNotEmpty() && phimVersionRawMore)
        ) {
            return
        }
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
                    } else if (family == Family.PHIM &&
                        _state.value.phimFilter.version.isNotEmpty()
                    ) {
                        // V2 (A1): a dry version round is not the end —
                        // paging continues while raw source pages remain.
                        phimVersionRawMore
                    } else {
                        more.isNotEmpty()
                    },
                    truyenRowsError = if (family == Family.TRUYEN) failed else it.truyenRowsError
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // PHIM (Khối A — merged KKPhim + Cobe catalog)
    // ------------------------------------------------------------------

    /** True while the last version-filter round still had raw source pages. */
    private var phimVersionRawMore = false

    /**
     * V2 (A1): with a PHIÊN BẢN version set, one "page" of results is a
     * ROUND of [PHIM_VERSION_SCAN_PAGES] raw source pages filtered
     * locally (the Cobe filtered-merge precedent) — version density
     * varies, and a single raw page would starve the grid. The KKPhim
     * API ignores version= entirely, so the server is never asked.
     */
    private suspend fun fetchPhim(filter: PhimFilter, page: Int): List<TitleItem> {
        if (filter.version.isEmpty()) return fetchPhimRaw(filter, page)
        val raw = mutableListOf<TitleItem>()
        val first = (page - 1) * PHIM_VERSION_SCAN_PAGES + 1
        for (p in first until first + PHIM_VERSION_SCAN_PAGES) {
            val chunk = fetchPhimRaw(filter, p)
            if (chunk.isEmpty()) break
            raw += chunk
        }
        phimVersionRawMore = raw.isNotEmpty()
        return raw.filter { it.matchesVersion(filter.version) }
            .distinctBy { it.id }
    }

    private suspend fun fetchPhimRaw(filter: PhimFilter, page: Int): List<TitleItem> =
        if (filter.kind == "chieu-rap" && filter.category.isEmpty() &&
            filter.country.isEmpty() && filter.year == 0
        ) {
            // Chiếu rạp is a KKPhim-only concept; status still applies.
            app.kkPhim.cinema(page).filter { it.matchesStatus(filter.status) }
        } else {
            app.mergedPhim.filteredMerged(
                kind = filter.kind,
                category = filter.category,
                country = filter.country,
                year = filter.year,
                status = filter.status,
                page = page
            )
        }

    /** Phim tab: apply a new filter (from the "Lọc phim" sheet) and reload both shelves. */
    fun applyPhimFilter(filter: PhimFilter) {
        _state.update { it.copy(phimFilter = filter, loading = true, rowsPage = 1) }
        viewModelScope.launch {
            try {
                if (filter.isDefault) {
                    val posters = app.mergedPhim.byKindMerged("phim-bo", 1, 24)
                    val rows = app.mergedPhim.latestMerged(1)
                    app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                    _state.update {
                        it.copy(
                            loading = false,
                            posters = posters,
                            rows = rows,
                            rowsPage = 1,
                            rowsHasMore = rows.isNotEmpty(),
                            familyError = false
                        )
                    }
                } else {
                    val list = fetchPhim(filter, 1)
                    app.database.titleDao().upsertAll(list.map { it.toEntity() })
                    _state.update {
                        it.copy(
                            loading = false,
                            posters = list,
                            rows = list,
                            rowsPage = 1,
                            // V2 (A1): under a version filter a dry
                            // first round can still have later matches.
                            rowsHasMore = list.isNotEmpty() ||
                                (filter.version.isNotEmpty() && phimVersionRawMore),
                            familyError = false
                        )
                    }
                }
            } catch (e: Exception) {
                // V2 (0.4): a failed Phim load is an error state, not a
                // silently empty tab.
                _state.update { it.copy(loading = false, familyError = true) }
            }
        }
    }

    // ------------------------------------------------------------------
    // ANIME (Khối B — Cobe pool, local filtering)
    // ------------------------------------------------------------------

    private fun startAnimePool() {
        if (animePoolStarted) {
            publishAnime()
            kickAnimeEnrichment()
            return
        }
        animePoolStarted = true
        _state.update { it.copy(loading = true) }
        // Country pools warm in parallel (persisted sets land instantly;
        // the first-run sweep then grows them country by country, Japan
        // first). Every growth re-publishes: the country gate is
        // default-deny, so titles appear as they become classified —
        // western titles never flash in and vanish.
        viewModelScope.launch {
            app.animeCatalog.warmCountryPools {
                publishAnime()
                kickAnimeEnrichment()
            }
        }
        viewModelScope.launch {
            // Warm the Donghua slug pool BEFORE the first pool page is
            // published, so no donghua series flashes into the tab.
            app.animeCatalog.warmDonghuaSlugs()
            loadAnimePoolPages(1)
            _state.update {
                it.copy(
                    loading = false,
                    // V2 (0.4): first page failed and the pool is empty
                    // → a real error state with Thử lại, not a blank tab.
                    familyError = animePoolFailed && animePool.isEmpty()
                )
            }
            publishAnime()
            kickAnimeEnrichment()
        }
    }

    /**
     * Background enrichment (1.2.0 fix + country-law fix): pool listing
     * records carry only the "hoat-hinh" umbrella category, so genre
     * chips/filter run on by-slug data fetched behind the list; the same
     * round resolves KKPhim poster fallbacks for Cobe covers that are
     * dead at the source. Both passes run ONLY over titles that pass
     * visible() — nothing is fetched for titles the law hides. Each
     * completed pass re-publishes, so chips and covers fill in live; the
     * loop only continues while pool pages that landed mid-pass still
     * owe attempts (each slug is attempted at most once per process
     * run, so this terminates).
     */
    private fun kickAnimeEnrichment() {
        if (animeEnrichRunning) return
        animeEnrichRunning = true
        viewModelScope.launch {
            try {
                while (true) {
                    val vis = app.animeCatalog.visible(animePool.toList())
                    val genresChanged = app.animeCatalog.enrichThinGenres(vis)
                    val postersChanged = app.animeCatalog.enrichPosterFallbacks(vis)
                    if (genresChanged || postersChanged) publishAnime()
                    if (!app.animeCatalog.hasUnattemptedThin(vis) &&
                        !app.animeCatalog.hasUnattemptedPoster(vis)
                    ) {
                        break
                    }
                }
            } finally {
                animeEnrichRunning = false
            }
        }
    }

    private suspend fun loadAnimePoolPages(count: Int) {
        // The pool is kept RAW: the catalog law (donghua + country gate)
        // is applied at read time in visible(), so titles classified
        // later — while the country pools are still harvesting — join
        // the tab without re-fetching their pool page.
        repeat(count) {
            if (animePoolNextPage > animePoolTotalPages) return
            val (items, total) = try {
                app.animeCatalog.poolPage(animePoolNextPage)
            } catch (e: Exception) {
                // V2 (0.4): remember a first-page failure so the tab can
                // show its error state instead of a permanent blank.
                if (animePoolNextPage == 1 && animePool.isEmpty()) {
                    animePoolFailed = true
                }
                return
            }
            animePoolTotalPages = total
            val known = animePool.map { it.id }.toSet()
            animePool += items.filter { it.id !in known }
            animePoolNextPage++
        }
    }

    private fun animeFiltered(): List<TitleItem> {
        val f = _state.value.animeFilter
        // visible() applies the full tab law (donghua exclusion +
        // country gate) and cross-page dedupe (the same show can
        // surface under two slugs on different pool pages).
        return app.animeCatalog.visible(animePool).filter { item ->
            // Category test runs on ENRICHED genres (AnimeCatalog cache):
            // raw pool records carry only the "hoat-hinh" umbrella.
            (f.category.isEmpty() ||
                app.animeCatalog.genresOf(item).any { it.second == f.category }) &&
                (f.year == 0 || item.year == f.year) &&
                item.matchesStatus(f.status) &&
                // V2 (A1): PHIÊN BẢN — local, on the gated pool only.
                item.matchesVersion(f.version)
        }
    }

    private fun publishAnime() {
        val filtered = animeFiltered()
        val pool = app.animeCatalog.visible(animePool)
        // Chips = categories REALLY present in the loaded pool (minus the
        // hoat-hinh umbrella itself), with real counts behind the order.
        // Genres come from AnimeCatalog.genresOf: pool records enriched
        // from the by-slug API count with their FULL categories, records
        // not yet enriched contribute only what the listing really gave.
        val counts = mutableMapOf<String, Pair<String, Int>>()
        for (item in pool) {
            for ((label, slug) in app.animeCatalog.genresOf(item)) {
                if (slug == "hoat-hinh") continue
                val cur = counts[slug]
                counts[slug] = label to ((cur?.second ?: 0) + 1)
            }
        }
        val categories = counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                    .thenBy { it.value.first }
            )
            .map { it.value.first to it.key }
        val years = pool.map { it.year }.filter { it > 0 }
            .distinct().sortedDescending()
        // Display copies carry the KK poster fallback (when the
        // enrichment cache has one) — the pool records themselves and
        // the DB upsert keep the item's own Cobe art as primary.
        val display = filtered.map { app.animeCatalog.withPosterFallback(it) }
        _state.update {
            it.copy(
                posters = display,
                rows = display,
                rowsHasMore = animePoolNextPage <= animePoolTotalPages,
                animeCategories = categories,
                animeYears = years,
                // V2 (0.4): real content clears the error state; while
                // the country pools still harvest, the screen explains
                // the gradual fill-in.
                familyError = if (display.isNotEmpty()) false else it.familyError,
                animeClassifying = !app.animeCatalog.countryPoolsReady()
            )
        }
        if (filtered.isNotEmpty()) {
            viewModelScope.launch {
                app.database.titleDao().upsertAll(filtered.map { x -> x.toEntity() })
            }
        }
    }

    /** Anime tab: apply a sheet filter — local over the loaded pool, then widen the pool. */
    fun applyAnimeFilter(filter: AnimeFilter) {
        _state.update { it.copy(animeFilter = filter) }
        publishAnime()
        kickAnimeEnrichment()
        if (!filter.isDefault) {
            // A filter deserves a wider horizon than page 1: pull two
            // more pool pages in the background and re-publish.
            viewModelScope.launch {
                loadAnimePoolPages(2)
                publishAnime()
                kickAnimeEnrichment()
            }
        }
    }

    // ------------------------------------------------------------------
    // DONGHUA (day layout + genre/status browse)
    // ------------------------------------------------------------------

    /** Donghua "Mới cập nhật" rows — independent of the day-chip schedule shelf. */
    private fun loadDonghuaRows() {
        viewModelScope.launch {
            try {
                val rows = app.hhKungfu.latest(1)
                app.database.titleDao().upsertAll(rows.map { it.toEntity() })
                _state.update {
                    // Browse modes (genre / status) own the rows — do not clobber them.
                    if (it.donghuaGenre == null && it.donghuaStatus == 0) {
                        it.copy(
                            rows = rows, rowsPage = 1, rowsHasMore = true,
                            familyError = false
                        )
                    } else {
                        it
                    }
                }
            } catch (e: Exception) {
                // V2 (0.4): the rows are this tab's main content — a
                // failure with nothing on screen is an error state.
                if (_state.value.rows.isEmpty()) {
                    _state.update { it.copy(familyError = true) }
                }
            }
        }
    }

    /** Donghua genre menu for the filter sheet, from the source's home page nav. */
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

    /** The /hoan-thanh slug set, harvested once (status filters need it). */
    private suspend fun ensureDonghuaCompletedSlugs(): Set<String> {
        donghuaCompletedSlugs?.let { return it }
        val slugs = mutableSetOf<String>()
        for (p in 1..30) {
            val items = try {
                app.hhKungfu.completed(p)
            } catch (e: Exception) {
                break
            }
            if (items.isEmpty()) break
            items.forEach { slugs += it.slug }
        }
        donghuaCompletedSlugs = slugs
        return slugs
    }

    private suspend fun fetchDonghuaBrowse(page: Int): List<TitleItem> {
        val s = _state.value
        val genre = s.donghuaGenre
        val base = when {
            genre != null -> app.hhKungfu.byCategory(genre.second, page)
            s.donghuaStatus == STATUS_COMPLETED -> app.hhKungfu.completed(page)
            else -> app.hhKungfu.latest(page)
        }
        return when (s.donghuaStatus) {
            STATUS_COMPLETED ->
                if (genre == null) base
                else base.filter { it.slug in ensureDonghuaCompletedSlugs() }
            STATUS_ONGOING ->
                base.filter { it.slug !in ensureDonghuaCompletedSlugs() }
            else -> base
        }
    }

    /**
     * Donghua sheet result: (genre, status) together. Both empty restores
     * the default layout (day-chip schedule shelf + latest rows); anything
     * else browses — Trọn bộ reads /hoan-thanh, Đang ra tiếp reads the
     * normal lists minus the completed set.
     */
    fun applyDonghuaFilter(genre: Pair<String, String>?, status: Int) {
        if (family != Family.DONGHUA) return
        if (genre == null && status == 0) {
            _state.update { it.copy(donghuaGenre = null, donghuaStatus = 0) }
            selectContext(_state.value.contextIndex)
            loadDonghuaRows()
            return
        }
        _state.update {
            it.copy(donghuaGenre = genre, donghuaStatus = status, loading = true, rowsPage = 1)
        }
        viewModelScope.launch {
            try {
                val list = fetchDonghuaBrowse(1)
                app.database.titleDao().upsertAll(list.map { it.toEntity() })
                _state.update {
                    it.copy(
                        loading = false,
                        posters = list,
                        rows = list,
                        rowsPage = 1,
                        rowsHasMore = list.isNotEmpty(),
                        familyError = false
                    )
                }
            } catch (e: Exception) {
                // V2 (0.4): a failed browse load is an error state.
                _state.update { it.copy(loading = false, familyError = true) }
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

    // ------------------------------------------------------------------
    // MANHWA (default NetTruyen lists / status server-side / genre index)
    // ------------------------------------------------------------------

    private fun manhwaUsesIndex(): Boolean {
        val f = _state.value.manhwaFilter
        return f.genre != null || f.audience != null
    }

    private fun loadManhwaLists() {
        _state.update { it.copy(loading = true, rowsPage = 1, familyError = false) }
        viewModelScope.launch {
            try {
                when {
                    // 1.3.2: index mode only when an index actually
                    // exists. When the TruyenQQ index cannot be built
                    // (both hosts down) and no cache survives, the tab
                    // falls through to the NetTruyen branches below —
                    // the shelf is then served by /tim-truyen/
                    // manhwa-11400 instead of an empty window.
                    manhwaUsesIndex() && !manhwaIndex.isNullOrEmpty() -> {
                        val (window, hasMore) = manhwaIndexWindow()
                        app.database.titleDao().upsertAll(window.map { it.toEntity() })
                        _state.update {
                            it.copy(
                                loading = false,
                                posters = window,
                                rows = window,
                                rowsPage = 1,
                                rowsHasMore = hasMore
                            )
                        }
                    }
                    _state.value.manhwaFilter.status == STATUS_COMPLETED -> {
                        val list = app.netTruyen.completed(1)
                        app.database.titleDao().upsertAll(list.map { it.toEntity() })
                        _state.update {
                            it.copy(
                                loading = false, posters = list, rows = list,
                                rowsPage = 1, rowsHasMore = list.isNotEmpty()
                            )
                        }
                    }
                    _state.value.manhwaFilter.status == STATUS_ONGOING -> {
                        val list = app.netTruyen.byGenreStatus(
                            NetTruyenSource.MANHWA_GENRE, 1, 1
                        )
                        app.database.titleDao().upsertAll(list.map { it.toEntity() })
                        _state.update {
                            it.copy(
                                loading = false, posters = list, rows = list,
                                rowsPage = 1, rowsHasMore = list.isNotEmpty()
                            )
                        }
                    }
                    else -> {
                        val posters = app.netTruyen.hot(1)
                        val rows = app.netTruyen.latest(1)
                        app.database.titleDao().upsertAll((posters + rows).map { it.toEntity() })
                        _state.update {
                            it.copy(
                                loading = false, posters = posters, rows = rows,
                                rowsPage = 1, rowsHasMore = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // V2 (0.4): a failed Manhwa load is an error state.
                _state.update { it.copy(loading = false, familyError = true) }
            }
        }
    }

    private fun manhwaIndexWindow(): Pair<List<TitleItem>, Boolean> {
        val idx = manhwaIndex ?: return emptyList<TitleItem>() to false
        val f = _state.value.manhwaFilter
        val filtered = ManhwaIndex.filter(idx, f.genre, f.audience, f.status)
            .map { it.item }
        return filtered.take(manhwaWindow) to (filtered.size > manhwaWindow)
    }

    /** Manhwa sheet result: draft-applied (genre, audience, status) in one go. */
    fun applyManhwaFilter(filter: ManhwaFilter) {
        if (family != Family.MANHWA) return
        manhwaWindow = MANHWA_WINDOW_STEP
        _state.update { it.copy(manhwaFilter = filter) }
        loadManhwaLists()
    }

    /**
     * Index lifecycle: serve the persisted cache immediately, rebuild in
     * the background when missing or older than a week. A failed build
     * keeps whatever cache exists — genre chips then simply stay empty
     * instead of taking the tab down.
     */
    private fun loadManhwaIndex() {
        viewModelScope.launch {
            val cached = ManhwaIndex.fromJson(app.settings.manhwaIndexJson.first())
            val builtAt = app.settings.manhwaIndexBuiltAtMs.first()
            if (cached.isNotEmpty()) {
                manhwaIndex = cached
                _state.update { it.copy(manhwaIndexReady = true) }
                if (manhwaUsesIndex()) loadManhwaLists()
            }
            val stale = cached.isEmpty() ||
                System.currentTimeMillis() - builtAt > 7L * 24 * 3600 * 1000
            if (!stale) return@launch
            _state.update { it.copy(manhwaIndexBuilding = true) }
            val built = try {
                ManhwaIndex.build(app.truyenQq)
            } catch (e: Exception) {
                emptyList()
            }
            if (built.isNotEmpty()) {
                manhwaIndex = built
                app.settings.setManhwaIndex(ManhwaIndex.toJson(built))
                _state.update { it.copy(manhwaIndexReady = true, manhwaIndexBuilding = false) }
                if (manhwaUsesIndex()) loadManhwaLists()
            } else {
                _state.update { it.copy(manhwaIndexBuilding = false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // TRUYỆN (genre contexts + status pair)
    // ------------------------------------------------------------------

    /** The /truyen-full slug set, harvested once (Đang ra tiếp = list minus this set). */
    private suspend fun ensureTruyenCompletedSlugs(): Set<String> {
        truyenCompletedSlugs?.let { return it }
        val set = try {
            app.truyenQq.completedSlugs()
        } catch (e: Exception) {
            emptySet()
        }
        truyenCompletedSlugs = set
        return set
    }

    /** Truyện sheet result: genre context + status, applied together. */
    fun applyTruyenFilter(genreIndex: Int, status: Int) {
        if (family != Family.TRUYEN) return
        _state.update { it.copy(truyenStatus = status) }
        selectContext(genreIndex)
    }
}
