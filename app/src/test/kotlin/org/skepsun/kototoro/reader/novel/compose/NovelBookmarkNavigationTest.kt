package org.skepsun.kototoro.reader.novel.compose

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import java.time.Instant

class NovelBookmarkNavigationTest {
    private val chapters = listOf(
        ContentChapter(10, "第一章", 1f, 0, "https://fixture.invalid/1", null, 0, "A", UnknownContentSource),
        ContentChapter(20, "第二章", 2f, 0, "https://fixture.invalid/2", null, 0, "A", UnknownContentSource),
    )
    private val bookmark = Bookmark(
        mockk(),
        123,
        20,
        page = 700,
        scroll = 0,
        imageUrl = "目标段落内容",
        createdAt = Instant.EPOCH,
        percent = .8f,
    )

    @Test
    fun `paged and scrolling windows project the same excerpt to their local and global indices`() {
        val pages = listOf(10L to "目标段落内容", 20L to "前文目标", 20L to "段落内容尾文")
        val blocks = listOf(10L to "目标段落内容", 20L to "前文", 20L to "", 20L to "目标段落内容", 20L to "尾文")
        assertEquals(
            NovelBookmarkResolvedPosition(20, 0, 1),
            resolveNovelBookmarkPositions(pages, listOf(bookmark), chapters)[123],
        )
        assertEquals(
            NovelBookmarkResolvedPosition(20, 2, 3),
            resolveNovelBookmarkPositions(blocks, listOf(bookmark), chapters)[123],
        )
        assertTrue(resolveNovelBookmarkPositions(blocks, listOf(bookmark.copy(imageUrl = "已变化")), chapters).isEmpty())
    }

    @Test
    fun `resolved bookmark icon follows new pagination and repository removal`() {
        val model = NovelComposeReaderViewModel()
        model.publishChapter(20, 1, "第二章", "正文", NovelReaderSettings(), null)
        model.publishNovelBookmarks(listOf(bookmark))
        model.publishBookmarkPositions(mapOf(123L to NovelBookmarkResolvedPosition(20, 1, 2)))
        model.publishPagedPosition(1, 3, 10, 20, "正文")
        assertTrue(model.uiState.value.isCurrentPageBookmarked)
        model.publishPagedPosition(2, 3, 20, 30, "正文")
        assertFalse(model.uiState.value.isCurrentPageBookmarked)
        model.publishPosition(NovelReadingPosition(20, 1, 3, .5f))
        assertTrue(model.uiState.value.isCurrentPageBookmarked)
        model.publishNovelBookmarks(emptyList())
        assertFalse(model.uiState.value.isCurrentPageBookmarked)
    }

    @Test
    fun `bookmark requests survive chapter publication and reject stale consumption and scroll reports`() {
        val model = NovelComposeReaderViewModel()
        model.publishNovelBookmarks(listOf(bookmark))
        model.requestBookmark(bookmark)
        val first = model.uiState.value.bookmarkRequest!!
        model.requestBookmark(bookmark)
        val second = model.uiState.value.bookmarkRequest!!
        assertTrue(second.id > first.id)
        model.consumeBookmarkRequest(first.id)
        assertEquals(second, model.uiState.value.bookmarkRequest)
        model.publishChapter(20, 1, "第二章", "正文", NovelReaderSettings(), null)
        assertEquals(second, model.uiState.value.bookmarkRequest)
        assertEquals(listOf(bookmark), model.uiState.value.novelBookmarks)
        model.publishScrollPosition(NovelComposeScrollPosition(0, 0))
        assertNull(model.uiState.value.scrollPosition)
        model.focusContinuousChapter(0)
        assertEquals(20L, model.uiState.value.chapterId)
        model.consumeBookmarkRequest(second.id)
        model.publishScrollPosition(NovelComposeScrollPosition(2, 0))
        assertEquals(2, model.uiState.value.scrollPosition?.firstVisibleBlock)
    }
}
