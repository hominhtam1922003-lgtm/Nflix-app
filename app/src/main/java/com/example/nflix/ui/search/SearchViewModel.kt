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
     * V2 (0.4): how many of the 5 sources failed the last search.
     * 5 = nothing could be searched at all (an error, not "no
     * results"); 1..4 = the screen notes the partial coverage.
     */
    val failedSources: Int = 0,
    /**
     * V2 (0.5): "Đang được tìm nhiều" — the REAL view chart (Cobe
     * view_day top titles). Replaces the 5 hand-written keywords with
     * hand-set 🔥 the sheet round called out (leftover K14).
     */
    val trending: List<TitleItem> = emptyList()
)

class SearchViewModel(private val app: NflixApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            app.settings.searchHistory.collect { h -> _state.update { it.copy(history = h) } }
        }
        // V2 (0.5): the trending section reads the same REAL chart the
        // Top tab uses (Cobe view_day). A failed load just hides the
        // section — it never falls back to invented keywords.
        viewModelScope.launch {
            try {
                val chart = app.cobe.searchByViews(weekly = false, limit = 10)
                if (chart.isNotEmpty()) {
                    app.database.titleDao().upsertAll(
                        chart.map { com.example.nflix.data.toEntity(it) }
                    )
                    _state.update { it.copy(trending = chart) }
                }
            } catch (e: Exception) { /* section stays hidden */ }
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
            async { counted { app.kkPhim.search(query) } },
            async { counted { app.hhKungfu.search(query) } },
            async { counted { app.netTruyen.search(query) } },
            async { counted { app.truyenQq.search(query) } },
            // Đợt 4: cobe results ride last — dedupe below keeps the
            // KKPhim copy of a shared title (identical slugs), so cobe
            // only surfaces titles KKPhim does not have.
            async { counted { app.cobe.search(query) } }
        )
        // Phim/Anime dedupe by slug across kkphim+cobe (their ids differ
        // by source); other families keep the exact id.
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
