package org.skepsun.kototoro.reader.core

/**
 * Declares a page that intersects the current [ReaderViewport] and requires rendering.
 *
 * @property pageId Identifier of the page.
 * @property sceneBounds Full bounding rect of the page in scene coordinates.
 * @property visibleRegion Sub-rectangle of [sceneBounds] actually visible inside the viewport.
 * @property zIndex Stacking order if pages overlap.
 */
data class VisibleNode(
    val pageId: PageId,
    val sceneBounds: FloatRect,
    val visibleRegion: FloatRect,
    val zIndex: Int = 0,
)
