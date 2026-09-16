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
    }

    @Test
    fun `resolveActivePageId matches last visible end semantics`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 1000,
            initialPages = listOf(
                PageId(105L) to PageGeometryHint.Exact(1000, 400),  // 0..400
                PageId(201L) to PageGeometryHint.Exact(1000, 800),  // 400..1200
            ),
        )

        // Viewport looking at 200..1200 (both pages visible; page 105 ends at 400, page 201 ends at 1200)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 200f, 1000f, 1000f))
        assertEquals(PageId(201L), scene.resolveActivePageId(viewport))

        // Viewport looking at 0..300 (only page 105 visible, none ends in viewport -> fallback to first)
        val topViewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 300f))
        assertEquals(PageId(105L), scene.resolveActivePageId(topViewport))
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
    fun `updatePageHint performs anchored intra-page compensation when reading the changing page`() {
        val scene = VerticalReaderScene(
            availableWidth = 1000,
            defaultViewportHeight = 2000,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Estimated(1f), // 0..2000
            ),
        )

        // Viewport is 500px into Page 1 (25% progress within the 2000px page)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 500f, 1000f, 1000f))

        // Page 1 resolves exact dimensions: 1000x4000 (new height = 4000px)
        // 25% into 4000px page is 1000px. Delta from 500px is +500px.
        val compensation = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(1000, 4000),
            currentViewport = viewport,
        )

        assertEquals(500f, compensation?.deltaY)
        assertEquals(1000f, compensation?.compensatedViewport?.bounds?.top)
    }
}
