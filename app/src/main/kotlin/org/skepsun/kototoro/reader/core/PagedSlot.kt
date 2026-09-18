package org.skepsun.kototoro.reader.core

/**
 * Geometric placement of a page inside a [PagedSlot] and in the overall [PagedReaderScene].
 */
data class PagedPagePlacement(
    val pageId: PageId,
    /** Bounds of the page relative to the slot's top-left origin (0, 0). */
    val boundsInSlot: FloatRect,
    /** Absolute bounds of the page in the scene space. */
    val sceneBounds: FloatRect,
)

/**
 * A discrete paging container holding one or more pages (single page or double-page spread).
 */
data class PagedSlot(
    val slotIndex: Int,
    /** Absolute bounds of this slot in the scene space. */
    val bounds: FloatRect,
    /** Placements of each constituent page within this slot. */
    val placements: List<PagedPagePlacement>,
    /**
     * The primary reading progress anchor [PageId] represented by this slot.
     * Guarantees deterministic progress persistence across screen rotations and double-page re-spreads.
     */
    val progressAnchorPageId: PageId,
) {
    val pageIds: List<PageId> get() = placements.map { it.pageId }

    fun containsPage(pageId: PageId): Boolean = placements.any { it.pageId == pageId }
    /** Inverse camera bounds may extend beyond the discrete slot when native content overflows. */
    fun contentViewport(scale: Float, offsetX: Float, offsetY: Float): FloatRect {
        val centerX = bounds.width / 2f
        val centerY = bounds.height / 2f
        return FloatRect.fromLtwh(
            bounds.left + centerX + (-offsetX - centerX) / scale,
            bounds.top + centerY + (-offsetY - centerY) / scale,
            bounds.width / scale,
            bounds.height / scale,
        )
    }

    fun visibleContentNodes(scale: Float, offsetX: Float, offsetY: Float): List<VisibleNode> {
        val viewport = contentViewport(scale, offsetX, offsetY)
        return placements.mapNotNull { placement ->
            val intersection = placement.sceneBounds.intersectionOrNull(viewport) ?: return@mapNotNull null
            if (intersection.width <= 0f || intersection.height <= 0f) return@mapNotNull null
            VisibleNode(placement.pageId, placement.sceneBounds, intersection)
        }
    }

    /**
     * Content of this slot that the screen actually shows, bounded by [screenViewportBounds].
     *
     * [visibleContentNodes] answers a different question: what this slot's own viewport shows. For a
     * neighbouring slot, which is rendered at its saved scale (often 1.0), that answer is the whole
     * page - and asking the image pipeline for the whole page as *visible* pins a page's worth of
     * level-zero tiles, hundreds of megabytes at high zoom. Intersecting with the screen viewport is
     * the only honest bound.
     */
    fun screenVisibleContentNodes(
        screenViewportBounds: FloatRect,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): List<VisibleNode> {
        if (bounds.intersectionOrNull(screenViewportBounds) == null) return emptyList()
        return visibleContentNodes(scale, offsetX, offsetY).mapNotNull { node ->
            val clipped = node.visibleRegion.intersectionOrNull(screenViewportBounds)
                ?: return@mapNotNull null
            if (clipped.width <= 0f || clipped.height <= 0f) null else node.copy(visibleRegion = clipped)
        }
    }

}
