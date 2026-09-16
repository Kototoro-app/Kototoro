package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisibleRegionResolverTest {

    @Test
    fun `empty viewport produces empty frame`() {
        val viewport = ReaderViewport(FloatRect.Zero)
        val pages = listOf(
            PageGeometry(PageId(1L), FloatRect.fromLtwh(0f, 0f, 1000f, 2000f)),
        )

        val frame = VisibleRegionResolver.resolve(viewport, pages)
        assertTrue(frame.visibleNodes.isEmpty())
    }

    @Test
    fun `empty page list produces empty frame`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 2000f))
        val frame = VisibleRegionResolver.resolve(viewport, emptyList())
        assertTrue(frame.visibleNodes.isEmpty())
    }

    @Test
    fun `resolves intersecting pages and visible sub-regions`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1500f, 1000f, 2000f))
        val pages = listOf(
            PageGeometry(PageId(1L), FloatRect.fromLtwh(0f, 0f, 1000f, 1000f)),     // 0..1000 (above viewport)
            PageGeometry(PageId(2L), FloatRect.fromLtwh(0f, 1000f, 1000f, 1000f)),  // 1000..2000 (partially visible: 1500..2000)
            PageGeometry(PageId(3L), FloatRect.fromLtwh(0f, 2000f, 1000f, 1000f)),  // 2000..3000 (fully visible)
            PageGeometry(PageId(4L), FloatRect.fromLtwh(0f, 3000f, 1000f, 1000f)),  // 3000..4000 (partially visible: 3000..3500)
            PageGeometry(PageId(5L), FloatRect.fromLtwh(0f, 4000f, 1000f, 1000f)),  // 4000..5000 (below viewport)
        )

        val frame = VisibleRegionResolver.resolve(viewport, pages)
        assertEquals(3, frame.visibleNodes.size)

        val node1 = frame.visibleNodes[0]
        assertEquals(PageId(2L), node1.pageId)
        assertEquals(FloatRect.fromLtwh(0f, 1500f, 1000f, 500f), node1.visibleRegion)

        val node2 = frame.visibleNodes[1]
        assertEquals(PageId(3L), node2.pageId)
        assertEquals(FloatRect.fromLtwh(0f, 2000f, 1000f, 1000f), node2.visibleRegion)

        val node3 = frame.visibleNodes[2]
        assertEquals(PageId(4L), node3.pageId)
        assertEquals(FloatRect.fromLtwh(0f, 3000f, 1000f, 500f), node3.visibleRegion)
    }

    @Test
    fun `edge touching pages are excluded from visible nodes`() {
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 1000f, 1000f, 1000f)) // 1000..2000
        val pages = listOf(
            PageGeometry(PageId(1L), FloatRect.fromLtwh(0f, 0f, 1000f, 1000f)),    // bottom is 1000 (touches top)
            PageGeometry(PageId(2L), FloatRect.fromLtwh(0f, 2000f, 1000f, 1000f)), // top is 2000 (touches bottom)
        )

        val frame = VisibleRegionResolver.resolve(viewport, pages)
        assertTrue(frame.visibleNodes.isEmpty())
    }

    @Test
    fun `resolveInto clears previous destination contents`() {
        val viewport = ReaderViewport(FloatRect.Zero)
        val destination = mutableListOf(
            VisibleNode(PageId(999L), FloatRect.Zero, FloatRect.Zero),
        )

        VisibleRegionResolver.resolveInto(viewport, emptyList(), destination)
        assertTrue(destination.isEmpty())
    }
}
