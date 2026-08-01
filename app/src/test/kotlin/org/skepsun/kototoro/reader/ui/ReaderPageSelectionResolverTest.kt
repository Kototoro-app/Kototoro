package org.skepsun.kototoro.reader.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

class ReaderPageSelectionResolverTest {

	@Test
	fun `paged reader preserves page identity when previous pages are inserted`() {
		val position = resolvePagedReaderAnchorPosition(
			pageKeys = listOf(10L, 11L, 20L, 21L, 22L),
			anchorPageKey = 21L,
			fallbackPosition = 1,
		)

		assertEquals(3, position)
	}

	@Test
	fun `paged reader uses bounded fallback when anchor page disappears`() {
		val position = resolvePagedReaderAnchorPosition(
			pageKeys = listOf(20L, 21L),
			anchorPageKey = 10L,
			fallbackPosition = 8,
		)

		assertEquals(1, position)
	}

	@Test
	fun `paged reader has no anchor for an empty window`() {
		val position = resolvePagedReaderAnchorPosition(
			pageKeys = emptyList(),
			anchorPageKey = 10L,
			fallbackPosition = 0,
		)

		assertEquals(null, position)
	}

	@Test
	fun `reload nonce invalidates compose page key`() {
		val page = page(chapterId = 183L, index = 1)

		assertEquals(page.readerKey, page.copy().readerKey)
		assertNotEquals(page.readerKey, page.copy(reloadNonce = 1L).readerKey)
	}

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
	fun `live compose page wins over stale persisted state during layout switch`() {
		val pages = listOf(
			page(chapterId = 183L, index = 0),
			page(chapterId = 183L, index = 1),
			page(chapterId = 183L, index = 2),
			page(chapterId = 183L, index = 3),
		)

		val position = resolveReaderCurrentPagePosition(
			pages = pages,
			currentPageKey = pages[3].readerKey,
			fallbackState = ReaderState(chapterId = 183L, page = 0, scroll = 0),
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
	fun `webtoon keeps current chapter until it leaves the viewport`() {
		val pages = listOf(
			page(chapterId = 1L, index = 130),
			page(chapterId = 1L, index = 131),
			page(chapterId = 2L, index = 0),
			page(chapterId = 2L, index = 1),
		)

		val selectedAtBoundary = resolveWebtoonVisiblePageSelection(
			pages = pages,
			lowerPos = 0,
			upperPos = 3,
			currentChapterId = 1L,
			activePageKey = pages[2].readerKey,
			boundsPageOffset = 2,
		)
		val selectedAfterBoundary = resolveWebtoonVisiblePageSelection(
			pages = pages,
			lowerPos = 2,
			upperPos = 3,
			currentChapterId = 1L,
			activePageKey = pages[2].readerKey,
			boundsPageOffset = 2,
		)

		assertEquals(1, selectedAtBoundary)
		assertEquals(2, selectedAfterBoundary)
	}

	@Test
	fun `selects last visible page from current chapter when viewport spans multiple short pages`() {
		val pages = listOf(
			page(chapterId = 1L, index = 0),
			page(chapterId = 1L, index = 1),
			page(chapterId = 1L, index = 2),
			page(chapterId = 2L, index = 0),
			page(chapterId = 2L, index = 1),
		)

		val selected = resolveVisiblePageSelection(
			pages = pages,
			lowerPos = 0,
			upperPos = 4,
			currentChapterId = 1L,
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

	@Test
	fun `selects current chapter last visible page when next chapter is preloaded`() {
		val pages = listOf(
			page(chapterId = 1L, index = 0),
			page(chapterId = 1L, index = 1),
			page(chapterId = 1L, index = 2),
			page(chapterId = 1L, index = 3),
			page(chapterId = 1L, index = 4),
			page(chapterId = 2L, index = 0),
			page(chapterId = 2L, index = 1),
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
