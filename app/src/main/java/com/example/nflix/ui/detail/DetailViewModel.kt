package com.example.nflix.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.local.watchedSet
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.ServerProbe
import com.example.nflix.data.ServerStatus
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.CobePlay
import com.example.nflix.data.source.CobeSource
import com.example.nflix.data.source.KkPhimSource
import com.example.nflix.data.source.NetTruyenSource
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun fmtNum(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()

/** One cell in the episode/chapter grid. */
data class GridUnit(
    val number: Float,
    val episode: EpisodeItem? = null,
    val chapter: ChapterItem? = null
) {
    val label: String get() = fmtNum(number)
}

data class DetailUiState(
    val loading: Boolean = true,
    val detail: TitleDetail? = null,
    val userState: UserStateEntity? = null,
    val serverIndex: Int = 0,
    val sortDesc: Boolean = true,
    val rangeIndex: Int = 0,
    val jumpError: String = "",
    /** Last real per-server measurement (server index to status); empty = not measured yet. */
    val serverStatuses: Map<Int, ServerStatus> = emptyMap(),
    val probingServers: Boolean = false,
    /** "Cùng thể loại" shelf at the end of the page. */
    val related: List<TitleItem> = emptyList()
)

class DetailViewModel(
    private val app: NflixApp,
    val item: TitleItem
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** True once the user picks a server by hand — auto-fastest must not override that. */
    private var userPickedServer = false
    private var autoFastest = true

    val isComic: Boolean = item.family == Family.TRUYEN || item.family == Family.MANHWA

    init {
        viewModelScope.launch {
            val st = app.database.userStateDao().get(item.id)
            _state.update { it.copy(userState = st) }
        }
        viewModelScope.launch {
            try {
                val detail = when (item.sourceId) {
                    // Đợt 4: Phim/Anime go through the merged front
                    // (KKPhim + Cobe server groups); cobe-only titles
                    // route inside detailFor to the pure cobe detail.
                    "kkphim", "cobe" -> app.mergedPhim.detailFor(item)
                    "hhkungfu" -> app.hhKungfu.detail(item.slug)
                    "nettruyen" -> app.netTruyen.detail(item.slug)
                    "truyenqq" -> app.truyenQq.detail(item.slug)
                    else -> when (item.family) {
                        Family.PHIM, Family.ANIME -> app.mergedPhim.detailFor(item)
                        Family.DONGHUA -> app.hhKungfu.detail(item.slug)
                        Family.MANHWA -> app.netTruyen.detail(item.slug)
                        Family.TRUYEN -> app.truyenQq.detail(item.slug)
                    }
                }
                app.database.titleDao().upsert(detail.item.toEntity())
                // Default server: prefer Thuyết minh per the user's settings default.
                val preferred = detail.servers.indexOfFirst { it.langKey == "tm" }
                    .takeIf { it >= 0 } ?: 0
                _state.update {
                    it.copy(loading = false, detail = detail, serverIndex = preferred)
                }
                // 0.1/0.2: when the "Tự chọn server sống nhanh nhất" setting
                // is on, really measure every server now (in the background)
                // and adopt the fastest live one. Statuses feed the server
                // bar and the sheet — no more hardcoded "đang tốt · nhanh".
                if (detail.servers.isNotEmpty()) {
                    viewModelScope.launch {
                        autoFastest = try {
                            app.settings.flow.first().autoFastestServer
                        } catch (e: Exception) {
                            true
                        }
                        if (autoFastest) measureServers(autoSelect = true)
                    }
                }
                loadRelated(detail)
                // Refresh the released count for a TRACKED title (favorited,
                // notify on, or started). "Có tập mới" = units the SOURCE added
                // after tracking began (user rule) — merely opening this page
                // must NOT clear them; only watching/reading them does. The
                // single exception: a tracked row with no baseline yet gets
                // its baseline here (everything out now = the starting point).
                val total = totalUnits(detail)
                val stNow = app.database.userStateDao().get(item.id)
                if (stNow != null && total > 0 &&
                    (stNow.following || stNow.notifyOn || stNow.favorite || stNow.lastUnitNumber > 0f)
                ) {
                    val next = if (stNow.seenCount == 0 && stNow.latestCount == 0) {
                        stNow.copy(seenCount = total, latestCount = total)
                    } else {
                        stNow.copy(latestCount = maxOf(stNow.latestCount, total))
                    }
                    if (next != stNow) {
                        app.database.userStateDao().upsert(next)
                        _state.update { it.copy(userState = next) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun server(): ServerGroup? = _state.value.detail?.servers?.getOrNull(_state.value.serverIndex)

    /**
     * A4: a KKPhim "sắp chiếu" title — detail loaded fine but there is no
     * playable server yet (trailer only). The page switches its main button
     * to "Theo dõi" and hides the empty episode UI. Comics/Donghua never
     * count: their empty lists mean a load failure, not a pre-release.
     */
    fun isAwaitingRelease(): Boolean {
        val d = _state.value.detail ?: return false
        if (isComic) return false
        val kk = item.sourceId == "kkphim" || item.family == Family.PHIM || item.family == Family.ANIME
        return kk && d.servers.isEmpty()
    }

    /**
     * Measure every server of this title in parallel (0.1). Results land in
     * state.serverStatuses for the bar/sheet (0.2). With [autoSelect] and the
     * setting on, the fastest LIVE server becomes the selection — unless the
     * user already picked one by hand. Runs in a coroutine; never blocks UI.
     */
    fun measureServers(autoSelect: Boolean = false) {
        val detail = _state.value.detail ?: return
        if (detail.servers.isEmpty() || _state.value.probingServers) return
        _state.update { it.copy(probingServers = true) }
        viewModelScope.launch {
            val target = resumeUnit()?.number ?: 0f
            val statuses = ServerProbe.probeAll(detail.servers, target) { ep -> probeUrl(ep) }
            _state.update { s ->
                var idx = s.serverIndex
                if (autoSelect && autoFastest && !userPickedServer) {
                    val best = statuses.entries
                        .filter { it.value.isAlive }
                        .minByOrNull { it.value.ms ?: Long.MAX_VALUE }
                    if (best != null) idx = best.key
                }
                s.copy(probingServers = false, serverStatuses = statuses, serverIndex = idx)
            }
        }
    }

    /** The URL worth timing for one episode (Donghua resolves its embed iframe). */
    private suspend fun probeUrl(ep: EpisodeItem): String? =
        if (CobeSource.isLocator(ep.slug)) {
            // Đợt 4: timing a cobe server means resolving its play link —
            // the resolve time counts into the measurement, like Donghua.
            try {
                when (val target = app.cobe.resolvePlay(ep.slug)) {
                    is CobePlay.Hls -> target.url
                    is CobePlay.Embed -> target.url
                }
            } catch (e: Exception) {
                ep.embedUrl.ifBlank { null }
            }
        } else if (item.sourceId == "hhkungfu" || item.family == Family.DONGHUA) {
            try {
                app.hhKungfu.resolveEmbed(ep.slug)
            } catch (e: Exception) {
                null
            }
        } else {
            ep.streamUrl.ifBlank { ep.embedUrl.ifBlank { null } }
        }

    /** A2: load the "Cùng thể loại" shelf from the title's first genre. */
    private fun loadRelated(detail: TitleDetail) {
        viewModelScope.launch {
            val related = try {
                fetchRelated(detail)
            } catch (e: Exception) {
                emptyList()
            }
            if (related.isNotEmpty()) {
                app.database.titleDao().upsertAll(related.map { it.toEntity() })
                _state.update { it.copy(related = related) }
            }
        }
    }

    private suspend fun fetchRelated(detail: TitleDetail): List<TitleItem> {
        val self = detail.item
        val pool: List<TitleItem> = when (self.family) {
            Family.PHIM -> {
                val slug = detail.genreLinks.firstOrNull()?.second
                    ?: kkGenreSlug(self.genres.firstOrNull())
                    ?: return emptyList()
                app.kkPhim.byCategory(slug, page = 1)
            }
            Family.ANIME -> {
                val slug = detail.genreLinks.firstOrNull()?.second
                    ?: kkGenreSlug(self.genres.firstOrNull())
                    ?: return emptyList()
                app.kkPhim.listFiltered(
                    kind = "hoat-hinh", category = slug, country = "nhat-ban", page = 1
                )
            }
            Family.DONGHUA -> {
                val slug = detail.genreLinks.firstOrNull()?.second ?: return emptyList()
                app.hhKungfu.byCategory(slug, page = 1)
            }
            Family.MANHWA -> {
                // Skip the "Manhwa" umbrella category itself — a genre shelf
                // keyed on it would just be the whole catalog again.
                val link = detail.genreLinks.firstOrNull { it.second != NetTruyenSource.MANHWA_GENRE }
                    ?: return emptyList()
                app.netTruyen.byGenre(link.second, page = 1)
            }
            Family.TRUYEN -> {
                val slug = detail.genreLinks.firstOrNull()?.second ?: return emptyList()
                app.truyenQq.byGenre(slug, page = 1)
            }
        }
        return pool.filter { it.slug != self.slug }.take(12)
    }

    /** KKPhim genre label to slug via the verified CATEGORIES table (fallback path). */
    private fun kkGenreSlug(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return KkPhimSource.CATEGORIES.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    /** All units ascending by number. */
    fun allUnits(): List<GridUnit> {
        val d = _state.value.detail ?: return emptyList()
        return if (isComic) {
            d.chapters.map { GridUnit(it.number, chapter = it) }.sortedBy { it.number }
        } else {
            (server()?.episodes ?: emptyList()).map { GridUnit(it.number, episode = it) }.sortedBy { it.number }
        }
    }

    /** Range chunks of 100 over the ascending list: "1–100", "101–200", ... */
    fun ranges(): List<Pair<String, List<GridUnit>>> {
        val all = allUnits()
        if (all.size <= 100) return listOf(("" to all))
        return all.chunked(100).map { chunk ->
            "${fmtNum(chunk.first().number)}–${fmtNum(chunk.last().number)}" to chunk
        }
    }

    /** Units in the selected range, in the current sort order. */
    fun visibleUnits(): List<GridUnit> {
        val chunk = ranges().getOrNull(_state.value.rangeIndex)?.second ?: return emptyList()
        return if (_state.value.sortDesc) chunk.reversed() else chunk
    }

    /** Only units the user actually opened in the player/reader count as watched. */
    fun isWatched(number: Float): Boolean {
        val st = _state.value.userState ?: return false
        return st.watchedSet().contains(number)
    }

    /** Total units currently out, from the loaded detail. */
    private fun totalUnits(detail: TitleDetail): Int {
        val fromUnits = if (isComic) {
            detail.chapters.maxOfOrNull { it.number }?.toInt() ?: 0
        } else {
            detail.servers.maxOfOrNull { srv -> srv.episodes.maxOfOrNull { it.number }?.toInt() ?: 0 } ?: 0
        }
        return maxOf(detail.totalCount, detail.item.episodeCount, fromUnits)
    }

    fun isCurrent(number: Float): Boolean {
        val st = _state.value.userState ?: return false
        return number == st.lastUnitNumber && !st.finished && st.lastUnitNumber > 0f
    }

    /** The unit the main button resumes: in-progress unit, else first (or first again when finished). */
    fun resumeUnit(): GridUnit? {
        val all = allUnits()
        if (all.isEmpty()) return null
        val st = _state.value.userState
        if (st != null && st.lastUnitNumber > 0f && !finishedAll()) {
            all.firstOrNull { it.number == st.lastUnitNumber }?.let { return it }
        }
        return all.first()
    }

    fun finishedAll(): Boolean {
        val all = allUnits()
        val st = _state.value.userState ?: return false
        if (all.isEmpty()) return false
        return st.finished && st.lastUnitNumber >= (all.maxOfOrNull { it.number } ?: 0f)
    }

    fun mainButtonLabel(): String {
        if (isAwaitingRelease()) {
            val st0 = _state.value.userState
            return if (st0?.notifyOn == true || st0?.following == true) "Đang theo dõi" else "Theo dõi"
        }
        val unitWord = if (isComic) "Chap" else "Tập"
        val verb = if (isComic) "Đọc" else "Xem"
        val resume = resumeUnit()
        val st = _state.value.userState
        return when {
            resume == null -> "$verb ngay"
            finishedAll() -> "$verb lại · $unitWord ${fmtNum(resume.number)}"
            st != null && st.lastUnitNumber > 0f -> "$verb tiếp · $unitWord ${fmtNum(resume.number)}"
            else -> "$verb ngay · $unitWord ${fmtNum(resume.number)}"
        }
    }

    fun selectServer(index: Int) {
        userPickedServer = true
        _state.update { it.copy(serverIndex = index, rangeIndex = 0) }
    }

    fun toggleSort() {
        _state.update { it.copy(sortDesc = !it.sortDesc) }
    }

    fun selectRange(index: Int) {
        _state.update { it.copy(rangeIndex = index) }
    }

    fun setJumpError(msg: String) {
        _state.update { it.copy(jumpError = msg) }
    }

    fun findUnit(numberText: String): GridUnit? {
        val n = numberText.trim().toFloatOrNull() ?: return null
        return allUnits().firstOrNull { it.number == n }
    }

    private fun mutateState(transform: (UserStateEntity) -> UserStateEntity) {
        viewModelScope.launch {
            app.database.titleDao().upsert(item.toEntity())
            val current = _state.value.userState
                ?: app.database.userStateDao().get(item.id)
                ?: UserStateEntity(titleId = item.id)
            val next = transform(current)
            app.database.userStateDao().upsert(next)
            _state.update { it.copy(userState = next) }
        }
    }

    fun toggleFavorite() {
        // Favoriting also starts tracking (user rule): baseline = what's out NOW.
        val total = _state.value.detail?.let { totalUnits(it) } ?: 0
        mutateState {
            val on = !it.favorite
            if (on && total > 0) {
                it.copy(
                    favorite = true,
                    seenCount = maxOf(it.seenCount, total),
                    latestCount = maxOf(it.latestCount, total)
                )
            } else {
                it.copy(favorite = on)
            }
        }
    }
    fun toggleWatchLater() = mutateState { it.copy(watchLater = !it.watchLater) }
    fun toggleBell() {
        // Baseline rule: following starts counting from what is already out NOW —
        // existing episodes must never show up as "+N tập mới".
        val total = _state.value.detail?.let { totalUnits(it) } ?: 0
        mutateState {
            val on = !it.notifyOn
            if (on && total > 0) {
                it.copy(
                    notifyOn = true,
                    following = true,
                    seenCount = maxOf(it.seenCount, total),
                    latestCount = maxOf(it.latestCount, total)
                )
            } else {
                it.copy(notifyOn = on, following = on)
            }
        }
    }
}
