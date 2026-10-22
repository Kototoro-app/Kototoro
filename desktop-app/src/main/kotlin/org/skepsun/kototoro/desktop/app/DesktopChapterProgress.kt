package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.chapters.ChapterSection

/**
 * Details' per-chapter "read" mark: history moves onto that chapter, keeping the same branch order the reader
 * writes, so the card turns current and chapters below it stay unread. Null when the list is stale.
 */
internal fun desktopMarkReadTarget(content: SourceContent, chapter: SourceChapter): SourceChapter? =
    content.chapters.orEmpty().firstOrNull {
        it.branch == chapter.branch && it.id == chapter.id && it.url == chapter.url
    }

/**
 * Details' per-chapter "unread" mark. Moving back onto the previous chapter of the same branch is what Android
 * does; for the first chapter the record is cleared instead of pointing history at nothing.
 */
internal sealed interface DesktopUnreadMark {
    /** History stays untouched because the chapter is no longer in the refreshed list. */
    data object Missing : DesktopUnreadMark

    /** The work shows no progress afterwards. */
    data object Clear : DesktopUnreadMark

    /** History moves onto this earlier chapter, so it and every later chapter count as unread again. */
    data class Move(val chapter: SourceChapter) : DesktopUnreadMark
}

/** Reading order is the directory's own order, not a re-sort: the reader walks the same list to write progress. */
internal fun desktopUnreadMark(content: SourceContent, chapter: SourceChapter): DesktopUnreadMark {
    val ordered = desktopChapterList(content.chapters.orEmpty(), chapter.branch, "", false).sections
        .mapNotNull { (it as? ChapterSection.Item)?.chapter }
    val index = ordered.indexOfFirst { it.id == chapter.id && it.url == chapter.url }
    if (index < 0) return DesktopUnreadMark.Missing
    return ordered.getOrNull(index - 1)?.let(DesktopUnreadMark::Move) ?: DesktopUnreadMark.Clear
}
