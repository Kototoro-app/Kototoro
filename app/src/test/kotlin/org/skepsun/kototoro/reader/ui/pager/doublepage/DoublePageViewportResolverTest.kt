package org.skepsun.kototoro.reader.ui.pager.doublepage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DoublePageViewportResolverTest {

	@Test
	fun `uses fully visible anchor when available`() {
		val viewport = resolveCurrentDoublePageViewport(
			firstCompletelyVisibleItemPosition = 6,
			firstVisibleItemPosition = 4,
			itemCount = 10,
		)

		assertEquals(DoublePageViewport(lowerPos = 6, upperPos = 7), viewport)
	}

	@Test
	fun `falls back to first visible item when rotation leaves no fully visible page`() {
		val viewport = resolveCurrentDoublePageViewport(
			firstCompletelyVisibleItemPosition = -1,
			firstVisibleItemPosition = 5,
			itemCount = 10,
		)

		assertEquals(DoublePageViewport(lowerPos = 4, upperPos = 5), viewport)
	}

	@Test
	fun `clamps upper bound to current chapter spread instead of trailing preloaded items`() {
		val viewport = resolveCurrentDoublePageViewport(
			firstCompletelyVisibleItemPosition = -1,
			firstVisibleItemPosition = 8,
			itemCount = 11,
		)

		assertEquals(DoublePageViewport(lowerPos = 8, upperPos = 9), viewport)
	}

	@Test
	fun `returns last single page when item count is odd`() {
		val viewport = resolveCurrentDoublePageViewport(
			firstCompletelyVisibleItemPosition = 10,
			firstVisibleItemPosition = 10,
			itemCount = 11,
		)

		assertEquals(DoublePageViewport(lowerPos = 10, upperPos = 10), viewport)
	}

	@Test
	fun `returns null when nothing is visible`() {
		val viewport = resolveCurrentDoublePageViewport(
			firstCompletelyVisibleItemPosition = -1,
			firstVisibleItemPosition = -1,
			itemCount = 10,
		)

		assertNull(viewport)
	}
}
