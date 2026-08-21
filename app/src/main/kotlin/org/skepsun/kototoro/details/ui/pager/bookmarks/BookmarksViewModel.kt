package org.skepsun.kototoro.details.ui.pager.bookmarks

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
) : BaseViewModel(), FlowCollector<ContentDetails?> {

    private val manga = MutableStateFlow<Content?>(null)
    val onActionDone = MutableEventFlow<ReversibleAction>()

    val gridScale = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_GRID_SIZE_PAGES,
        valueProducer = { gridSizePages / 100f },
    )

    val content: StateFlow<List<ListModel>> = manga.filterNotNull().flatMapLatest { m ->
        bookmarksRepository.observeBookmarks(m)
            .map { mapList(m, it) }
    }.withErrorHandling()
        .filterNotNull()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, listOf(LoadingState))

    override suspend fun emit(value: ContentDetails?) {
        manga.value = value?.toContent()
    }

    fun removeBookmarks(ids: Set<Long>) {
        launchJob(Dispatchers.Default) {
            val handle = bookmarksRepository.removeBookmarks(ids)
            onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
        }
    }

    fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
        launchLoadingJob(Dispatchers.Default) {
            val m = manga.requireValue()
            val tasks = content.value.mapNotNull {
                if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
                PageSaveHelper.Task(
                    manga = m,
                    chapterId = it.chapterId,
                    pageNumber = it.page + 1,
                    page = it.toContentPage(),
                )
            }
            val dest = pageSaveHelper.save(tasks)
            val msg = if (dest.size == 1) R.string.page_saved else R.string.pages_saved
            onActionDone.call(ReversibleAction(msg, null))
        }
    }

    private fun mapList(manga: Content, bookmarks: List<Bookmark>): List<ListModel>? {
        val chapters = manga.chapters ?: return null
        val bookmarksMap = bookmarks.groupBy { it.chapterId }
        val result = ArrayList<ListModel>(bookmarks.size + bookmarksMap.size)
        for (chapter in chapters) {
            val b = bookmarksMap[chapter.id]
            if (b.isNullOrEmpty()) {
                continue
            }
            result += ListHeader(chapter)
            result.addAll(b)
        }
        if (result.isEmpty()) {
            result.add(
                EmptyState(
                    icon = 0,
                    textPrimary = R.string.no_bookmarks_yet,
                    textSecondary = R.string.no_bookmarks_summary,
                    actionStringRes = 0,
                ),
            )
        }
        return result
    }
}
