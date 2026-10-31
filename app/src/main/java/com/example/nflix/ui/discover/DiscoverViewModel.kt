package com.example.nflix.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val ranking: List<TitleItem> = emptyList(),
    val completed: List<TitleItem> = emptyList(),
    val genreLoading: Boolean = false,
    val genreItems: List<TitleItem> = emptyList()
)

class DiscoverViewModel(private val app: NflixApp) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val ranking = app.hhKungfu.trending()
                app.database.titleDao().upsertAll(ranking.map { it.toEntity() })
                _state.update { it.copy(ranking = ranking) }
            } catch (e: Exception) { /* section stays hidden */ }
        }
        viewModelScope.launch {
            try {
                val completed = app.kkPhim.listByKind("phim-le", page = 1, limit = 12)
                app.database.titleDao().upsertAll(completed.map { it.toEntity() })
                _state.update { it.copy(completed = completed) }
            } catch (e: Exception) { /* section stays hidden */ }
        }
    }

    fun loadGenre(genreSlug: String) {
        _state.update { it.copy(genreLoading = true, genreItems = emptyList()) }
        viewModelScope.launch {
            try {
                val items = app.truyenQq.byGenre(genreSlug)
                app.database.titleDao().upsertAll(items.map { it.toEntity() })
                _state.update { it.copy(genreLoading = false, genreItems = items) }
            } catch (e: Exception) {
                _state.update { it.copy(genreLoading = false) }
            }
        }
    }

    companion object {
        /** label, TruyenQQ slug, card color, design count label. */
        val GENRES = listOf(
            GenreDef("Tiên hiệp", "tien-hiep", 0xFF7C6CF0, "12.480 bộ"),
            GenreDef("Cổ trang", "co-trang", 0xFFB08945, "8.214 bộ"),
            GenreDef("Hành động", "hanh-dong", 0xFF3E7CC4, "15.902 bộ"),
            GenreDef("Ngôn tình", "ngon-tinh", 0xFFC24E7E, "6.377 bộ"),
            GenreDef("Huyền huyễn", "huyen-huyen", 0xFF4C9950, "9.845 bộ"),
            GenreDef("Học đường", "hoc-duong", 0xFF3AA79B, "3.156 bộ")
        )
    }

    data class GenreDef(
        val label: String,
        val slug: String,
        val colorHex: Long,
        val countLabel: String
    )
}
