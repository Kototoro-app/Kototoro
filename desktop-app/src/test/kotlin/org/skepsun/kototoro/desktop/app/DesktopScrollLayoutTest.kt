package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.reader.core.PageId
import java.nio.file.Path

class DesktopScrollLayoutTest {
    private val pages = (0..3).map { SourcePage(Long.MAX_VALUE - it, "/$it", null, SourceRef("fixture", "zh", "MANGA")) }
    private fun image(width: Int, height: Int) = DesktopReaderImage(Path.of("fixture.img"), width, height)

    @Test
    fun `visible frame combines a short page and following page with the exact lazy item heights`() {
        val images = mapOf(pages[0].id to image(900, 300), pages[1].id to image(400, 600))
        val layout = DesktopScrollLayout(pages, images, 600, 450, 8)
        val frame = layout.frame(0, 30)
        assertEquals(listOf(PageId(pages[0].id), PageId(pages[1].id)), frame.visibleNodes.map { it.pageId })
        assertEquals(200f, layout.geometries[0].sceneBounds.height)
        assertEquals(900f, layout.geometries[1].sceneBounds.height)
        assertEquals(PageId(pages[0].id), frame.progress.activePageId)
        assertEquals(30f, frame.progress.intraPageOffsetPx)
        assertEquals(208f, layout.geometries[1].sceneBounds.top)
    }

    @Test
    fun `late headers above the viewport and width changes preserve the stable item anchor and pixel offset`() {
        val unknown = DesktopScrollLayout(pages, emptyMap(), 600, 450, 8)
        val images = mapOf(pages[0].id to image(400, 1200), pages[2].id to image(400, 600))
        val exact = DesktopScrollLayout(pages, images, 600, 450, 8)
        val resized = DesktopScrollLayout(pages, images, 400, 300, 8)
        for (layout in listOf(unknown, exact, resized)) {
            val frame = layout.frame(2, 120)
            assertEquals(PageId(pages[2].id), frame.progress.activePageId)
            assertEquals(120f, frame.progress.intraPageOffsetPx)
        }
        assertTrue(exact.frame(2, 120).viewport.bounds.top > unknown.frame(2, 120).viewport.bounds.top)
    }
}
