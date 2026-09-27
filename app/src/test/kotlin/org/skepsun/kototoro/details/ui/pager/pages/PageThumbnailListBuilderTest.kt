package org.skepsun.kototoro.details.ui.pager.pages

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.domain.ChaptersLoader
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class PageThumbnailListBuilderTest {

    @Test
    fun `thumbnail keys stay unique when chapters share a page id`() {
        // A source that appends the same banner image to every chapter yields the same page id twice.
        val loader = loaderOf(page(chapterId = 1L, index = 0, id = 42L), page(chapterId = 2L, index = 0, id = 42L))

        val keys = loader.buildPageThumbnailList(chapters = listOf(chapter(1L), chapter(2L)))
            .filterIsInstance<PageThumbnail>()
            .map { it.listKey }

        assertEquals(2, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `repeated chapter entries are emitted once`() {
        val loader = loaderOf(page(chapterId = 1L, index = 0, id = 1L), page(chapterId = 1L, index = 1, id = 2L))

        val items = loader.buildPageThumbnailList(chapters = listOf(chapter(1L), chapter(1L), chapter(3L)))

        assertEquals(2, items.count { it is PageThumbnail })
        assertEquals(listOf(3L), items.filterIsInstance<PageThumbnailPlaceholder>().map { it.chapterId })
    }

    private fun loaderOf(vararg pages: ReaderPage): ChaptersLoader = mockk {
        every { snapshot() } returns pages.toList()
        every { size } returns 0
    }

    private fun page(chapterId: Long, index: Int, id: Long) = ReaderPage(
        id = id,
        url = "https://example.org/banner.jpg",
        preview = null,
        headers = null,
        chapterId = chapterId,
        index = index,
        source = TestContentSource,
    )

    private fun chapter(id: Long) = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "/chapter/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = TestContentSource,
    )
}
