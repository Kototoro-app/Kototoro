package org.skepsun.kototoro.reader.render.compose

/**
 * Seam elimination policy for adjacent page slices rendered onto the canvas.
 *
 * Adds subtle overlap along the continuous axis to eliminate 1px bilinear interpolation
 * cracks / gaps caused by floating-point rounding during subpixel rasterization.
 */
data class PageSeamPolicy(
    val overlapX: Int = 0,
    val overlapY: Int = 1,
) {
    companion object {
        val Zero = PageSeamPolicy(overlapX = 0, overlapY = 0)
        val VerticalContinuous = PageSeamPolicy(overlapX = 0, overlapY = 1)
        val HorizontalContinuous = PageSeamPolicy(overlapX = 1, overlapY = 0)

        fun forScene(spacingPx: Int, isVertical: Boolean): PageSeamPolicy {
            if (spacingPx > 0) return Zero
            return if (isVertical) VerticalContinuous else HorizontalContinuous
        }
    }
}
