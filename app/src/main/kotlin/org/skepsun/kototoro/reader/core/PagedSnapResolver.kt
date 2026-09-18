package org.skepsun.kototoro.reader.core

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure mathematical snapping resolver for discrete paging gestures and fling velocity.
 */
object PagedSnapResolver {
    const val DEFAULT_SNAP_FRACTION_THRESHOLD = 0.20f
    const val DEFAULT_VELOCITY_THRESHOLD = 0.5f

    /**
     * Resolves the target slot index when a page drag or fling gesture releases.
     *
     * @param currentSlot The slot index at the start of the gesture.
     * @param offsetFraction The signed fractional displacement along reading progression [-1.0f..1.0f].
     *                       Positive means progressing forward to next slot; negative means retreating to previous.
     * @param normalizedVelocity The signed velocity along reading progression (positive = forward).
     * @param totalSlots Total number of available slots.
     * @param fractionThreshold Minimum displacement fraction required to trigger a page turn.
     * @param velocityThreshold Minimum velocity required to trigger a fling page turn regardless of distance.
     */
    fun resolveTargetSlot(
        currentSlot: Int,
        offsetFraction: Float,
        normalizedVelocity: Float,
        totalSlots: Int,
        fractionThreshold: Float = DEFAULT_SNAP_FRACTION_THRESHOLD,
        velocityThreshold: Float = DEFAULT_VELOCITY_THRESHOLD,
    ): Int {
        if (totalSlots <= 0) return 0
        val clampedCurrent = currentSlot.coerceIn(0, totalSlots - 1)

        val delta: Int = when {
            abs(normalizedVelocity) >= velocityThreshold -> {
                normalizedVelocity.sign.toInt()
            }
            abs(offsetFraction) >= fractionThreshold -> {
                offsetFraction.sign.toInt()
            }
            else -> 0
        }

        return (clampedCurrent + delta).coerceIn(0, totalSlots - 1)
    }
}
