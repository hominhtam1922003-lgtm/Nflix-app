package com.example.nflix.ui.activity

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

data class ActivityEntry(
    val item: TitleItem,
    val state: UserStateEntity
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(private val app: NflixApp) : ViewModel() {

    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            entriesFlow().collect { list -> _entries.update { list } }
        }
    }

    private fun entriesFlow(): Flow<List<ActivityEntry>> =
        app.database.userStateDao().history(100).flatMapLatest { states ->
            if (states.isEmpty()) flowOf(emptyList())
            else app.database.titleDao().observeByIds(states.map { it.titleId }).map { titles ->
                val byId = titles.associateBy { it.id }
                states.mapNotNull { st ->
                    byId[st.titleId]?.let { ActivityEntry(it.toItem(), st) }
                }
            }
        }

    fun clearHistory() {
        viewModelScope.launch { app.database.userStateDao().clearHistory() }
    }
}
