package com.example.nflix.ui.mypage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryEntry(
    val item: TitleItem,
    val state: UserStateEntity
)

data class MyPageUiState(
    val followingCount: Int = 0,
    val favoriteCount: Int = 0,
    val watchLaterCount: Int = 0,
    val following: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(),
    val watchLater: List<LibraryEntry> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class MyPageViewModel(private val app: NflixApp) : ViewModel() {

    private val _state = MutableStateFlow(MyPageUiState())
    val state: StateFlow<MyPageUiState> = _state.asStateFlow()

    private val dao get() = app.database.userStateDao()

    init {
        viewModelScope.launch {
            dao.trackedCount().collect { c -> _state.update { it.copy(followingCount = c) } }
        }
        viewModelScope.launch {
            dao.favoriteCount().collect { c -> _state.update { it.copy(favoriteCount = c) } }
        }
        viewModelScope.launch {
            dao.watchLaterCount().collect { c -> _state.update { it.copy(watchLaterCount = c) } }
        }
        viewModelScope.launch {
            joined(dao.tracked()).collect { list -> _state.update { it.copy(following = list) } }
        }
        viewModelScope.launch {
            joined(dao.favorites()).collect { list -> _state.update { it.copy(favorites = list) } }
        }
        viewModelScope.launch {
            joined(dao.watchLater()).collect { list -> _state.update { it.copy(watchLater = list) } }
        }
    }

    private fun joined(source: Flow<List<UserStateEntity>>): Flow<List<LibraryEntry>> =
        source.flatMapLatest { states ->
            if (states.isEmpty()) flowOf(emptyList())
            else app.database.titleDao().observeByIds(states.map { it.titleId }).map { titles ->
                val byId = titles.associateBy { it.id }
                states.mapNotNull { st -> byId[st.titleId]?.let { LibraryEntry(it.toItem(), st) } }
            }
        }
}
