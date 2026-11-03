package com.example.nflix.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class ShelfEntry(
    val item: TitleItem,
    val progressFraction: Float = 0f,
    val progressLabel: String = ""
)

data class HomeUiState(
    val loading: Boolean = true,
    val hero: TitleItem? = null,
    val heroList: List<TitleItem> = emptyList(),
    val familyFilter: Family? = null,
    val continueList: List<ShelfEntry> = emptyList(),
    val suggestedList: List<TitleItem> = emptyList(),
    val latestList: List<TitleItem> = emptyList(),
    /** B2: today's Donghua schedule + Phim titles updated today (VN). */
    val todayList: List<TitleItem> = emptyList(),
    /** B1: personalised picks from the user's most-watched genre. */
    val forYouList: List<TitleItem> = emptyList(),
    val forYouTitle: String = "Dành cho bạn",
    val shelves: List<String> = listOf("continue", "today", "suggested", "foryou", "latest"),
    /**
     * V2 (0.4): true when EVERY remote shelf failed and nothing remote
     * loaded — Home shows the frame-6 error block (message + Thử lại)
     * instead of a silently near-empty page. A single failed shelf
     * still just hides, as before.
     */
    val loadError: Boolean = false,
    /**
     * 1.3.3: the REAL error text behind [loadError] — the first
     * failure message the hero/latest/suggested loaders kept (1.3.2
     * reduced every failure to a boolean, so the Home error block
     * could only print a canned "check your connection" line).
     */
    val loadErrorDetail: String = "",
    /**
     * 1.3.1: ids of the titles currently flagged Xem sau. The hero's
     * "Xem sau" button reads this to show its saved state — before
     * this, the button wrote to Room on tap but never changed on
     * screen, so it read as a dead button. Detail's toggle tile has
     * always reflected the same flag.
     */
    val watchLaterIds: Set<String> = emptySet()
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val nflix = app as NflixApp
    private val db = nflix.database

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadHero()
        loadShelves()
        loadToday()
        loadForYou()
        viewModelScope.launch {
            nflix.settings.flow.collect { s ->
                // One-time 1.1.0 merge: lists saved before 1.1.0 lack the
                // "today"/"foryou" keys — insert them at their default
                // spots once (flagged), after which the saved list is
                // respected exactly (the user can turn them off for good).
                if (!s.shelvesMigrated) {
                    val merged = effectiveShelves(s.homeShelves)
                    if (merged != s.homeShelves) nflix.settings.setHomeShelves(merged)
                    nflix.settings.set(
                        com.example.nflix.data.SettingsStore.SHELVES_MIGRATED, true
                    )
                    _state.update { it.copy(shelves = merged) }
                } else {
                    _state.update { it.copy(shelves = s.homeShelves - "new") }
                }
            }
        }
        viewModelScope.launch {
            continueFlow().collect { list -> _state.update { it.copy(continueList = list) } }
        }
        // 1.3.1: keep the hero's Xem sau state live from the same DAO
        // flow My Page reads — toggling anywhere reflects everywhere.
        viewModelScope.launch {
            db.userStateDao().watchLater().collect { rows ->
                _state.update {
                    it.copy(watchLaterIds = rows.map { row -> row.titleId }.toSet())
                }
            }
        }
    }

    // V2 (0.4): per-loader failure flags. A shelf failing alone still
    // just hides (its content is optional), but when hero + latest +
    // suggested ALL fail the page itself is broken — that case must
    // surface as an error with a retry, never as a quiet empty Home.
    private var heroFailed = false
    private var latestFailed = false
    private var suggestedFailed = false

    /** 1.3.3: first real error text seen by the three tracked loaders. */
    private var loadErrorDetailText: String = ""

    // 1.3.4: per-loader job handles (Dương Tử S3) — retry() used to
    // stack a second full set of Home loads on top of the running one.
    private var heroJob: Job? = null
    private var latestJob: Job? = null
    // 1.3.8: "Mới nhất" full-grid pagination state.
    private var latestPage = 1
    private var latestHasMore = true
    private var latestLoadingMore = false
    private var suggestedJob: Job? = null
    private var todayJob: Job? = null
    private var forYouJob: Job? = null

    private fun noteLoadError(e: Exception) {
        if (loadErrorDetailText.isBlank()) {
            loadErrorDetailText = e.message?.takeIf { it.isNotBlank() }
                ?: e.javaClass.simpleName
        }
    }

    private fun refreshLoadError() {
        _state.update { st ->
            val failed = heroFailed && latestFailed && suggestedFailed &&
                st.hero == null && st.latestList.isEmpty() &&
                st.suggestedList.isEmpty()
            st.copy(
                loadError = failed,
                loadErrorDetail = if (failed) loadErrorDetailText else ""
            )
        }
    }

    /** V2 (0.4): the frame-6 "Thử lại" — reruns every Home loader. */
    fun retry() {
        heroFailed = false
        latestFailed = false
        suggestedFailed = false
        loadErrorDetailText = ""
        _state.update { it.copy(loading = true, loadError = false, loadErrorDetail = "") }
        loadHero()
        loadShelves()
        loadToday()
        loadForYou()
    }

    private fun loadShelves() {
        // Home's Phim shelves read the same catalog as the Phim tab
        // (Cobe only since 1.3.4).
        latestJob?.cancel()
        latestJob = viewModelScope.launch {
            try {
                val latest = nflix.mergedPhim.latestMerged(page = 1)
                db.titleDao().upsertAll(latest.map { it.toEntity() })
                latestFailed = false
                latestPage = 1
                latestHasMore = latest.isNotEmpty()
                _state.update { it.copy(latestList = latest) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                latestFailed = true
                noteLoadError(e)
            }
            refreshLoadError()
        }
        suggestedJob?.cancel()
        suggestedJob = viewModelScope.launch {
            try {
                val suggested = nflix.mergedPhim.byKindMerged("phim-le", page = 1, limit = 12)
                db.titleDao().upsertAll(suggested.map { it.toEntity() })
                suggestedFailed = false
                _state.update { it.copy(suggestedList = suggested) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                suggestedFailed = true
                noteLoadError(e)
            }
            refreshLoadError()
        }
    }

    /**
     * Shelf order Home actually renders: the saved list, with the two 1.1.0
     * shelves inserted at their default positions when an older saved list
     * does not know them yet ("today" after "Xem tiếp", "foryou" after
     * "Gợi ý cho bạn"). The retired "new" shelf ("Có tập mới" — removed by
     * user order 2026-11-01) is stripped from older saved lists so it can
     * never render again.
     */
    /**
     * 1.3.8: paginate the "Mới nhất" full grid — pre-1.3.8 the Home
     * shelves' full views were fixed lists with no load-more, so the
     * grid ended after the first page (user verdict 1.3.7). A failed
     * page keeps hasMore so a later scroll can retry it.
     */
    fun loadMoreLatest() {
        if (latestLoadingMore || !latestHasMore) return
        latestLoadingMore = true
        viewModelScope.launch {
            var failed = false
            val more = try {
                nflix.mergedPhim.latestMerged(page = latestPage + 1)
            } catch (e: CancellationException) {
                latestLoadingMore = false
                throw e
            } catch (e: Exception) {
                failed = true
                emptyList()
            }
            if (more.isNotEmpty()) {
                db.titleDao().upsertAll(more.map { it.toEntity() })
                latestPage++
            }
            latestHasMore = if (failed) latestHasMore else more.isNotEmpty()
            _state.update {
                it.copy(latestList = (it.latestList + more).distinctBy { d -> d.id })
            }
            latestLoadingMore = false
        }
    }

    private fun effectiveShelves(saved: List<String>): List<String> {
        val list = saved.toMutableList()
        list.remove("new")
        if ("today" !in list) {
            val anchor = list.indexOf("continue")
            list.add(if (anchor >= 0) anchor + 1 else list.size, "today")
        }
        if ("foryou" !in list) {
            val anchor = list.indexOf("suggested")
            list.add(if (anchor >= 0) anchor + 1 else list.size, "foryou")
        }
        return list
    }

    /**
     * B2 "Hôm nay có gì": today's Donghua broadcast group (the source has a
     * real per-day schedule) merged with Phim titles whose updated time
     * falls inside today (Asia/Ho_Chi_Minh — the Cobe adapter normalizes
     * its timestamps to true epoch). No schedule is ever inferred for the
     * families that don't publish one.
     */
    private fun loadToday() {
        todayJob?.cancel()
        todayJob = viewModelScope.launch {
            val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
            val startOfToday = java.time.LocalDate.now(zone)
                .atStartOfDay(zone).toEpochSecond()
            val donghuaToday = try {
                nflix.hhKungfu.schedule(
                    com.example.nflix.ui.family.FamilyViewModel.dayKey(0)
                )
            } catch (e: Exception) {
                emptyList()
            }
            val phimToday = try {
                nflix.mergedPhim.latestMerged(page = 1)
                    .filter { it.updatedEpochSec >= startOfToday }
            } catch (e: Exception) {
                emptyList()
            }
            val merged = (donghuaToday + phimToday).distinctBy { it.id }.take(16)
            if (merged.isNotEmpty()) {
                db.titleDao().upsertAll(merged.map { it.toEntity() })
                _state.update { it.copy(todayList = merged) }
            }
        }
    }

    /** (label, slug) genre links of one title, from its own source's detail. */
    private suspend fun genreLinksOf(item: TitleItem): List<Pair<String, String>> =
        try {
            when (item.sourceId) {
                // 1.3.4: Phim AND Anime genre links both come from
                // Cobe (KK dropped by user order); a legacy "kkphim"
                // item re-homes by slug, exactly like detailFor.
                "kkphim", "cobe" -> nflix.phimcobe.detail(item.slug, item.family).genreLinks
                "hhkungfu" -> nflix.hhKungfu.detail(item.slug).genreLinks
                "nettruyen" -> nflix.netTruyen.detail(item.slug).genreLinks
                "truyenqq" -> nflix.truyenQq.detail(item.slug, item.family).genreLinks
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

    /** Genre shelf of one family/source for a genre slug. */
    private suspend fun genreShelf(
        family: Family,
        sourceId: String,
        slug: String
    ): List<TitleItem> = try {
        when (sourceId) {
            // 1.3.4: Cobe genre links carry Cobe's own slugs — the
            // shelf is Cobe's /the-loai listing for the same slug.
            "kkphim", "cobe" -> nflix.phimcobe.categoryPage(slug, page = 1, kind = "", family = family)
                .first.take(24)
            "hhkungfu" -> nflix.hhKungfu.byCategory(slug, page = 1)
            "nettruyen" -> nflix.netTruyen.byGenre(slug, page = 1)
            "truyenqq" -> nflix.truyenQq.byGenre(slug, page = 1)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * B1 personalisation, computed fully on-device: genres of the titles
     * the user started / favorited / follows (detail genre links), counted
     * by frequency. Needs at least 3 genre-bearing titles — with less data
     * the shelf stays hidden rather than faking a taste profile.
     */
    private fun loadForYou() {
        forYouJob?.cancel()
        forYouJob = viewModelScope.launch {
            val states = db.userStateDao().trackedOnce()
                .filter { it.lastUnitNumber > 0 || it.favorite || it.following }
                .sortedByDescending { it.lastTouchedEpochSec }
            if (states.isEmpty()) return@launch
            val titles = db.titleDao().getByIds(states.map { it.titleId })
                .map { it.toItem() }
            // key: (family, sourceId, genreSlug) — slugs are per-source.
            val counts = mutableMapOf<Triple<Family, String, String>, Int>()
            val labels = mutableMapOf<Triple<Family, String, String>, String>()
            var genreBearing = 0
            for (title in titles.take(10)) {
                val links = genreLinksOf(title)
                if (links.isEmpty()) continue
                genreBearing++
                links.distinctBy { it.second }.forEach { (label, slug) ->
                    val key = Triple(title.family, title.sourceId, slug)
                    counts[key] = (counts[key] ?: 0) + 1
                    labels.putIfAbsent(key, label)
                }
            }
            if (genreBearing < 3 || counts.isEmpty()) return@launch
            val ranked = counts.entries.sortedByDescending { it.value }
            val top = ranked[0]
            val secondCount = ranked.getOrNull(1)?.value ?: 0
            val (family, sourceId, slug) = top.key
            val excluded = states.map { it.titleId }.toSet()
            val picks = genreShelf(family, sourceId, slug)
                .filter { it.id !in excluded }
                .take(12)
            if (picks.size < 4) return@launch
            db.titleDao().upsertAll(picks.map { it.toEntity() })
            val title = if (top.value >= 2 && top.value > secondCount) {
                "Vì bạn hay xem ${labels[top.key] ?: ""}".trim()
            } else {
                "Dành cho bạn"
            }
            _state.update { it.copy(forYouList = picks, forYouTitle = title) }
        }
    }

    private fun loadHero() {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            try {
                val trending = nflix.mergedPhim.byKindMerged("phim-bo", page = 1, limit = 12)
                db.titleDao().upsertAll(trending.map { it.toEntity() })
                heroFailed = false
                _state.update {
                    it.copy(loading = false, hero = trending.firstOrNull(), heroList = trending.take(8))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                heroFailed = true
                noteLoadError(e)
                _state.update { it.copy(loading = false) }
            }
            refreshLoadError()
        }
    }

    private fun continueFlow(): Flow<List<ShelfEntry>> =
        db.userStateDao().continueWatching().flatMapLatest { states ->
            if (states.isEmpty()) flowOf(emptyList())
            else db.titleDao().observeByIds(states.map { it.titleId }).map { titles ->
                val byId = titles.associateBy { it.id }
                states.mapNotNull { st ->
                    val item = byId[st.titleId]?.toItem() ?: return@mapNotNull null
                    val label = when (item.family) {
                        Family.TRUYEN, Family.MANHWA ->
                            "${st.lastUnitLabel} · trang ${st.pageIndex}/${st.pageCount}"
                        else -> {
                            val remainMs = st.durationMs - st.positionMs
                            if (remainMs > 60_000) "${st.lastUnitLabel} · còn ${remainMs / 60000} phút"
                            else st.lastUnitLabel
                        }
                    }
                    ShelfEntry(item, st.progressFraction, label)
                }
            }
        }

    fun setFamilyFilter(family: Family?) {
        _state.update { it.copy(familyFilter = family) }
    }

    fun toggleWatchLater(item: TitleItem) {
        viewModelScope.launch {
            db.titleDao().upsert(item.toEntity())
            val current = db.userStateDao().get(item.id)
            db.userStateDao().upsert(
                (current ?: com.example.nflix.data.local.UserStateEntity(titleId = item.id))
                    .copy(watchLater = !(current?.watchLater ?: false))
            )
        }
    }
}
