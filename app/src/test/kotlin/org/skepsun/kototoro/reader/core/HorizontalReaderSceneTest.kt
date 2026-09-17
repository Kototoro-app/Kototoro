package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HorizontalReaderSceneTest {

    @Test
    fun `lays out pages sequentially in LTR scene space`() {
        val scene = HorizontalReaderScene(
            availableHeight = 1500,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500),
                PageId(2L) to PageGeometryHint.Exact(2000, 1500),
                PageId(3L) to PageGeometryHint.Estimated(0.5f),
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        assertEquals(3, scene.pageCount)
        assertEquals(4000f, scene.totalSceneWidth) // 1000 + 2000 + 1000

        val pages = scene.pageGeometries
        assertEquals(FloatRect.fromLtwh(0f, 0f, 1000f, 1500f), pages[0].sceneBounds)
        assertEquals(FloatRect.fromLtwh(1000f, 0f, 2000f, 1500f), pages[1].sceneBounds)
        assertEquals(FloatRect.fromLtwh(3000f, 0f, 1000f, 1500f), pages[2].sceneBounds)
    }

    @Test
    fun `lays out pages sequentially in RTL scene space preserving reading order`() {
        val scene = HorizontalReaderScene(
            availableHeight = 1500,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1000, 1500), // width = 1000
                PageId(2L) to PageGeometryHint.Exact(2000, 1500), // width = 2000
                PageId(3L) to PageGeometryHint.Estimated(0.5f),    // width = 1000
            ),
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        assertEquals(3, scene.pageCount)
        assertEquals(4000f, scene.totalSceneWidth) // 1000 + 2000 + 1000

        val pages = scene.pageGeometries
        // Page 1 is first in reading order: placed at the rightmost position [3000..4000]
        assertEquals(PageId(1L), pages[0].pageId)
        assertEquals(FloatRect.fromLtwh(3000f, 0f, 1000f, 1500f), pages[0].sceneBounds)

        // Page 2 is second in reading order: placed at [1000..3000]
        assertEquals(PageId(2L), pages[1].pageId)
        assertEquals(FloatRect.fromLtwh(1000f, 0f, 2000f, 1500f), pages[1].sceneBounds)

        // Page 3 is third in reading order: placed at [0..1000]
        assertEquals(PageId(3L), pages[2].pageId)
        assertEquals(FloatRect.fromLtwh(0f, 0f, 1000f, 1500f), pages[2].sceneBounds)
    }

    @Test
    fun `resolvePageScrollPosition returns left for LTR and right for RTL`() {
        val sceneLtr = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1000, 1000),
                PageId(20L) to PageGeometryHint.Exact(2000, 1000),
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        assertEquals(0f, sceneLtr.resolvePageScrollPosition(PageId(10L)))
        assertEquals(1000f, sceneLtr.resolvePageScrollPosition(PageId(20L)))
        assertNull(sceneLtr.resolvePageScrollPosition(PageId(999L)))

        val sceneRtl = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1000, 1000),
                PageId(20L) to PageGeometryHint.Exact(2000, 1000),
            ),
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // Total width: 3000. Page 10 is at [2000..3000], Page 20 is at [0..2000].
        assertEquals(3000f, sceneRtl.resolvePageScrollPosition(PageId(10L)))
        assertEquals(2000f, sceneRtl.resolvePageScrollPosition(PageId(20L)))
        assertNull(sceneRtl.resolvePageScrollPosition(PageId(999L)))
    }

    @Test
    fun `resolve returns intersecting pages with fast binary search for LTR`() {
        val pages = (1..50).map { i ->
            PageId(i.toLong()) to PageGeometryHint.Exact(1000, 1000)
        }
        val scene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        // Viewport looking at X = 25500..27500 (pages 26, 27, 28)
        val viewport = ReaderViewport(FloatRect.fromLtwh(25500f, 0f, 2000f, 1000f))
        val frame = scene.resolve(viewport)

        assertEquals(3, frame.visibleNodes.size)
        assertEquals(PageId(26L), frame.visibleNodes[0].pageId) // Page 26: 25000..26000
        assertEquals(PageId(27L), frame.visibleNodes[1].pageId) // Page 27: 26000..27000
        assertEquals(PageId(28L), frame.visibleNodes[2].pageId) // Page 28: 27000..28000
        assertEquals(PageId(26L), frame.progress.firstVisiblePageId)
        assertEquals(PageId(28L), frame.progress.lastVisiblePageId)
        assertEquals(PageId(26L), frame.progress.activePageId)
        assertEquals(500f, frame.progress.intraPageOffsetPx)
    }

    @Test
    fun `resolve returns intersecting pages in canonical reading order for RTL`() {
        val pages = (1..50).map { i ->
            PageId(i.toLong()) to PageGeometryHint.Exact(1000, 1000)
        }
        val scene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = pages,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // Total width: 50,000.
        // Page 1 is [49000..50000], Page 25 is [25000..26000], Page 26 is [24000..25000], Page 27 is [23000..24000].
        // Viewport looking at X = 23500..25500 (width 2000)
        // Intersects Page 25 [25000..26000], Page 26 [24000..25000], Page 27 [23000..24000].
        val viewport = ReaderViewport(FloatRect.fromLtwh(23500f, 0f, 2000f, 1000f))
        val frame = scene.resolve(viewport)

        assertEquals(3, frame.visibleNodes.size)
        // Canonical reading order: Page 25, then Page 26, then Page 27
        assertEquals(PageId(25L), frame.visibleNodes[0].pageId)
        assertEquals(PageId(26L), frame.visibleNodes[1].pageId)
        assertEquals(PageId(27L), frame.visibleNodes[2].pageId)
        assertEquals(PageId(25L), frame.progress.firstVisiblePageId)
        assertEquals(PageId(27L), frame.progress.lastVisiblePageId)
        assertEquals(PageId(25L), frame.progress.activePageId)
        // Intra-page offset in RTL: first.right (26000) - viewport.right (25500) = 500f
        assertEquals(500f, frame.progress.intraPageOffsetPx)
    }

    @Test
    fun `updatePageHint in LTR shifts viewport right when updated page is to the left`() {
        val scene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f),     // 0..1000
                PageId(2L) to PageGeometryHint.Exact(1000, 1000), // 1000..2000
            ),
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        // Viewport looking at Page 2: X = 1200..2000
        val viewport = ReaderViewport(FloatRect.fromLtwh(1200f, 0f, 800f, 1000f))

        // Page 1 (to the left of viewport) updates from 1000px to 2500px (+1500px change)
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(2500, 1000),
            currentViewport = viewport,
        )

        assertEquals(1500f, compensation?.deltaX)
        assertEquals(2700f, compensation?.compensatedViewport?.bounds?.left)
    }

    @Test
    fun `updatePageHint in RTL preserves visual position when reading the changing page`() {
        val scene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f),     // [1000..2000]
                PageId(2L) to PageGeometryHint.Exact(1000, 1000), // [0..1000]
            ),
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // Total width: 2000. Page 1 is at [1000..2000].
        // Viewport is looking at Page 1: right edge at 1800 (intraPageOffset = 2000 - 1800 = 200px)
        val viewport = ReaderViewport(FloatRect.fromLtwh(1000f, 0f, 800f, 1000f))
        assertEquals(PageId(1L), scene.resolveActivePageId(viewport))

        // Page 1 resolves exact dimensions: width = 2500px (+1500px increase)
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(2500, 1000),
            currentViewport = viewport,
        )

        // Page 1 new right edge is 3500. Viewport right edge should be 3500 - 200 = 3300 (+1500 deltaX).
        assertEquals(1500f, compensation?.deltaX)
        assertEquals(2500f, compensation?.compensatedViewport?.bounds?.left)
        assertEquals(3300f, compensation?.compensatedViewport?.bounds?.right)

        // In compensated viewport, intra-page offset is still exactly 200px
        val frame = scene.resolve(compensation!!.compensatedViewport)
        assertEquals(PageId(1L), frame.progress.activePageId)
        assertEquals(200f, frame.progress.intraPageOffsetPx)
    }

    @Test
    fun `updatePages in RTL preserves exact hints and anchors prepended pages with zero jump`() {
        val scene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = listOf(
                PageId(10L) to PageGeometryHint.Exact(1000, 1000), // [1020..2020]
                PageId(20L) to PageGeometryHint.Estimated(1f),     // [0..1000]
            ),
            pageSpacingPx = 20,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        // Resolve Page 20 exact width: 1800px
        scene.updatePageHint(PageId(20L), PageGeometryHint.Exact(1800, 1000))
        // Page 10 is [1820..2820], Page 20 is [0..1800]. Total = 2820.

        // Reader is reading Page 20, 300px into the page from right edge (right = 1800, viewport right = 1500)
        val viewport = ReaderViewport(FloatRect.fromLtwh(700f, 0f, 800f, 1000f))
        assertEquals(PageId(20L), scene.resolveActivePageId(viewport))

        // Earlier chapter is prepended: Page 1, Page 2
        val newPages = listOf(
            PageId(1L) to PageGeometryHint.Exact(1200, 1000),
            PageId(2L) to PageGeometryHint.Exact(800, 1000),
            PageId(10L) to PageGeometryHint.Estimated(1f),
            PageId(20L) to PageGeometryHint.Estimated(1f),
        )

        val compensation = scene.updatePages(newPages, viewport)
        assertTrue(compensation != null)

        // In compensated viewport, active page is still Page 20 and intra-page offset is still 300px!
        val frame = scene.resolve(compensation!!.compensatedViewport)
        assertEquals(PageId(20L), frame.progress.activePageId)
        assertEquals(300f, frame.progress.intraPageOffsetPx)
    }

    @Test
    fun `transposition property between vertical and LTR scene holds exactly`() {
        val pageHints = listOf(
            PageId(1L) to PageGeometryHint.Exact(1000, 1200),
            PageId(2L) to PageGeometryHint.Exact(1000, 800),
            PageId(3L) to PageGeometryHint.Exact(1000, 2000),
            PageId(4L) to PageGeometryHint.Exact(1000, 1500),
        )

        // Vertical: availableWidth = 1000, defaultViewportHeight = 1000
        val vScene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 1000,
            initialPages = pageHints,
            pageSpacingPx = 15,
        )

        // Horizontal LTR: availableHeight = 1000, defaultViewportWidth = 1000
        // Transposed hints: width and height swapped so page width in H matches page height in V
        val transposedHints = pageHints.map { (id, hint) ->
            id to PageGeometryHint.Exact(hint.height, hint.width)
        }
        val hScene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = transposedHints,
            pageSpacingPx = 15,
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        assertEquals(vScene.totalSceneExtent, hScene.totalSceneExtent)

        // Test at multiple scroll positions
        for (offset in listOf(0f, 500f, 1210f, 2025f, 3500f, 5000f)) {
            val vVp = ReaderViewport(FloatRect.fromLtwh(0f, offset, 1000f, 1000f))
            val hVp = ReaderViewport(FloatRect.fromLtwh(offset, 0f, 1000f, 1000f))

            val vFrame = vScene.resolve(vVp)
            val hFrame = hScene.resolve(hVp)

            assertEquals(
                vFrame.visibleNodes.map { it.pageId },
                hFrame.visibleNodes.map { it.pageId },
                "Visible page IDs must match at offset $offset",
            )
            assertEquals(
                vFrame.progress.activePageId,
                hFrame.progress.activePageId,
                "Active page must match at offset $offset",
            )
            assertEquals(
                vFrame.progress.intraPageOffsetPx,
                hFrame.progress.intraPageOffsetPx,
                "Intra-page offset must match at offset $offset",
            )
        }
    }

    @Test
    fun `mirror property between LTR and RTL scene holds symmetrically`() {
        val pageHints = listOf(
            PageId(1L) to PageGeometryHint.Exact(1200, 1000),
            PageId(2L) to PageGeometryHint.Exact(800, 1000),
            PageId(3L) to PageGeometryHint.Exact(2000, 1000),
            PageId(4L) to PageGeometryHint.Exact(1500, 1000),
        )

        val ltrScene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = pageHints,
            pageSpacingPx = 25,
            readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
        )

        val rtlScene = HorizontalReaderScene(
            availableHeight = 1000,
            defaultViewportWidth = 1000,
            initialPages = pageHints,
            pageSpacingPx = 25,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
        )

        val totalExtent = ltrScene.totalSceneWidth
        assertEquals(totalExtent, rtlScene.totalSceneWidth)

        // For any scroll offset in LTR, mirrored viewport in RTL is [totalExtent - X - vpWidth .. totalExtent - X]
        val vpWidth = 900f
        for (x in listOf(0f, 400f, 1220f, 2050f, 3500f, totalExtent - vpWidth)) {
            val ltrVp = ReaderViewport(FloatRect.fromLtwh(x, 0f, vpWidth, 1000f))
            val rtlVp = ReaderViewport(FloatRect.fromLtwh(totalExtent - x - vpWidth, 0f, vpWidth, 1000f))

            val ltrFrame = ltrScene.resolve(ltrVp)
            val rtlFrame = rtlScene.resolve(rtlVp)

            assertEquals(
                ltrFrame.visibleNodes.map { it.pageId },
                rtlFrame.visibleNodes.map { it.pageId },
                "Visible page IDs must match symmetrically at LTR x=$x",
            )
            assertEquals(
                ltrFrame.progress.activePageId,
                rtlFrame.progress.activePageId,
                "Active page must match at LTR x=$x",
            )
            assertEquals(
                ltrFrame.progress.intraPageOffsetPx,
                rtlFrame.progress.intraPageOffsetPx,
                "Intra-page offset must match at LTR x=$x",
            )
        }
    }
}
