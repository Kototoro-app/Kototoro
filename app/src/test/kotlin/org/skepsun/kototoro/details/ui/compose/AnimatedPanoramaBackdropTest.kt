package org.skepsun.kototoro.details.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnimatedPanoramaBackdropTest {
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
