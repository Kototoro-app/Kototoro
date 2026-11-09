package org.skepsun.kototoro.reader.ui

import kotlin.math.abs

/** An axis-aligned rectangle on screen, right and bottom exclusive. */
internal data class FrameBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun offset(dx: Float, dy: Float) = FrameBox(left + dx, top + dy, right + dx, bottom + dy)

    fun intersect(other: FrameBox) = FrameBox(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom),
    )
}

/**
 * Keeps the crop [frame] on the [image] while it is dragged, so the image never has to grow to fill
 * a frame that ran off it. A frame moved whole (its size the same as [previous]) slides back inside;
 * an edge or corner dragged past the image stops at the image edge. Returns [frame] when it already
 * fits, and null when the frame and the image no longer overlap at all.
 */
internal fun keepFrameOnImage(frame: FrameBox, previous: FrameBox, image: FrameBox): FrameBox? {
    if (image.width <= 0f || image.height <= 0f) return null
    if (frame.left >= image.left && frame.top >= image.top &&
        frame.right <= image.right && frame.bottom <= image.bottom
    ) {
        return frame
    }
    val moved = abs(frame.width - previous.width) < SAME_SIZE && abs(frame.height - previous.height) < SAME_SIZE
    val placed = if (moved) frame.offset(slideInto(frame.left, frame.right, image.left, image.right),
        slideInto(frame.top, frame.bottom, image.top, image.bottom)) else frame
    val kept = placed.intersect(image)
    return kept.takeIf { it.width > 0f && it.height > 0f }
}

/** How far a span at [start]..[end] has to move to sit inside [min]..[max], or line up with [min] if wider. */
private fun slideInto(start: Float, end: Float, min: Float, max: Float): Float = when {
    end - start >= max - min -> min - start
    start < min -> min - start
    end > max -> max - end
    else -> 0f
}

private const val SAME_SIZE = 0.5f
