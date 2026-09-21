package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The settle an interrupted turn has to perform.
 *
 * Regression: a gesture that interrupted the 220ms turn animation and never became a drag left the
 * paging offset between two slots, so the page stayed frozen mid-turn. These cases pin the landing
 * points.
 */
class SettledTurnOffsetTest {

	private val extent = 1000f
	private val maxOffset = 3000f

	@Test
	fun `an offset already on a slot needs no settle`() {
		assertNull(resolveSettledTurnOffset(0f, extent, slotCount = 4, maxOffset = maxOffset))
		assertNull(resolveSettledTurnOffset(2000f, extent, slotCount = 4, maxOffset = maxOffset))
	}

	@Test
	fun `a mid-turn offset settles on the nearest slot`() {
		assertEquals(1000f, resolveSettledTurnOffset(600f, extent, slotCount = 4, maxOffset = maxOffset))
		assertEquals(0f, resolveSettledTurnOffset(400f, extent, slotCount = 4, maxOffset = maxOffset))
		assertEquals(1000f, resolveSettledTurnOffset(999f, extent, slotCount = 4, maxOffset = maxOffset))
	}

	@Test
	fun `the settle stays inside the scrollable range`() {
		assertEquals(3000f, resolveSettledTurnOffset(5000f, extent, slotCount = 4, maxOffset = maxOffset))
		assertEquals(0f, resolveSettledTurnOffset(-120f, extent, slotCount = 4, maxOffset = maxOffset))
		assertEquals(0f, resolveSettledTurnOffset(400f, extent, slotCount = 1, maxOffset = 0f))
	}

	@Test
	fun `degenerate geometry asks for no settle`() {
		assertNull(resolveSettledTurnOffset(400f, primaryExtent = 0f, slotCount = 4, maxOffset = maxOffset))
		assertNull(resolveSettledTurnOffset(400f, extent, slotCount = 0, maxOffset = maxOffset))
		assertNull(resolveSettledTurnOffset(Float.NaN, extent, slotCount = 4, maxOffset = maxOffset))
	}
}
