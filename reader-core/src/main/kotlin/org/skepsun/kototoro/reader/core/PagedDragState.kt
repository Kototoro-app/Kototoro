package org.skepsun.kototoro.reader.core

import kotlin.math.abs
import kotlin.math.sign

/** Allocates a baseline drag between overflowing content and discrete page navigation. */
class PagedDragState(readingDirection: SceneReadingDirection) {
    private val forwardSign = if (readingDirection == SceneReadingDirection.RIGHT_TO_LEFT) 1f else -1f
    private var physicalPageOffset = 0f
    private var canFling = false

    val pageOffset: Float get() = if (physicalPageOffset == 0f) 0f else physicalPageOffset * forwardSign

    data class Movement(
        val contentOffset: Float,
        val pageDelta: Float,
        val resetVelocity: Boolean,
    )

    fun dragBy(delta: Float, contentOffset: Float, range: ClosedFloatingPointRange<Float>): Movement {
        if (!delta.isFinite() || delta == 0f) return Movement(contentOffset, 0f, false)
        val previousPageOffset = physicalPageOffset
        var remaining = delta

        // Reverse an in-progress page turn before moving content back away from its edge.
        if (physicalPageOffset != 0f && remaining.sign != physicalPageOffset.sign) {
            val unwind = minOf(abs(remaining), abs(physicalPageOffset)) * remaining.sign
            physicalPageOffset += unwind
            remaining -= unwind
        }

        var residual = remaining
        val nextContentOffset = if (physicalPageOffset == 0f) {
            val requested = contentOffset + remaining
            val clamped = requested.coerceIn(range)
            // Subtract the clamp, not the rounded content delta: in-bounds movement has no residual.
            residual = requested - clamped
            clamped
        } else {
            contentOffset
        }
        val contentDelta = nextContentOffset - contentOffset
        physicalPageOffset += residual
        val pageDelta = (physicalPageOffset - previousPageOffset) * forwardSign
        val resetVelocity = !canFling || contentDelta != 0f
        // If the drag returns to content, neither earlier page nor content velocity can fling it.
        canFling = pageDelta != 0f && physicalPageOffset != 0f
        return Movement(nextContentOffset, pageDelta, resetVelocity)
    }

    fun resolveTargetSlot(currentSlot: Int, pageExtent: Float, forwardVelocity: Float, totalSlots: Int): Int =
        PagedSnapResolver.resolveTargetSlot(
            currentSlot = currentSlot,
            offsetFraction = (pageOffset / pageExtent).coerceIn(-1f, 1f),
            normalizedVelocity = if (canFling) forwardVelocity / (pageExtent * 0.5f) else 0f,
            totalSlots = totalSlots,
        )

    fun cancel() {
        physicalPageOffset = 0f
        canFling = false
    }
}
