package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class ComposeSceneWebtoonReaderTest {

    private fun createPage(id: Long, index: Int = 0): ReaderPage {
        return ReaderPage(
            id = id,
            url = "https://example.com/page$id.jpg",
            preview = null,
            headers = null,
            chapterId = 10L,
            index = index,
            source = TestContentSource,
        )
    }

    @Test
    fun `createInitialScenePageHints creates estimated hints for all pages`() {
        val pages = listOf(
            createPage(1L, 0),
            createPage(2L, 1),
            createPage(3L, 2),
        )

        val hints = createInitialScenePageHints(pages, defaultRatio = 1.5f)

        assertEquals(3, hints.size)
        assertEquals(PageId(pages[0].readerKey), hints[0].first)
        assertTrue(hints[0].second is PageGeometryHint.Estimated)
        assertEquals(1.5f, (hints[0].second as PageGeometryHint.Estimated).ratio)
    }

    @Test
    fun `resolveActivePageRelativeScroll computes offset from node top to viewport top`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500),
                PageId(2L) to PageGeometryHint.Exact(1000, 1500),
            ),
        )

        // Viewport is at scrollY = 500, so PageId(1L) node starts at Y=0, viewport top is at 500
        val vp1 = ReaderViewport(FloatRect.fromLtwh(0f, 500f, 1000f, 2000f))
        val scroll1 = resolveActivePageRelativeScroll(scene, vp1, PageId(1L))
        assertEquals(500, scroll1)

        // Viewport is at scrollY = 1800, so PageId(2L) starts at Y=1500, relative scroll is 1800 - 1500 = 300
        val vp2 = ReaderViewport(FloatRect.fromLtwh(0f, 1800f, 1000f, 2000f))
        val scroll2 = resolveActivePageRelativeScroll(scene, vp2, PageId(2L))
        assertEquals(300, scroll2)
    }

    @Test
    fun `resolveActivePageRelativeScroll handles null active page gracefully`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(PageId(1L) to PageGeometryHint.Exact(1000, 1000)),
        )
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, 100f, 1000f, 2000f))
        assertEquals(0, resolveActivePageRelativeScroll(scene, vp, null))
    }
}
