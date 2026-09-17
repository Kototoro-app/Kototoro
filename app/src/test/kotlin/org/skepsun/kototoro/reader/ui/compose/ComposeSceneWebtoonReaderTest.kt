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
    fun `scene frame computes progress offset from active page top`() {
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
        val progress1 = scene.resolve(vp1).progress
        assertEquals(PageId(1L), progress1.activePageId)
        assertEquals(500f, progress1.intraPageOffsetPx)

        // Viewport is at scrollY = 1800, so PageId(2L) starts at Y=1500, relative scroll is 1800 - 1500 = 300
        val vp2 = ReaderViewport(FloatRect.fromLtwh(0f, 1800f, 1000f, 2000f))
        val progress2 = scene.resolve(vp2).progress
        assertEquals(PageId(2L), progress2.activePageId)
        assertEquals(300f, progress2.intraPageOffsetPx)
    }

    @Test
    fun `ComposeSceneScrollState clamps to maxScrollY and supports snapBy anchor compensation`() {
        val state = org.skepsun.kototoro.reader.render.compose.ComposeSceneScrollState(initialScrollY = 100f)
        state.maxScrollY = 5000f

        assertEquals(100f, state.scrollY)

        // Test snapBy positive
        state.snapBy(250f)
        assertEquals(350f, state.scrollY)

        // Test snapBy negative
        state.snapBy(-100f)
        assertEquals(250f, state.scrollY)

        // Test clamping beyond max
        state.snapBy(10000f)
        assertEquals(5000f, state.scrollY)

        // Test clamping below zero
        state.snapBy(-20000f)
        assertEquals(0f, state.scrollY)
    }

    @Test
    fun `initial page scroll position resolves pageTop plus intraPageOffset`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500), // Page 1: 0..1500
                PageId(2L) to PageGeometryHint.Exact(1000, 3000), // Page 2: 1500..4500
                PageId(3L) to PageGeometryHint.Exact(1000, 2000), // Page 3: 4500..6500
            ),
        )

        val page2Top = scene.resolvePageScrollPosition(PageId(2L))
        assertEquals(1500f, page2Top)

        val intraPageScroll = 300
        val targetScrollY = (page2Top!! + intraPageScroll.toFloat())
        assertEquals(1800f, targetScrollY)

        val vp = ReaderViewport(FloatRect.fromLtwh(0f, targetScrollY, 1000f, 2000f))
        val progress = scene.resolve(vp).progress
        assertEquals(PageId(2L), progress.activePageId)
        assertEquals(300f, progress.intraPageOffsetPx)
    }
}
