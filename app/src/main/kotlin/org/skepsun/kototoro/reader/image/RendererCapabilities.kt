package org.skepsun.kototoro.reader.image

import org.skepsun.kototoro.reader.core.IntSize

/**
 * Objective rendering engine limits reported by the active display backend.
 *
 * For Compose/View Canvas: populated from `Canvas.maximumBitmapWidth` / `maximumBitmapHeight`.
 * For WebGPU: populated from `maxTextureDimension2D`.
 */
sealed interface RendererCapabilities {

    /**
     * Initial uninitialized state before the first rendering frame.
     */
    data object Unknown : RendererCapabilities

    /**
     * Confirmed physical limits reported by the renderer Canvas or GPU device.
     */
    data class Resolved(
        val maxDrawableWidthPx: Int,
        val maxDrawableHeightPx: Int,
    ) : RendererCapabilities {
        init {
            require(maxDrawableWidthPx > 0) { "maxDrawableWidthPx must be > 0: $maxDrawableWidthPx" }
            require(maxDrawableHeightPx > 0) { "maxDrawableHeightPx must be > 0: $maxDrawableHeightPx" }
        }
    }

    /**
     * Resolves effective dimension limits by clamping against an optional policy safety ceiling.
     * When [Unknown], uses [fallbackDefaultPx] (default 4096) as a conservative bootstrap value.
     */
    fun effectiveLimits(
        safetyLimitPx: Int = DEFAULT_SAFETY_DIMENSION_LIMIT_PX,
        fallbackDefaultPx: Int = DEFAULT_SAFETY_DIMENSION_LIMIT_PX,
    ): IntSize {
        return when (this) {
            is Unknown -> IntSize(fallbackDefaultPx, fallbackDefaultPx)
            is Resolved -> IntSize(
                minOf(maxDrawableWidthPx, safetyLimitPx),
                minOf(maxDrawableHeightPx, safetyLimitPx),
            )
        }
    }

    companion object {
        const val DEFAULT_SAFETY_DIMENSION_LIMIT_PX = 4096
    }
}
