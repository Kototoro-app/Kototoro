package org.skepsun.kototoro.reader.core

/**
 * High-frequency motion telemetry emitted by gesture processors in the view/compose layer.
 *
 * Conforms to ADR 0002 Constraint 2:
 * Dual-use contract:
 * 1. Consumed by [ReaderPrediction] to compute dynamic prefetch lookaheads.
 * 2. Consumed by presentation layer to negotiate Adaptive Refresh Rate (ARR) frame rate hints.
 *
 * Zero Android UI dependencies (pure Kotlin).
 *
 * @property velocityX Horizontal velocity in px/s.
 * @property velocityY Vertical velocity in px/s (positive = scrolling forward towards subsequent pages).
 * @property isDragging True if user finger/pointer is actively touching the screen.
 * @property timestampNanos Monotonic timestamp in nanoseconds when motion was sampled.
 */
data class ViewportMotion(
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val isDragging: Boolean = false,
    val timestampNanos: Long = 0L,
) {
    val speed: Float
        get() = kotlin.math.hypot(velocityX, velocityY)

    val isIdle: Boolean
        get() = !isDragging && speed < 1f

    companion object {
        val Idle = ViewportMotion()
    }
}
