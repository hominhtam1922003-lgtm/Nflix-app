package com.example.nflix.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Lowercase, diacritic-stripped form for matching ("Đấu La" → "dau la"). */
fun normalizeText(s: String): String {
    val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
    val stripped = nfd.replace(Regex("\\p{Mn}+"), "")
    return stripped.replace('đ', 'd').replace('Đ', 'd').lowercase()
}

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val suggestions: List<TitleItem> = emptyList(),
    val history: List<String> = emptyList()
)

class SearchViewModel(private val app: NflixApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            app.settings.searchHistory.collect { h -> _state.update { it.copy(history = h) } }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            delay(300)
            val results = searchAll(query)
            // Only publish if the query is still what the user sees.
            if (_state.value.query == query) {
                _state.update { it.copy(suggestions = results, searching = false) }
            }
        }
    }

    /** Full search (results page) — same merge, no debounce. */
    suspend fun searchNow(query: String): List<TitleItem> = searchAll(query)

    private suspend fun searchAll(query: String): List<TitleItem> = coroutineScope {
        val deferred = listOf(
            async { runCatching { app.kkPhim.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { app.hhKungfu.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { app.netTruyen.search(query) }.getOrDefault(emptyList()) },
            async { runCatching { app.truyenQq.search(query) }.getOrDefault(emptyList()) }
        )
        val merged = deferred.awaitAll().flatten().distinctBy { it.id }
        val nq = normalizeText(query)
        merged.sortedBy { item ->
            val nn = normalizeText(item.name)
            when {
                nn.startsWith(nq) -> 0
                nn.contains(nq) -> 1
                else -> 2
            }
        }.take(24)
    }

    fun recordHistory(query: String) {
        viewModelScope.launch { app.settings.addSearchQuery(query) }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { app.settings.removeSearchQuery(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { app.settings.clearSearchHistory() }
    }

    companion object {
        val TRENDING = listOf(
            "Đấu La Đại Lục" to true,
            "Solo Leveling" to true,
            "Thôn Phệ Tinh Không" to false,
            "Phàm Nhân Tu Tiên" to false,
            "Kiếm Lai" to false
        )
    }
}
