package org.skepsun.kototoro.reader.core

/**
 * Policy governing wide page detection for double-page spreads and page splitting.
 *
 * Aligned with the production standard: width > height * 1.15f (aspect ratio > 1.15).
 */
object WidePagePolicy {
    const val WIDE_PAGE_ASPECT_RATIO_THRESHOLD = 1.15f

    fun isWidePage(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return width > height * WIDE_PAGE_ASPECT_RATIO_THRESHOLD
    }

    fun isWidePage(hint: PageGeometryHint): Boolean = when (hint) {
        is PageGeometryHint.Exact -> isWidePage(hint.width, hint.height)
        is PageGeometryHint.AspectRatio -> hint.ratio > WIDE_PAGE_ASPECT_RATIO_THRESHOLD
        is PageGeometryHint.Estimated -> hint.ratio > WIDE_PAGE_ASPECT_RATIO_THRESHOLD
    }
}
