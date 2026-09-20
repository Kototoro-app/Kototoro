package org.skepsun.kototoro.reader.core

/**
 * Platform-agnostic 2D integer dimensions.
 *
 * Conforms to ADR 0002 Invariant I1: no android.util.Size or compose.ui dependencies.
 */
data class IntSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0) { "width must be >= 0: $width" }
        require(height >= 0) { "height must be >= 0: $height" }
    }

    val isEmpty: Boolean get() = width == 0 || height == 0

    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 0f

    companion object {
        val Zero = IntSize(0, 0)
    }
}
