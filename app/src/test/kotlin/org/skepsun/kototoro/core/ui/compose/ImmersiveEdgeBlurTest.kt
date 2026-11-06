package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImmersiveEdgeBlurTest {

    @Test
    fun `blur radius is linear across the entire band at both edges`() {
        for (edge in ImmersiveEdge.entries) {
            for (radius in listOf(0f, 16f, 48f)) {
                val stops = immersiveEdgeBlurRadiusStops(edge, radius.dp)
                    .map { it.fraction to it.radius.value }
                    .sortedBy { it.first }
                for (index in 0..100) {
                    val position = index / 100f
                    val strength = if (edge == ImmersiveEdge.TOP) 1f - position else position
                    assertEquals(radius * strength, sample(stops, position), 0.0001f, "$edge at $position")
                }
            }
        }
    }

    @Test
    fun `Android 12 cross-fades adjacent radii without changing the band opacity`() {
        for (edge in ImmersiveEdge.entries) {
            val masks = ImmersiveEdgeBlurSteps.map { step ->
                step.radiusFraction to immersiveEdgeBlurMaskStops(step, edge).map { it.first to it.second.alpha }
            }
            for (index in 0..100) {
                val position = index / 100f
                val strength = if (edge == ImmersiveEdge.TOP) 1f - position else position
                var opacity = 0f
                var radius = 0f
                for ((radiusFraction, mask) in masks) {
                    val alpha = sample(mask, position)
                    opacity = alpha + opacity * (1f - alpha)
                    radius = radiusFraction * alpha + radius * (1f - alpha)
                }
                assertEquals(1f, opacity, 0.0001f, "$edge opacity at $position")
                assertEquals(strength, radius, 0.0001f, "$edge radius mixture at $position")
            }
        }
    }

    private fun sample(stops: List<Pair<Float, Float>>, position: Float): Float {
        val upper = stops.indexOfFirst { it.first >= position }
        if (upper <= 0) return stops.first().second
        val (start, startValue) = stops[upper - 1]
        val (end, endValue) = stops[upper]
        val fraction = (position - start) / (end - start)
        return startValue + fraction * (endValue - startValue)
    }
}
