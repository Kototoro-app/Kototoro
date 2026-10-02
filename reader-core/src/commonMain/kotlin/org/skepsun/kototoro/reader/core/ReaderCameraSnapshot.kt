package org.skepsun.kototoro.reader.core

/**
 * Geometric snapshot of the current camera transform in presentation space.
 *
 * @property scale Pinch-to-zoom factor applied to the scene viewport.
 * @property visibleBoundsInScene Sub-rectangle of the 2D scene actually displayed on screen
 *   after accounting for camera zoom and pan offsets.
 */
data class ReaderCameraSnapshot(
    val scale: Float,
    val visibleBoundsInScene: FloatRect,
) {
    init {
        require(scale > 0f && scale.isFinite()) { "scale must be > 0 and finite: $scale" }
    }
}
