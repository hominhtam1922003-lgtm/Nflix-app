package com.example.nflix.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val jumpError: String = ""
)

class DetailViewModel(
    private val app: NflixApp,
    val item: TitleItem
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    val isComic: Boolean = item.family == Family.TRUYEN || item.family == Family.MANHWA

    init {
        viewModelScope.launch {
            val st = app.database.userStateDao().get(item.id)
            _state.update { it.copy(userState = st) }
        }
        viewModelScope.launch {
            try {
                val detail = when (item.sourceId) {
                    "kkphim" -> app.kkPhim.detail(item.slug)
                    "hhkungfu" -> app.hhKungfu.detail(item.slug)
                    "nettruyen" -> app.netTruyen.detail(item.slug)
                    "truyenqq" -> app.truyenQq.detail(item.slug)
                    else -> when (item.family) {
                        Family.PHIM -> app.kkPhim.detail(item.slug)
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
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun server(): ServerGroup? = _state.value.detail?.servers?.getOrNull(_state.value.serverIndex)

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

    fun isWatched(number: Float): Boolean {
        val st = _state.value.userState ?: return false
        return number < st.lastUnitNumber || (number == st.lastUnitNumber && st.finished)
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

    fun toggleFavorite() = mutateState { it.copy(favorite = !it.favorite) }
    fun toggleWatchLater() = mutateState { it.copy(watchLater = !it.watchLater) }
    fun toggleBell() = mutateState {
        val on = !it.notifyOn
        it.copy(notifyOn = on, following = on)
    }
}
