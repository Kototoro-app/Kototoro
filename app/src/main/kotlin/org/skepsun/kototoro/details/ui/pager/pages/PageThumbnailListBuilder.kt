package org.skepsun.kototoro.details.ui.pager.pages

import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.domain.ChaptersLoader
import org.skepsun.kototoro.reader.ui.ReaderState

internal fun ChaptersLoader.buildPageThumbnailList(
    readerState: ReaderState? = null,
    chapters: List<ContentChapter>? = null,
): List<ListModel> {
    val snapshot = snapshot()
    val pagesByChapter = snapshot.groupBy { it.chapterId }
    return buildList(snapshot.size + (chapters?.size ?: size) * 2) {
        if (chapters != null) {
            for (chapter in chapters) {
                add(ListHeader(chapter))
                val pages = pagesByChapter[chapter.id]
                if (pages.isNullOrEmpty()) {
                    add(PageThumbnailPlaceholder(chapter.id))
                } else {
                    addAll(pages.map { page -> page.toThumbnail(readerState) })
                }
            }
            return@buildList
        }
        var previousChapterId = 0L
        for (page in snapshot) {
            if (page.chapterId != previousChapterId) {
                peekChapter(page.chapterId)?.let(::ListHeader)?.let(::add)
                previousChapterId = page.chapterId
            }
            add(page.toThumbnail(readerState))
        }
    }
}

private fun org.skepsun.kototoro.reader.ui.pager.ReaderPage.toThumbnail(
    readerState: ReaderState?,
) = PageThumbnail(
    isCurrent = readerState?.let {
        chapterId == it.chapterId && index == it.page
    } == true,
    page = this,
)
