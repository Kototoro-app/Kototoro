package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PagedCameraTransformTest {
    private fun slot(width: Int = 800, height: Int = 600, config: PagedSpreadConfig = PagedSpreadConfig(),
        pageWidth: Int = 800, pageHeight: Int = 600): PagedSlot = PagedReaderScene(width, height, config,
        (0..3).map { PagedPageSpec(PageId(it.toLong()), PageGeometryHint.Exact(pageWidth, pageHeight), 7, it) })
        .allSlots.last()

    @Test
    fun `pointer anchored zoom preserves the same scene point in a nonzero slot`() {
        val slot = slot()
        val camera = PagedCameraTransform.initial(slot)
        val x = 450f
        val y = 270f
        val before = camera.viewport(slot)
        val zoom = camera.transform(slot, factor = 2f, anchorX = x, anchorY = y)
        val after = zoom.viewport(slot)
        assertEquals(before.bounds.left + x, after.bounds.left + x / after.scale, .001f)
        assertEquals(before.bounds.top + y, after.bounds.top + y / after.scale, .001f)
        assertEquals(before.bounds.width / 2, after.bounds.width, .001f)
        assertEquals(before.bounds.height / 2, after.bounds.height, .001f)
        assertEquals(listOf(slot.progressAnchorPageId), slot.visibleContentNodes(zoom.scale, zoom.offsetX,
            zoom.offsetY).map { it.pageId })
    }

    @Test
    fun `extreme finite motion stays bounded and reset exposes the baseline page`() {
        val slot = slot()
        val zoom = PagedCameraTransform().transform(slot, factor = Float.MAX_VALUE,
            panX = Float.MAX_VALUE, panY = -Float.MAX_VALUE)
        assertEquals(5f, zoom.scale)
        val viewport = zoom.viewport(slot)
        val content = slot.placements.single().sceneBounds
        assertTrue(content.left <= viewport.bounds.left && content.right >= viewport.bounds.right)
        assertTrue(content.top <= viewport.bounds.top && content.bottom >= viewport.bounds.bottom)
        val shrunk = zoom.transform(slot, factor = Float.MIN_VALUE)
        assertEquals(PagedCameraTransform(), shrunk)
        assertEquals(slot.bounds, shrunk.viewport(slot).bounds)
    }

    @Test
    fun `fit width overflow starts at the top and can pan to the bottom at baseline scale`() {
        val slot = slot(400, 300, PagedSpreadConfig(zoomMode = ZoomMode.FIT_WIDTH), 600, 1400)
        val camera = PagedCameraTransform.initial(slot)
        val page = slot.placements.single().sceneBounds
        assertEquals(page.top, camera.viewport(slot).bounds.top, .001f)
        val bottom = camera.transform(slot, panY = -Float.MAX_VALUE)
        assertEquals(page.bottom, bottom.viewport(slot).bounds.bottom, .001f)
        assertEquals(1f, bottom.scale)
        assertEquals(camera.offsetX, bottom.offsetX)
        assertEquals(slot.progressAnchorPageId, slot.pageIds.single())
    }

    @Test
    fun `native double spread honors RTL reading edge and keeps its whole canonical group when zoomed`() {
        val slot = slot(300, 200, PagedSpreadConfig(isDoublePage = true, zoomMode = ZoomMode.KEEP_START,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT), 400, 600)
        val camera = PagedCameraTransform.initial(slot, rightToLeft = true)
        val right = slot.placements.first().sceneBounds
        assertEquals(right.right, camera.viewport(slot).bounds.right, .001f)
        val anchor = slot.progressAnchorPageId
        val ids = slot.pageIds
        val zoom = camera.transform(slot, factor = 3f, panX = Float.MAX_VALUE, panY = -Float.MAX_VALUE)
        assertTrue(slot.visibleContentNodes(zoom.scale, zoom.offsetX, zoom.offsetY).isNotEmpty())
        assertEquals(anchor, slot.progressAnchorPageId)
        assertEquals(ids, slot.pageIds)
        assertEquals(2, ids.size)
        val resized = slot(230, 160, PagedSpreadConfig(isDoublePage = true, zoomMode = ZoomMode.KEEP_START,
            readingDirection = SceneReadingDirection.RIGHT_TO_LEFT), 400, 600)
        val adjusted = zoom.constrained(resized)
        assertEquals(zoom.scale, adjusted.scale)
        assertTrue(resized.visibleContentNodes(adjusted.scale, adjusted.offsetX, adjusted.offsetY).isNotEmpty())
    }

    @Test
    fun `invalid camera and gesture values fail before becoming a renderer transform`() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 6f)) {
            assertThrows(IllegalArgumentException::class.java) { PagedCameraTransform(scale) }
        }
        assertThrows(IllegalArgumentException::class.java) { PagedCameraTransform(offsetX = Float.NaN) }
        val slot = slot()
        for (factor in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { PagedCameraTransform().transform(slot, factor) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PagedCameraTransform().transform(slot, anchorY = Float.NEGATIVE_INFINITY)
        }
    }
}
