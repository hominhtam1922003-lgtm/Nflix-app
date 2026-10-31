package com.example.nflix.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import com.example.nflix.data.toItem
import com.example.nflix.data.unitWord
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
    val progressLabel: String = "",
    val newCount: Int = 0
)

data class HomeUiState(
    val loading: Boolean = true,
    val hero: TitleItem? = null,
    val heroList: List<TitleItem> = emptyList(),
    val familyFilter: Family? = null,
    val continueList: List<ShelfEntry> = emptyList(),
    val newList: List<ShelfEntry> = emptyList(),
    val suggestedList: List<TitleItem> = emptyList(),
    val latestList: List<TitleItem> = emptyList(),
    val shelves: List<String> = listOf("continue", "new", "suggested", "latest")
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
        viewModelScope.launch {
            nflix.settings.flow.collect { s -> _state.update { it.copy(shelves = s.homeShelves) } }
        }
        viewModelScope.launch {
            continueFlow().collect { list -> _state.update { it.copy(continueList = list) } }
        }
        viewModelScope.launch {
            newFlow().collect { list -> _state.update { it.copy(newList = list) } }
        }
    }

    private fun loadShelves() {
        viewModelScope.launch {
            try {
                val latest = nflix.kkPhim.latest(page = 1)
                db.titleDao().upsertAll(latest.map { it.toEntity() })
                _state.update { it.copy(latestList = latest) }
            } catch (e: Exception) { /* shelf stays hidden */ }
        }
        viewModelScope.launch {
            try {
                val suggested = nflix.kkPhim.listByKind("phim-le", page = 1, limit = 12)
                db.titleDao().upsertAll(suggested.map { it.toEntity() })
                _state.update { it.copy(suggestedList = suggested) }
            } catch (e: Exception) { /* shelf stays hidden */ }
        }
    }

    private fun loadHero() {
        viewModelScope.launch {
            try {
                val trending = nflix.kkPhim.listByKind("phim-bo", page = 1, limit = 12)
                db.titleDao().upsertAll(trending.map { it.toEntity() })
                _state.update {
                    it.copy(loading = false, hero = trending.firstOrNull(), heroList = trending.take(8))
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
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

    private fun newFlow(): Flow<List<ShelfEntry>> =
        db.userStateDao().following().flatMapLatest { states ->
            val withNew = states.filter { it.newCount > 0 }
            if (withNew.isEmpty()) flowOf(emptyList())
            else db.titleDao().observeByIds(withNew.map { it.titleId }).map { titles ->
                val byId = titles.associateBy { it.id }
                withNew.mapNotNull { st ->
                    val item = byId[st.titleId]?.toItem() ?: return@mapNotNull null
                    ShelfEntry(item, newCount = st.newCount)
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

    fun unitOf(item: TitleItem): String = item.family.unitWord()
}
