package org.skepsun.kototoro.reader.core

/**
 * Standardized snapshot of paging gesture motion and slot indices.
 *
 * Bridges custom paged scene hosts to animation transformers (e.g. Page Curl, Slide, 3D flip)
 * without coupling to Android PagerState.
 */
data class PagedMotionSnapshot(
    val currentSlot: Int,
    val settledSlot: Int,
    val targetSlot: Int,
    /**
     * Signed fractional displacement between [-1.0f..1.0f] relative to current slot.
     */
    val offsetFraction: Float = 0f,
    val isScrollInProgress: Boolean = false,
)
