package com.example.nflix.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.model.Family
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
    val history: List<String> = emptyList(),
    /**
     * V2 (0.4): how many of the 4 sources failed the last search.
     * 4 = nothing could be searched at all (an error, not "no
     * results"); 1..3 = the screen notes the partial coverage.
     * 1.3.3: Cobe left the search pool together with every other
     * Phim path (user order 09:42) — it only ever contributed
     * Family.PHIM items, and KKPhim search covers that catalog.
     * 1.3.4: REVERSED again (user order 11:39) — Phim is Cobe-only,
     * KKPhim is gone from the app, and search below calls Cobe.
     */
    val failedSources: Int = 0
)

class SearchViewModel(private val app: NflixApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            app.settings.searchHistory.collect { h -> _state.update { it.copy(history = h) } }
        }
        // 1.3.3: the V2 "Đang được tìm nhiều" strip (Cobe's view_day
        // chart) is REMOVED with the rest of the Cobe Phim surfaces —
        // KKPhim publishes no view counts, so there is no honest KK
        // equivalent to swap in and no invented replacement is shown.
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
            val (results, failed) = searchAll(query)
            // Only publish if the query is still what the user sees.
            if (_state.value.query == query) {
                _state.update {
                    it.copy(
                        suggestions = results,
                        searching = false,
                        failedSources = failed
                    )
                }
            }
        }
    }

    /** Outcome of one search round: merged results + failed-source count. */
    data class SearchOutcome(val results: List<TitleItem>, val failedSources: Int)

    /** Full search (results page) — same merge, no debounce. */
    suspend fun searchNow(query: String): SearchOutcome = searchAll(query)

    private suspend fun searchAll(query: String): SearchOutcome = coroutineScope {
        // V2 (0.4): failures are COUNTED, not swallowed — the screen
        // distinguishes "no title matches" from "the sources could not
        // be reached at all".
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
        suspend fun <T> counted(block: suspend () -> List<T>): List<T> =
            try {
                block()
            } catch (e: Exception) {
                failedCount.incrementAndGet()
                emptyList()
            }
        val deferred = listOf(
            // 1.3.4: Phim search is Cobe's (KKPhim dropped by user order).
            async { counted { app.phimcobe.search(query) } },
            async { counted { app.hhKungfu.search(query) } },
            async { counted { app.netTruyen.search(query) } },
            async { counted { app.truyenQq.search(query) } }
        )
        // Phim/Anime dedupe by slug (a title can surface under more
        // than one source-shaped id); other families keep the exact id.
        val merged = deferred.awaitAll().flatten().distinctBy { item ->
            if (item.family == Family.PHIM || item.family == Family.ANIME) {
                "slug:${item.slug}"
            } else {
                item.id
            }
        }
        val nq = normalizeText(query)
        val sorted = merged.sortedBy { item ->
            val nn = normalizeText(item.name)
            when {
                nn.startsWith(nq) -> 0
                nn.contains(nq) -> 1
                else -> 2
            }
        }.take(24)
        SearchOutcome(sorted, failedCount.get())
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
}
