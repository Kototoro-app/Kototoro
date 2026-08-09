package org.skepsun.kototoro.core.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoEnhancementCompatibilityTest {

	@Test
	fun `gpu next keeps renderer and disables inherited super resolution`() {
		val mode = VideoEnhancementCompatibility.superResolutionForRenderer(
			VideoRendererMode.GPU_NEXT,
			VideoSuperResolutionMode.BALANCED,
		)

		assertEquals(VideoSuperResolutionMode.OFF, mode)
	}

	@Test
	fun `enabling super resolution replaces gpu next with gpu`() {
		val renderer = VideoEnhancementCompatibility.rendererForSuperResolution(
			VideoSuperResolutionMode.QUALITY,
			VideoRendererMode.GPU_NEXT,
		)

		assertEquals(VideoRendererMode.GPU, renderer)
	}

	@Test
	fun `compatible selections remain unchanged`() {
		assertEquals(
			VideoSuperResolutionMode.PERFORMANCE,
			VideoEnhancementCompatibility.superResolutionForRenderer(
				VideoRendererMode.GPU,
				VideoSuperResolutionMode.PERFORMANCE,
			),
		)
		assertEquals(
			VideoRendererMode.AUTO,
			VideoEnhancementCompatibility.rendererForSuperResolution(
				VideoSuperResolutionMode.OFF,
				VideoRendererMode.AUTO,
			),
		)
	}
}
