package com.example.nflix.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.example.nflix.NflixApp
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.local.watchedSet
import com.example.nflix.data.local.withWatched
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val loading: Boolean = true,
    val pages: List<String> = emptyList(),
    val chapters: List<ChapterItem> = emptyList(),
    val chapter: ChapterItem? = null,
    val currentPage: Int = 1,
    val startPage: Int = 1,
    val userState: UserStateEntity? = null,
    val error: String = ""
)

class ReaderViewModel(
    private val app: NflixApp,
    val item: TitleItem,
    initialChapter: ChapterItem
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState(chapter = initialChapter))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val st = app.database.userStateDao().get(item.id)
            _state.update { it.copy(userState = st) }
            try {
                val detail = when (item.sourceId) {
                    "nettruyen" -> app.netTruyen.detail(item.slug)
                    "truyenqq" -> app.truyenQq.detail(item.slug, item.family)
                    else -> app.truyenQq.detail(item.slug, item.family)
                }
                app.database.titleDao().upsert(detail.item.toEntity())
                val chapters = detail.chapters.sortedBy { it.number }
                val current = chapters.firstOrNull { it.number == initialChapter.number }
                    ?: initialChapter
                val startPage = if (st != null && st.lastUnitNumber == current.number && st.pageIndex > 0)
                    st.pageIndex else 1
                _state.update { it.copy(chapters = chapters, chapter = current, startPage = startPage) }
                loadPages(current, startPage)
            } catch (e: Exception) {
                loadPages(initialChapter, 1)
            }
        }
    }

    private suspend fun loadPages(chapter: ChapterItem, startPage: Int) {
        _state.update { it.copy(loading = true, error = "") }
        try {
            val pages = when (item.sourceId) {
                "nettruyen" -> app.netTruyen.chapterPages(chapter.id)
                else -> app.truyenQq.chapterPages(chapter.id)
            }
            if (pages.isEmpty()) throw IllegalStateException("empty")
            _state.update {
                it.copy(
                    loading = false,
                    pages = pages,
                    currentPage = startPage.coerceIn(1, pages.size),
                    startPage = startPage.coerceIn(1, pages.size)
                )
            }
            saveProgress(startPage, pages.size)
        } catch (e: Exception) {
            _state.update {
                it.copy(loading = false, error = "Không tải được trang chap — kiểm tra mạng rồi thử lại.")
            }
        }
    }

    fun onPageVisible(page: Int) {
        val s = _state.value
        if (page == s.currentPage || page < 1 || page > s.pages.size) return
        _state.update { it.copy(currentPage = page) }
        viewModelScope.launch { saveProgress(page, s.pages.size) }
    }

    private suspend fun saveProgress(page: Int, pageCount: Int) {
        if (pageCount <= 0) return
        val s = _state.value
        val chapter = s.chapter ?: return
        app.database.titleDao().upsert(item.toEntity())
        val current = s.userState
            ?: app.database.userStateDao().get(item.id)
            ?: UserStateEntity(titleId = item.id)
        val finished = page >= pageCount
        val total = _state.value.chapters.maxOfOrNull { it.number }?.toInt()
            ?: current.latestCount
        val newLatest = maxOf(current.latestCount, total)
        // Same rule as the player: first read = baseline; afterwards only the
        // chapters actually read (<= this one) stop counting as new.
        val consumed = _state.value.chapters.count { it.number <= chapter.number }
        val newSeen = when {
            current.seenCount == 0 && current.latestCount == 0 -> newLatest
            consumed > 0 -> maxOf(current.seenCount, minOf(consumed, newLatest))
            else -> current.seenCount
        }
        val next = current.withWatched(chapter.number).copy(
            lastUnitLabel = chapter.label,
            lastUnitNumber = chapter.number,
            pageIndex = page,
            pageCount = pageCount,
            progressFraction = page.toFloat() / pageCount,
            finished = finished,
            seenCount = newSeen,
            latestCount = newLatest,
            lastTouchedEpochSec = System.currentTimeMillis() / 1000
        )
        app.database.userStateDao().upsert(next)
        _state.update { it.copy(userState = next) }
    }

    fun toggleBookmark() {
        viewModelScope.launch {
            val s = _state.value
            val chapter = s.chapter ?: return@launch
            val current = s.userState
                ?: app.database.userStateDao().get(item.id)
                ?: UserStateEntity(titleId = item.id)
            val isHere = current.bookmarkChapter == chapter.number && current.bookmarkPage == s.currentPage
            val next = if (isHere) {
                current.copy(bookmarkChapter = 0f, bookmarkPage = 0)
            } else {
                current.copy(bookmarkChapter = chapter.number, bookmarkPage = s.currentPage)
            }
            app.database.userStateDao().upsert(next)
            _state.update { it.copy(userState = next) }
        }
    }

    fun isBookmarkedHere(): Boolean {
        val s = _state.value
        val st = s.userState ?: return false
        return st.bookmarkPage > 0 &&
            st.bookmarkChapter == (s.chapter?.number ?: -1f) &&
            st.bookmarkPage == s.currentPage
    }

    /** Chapter numbers whose pages were already warmed into the image cache. */
    private val prefetchedChapters = mutableSetOf<Float>()

    /**
     * C5 (K7): near the end of the current chapter, warm the NEXT chapter's
     * page images into the Coil cache (in-session prefetch only — nothing is
     * stored as a download). "Đọc tiếp" then renders instantly instead of
     * starting from zero on a slow host. No-op without a next chapter.
     */
    fun prefetchNextChapter() {
        val next = nextChapter() ?: return
        if (!prefetchedChapters.add(next.number)) return
        viewModelScope.launch {
            try {
                val urls = when (item.sourceId) {
                    "nettruyen" -> app.netTruyen.chapterPages(next.id)
                    else -> app.truyenQq.chapterPages(next.id)
                }
                val loader = app.imageLoader
                urls.forEach { url ->
                    loader.enqueue(ImageRequest.Builder(app).data(url).build())
                }
            } catch (e: Exception) {
                // Warm-up failed — allow a later near-end pass to try again.
                prefetchedChapters.remove(next.number)
            }
        }
    }

    fun prevChapter(): ChapterItem? {
        val s = _state.value
        val cur = s.chapter ?: return null
        return s.chapters.filter { it.number < cur.number }.maxByOrNull { it.number }
    }

    fun nextChapter(): ChapterItem? {
        val s = _state.value
        val cur = s.chapter ?: return null
        return s.chapters.filter { it.number > cur.number }.minByOrNull { it.number }
    }

    /** Only chapters actually opened in the reader count as read. */
    fun isRead(chapter: ChapterItem): Boolean {
        val st = _state.value.userState ?: return false
        return st.watchedSet().contains(chapter.number)
    }

    fun isCurrent(chapter: ChapterItem): Boolean =
        chapter.number == (_state.value.chapter?.number ?: -1f)

    fun sourceDisplayName(): String = when (item.sourceId) {
        "truyenqq" -> "TruyenQQ"
        "nettruyen" -> "NetTruyen"
        else -> item.sourceId
    }
}
