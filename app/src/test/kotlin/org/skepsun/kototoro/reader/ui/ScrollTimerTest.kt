package org.skepsun.kototoro.reader.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings

class ScrollTimerTest {

	@Test
	fun `speed endpoints match the displayed multipliers`() {
		assertEquals(0.1f, autoScrollSpeedMultiplier(0f), 0.0001f)
		assertEquals(10.1f, autoScrollSpeedMultiplier(1f), 0.0001f)
	}

	@Test
	fun `default speed is two point five times`() {
		assertEquals(
			2.5f,
			autoScrollSpeedMultiplier(AppSettings.DEFAULT_READER_AUTOSCROLL_SPEED),
			0.0001f,
		)
	}

	@Test
	fun `maximum speed remains active and faster than minimum`() {
		assertTrue(autoScrollDelayMs(1f) > 0L)
		assertTrue(autoScrollDelayMs(1f) < autoScrollDelayMs(0f))
		assertTrue(autoPageSwitchDelayMs(1f) < autoPageSwitchDelayMs(0f))
	}
}
