package org.skepsun.kototoro.details.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnimatedPanoramaBackdropTest {
    @Test
    fun `fade blends into the page color at the same smoothstep stops as the alpha mask`() {
        val page = Color(0xFF25446C)
        for (range in listOf(0f, 0.5f, 1f)) {
            val stops = panoramaFadeStops(page, range)
            val start = resolvePanoramaFadeStart(range)
            assertEquals(0f to page.copy(alpha = 0f), stops.first())
            assertEquals(start to page.copy(alpha = 0f), stops[1])
            assertEquals(1f to page, stops.last())
            for (i in 0..16) {
                val t = i / 16f
                val (position, color) = stops[i + 1]
                assertEquals(start + (1f - start) * t, position, 0.00001f)
                assertEquals(page.copy(alpha = t * t * (3f - 2f * t)), color)
            }
        }
    }

	@Test
	fun `transition range moves fade start within fixed bounds`() {
		assertEquals(0.82f, resolvePanoramaFadeStart(0f))
		assertEquals(0.50f, resolvePanoramaFadeStart(0.5f))
		assertEquals(0.18f, resolvePanoramaFadeStart(1f))
	}

	@Test
	fun `transition range is clamped`() {
		assertEquals(0.82f, resolvePanoramaFadeStart(-1f))
		assertEquals(0.18f, resolvePanoramaFadeStart(2f))
	}

	@Test
	fun `top opacity multiplies the current content alpha`() {
		assertEquals(0.4f, resolvePanoramaContentAlpha(contentAlpha = 0.8f, topOpacityPercent = 50))
		assertEquals(0f, resolvePanoramaContentAlpha(contentAlpha = 1f, topOpacityPercent = 0))
	}

	@Test
	fun `top opacity and content alpha are clamped`() {
		assertEquals(1f, resolvePanoramaContentAlpha(contentAlpha = 2f, topOpacityPercent = 200))
		assertEquals(0f, resolvePanoramaContentAlpha(contentAlpha = -1f, topOpacityPercent = -20))
	}
}
