package org.skepsun.kototoro.reader.core

import kotlin.math.max

/**
 * Pure mathematical resolver for calculating allowable pan translation ranges
 * and start-alignment offsets when content dimensions differ from viewport dimensions.
 *
 * Preserves ADR Invariant I1: strictly zero platform/Android dependencies.
 */
object PagedPanBoundsResolver {

    /**
     * Resolves the allowable translation range for an axis under the given [scale]
     * where transformations are centered around viewport midpoint (viewportSize / 2).
     *
     * @param contentMin The unscaled start coordinate of content in the slot (e.g. left or top).
     * @param contentMax The unscaled end coordinate of content in the slot (e.g. right or bottom).
     * @param viewportSize The size of the viewport along this axis.
     * @param scale The current zoom scale factor (>= 1.0).
     * @return A [ClosedFloatingPointRange] representing valid translation offsets.
     *         If content fits entirely within the viewport, returns a degenerate range
     *         representing the neutral resting translation (0.0f for centered content).
     */
    fun resolvePanRange(
        contentMin: Float,
        contentMax: Float,
        viewportSize: Float,
        scale: Float,
    ): ClosedFloatingPointRange<Float> {
        if (viewportSize <= 0f) return 0f..0f
        val contentSize = max(0f, contentMax - contentMin)
        val transformedSize = contentSize * scale
        val viewportCenter = viewportSize / 2f

        if (transformedSize <= viewportSize + 0.5f) {
            val contentCenter = (contentMin + contentMax) / 2f
            val neutral = (viewportCenter - contentCenter) * (1f - scale)
            return neutral..neutral
        }

        val maxTranslation = -(viewportCenter + (contentMin - viewportCenter) * scale)
        val minTranslation = viewportSize - (viewportCenter + (contentMax - viewportCenter) * scale)

        return if (minTranslation <= maxTranslation) {
            minTranslation..maxTranslation
        } else {
            maxTranslation..minTranslation
        }
    }

    /**
     * Resolves the initial translation offset to align the beginning of overflowing content
     * with the viewport start (e.g. top of page for vertical overflow in LTR/RTL,
     * or left/right of page for horizontal overflow).
     *
     * @param isStartReversed If true (e.g. RTL horizontal), aligns to the end rather than start.
     */
    fun resolveInitialOverflowOffset(
        contentMin: Float,
        contentMax: Float,
        viewportSize: Float,
        scale: Float,
        isStartReversed: Boolean = false,
    ): Float {
        val range = resolvePanRange(contentMin, contentMax, viewportSize, scale)
        if (range.start == range.endInclusive) {
            return range.start
        }
        return if (isStartReversed) range.start else range.endInclusive
    }
}
