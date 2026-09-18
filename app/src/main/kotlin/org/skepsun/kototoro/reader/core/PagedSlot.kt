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
}
