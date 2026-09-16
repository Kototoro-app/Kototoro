package org.skepsun.kototoro.reader.core

/**
 * Camera/viewport window looking into the 2D scene.
 *
 * [bounds] defines the visible window in scene coordinates.
 * [scale] captures the zoom factor applied to the scene view.
 */
data class ReaderViewport(
    val bounds: FloatRect,
    val scale: Float = 1f,
) {
    init {
        require(scale > 0f && scale.isFinite()) { "scale must be > 0 and finite: $scale" }
    }

    val width: Float get() = bounds.width
    val height: Float get() = bounds.height
}
