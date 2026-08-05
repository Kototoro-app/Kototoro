package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProgressUpdateUseCaseTest {

	@Test
	fun `video progress uses chapter index and normalized episode scroll`() {
		assertEquals(0.17781667f, requireNotNull(calculateVideoSeriesProgress(1, 6, 669)), 0.000001f)
	}

	@Test
	fun `video progress clamps persisted episode scroll`() {
		assertEquals(1f / 6f, requireNotNull(calculateVideoSeriesProgress(0, 6, 88_006)), 0.000001f)
		assertEquals(0f, requireNotNull(calculateVideoSeriesProgress(0, 6, -1)), 0.000001f)
	}

	@Test
	fun `video progress rejects invalid chapter coordinates`() {
		assertNull(calculateVideoSeriesProgress(-1, 6, 5000))
		assertNull(calculateVideoSeriesProgress(6, 6, 5000))
		assertNull(calculateVideoSeriesProgress(0, 0, 5000))
	}
}
