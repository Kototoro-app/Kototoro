package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImmersiveEdgeGradientColorsTest {

    @Test
    fun `tint uses only two endpoints and preserves the configured edge strength`() {
        for (edge in ImmersiveEdge.entries) {
            for (alpha in listOf(0f, 0.35f, 0.8f, 1f)) {
                val base = Color(0xFF27394B).copy(alpha = alpha)
                val colors = immersiveEdgeGradientColors(base, edge)
                assertEquals(2, colors.size)
                for (index in 0..100) {
                    val position = index / 100f
                    val strength = if (edge == ImmersiveEdge.TOP) 1f - position else position
                    val interpolatedAlpha = colors[0].alpha + (colors[1].alpha - colors[0].alpha) * position
                    assertEquals(base.alpha * strength, interpolatedAlpha, 0.0001f)
                }
                for (color in colors) {
                    assertEquals(base.copy(alpha = color.alpha), color)
                }
            }
        }
    }

    @Test
    fun `top and bottom are exact mirrors`() {
        val color = Color(0xFF14385A).copy(alpha = 0.65f)
        assertEquals(
            immersiveEdgeGradientColors(color, ImmersiveEdge.TOP),
            immersiveEdgeGradientColors(color, ImmersiveEdge.BOTTOM).reversed(),
        )
    }
}
