package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef

class DesktopBookmarkShelfTest {
    private val source = SourceRef("MIHON_1", "zh", "MANGA")
    private val adultSource = SourceRef("MIHON_2", "zh", "HENTAI_MANGA")

    private fun content(id: Long, title: String, from: SourceRef = source) =
        SourceContent(id, title, emptySet(), "/$id", "https://fixture.invalid/$id", -1f, null, null, emptySet(),
            null, emptySet(), from)

    private fun bookmark(contentId: Long, createdAt: Long, page: Int = 0) =
        DesktopBookmark(contentId, pageId = contentId * 100 + page, chapterId = contentId, page = page, scroll = 0,
            createdAt = createdAt, percent = 0f)

    private fun group(content: SourceContent, vararg createdAt: Long) =
        DesktopBookmarkGroup(content, createdAt.map { bookmark(content.id, it) })

    @Test
    fun `the work with the newest saved position comes first and works are ordered by that position`() {
        val shelf = desktopBookmarkShelf(
            listOf(
                group(content(1, "旧书签"), 10, 20),
                group(content(2, "新书签"), 90),
                group(content(3, "中间"), 50, 60),
            ),
            hideNsfw = false,
        )
        assertEquals(listOf(2L, 3L, 1L), shelf.map { it.content.id })
    }

    @Test
    fun `works without saved positions are not sections`() {
        val shelf = desktopBookmarkShelf(
            listOf(group(content(1, "有书签"), 10), DesktopBookmarkGroup(content(2, "空作品"), emptyList())),
            hideNsfw = false,
        )
        assertEquals(listOf(1L), shelf.map { it.content.id })
    }

    @Test
    fun `the bookmarks filter hides adult works only when it is on`() {
        val groups = listOf(group(content(1, "普通作品"), 10), group(content(2, "成人作品", adultSource), 20))
        assertEquals(listOf(2L, 1L), desktopBookmarkShelf(groups, hideNsfw = false).map { it.content.id })
        assertEquals(listOf(1L), desktopBookmarkShelf(groups, hideNsfw = true).map { it.content.id })
        assertEquals(emptyList<Long>(), desktopBookmarkShelf(groups.filter { it.content.id == 2L },
            hideNsfw = true).map { it.content.id })
    }

    @Test
    fun `positions inside a section keep the stored page order`() {
        val saved = listOf(bookmark(1, 10, page = 4), bookmark(1, 10, page = 1))
        val shelf = desktopBookmarkShelf(listOf(DesktopBookmarkGroup(content(1, "作品"), saved)), hideNsfw = false)
        assertEquals(listOf(4, 1), shelf.single().bookmarks.map { it.page })
    }

    @Test
    fun `a position saved under the chapter id is a chapter bookmark`() {
        val page = DesktopBookmark(1, pageId = 107, chapterId = 3, page = 6, scroll = 0, createdAt = 1, percent = 0.4f)
        val chapter = DesktopBookmark(1, pageId = 3, chapterId = 3, page = 0, scroll = 0, createdAt = 1, percent = 0f)
        assertFalse(page.isChapterBookmark)
        assertTrue(chapter.isChapterBookmark)
    }
}
