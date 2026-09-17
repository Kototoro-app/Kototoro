package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderProgressSnapshotTest {

    @Test
    fun `neutral names match lower and upper page IDs`() {
        val snapshot = ReaderProgressSnapshot(
            firstVisiblePageId = PageId(10L),
            lastVisiblePageId = PageId(15L),
            activePageId = PageId(10L),
            intraPageOffsetPx = 120f,
        )

        assertEquals(PageId(10L), snapshot.firstVisiblePageId)
        assertEquals(PageId(15L), snapshot.lastVisiblePageId)
        assertEquals(PageId(10L), snapshot.lowerPageId)
        assertEquals(PageId(15L), snapshot.upperPageId)
        assertEquals(PageId(10L), snapshot.activePageId)
        assertEquals(120f, snapshot.intraPageOffsetPx)
    }

    @Test
    fun `empty snapshot returns null IDs`() {
        val empty = ReaderProgressSnapshot.Empty
        assertNull(empty.firstVisiblePageId)
        assertNull(empty.lastVisiblePageId)
        assertNull(empty.lowerPageId)
        assertNull(empty.upperPageId)
        assertNull(empty.activePageId)
        assertEquals(0f, empty.intraPageOffsetPx)
    }

    @Test
    fun `intraPageOffset calculates correctly and symmetrically across reading directions`() {
        // Vertical: viewport at Y=1250, page at Y=1000..3000
        val vViewport = ReaderViewport(FloatRect.fromLtwh(0f, 1250f, 800f, 1200f))
        val vNode = VisibleNode(
            pageId = PageId(1L),
            sceneBounds = FloatRect.fromLtwh(0f, 1000f, 800f, 2000f),
            visibleRegion = FloatRect.fromLtwh(0f, 1250f, 800f, 950f),
        )
        val vSnapshot = ReaderProgressSnapshot.from(vViewport, listOf(vNode), SceneReadingDirection.TOP_TO_BOTTOM)
        assertEquals(250f, vSnapshot.intraPageOffsetPx) // 1250 - 1000

        // LTR: viewport at X=300, page at X=200..1000
        val ltrViewport = ReaderViewport(FloatRect.fromLtwh(300f, 0f, 600f, 1200f))
        val ltrNode = VisibleNode(
            pageId = PageId(2L),
            sceneBounds = FloatRect.fromLtwh(200f, 0f, 800f, 1200f),
            visibleRegion = FloatRect.fromLtwh(300f, 0f, 500f, 1200f),
        )
        val ltrSnapshot = ReaderProgressSnapshot.from(ltrViewport, listOf(ltrNode), SceneReadingDirection.LEFT_TO_RIGHT)
        assertEquals(100f, ltrSnapshot.intraPageOffsetPx) // 300 - 200

        // RTL: page at X=2000..2800 (starts at 2800, ends at 2000), viewport right edge at 2650
        // Intra-page offset: distance read from right edge = 2800 - 2650 = 150
        val rtlViewport = ReaderViewport(FloatRect.fromLtwh(1850f, 0f, 800f, 1200f)) // right = 2650
        val rtlNode = VisibleNode(
            pageId = PageId(3L),
            sceneBounds = FloatRect.fromLtwh(2000f, 0f, 800f, 1200f), // right = 2800
            visibleRegion = FloatRect.fromLtwh(2000f, 0f, 650f, 1200f),
        )
        val rtlSnapshot = ReaderProgressSnapshot.from(rtlViewport, listOf(rtlNode), SceneReadingDirection.RIGHT_TO_LEFT)
        assertEquals(150f, rtlSnapshot.intraPageOffsetPx) // 2800 - 2650
    }
}
