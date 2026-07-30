package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class DoublePageSpreadModelTest {

	private val source = object : ContentSource {
		override val name = "test"
		override val locale = "en"
		override val contentType = ContentType.MANGA
	}

	private fun page(position: Int, chapterId: Long) = ReaderPage(
		id = position.toLong(),
		url = "https://example.test/$position",
		preview = null,
		headers = null,
		chapterId = chapterId,
		index = position,
		source = source,
	)

	@Test
	fun `inserts a spacer between odd chapter boundaries`() {
		val items = buildDoublePageDisplayItems(
			listOf(page(0, 1), page(1, 2), page(2, 2)),
		)

		assertEquals(listOf(0, -1, 1, 2), items.map { it.originalPosition })
		assertEquals(null, items[1].page)
	}

	@Test
	fun `inserts a spacer before the first page when cover mode is enabled`() {
		val items = buildDoublePageDisplayItems(
			listOf(page(0, 1), page(1, 1)),
			coverPage = true,
		)

		assertEquals(listOf(-1, 0, 1), items.map { it.originalPosition })
		assertEquals(null, items.first().page)
	}

	@Test
	fun `inserts a cover spacer at every chapter boundary`() {
		val items = buildDoublePageDisplayItems(
			listOf(page(0, 1), page(1, 1), page(2, 2)),
			coverPage = true,
		)

		assertEquals(listOf(-1, 0, 1, -1, -1, 2), items.map { it.originalPosition })
		assertEquals(null, items[4].page)
	}

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
	fun `reversed spreads render the partner page first`() {
		val spread = DoublePageSpread(lowerPosition = 2, upperPosition = 3)

		assertEquals(listOf(2, 3), spread.orderedPositions(reverseLayout = false))
		assertEquals(listOf(3, 2), spread.orderedPositions(reverseLayout = true))
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
			resolvePageNavigationTarget(currentPosition = 3, delta = 1, pageStep = 1),
		)
	}

	@Test
	fun `double page navigation advances one complete spread`() {
		assertEquals(
			4,
			resolvePageNavigationTarget(currentPosition = 2, delta = 1, pageStep = 2),
		)
	}

	@Test
	fun `reversed layout navigation still advances through content indexes`() {
		assertEquals(
			6,
			resolvePageNavigationTarget(currentPosition = 4, delta = 1, pageStep = 2),
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
