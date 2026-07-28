package org.skepsun.kototoro.reader.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class ReaderPageSelectionResolverTest {

	@Test
	fun `restores current chapter page when previous chapter is preloaded`() {
		val pages = listOf(
			page(chapterId = 182L, index = 0),
			page(chapterId = 182L, index = 1),
			page(chapterId = 183L, index = 0),
			page(chapterId = 183L, index = 1),
		)

		val position = resolveReaderInitialPagePosition(
			pages = pages,
			state = ReaderState(chapterId = 183L, page = 1, scroll = 120),
		)

		assertEquals(3, position)
	}

	@Test
	fun `prefers lower page when visible double spread crosses into preloaded next chapter`() {
		val pages = listOf(
			page(chapterId = 1L, index = 0),
			page(chapterId = 1L, index = 1),
			page(chapterId = 2L, index = 0),
		)

		val selected = resolveVisiblePageSelection(
			pages = pages,
			lowerPos = 1,
			upperPos = 2,
			currentChapterId = 1L,
			boundsPageOffset = 2,
		)

		assertEquals(1, selected)
	}

	@Test
	fun `keeps upper page when current chapter already switched to next chapter`() {
		val pages = listOf(
			page(chapterId = 1L, index = 0),
			page(chapterId = 1L, index = 1),
			page(chapterId = 2L, index = 0),
		)

		val selected = resolveVisiblePageSelection(
			pages = pages,
			lowerPos = 1,
			upperPos = 2,
			currentChapterId = 2L,
			boundsPageOffset = 2,
		)

		assertEquals(2, selected)
	}

	@Test
	fun `keeps existing same chapter near end behavior when viewport does not cross chapter`() {
		val pages = listOf(
			page(chapterId = 1L, index = 0),
			page(chapterId = 1L, index = 1),
			page(chapterId = 1L, index = 2),
			page(chapterId = 1L, index = 3),
			page(chapterId = 1L, index = 4),
		)

		val selected = resolveVisiblePageSelection(
			pages = pages,
			lowerPos = 3,
			upperPos = 4,
			currentChapterId = 1L,
			boundsPageOffset = 2,
		)

		assertEquals(4, selected)
	}

	private fun page(chapterId: Long, index: Int) = ReaderPage(
		id = chapterId * 100 + index,
		url = "http://localhost/$chapterId/$index",
		preview = null,
		headers = null,
		chapterId = chapterId,
		index = index,
		source = TestContentSource,
	)
}
