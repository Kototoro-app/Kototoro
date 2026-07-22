package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageSpreadModelTest {

	@Test
	fun `creates complete spreads for an even page count`() {
		val model = DoublePageSpreadModel.create(pageCount = 4)

		assertEquals(
			listOf(
				DoublePageSpread(lowerPosition = 0, upperPosition = 1),
				DoublePageSpread(lowerPosition = 2, upperPosition = 3),
			),
			model.spreads,
		)
	}

	@Test
	fun `keeps a blank partner for the final odd page`() {
		val model = DoublePageSpreadModel.create(pageCount = 5)

		assertEquals(DoublePageSpread(lowerPosition = 4, upperPosition = 4), model.spreads.last())
	}

	@Test
	fun `maps either page in a spread to the same logical index`() {
		val model = DoublePageSpreadModel.create(pageCount = 6)

		assertEquals(1, model.spreadIndexForPage(2))
		assertEquals(1, model.spreadIndexForPage(3))
	}

	@Test
	fun `clamps restored positions at both content boundaries`() {
		val model = DoublePageSpreadModel.create(pageCount = 5)

		assertEquals(0, model.spreadIndexForPage(-1))
		assertEquals(2, model.spreadIndexForPage(10))
	}

	@Test
	fun `creates no spreads for empty content`() {
		assertTrue(DoublePageSpreadModel.create(pageCount = 0).spreads.isEmpty())
	}

	@Test
	fun `standard navigation advances one content page`() {
		assertEquals(
			4,
			resolvePageNavigationTarget(currentPosition = 3, delta = 1, pageStep = 1, navigationDirection = 1),
		)
	}

	@Test
	fun `double page navigation advances one complete spread`() {
		assertEquals(
			4,
			resolvePageNavigationTarget(currentPosition = 2, delta = 1, pageStep = 2, navigationDirection = 1),
		)
	}

	@Test
	fun `reversed double page navigation maps commands toward lower content indexes`() {
		assertEquals(
			2,
			resolvePageNavigationTarget(currentPosition = 4, delta = 1, pageStep = 2, navigationDirection = -1),
		)
	}

	@Test
	fun `keeps the same content page anchored when an odd page count is prepended`() {
		val previousKeys = listOf(10L, 11L, 12L, 13L)
		val anchorKey = previousKeys[2]
		val updatedKeys = listOf(1L, 2L, 3L) + previousKeys
		val model = DoublePageSpreadModel.create(updatedKeys.size)

		assertEquals(
			2,
			model.resolveAnchorSpreadIndex(updatedKeys, anchorKey, fallbackPosition = 2),
		)
		assertTrue(anchorKey in model.spreads[2].positions.map(updatedKeys::get))
	}

	@Test
	fun `appending pages does not move the current spread anchor`() {
		val updatedKeys = listOf(10L, 11L, 12L, 13L, 20L, 21L)
		val model = DoublePageSpreadModel.create(updatedKeys.size)

		assertEquals(
			1,
			model.resolveAnchorSpreadIndex(updatedKeys, anchorPageKey = 12L, fallbackPosition = 2),
		)
	}

	@Test
	fun `missing boundary anchor falls back to a clamped visible position`() {
		val model = DoublePageSpreadModel.create(pageCount = 5)

		assertEquals(
			2,
			model.resolveAnchorSpreadIndex(
				pageKeys = listOf(10L, 11L, 12L, 13L, 14L),
				anchorPageKey = 99L,
				fallbackPosition = 20,
			),
		)
	}

	@Test
	fun `page navigation animates only short explicitly smooth moves`() {
		assertTrue(shouldAnimatePageNavigation(2, 4, smoothRequested = true, isAnimationEnabled = true))
		assertEquals(false, shouldAnimatePageNavigation(2, 5, smoothRequested = true, isAnimationEnabled = true))
		assertEquals(false, shouldAnimatePageNavigation(2, 3, smoothRequested = false, isAnimationEnabled = true))
		assertEquals(false, shouldAnimatePageNavigation(2, 3, smoothRequested = true, isAnimationEnabled = false))
	}
}
