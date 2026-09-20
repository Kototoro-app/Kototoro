package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a paged slot reports as visible to the image pipeline.
 *
 * A slot's own viewport is not the screen. A neighbouring slot renders at its saved scale, so its
 * "visible content" can be the entire page, and treating that as visible asked the pipeline to pin
 * a page's worth of level-zero tiles - the oversized-page zoom run held 406MB of tiles that way.
 */
class PagedSlotScreenVisibilityTest {

    private val pageId = PageId(42L)
    private val screenViewport = FloatRect.fromLtwh(0f, 0f, 1280f, 2772f)

    private fun slot(
        slotIndex: Int,
        left: Float,
        contentWidth: Float = 1848f,
    ): PagedSlot {
        val bounds = FloatRect.fromLtwh(left, 0f, 1280f, 2772f)
        return PagedSlot(
            slotIndex = slotIndex,
            bounds = bounds,
            placements = listOf(
                PagedPagePlacement(
                    pageId = pageId,
                    boundsInSlot = FloatRect.fromLtwh(0f, 0f, contentWidth, 2772f),
                    sceneBounds = FloatRect.fromLtwh(left, 0f, left + contentWidth, 2772f),
                ),
            ),
            progressAnchorPageId = pageId,
        )
    }

    @Test
    fun `a slot outside the screen viewport reports nothing visible`() {
        val neighbour = slot(slotIndex = 1, left = 1280f)

        // The slot's own viewport is fully covered by its page, which is exactly why the screen
        // viewport has to be the bound instead.
        assertEquals(1, neighbour.visibleContentNodes(1f, 0f, 0f).size)
        assertTrue(neighbour.screenVisibleContentNodes(screenViewport, 1f, 0f, 0f).isEmpty())
    }

    @Test
    fun `the on-screen slot keeps its content but clipped to the viewport`() {
        val current = slot(slotIndex = 0, left = 0f)

        val nodes = current.screenVisibleContentNodes(screenViewport, 1f, 0f, 0f)

        assertEquals(1, nodes.size)
        val region = nodes.first().visibleRegion
        // Content is wider than the screen at fit-height, so the reported region stops at the screen
        // edge even though the page continues past it.
        assertEquals(0f, region.left, TOLERANCE)
        assertEquals(1280f, region.right, TOLERANCE)
        assertEquals(2772f, region.bottom, TOLERANCE)
    }

    @Test
    fun `a cover-pinned neighbour reports the whole visually exposed viewport`() {
        val neighbour = slot(slotIndex = 1, left = 1280f, contentWidth = 1280f)
        val sceneViewportDuringTurn = FloatRect.fromLtwh(128f, 0f, 1280f, 2772f)

        // At 10% forward travel the scene viewport only intersects 128 px of the next slot, but
        // Cover cancels that slot's screen offset and exposes it across the whole viewport. Tile
        // visibility must therefore use the pinned slot viewport, not the untransformed scene
        // viewport; otherwise only a strip is resident and the slot background flashes through.
        val nodes = neighbour.screenVisibleContentNodes(
            screenViewportBounds = sceneViewportDuringTurn,
            scale = 1f,
            offsetX = 0f,
            offsetY = 0f,
            isPinnedToViewport = true,
        )

        assertEquals(1, nodes.size)
        assertEquals(neighbour.bounds, nodes.single().visibleRegion)
    }

    @Test
    fun `a zoomed slot reports only the magnified region`() {
        val current = slot(slotIndex = 0, left = 0f)

        val zoomed = current.screenVisibleContentNodes(screenViewport, scale = 2.5f, offsetX = 0f, offsetY = 0f)
        val wide = current.screenVisibleContentNodes(screenViewport, scale = 1f, offsetX = 0f, offsetY = 0f)

        assertEquals(1, zoomed.size)
        assertTrue(zoomed.first().visibleRegion.width < wide.first().visibleRegion.width)
        // Zooming in must shrink the reported region in both axes, not only in width: a region that
        // keeps the full page height while narrowing in x is what the tile probe caught on device
        // (a strip of ~1031 x 9000 logical pixels at level zero, 4012 times in one run).
        assertTrue(zoomed.first().visibleRegion.height < wide.first().visibleRegion.height)
    }

    @Test
    fun `panning the camera never reports more than the camera sees`() {
        val current = slot(slotIndex = 0, left = 0f)

        // A pan pushes the camera rect past the page edge; the intersection with the page must clamp
        // to what the camera covers, not re-expand to the page.
        val panned = current.screenVisibleContentNodes(
            screenViewportBounds = screenViewport,
            scale = 2.5f,
            offsetX = -600f,
            offsetY = -900f,
        )

        assertEquals(1, panned.size)
        val sceneRegion = panned.first().visibleRegion
        assertTrue(
            sceneRegion.height <= 2772f / 2.5f + 1f,
            "panned region height ${sceneRegion.height} exceeds the magnified viewport",
        )
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
