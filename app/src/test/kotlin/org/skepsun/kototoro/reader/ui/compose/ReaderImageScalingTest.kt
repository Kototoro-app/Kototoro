package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderImageScalingTest {

	@Test
	fun `fitWithin preserves aspect ratio when width is limiting`() {
		assertEquals(ReaderScaledSize(1080, 1620), fitWithin(2000, 3000, 1080, 1920))
	}

	@Test
	fun `fitWithin preserves aspect ratio when height is limiting`() {
		assertEquals(ReaderScaledSize(1280, 1920), fitWithin(2000, 3000, 1920, 1920))
	}

	@Test
	fun `fitWithin does not upscale or accept invalid bounds`() {
		assertNull(fitWithin(800, 1200, 1080, 1920))
		assertNull(fitWithin(2000, 3000, 0, 1920))
	}
}
