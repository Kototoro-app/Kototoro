package org.skepsun.kototoro.reader.core

import kotlin.math.max
import kotlin.math.min

/**
 * Platform-agnostic 2D floating point bounding box in scene or viewport coordinates.
 *
 * Conforms to ADR 0002 Invariant I1: avoids android.graphics.RectF and Compose geometry.
 */
data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left <= right) { "left must be <= right: $left > $right" }
        require(top <= bottom) { "top must be <= bottom: $top > $bottom" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun intersects(other: FloatRect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fun intersectionOrNull(other: FloatRect): FloatRect? {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        return if (interLeft < interRight && interTop < interBottom) {
            FloatRect(interLeft, interTop, interRight, interBottom)
        } else {
            null
        }
    }

    fun translate(dx: Float, dy: Float): FloatRect {
        if (dx == 0f && dy == 0f) return this
        return FloatRect(left + dx, top + dy, right + dx, bottom + dy)
    }

    companion object {
        val Zero = FloatRect(0f, 0f, 0f, 0f)

        fun fromLtwh(left: Float, top: Float, width: Float, height: Float): FloatRect {
            require(width >= 0f) { "width must be >= 0: $width" }
            require(height >= 0f) { "height must be >= 0: $height" }
            return FloatRect(left, top, left + width, top + height)
        }
    }
}
