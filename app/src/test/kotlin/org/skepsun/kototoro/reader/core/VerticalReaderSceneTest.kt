package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerticalReaderSceneTest {

    @Test
    fun `lays out pages sequentially in vertical scene space`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500),
                PageId(2L) to PageGeometryHint.Exact(1000, 3000),
                PageId(3L) to PageGeometryHint.Estimated(0.5f),
            ),
        )

        assertEquals(3, scene.pageCount)
        assertEquals(6500f, scene.totalSceneHeight) // 1500 + 3000 + 2000

        val pages = scene.pageGeometries
        assertEquals(FloatRect.fromLtwh(0f, 0f, 1000f, 1500f), pages[0].sceneBounds)
        assertEquals(FloatRect.fromLtwh(0f, 1500f, 1000f, 3000f), pages[1].sceneBounds)
        assertEquals(FloatRect.fromLtwh(0f, 4500f, 1000f, 2000f), pages[2].sceneBounds)
    }

    @Test
    fun `resolvePageScrollPosition returns exact top offset`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1000, 1000),
                PageId(20L) to PageGeometryHint.Exact(1000, 2000),
            ),
        )

        assertEquals(0f, scene.resolvePageScrollPosition(PageId(10L)))
        assertEquals(1000f, scene.resolvePageScrollPosition(PageId(20L)))
        assertNull(scene.resolvePageScrollPosition(PageId(999L)))
    }

    @Test
    fun `resolve returns intersecting pages with fast binary search`() {
        val pages = (1..50).map { i ->
            PageId(i.toLong()) to PageGeometryHint.Exact(1000, 1000) // Each page 1000px high
        }
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = pages,
        )

        // Viewport looking at Y = 25000..27000 (pages 25, 26, 27)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 25500f, 1000f, 2000f))
        val frame = scene.resolve(viewport)

        assertEquals(3, frame.visibleNodes.size)
        assertEquals(PageId(26L), frame.visibleNodes[0].pageId) // Page 26: 25000..26000
        assertEquals(PageId(27L), frame.visibleNodes[1].pageId) // Page 27: 26000..27000
        assertEquals(PageId(28L), frame.visibleNodes[2].pageId) // Page 28: 27000..28000
        assertEquals(PageId(26L), frame.progress.lowerPageId)
        assertEquals(PageId(28L), frame.progress.upperPageId)
        assertEquals(PageId(26L), frame.progress.activePageId)
        assertEquals(500f, frame.progress.intraPageOffsetPx)
    }

    @Test
    fun `resolveActivePageId anchors to first visible page at viewport top`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 1000,
            initialPages = listOf(
                PageId(105L) to PageGeometryHint.Exact(1000, 400),  // 0..400
                PageId(201L) to PageGeometryHint.Exact(1000, 800),  // 400..1200
            ),
        )

        // Viewport looking at 200..1200 (page 105 spans 0..400, covering viewport top at 200)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 200f, 1000f, 1000f))
        assertEquals(PageId(105L), scene.resolveActivePageId(viewport))

        // Viewport looking at 0..300 (page 105 spans 0..400, covering viewport top at 0)
        val topViewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 300f))
        assertEquals(PageId(105L), scene.resolveActivePageId(topViewport))

        // Viewport looking at 500..1500 (page 201 spans 400..1200, covering viewport top at 500)
        val nextViewport = ReaderViewport(FloatRect.fromLtwh(0f, 500f, 1000f, 1000f))
        assertEquals(PageId(201L), scene.resolveActivePageId(nextViewport))
    }

    @Test
    fun `save and restore roundtrip preserves exact viewport position across sessions without page drift`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1200), // 0..1200
                PageId(2L) to PageGeometryHint.Exact(1000, 1200), // 1200..2400
                PageId(3L) to PageGeometryHint.Exact(1000, 1200), // 2400..3600
                PageId(4L) to PageGeometryHint.Exact(1000, 1200), // 3600..4800
            ),
        )

        // User is reading at scrollY = 1500 (inside Page 2: 1200..2400, 300px into Page 2)
        val initialScrollY = 1500f
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, initialScrollY, 1000f, 2000f))
        val activeId = scene.resolveActivePageId(vp)
        assertEquals(PageId(2L), activeId)

        val frame = scene.resolve(vp)
        val node = frame.visibleNodes.first { it.pageId == activeId }
        val savedScroll = (vp.bounds.top - node.sceneBounds.top).toInt()
        assertEquals(300, savedScroll)

        // Session 2: User re-enters at Page 2 with savedScroll = 300
        val restoredPageTop = scene.resolvePageScrollPosition(activeId!!)!!
        val restoredScrollY = restoredPageTop + savedScroll.toFloat()
        assertEquals(initialScrollY, restoredScrollY)

        // Verify active page on re-entry is still Page 2, not Page 3 or Page 4
        val restoredVp = ReaderViewport(FloatRect.fromLtwh(0f, restoredScrollY, 1000f, 2000f))
        assertEquals(PageId(2L), scene.resolveActivePageId(restoredVp))
    }

    @Test
    fun `updatePageHint does not shift viewport when updated page is below viewport`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1000), // 0..1000
                PageId(2L) to PageGeometryHint.Estimated(1f),     // 1000..3000
            ),
        )

        // Viewport looking at 0..800 (Page 1)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 800f))

        // Page 2 (below viewport) updates from 2000px to 4000px
        val compensation = scene.updatePageHint(
            pageId = PageId(2L),
            newHint = PageGeometryHint.Exact(1000, 4000),
            currentViewport = viewport,
        )

        assertEquals(0f, compensation?.deltaY)
        assertEquals(0f, compensation?.compensatedViewport?.bounds?.top)
        assertEquals(5000f, scene.totalSceneHeight) // 1000 + 4000
    }

    @Test
    fun `updatePageHint shifts viewport down when updated page is above viewport`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f),     // 0..2000
                PageId(2L) to PageGeometryHint.Exact(1000, 1000), // 2000..3000
            ),
        )

        // Viewport looking at Page 2: Y = 2200..3000
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 2200f, 1000f, 800f))

        // Page 1 (above viewport) updates from 2000px to 3500px (+1500px change)
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(1000, 3500),
            currentViewport = viewport,
        )

        // Viewport top must shift from 2200 to 3700 (+1500) so Page 2 remains in exact same visual spot
        assertEquals(1500f, compensation?.deltaY)
        assertEquals(3700f, compensation?.compensatedViewport?.bounds?.top)
    }

    @Test
    fun `updatePageHint preserves intra-page absolute offset when reading the changing page`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f), // 0..2000
            ),
        )

        // Viewport is 500px into Page 1
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 500f, 1000f, 1000f))

        // Page 1 resolves exact dimensions: 1000x4000 (new height = 4000px)
        // Since intraPageOffset (500px) <= newHeight (4000px), reading offset must remain exactly 500px (deltaY = 0f)
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(1000, 4000),
            currentViewport = viewport,
        )

        assertEquals(0f, compensation?.deltaY)
        assertEquals(500f, compensation?.compensatedViewport?.bounds?.top)
    }

    @Test
    fun `updatePageHint clamps intra-page offset when page shrinks below viewport top`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f), // 0..2000
            ),
        )

        // Viewport is 1500px into Page 1
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1500f, 1000f, 1000f))

        // Page 1 resolves exact dimensions: 1000x1000 (new height = 1000px)
        // Since intraPageOffset (1500px) > newHeight (1000px), viewport clamps to 1000px (deltaY = -500f)
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(1000, 1000),
            currentViewport = viewport,
        )

        assertEquals(-500f, compensation?.deltaY)
        assertEquals(1000f, compensation?.compensatedViewport?.bounds?.top)
    }

    @Test
    fun `lays out pages with pageSpacingPx between pages`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500),
                PageId(2L) to PageGeometryHint.Exact(1000, 2000),
                PageId(3L) to PageGeometryHint.Exact(1000, 1000),
            ),
            pageSpacingPx = 50,
        )

        assertEquals(3, scene.pageCount)
        // Heights: 1500 + 2000 + 1000 = 4500. Spacings: 2 * 50 = 100. Total = 4600.
        assertEquals(4600f, scene.totalSceneHeight)

        val pages = scene.pageGeometries
        assertEquals(FloatRect.fromLtwh(0f, 0f, 1000f, 1500f), pages[0].sceneBounds)
        assertEquals(FloatRect.fromLtwh(0f, 1550f, 1000f, 2000f), pages[1].sceneBounds)
        assertEquals(FloatRect.fromLtwh(0f, 3600f, 1000f, 1000f), pages[2].sceneBounds)

        // Resolving page scroll positions
        assertEquals(0f, scene.resolvePageScrollPosition(PageId(1L)))
        assertEquals(1550f, scene.resolvePageScrollPosition(PageId(2L)))
        assertEquals(3600f, scene.resolvePageScrollPosition(PageId(3L)))

        // Viewport falling exactly into the gap (1500..1550)
        val gapViewport = ReaderViewport(FloatRect.fromLtwh(0f, 1520f, 1000f, 1000f))
        val frame = scene.resolve(gapViewport)
        // Page 1 ends at 1500 (does not intersect 1520..2520). Page 2 spans 1550..3550 (intersects).
        assertEquals(1, frame.visibleNodes.size)
        assertEquals(PageId(2L), frame.visibleNodes[0].pageId)
        assertEquals(PageId(2L), scene.resolveActivePageId(gapViewport))
    }

    @Test
    fun `updatePages preserves existing exact hints and anchors prepended pages with zero jump`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1000, 1000), // 0..1000
                PageId(20L) to PageGeometryHint.Estimated(1f),     // 1020..3020
            ),
            pageSpacingPx = 20,
        )

        // Page 20 gets resolved to Exact(1000, 1800) during reading
        scene.updatePageHint(PageId(20L), PageGeometryHint.Exact(1000, 1800))

        // Reader is currently looking at Page 20, 300px into the page
        // Page 20 starts at 1020f. Viewport top = 1320f.
        val currentViewport = ReaderViewport(FloatRect.fromLtwh(0f, 1320f, 1000f, 1500f))
        assertEquals(PageId(20L), scene.resolveActivePageId(currentViewport))

        // Previous chapter is prepended: Page 1, Page 2
        val newPages = listOf(
            PageId(1L) to PageGeometryHint.Exact(1000, 1200),
            PageId(2L) to PageGeometryHint.Exact(1000, 800),
            PageId(10L) to PageGeometryHint.Estimated(1f), // Re-passed as Estimated in new list
            PageId(20L) to PageGeometryHint.Estimated(1f), // Re-passed as Estimated in new list
        )

        val compensation = scene.updatePages(newPages, currentViewport)
        assertTrue(compensation != null)

        // Verify that Page 10 and Page 20 retained their Exact dimensions!
        // Page 1: 1200
        // Gap: 20
        // Page 2: 800
        // Gap: 20
        // Page 10: 1000 (retained Exact from initial)
        // Gap: 20
        // Page 20: 1800 (retained Exact from updatePageHint)
        // New top of Page 20 = 1200 + 20 + 800 + 20 + 1000 + 20 = 3060f.
        assertEquals(3060f, scene.resolvePageScrollPosition(PageId(20L)))

        // Old Page 20 top was 1020f. Delta = 3060 - 1020 = +2040f.
        assertEquals(2040f, compensation!!.deltaY)
        assertEquals(1320f + 2040f, compensation.compensatedViewport.bounds.top)

        // In the compensated viewport (top = 3360), active page is still Page 20 and intra-page offset is 300px!
        val compensatedFrame = scene.resolve(compensation.compensatedViewport)
        assertEquals(PageId(20L), compensatedFrame.progress.activePageId)
        assertEquals(300f, compensatedFrame.progress.intraPageOffsetPx)
    }

    @Test
    fun `updatePages appending pages returns zero deltaY`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1000),
            ),
            pageSpacingPx = 10,
        )

        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 200f, 1000f, 1500f))
        val newPages = listOf(
            PageId(1L) to PageGeometryHint.Exact(1000, 1000),
            PageId(2L) to PageGeometryHint.Exact(1000, 1500),
            PageId(3L) to PageGeometryHint.Exact(1000, 1200),
        )

        val compensation = scene.updatePages(newPages, viewport)
        assertTrue(compensation != null)
        assertEquals(0f, compensation!!.deltaY)
        assertEquals(200f, compensation.compensatedViewport.bounds.top)
    }
}
