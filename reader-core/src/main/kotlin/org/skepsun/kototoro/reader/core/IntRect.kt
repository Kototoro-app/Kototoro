package org.skepsun.kototoro.reader.core

import kotlin.math.max
import kotlin.math.min

/**
 * Platform-agnostic 2D integer bounding box in scene, page, or image pixel coordinates.
 *
 * Conforms to ADR 0002 Invariant I1: avoids android.graphics.Rect and Compose geometry.
 */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right) { "left must be <= right: $left > $right" }
        require(top <= bottom) { "top must be <= bottom: $top > $bottom" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom

    fun contains(x: Int, y: Int): Boolean {
        return x in left..right && y in top..bottom
    }

    fun intersects(other: IntRect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fun intersectionOrNull(other: IntRect): IntRect? {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        return if (interLeft < interRight && interTop < interBottom) {
            IntRect(interLeft, interTop, interRight, interBottom)
        } else {
            null
        }
    }

    fun translate(dx: Int, dy: Int): IntRect {
        if (dx == 0 && dy == 0) return this
        return IntRect(left + dx, top + dy, right + dx, bottom + dy)
    }

    fun toFloatRect(): FloatRect {
        return FloatRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    companion object {
        val Zero = IntRect(0, 0, 0, 0)

        fun fromLtwh(left: Int, top: Int, width: Int, height: Int): IntRect {
            require(width >= 0) { "width must be >= 0: $width" }
            require(height >= 0) { "height must be >= 0: $height" }
            return IntRect(left, top, left + width, top + height)
        }
    }
}
