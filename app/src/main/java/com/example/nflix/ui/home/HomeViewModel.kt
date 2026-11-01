package com.example.nflix.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
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
    /** B2: today's Donghua schedule + KKPhim titles updated today (VN). */
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
    /** V2 (A3): Cobe editorial collections for the "Bộ sưu tập" shelf. */
    val collections: List<com.example.nflix.data.source.CobeCollection> = emptyList(),
    /** The collection whose grid is open (set when its card is tapped). */
    val selectedCollection: com.example.nflix.data.source.CobeCollection? = null
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
        loadCollections()
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
    }

    // V2 (0.4): per-loader failure flags. A shelf failing alone still
    // just hides (its content is optional), but when hero + latest +
    // suggested ALL fail the page itself is broken — that case must
    // surface as an error with a retry, never as a quiet empty Home.
    private var heroFailed = false
    private var latestFailed = false
    private var suggestedFailed = false

    private fun refreshLoadError() {
        _state.update { st ->
            st.copy(
                loadError = heroFailed && latestFailed && suggestedFailed &&
                    st.hero == null && st.latestList.isEmpty() &&
                    st.suggestedList.isEmpty()
            )
        }
    }

    /** V2 (0.4): the frame-6 "Thử lại" — reruns every Home loader. */
    fun retry() {
        heroFailed = false
        latestFailed = false
        suggestedFailed = false
        _state.update { it.copy(loading = true, loadError = false) }
        loadHero()
        loadShelves()
        loadToday()
        loadForYou()
        loadCollections()
    }

    private fun loadShelves() {
        // 1.2.0 (Khối A): Home's Phim shelves read the MERGED catalog
        // (KKPhim + Cobe union) like the Phim tab does.
        viewModelScope.launch {
            try {
                val latest = nflix.mergedPhim.latestMerged(page = 1)
                db.titleDao().upsertAll(latest.map { it.toEntity() })
                latestFailed = false
                _state.update { it.copy(latestList = latest) }
            } catch (e: Exception) {
                latestFailed = true
            }
            refreshLoadError()
        }
        viewModelScope.launch {
            try {
                val suggested = nflix.mergedPhim.byKindMerged("phim-le", page = 1, limit = 12)
                db.titleDao().upsertAll(suggested.map { it.toEntity() })
                suggestedFailed = false
                _state.update { it.copy(suggestedList = suggested) }
            } catch (e: Exception) {
                suggestedFailed = true
            }
            refreshLoadError()
        }
    }

    /**
     * V2 (A3): the "Bộ sưu tập" shelf — Cobe's editorial homepage
     * collections. Lists duplicating shelves Home already has (charts,
     * sắp chiếu, chiếu rạp, phim lẻ/bộ rails, mới nhất) are dropped by
     * name; the rest render as wide cards (name + real title count)
     * opening the collection's own grid. A Cobe failure just hides the
     * shelf — Home's other content is unaffected.
     */
    private fun loadCollections() {
        viewModelScope.launch {
            try {
                val dupHints = listOf(
                    "top", "hot", "xem nhiều", "bảng xếp hạng", "sắp chiếu",
                    "chiếu rạp", "phim lẻ", "phim bộ", "mới nhất", "mới cập nhật",
                    "đang chiếu", "thịnh hành"
                )
                val cols = nflix.cobe.homepageLists().filter { c ->
                    c.items.size >= 4 &&
                        dupHints.none { hint -> c.name.lowercase().contains(hint) }
                }.take(8)
                if (cols.isNotEmpty()) {
                    db.titleDao().upsertAll(cols.flatMap { it.items }.map { it.toEntity() })
                    _state.update { it.copy(collections = cols) }
                }
            } catch (e: Exception) { /* shelf stays hidden */ }
        }
    }

    /** V2 (A3): remember which collection the user opened for its grid. */
    fun selectCollection(c: com.example.nflix.data.source.CobeCollection) {
        _state.update { it.copy(selectedCollection = c) }
    }

    /**
     * Shelf order Home actually renders: the saved list, with the two 1.1.0
     * shelves inserted at their default positions when an older saved list
     * does not know them yet ("today" after "Xem tiếp", "foryou" after
     * "Gợi ý cho bạn"). The retired "new" shelf ("Có tập mới" — removed by
     * user order 2026-11-01) is stripped from older saved lists so it can
     * never render again.
     */
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
     * real per-day schedule) merged with KKPhim titles whose modified time
     * falls inside today (Asia/Ho_Chi_Minh — the KKPhim adapter already
     * normalized its timestamps to true epoch). No schedule is ever
     * inferred for the families that don't publish one.
     */
    private fun loadToday() {
        viewModelScope.launch {
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
                "kkphim" -> nflix.kkPhim.detail(item.slug).genreLinks
                "cobe" -> nflix.cobe.detail(item.slug, item.family).genreLinks
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
            // Cobe genre links are KKPhim slugs by construction (the
            // adapter maps genre names through the KKPhim table).
            "kkphim", "cobe" -> nflix.kkPhim.byCategory(slug, page = 1, limit = 24)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                val trending = nflix.mergedPhim.byKindMerged("phim-bo", page = 1, limit = 12)
                db.titleDao().upsertAll(trending.map { it.toEntity() })
                heroFailed = false
                _state.update {
                    it.copy(loading = false, hero = trending.firstOrNull(), heroList = trending.take(8))
                }
            } catch (e: Exception) {
                heroFailed = true
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
