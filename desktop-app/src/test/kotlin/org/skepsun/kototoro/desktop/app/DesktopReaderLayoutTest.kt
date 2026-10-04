package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.reader.core.IntSize
import java.nio.file.Path

class DesktopReaderLayoutTest {
    private val pages = (0..4).map { SourcePage(Long.MAX_VALUE - it, "/page/$it", null,
        SourceRef("fixture", "zh", "MANGA")) }
    private val images = pages.associate { it.id to DesktopReaderImage(Path.of("fixture.img"),
        if (it == pages[2]) 900 else 400, if (it == pages[2]) 300 else 600) }

    @Test
    fun `native dimensions retain wide solo pages and canonical turns through every spread`() {
        val settings = DesktopReaderSettings(DesktopReaderMode.DOUBLE)
        val first = DesktopReaderLayout(pages, Long.MIN_VALUE, images, settings, 0)
        assertEquals(listOf(0, 1), first.indices)
        assertEquals(0, first.anchorIndex)
        assertNull(first.turnIndex(false))
        assertEquals(2, first.turnIndex(true))
        val wide = DesktopReaderLayout(pages, Long.MIN_VALUE, images, settings, 2)
        assertEquals(listOf(2), wide.indices)
        assertEquals(3, wide.turnIndex(true))
        val last = DesktopReaderLayout(pages, Long.MIN_VALUE, images, settings, 4)
        assertEquals(listOf(3, 4), last.indices)
        assertEquals(3, last.anchorIndex)
        assertNull(last.turnIndex(true))
        assertEquals(2, last.turnIndex(false))
    }

    @Test
    fun `persisted geometry keeps the last canonical spread while its images still require loading`() {
        val hints = images.mapValues { (_, image) -> IntSize(image.width, image.height) }
        val layout = DesktopReaderLayout(pages, 7, emptyMap(), DesktopReaderSettings(DesktopReaderMode.DOUBLE),
            4, geometry = hints)
        assertEquals(listOf(3, 4), layout.indices)
        assertEquals(3, layout.anchorIndex)
        assertEquals(2, layout.turnIndex(false))
        val staleHint = hints + (pages[2].id to IntSize(400, 600))
        val verified = DesktopReaderLayout(pages, 7, images, DesktopReaderSettings(DesktopReaderMode.DOUBLE),
            4, geometry = staleHint)
        assertEquals(layout.indices, verified.indices)
        assertEquals(layout.anchorIndex, verified.anchorIndex)
    }

    @Test
    fun `native overflow retains offscreen pages in the canonical loading group and anchor`() {
        val native = DesktopReaderLayout(pages, 7, images,
            DesktopReaderSettings(DesktopReaderMode.DOUBLE, true,
                org.skepsun.kototoro.reader.core.ZoomMode.KEEP_START), 4, 300, 200)
        assertEquals(listOf(3, 4), native.indices)
        assertEquals(3, native.anchorIndex)
        assertEquals(2, native.slot!!.placements.size)
        assertTrue(native.slot.placements.any { it.boundsInSlot.left < 0 })
        assertEquals(2, native.turnIndex(false))
    }

    @Test
    fun `RTL reverses physical placement without changing anchor identity and resize preserves the spread`() {
        for ((width, height) in listOf(1000 to 700, 380 to 500)) {
            val settings = DesktopReaderSettings(DesktopReaderMode.DOUBLE)
            val ltr = DesktopReaderLayout(pages, 7, images, settings, 1, width, height)
            val rtl = DesktopReaderLayout(pages, 7, images, settings.copy(rightToLeft = true), 1, width, height)
            assertEquals(ltr.indices, rtl.indices)
            assertEquals(0, rtl.anchorIndex)
            assertEquals(pages[0].id, rtl.frame?.progress?.activePageId?.value)
            val left = ltr.frame!!.visibleNodes
            val right = rtl.frame!!.visibleNodes
            assertTrue(left[0].sceneBounds.left < left[1].sceneBounds.left)
            assertTrue(right[0].sceneBounds.left > right[1].sceneBounds.left)
            assertTrue(right.all { it.visibleRegion.width > 0 && it.visibleRegion.height > 0 })
            assertEquals(2, rtl.turnIndex(true))
        }
    }
}
