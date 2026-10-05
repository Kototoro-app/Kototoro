package org.skepsun.kototoro.reader.novel.compose

import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.NovelBookmarkTextIndex
import org.skepsun.kototoro.bookmarks.domain.extractNovelBookmarkPreview
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkChapterProgress
import org.skepsun.kototoro.parsers.model.ContentChapter

data class NovelBookmarkResolvedPosition(val chapterId: Long, val segmentIndex: Int, val windowIndex: Int)

data class NovelBookmarkRequest(val id: Long, val bookmark: Bookmark)

/** Paged and scrolling hosts project their rendered text to the same portable locator. */
internal fun resolveNovelBookmarkPositions(
    segments: List<Pair<Long, String>>,
    bookmarks: List<Bookmark>,
    chapters: List<ContentChapter>,
): Map<Long, NovelBookmarkResolvedPosition> {
    val windows = segments.withIndex().groupBy { it.value.first }
    val indices = windows.mapValues { (_, window) -> NovelBookmarkTextIndex(window.map { it.value.second }) }
    return bookmarks.mapNotNull { bookmark ->
        val chapter = chapters.firstOrNull { it.id == bookmark.chapterId } ?: return@mapNotNull null
        val branch = chapters.filter { it.branch == chapter.branch }
        val window = windows[chapter.id] ?: return@mapNotNull null
        val hint = novelBookmarkChapterProgress(bookmark.percent, branch.indexOf(chapter), branch.size)
        val index = indices.getValue(chapter.id).resolve(
            extractNovelBookmarkPreview(bookmark.imageUrl),
            bookmark.page,
            hint,
        ) ?: return@mapNotNull null
        bookmark.pageId to NovelBookmarkResolvedPosition(chapter.id, index, window[index].index)
    }.toMap()
}
