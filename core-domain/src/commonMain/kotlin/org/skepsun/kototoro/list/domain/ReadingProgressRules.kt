package org.skepsun.kototoro.list.domain

/** Completion threshold shared by history filtering and the Android progress indicator. */
fun isReadingCompleted(percent: Float): Boolean = percent >= 0.99999f

/**
 * Overall progress of a title while the reader sits on [pageIndex] of [pagesCount] inside the chapter at
 * [chapterIndex] of [chaptersCount].
 *
 * The last page of the last chapter is pinned to 100%: multiplying float chapter weights otherwise lands a
 * completed read at 99.x%, which leaves the title neither completed nor marked as read (issue #750).
 *
 * Returns [ReadingProgress.PROGRESS_NONE] when the reader cannot tell where it is.
 */
fun readingProgressOf(
    chapterIndex: Int,
    chaptersCount: Int,
    pageIndex: Int,
    pagesCount: Int,
): Float {
    if (chaptersCount <= 0 || pagesCount <= 0 || chapterIndex < 0) {
        return ReadingProgress.PROGRESS_NONE
    }
    if (chapterIndex >= chaptersCount - 1 && pageIndex >= pagesCount - 1) {
        return ReadingProgress.PROGRESS_COMPLETED
    }
    val pagePercent = (pageIndex + 1) / pagesCount.toFloat()
    val perChapter = 1f / chaptersCount
    return perChapter * chapterIndex + perChapter * pagePercent
}
