package org.skepsun.kototoro.reader.core

/**
 * Concrete spatial placement of a page within the continuous 2D scene coordinate system.
 *
 * Distinguishes "what are the dimensions of this page" ([PageGeometryHint]) from
 * "where is this page placed in the scene space" ([PageGeometry]).
 */
data class PageGeometry(
    val pageId: PageId,
    val sceneBounds: FloatRect,
    val zIndex: Int = 0,
)
