package com.example.nflix.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nflix.NflixApp
import com.example.nflix.data.local.UserStateEntity
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
                    "truyenqq" -> app.truyenQq.detail(item.slug)
                    else -> app.truyenQq.detail(item.slug)
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
        val next = current.copy(
            lastUnitLabel = chapter.label,
            lastUnitNumber = chapter.number,
            pageIndex = page,
            pageCount = pageCount,
            progressFraction = page.toFloat() / pageCount,
            finished = finished,
            seenCount = if (finished) maxOf(current.seenCount, chapter.number.toInt()) else current.seenCount,
            latestCount = maxOf(current.latestCount, total),
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

    fun isRead(chapter: ChapterItem): Boolean {
        val st = _state.value.userState ?: return false
        return chapter.number < st.lastUnitNumber ||
            (chapter.number == st.lastUnitNumber && st.finished)
    }

    fun isCurrent(chapter: ChapterItem): Boolean =
        chapter.number == (_state.value.chapter?.number ?: -1f)

    fun sourceDisplayName(): String = when (item.sourceId) {
        "truyenqq" -> "TruyenQQ"
        "nettruyen" -> "NetTruyen"
        else -> item.sourceId
    }
}
