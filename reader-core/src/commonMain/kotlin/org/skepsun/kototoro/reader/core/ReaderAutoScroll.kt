package org.skepsun.kototoro.reader.core

import kotlin.math.roundToLong

/**
 * Timing of the reader's auto scroll (Android's `ScrollTimer`): a speed setting in 0..1 maps to a multiplier, the
 * continuous strip moves one dp per [scrollDelayMs], and a page that cannot scroll turns after [pageSwitchDelayMs].
 * Any interaction pauses it for [INTERACTION_PAUSE_MS].
 */
object ReaderAutoScroll {
    const val DEFAULT_SPEED = 0.24f
    const val INTERACTION_PAUSE_MS = 2_000L
    private const val BASE_SCROLL_DELAY_MS = 32L
    private const val BASE_PAGE_SWITCH_DELAY_MS = 10_000L
    private const val MIN_SPEED_MULTIPLIER = 0.1f
    private const val SPEED_MULTIPLIER_RANGE = 10f

    fun speedMultiplier(speed: Float): Float = MIN_SPEED_MULTIPLIER + speed.coerceIn(0f, 1f) * SPEED_MULTIPLIER_RANGE

    fun scrollDelayMs(speed: Float): Long =
        (BASE_SCROLL_DELAY_MS / speedMultiplier(speed)).roundToLong().coerceAtLeast(1L)

    fun pageSwitchDelayMs(speed: Float): Long =
        (BASE_PAGE_SWITCH_DELAY_MS / speedMultiplier(speed)).roundToLong().coerceAtLeast(1L)
}
