package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeReaderControllerPolicyTest {

	@Test
	fun `old pager callback cannot consume layout transition request`() {
		assertFalse(shouldAcceptReaderPosition(position = 9, requestedPosition = 12))
	}

	@Test
	fun `target pager callback completes layout transition request`() {
		assertTrue(shouldAcceptReaderPosition(position = 12, requestedPosition = 12))
	}

	@Test
	fun `neighbour page callback completes a double-page transition request`() {
		assertTrue(shouldAcceptReaderPosition(position = 11, requestedPosition = 12))
	}
	@Test
	fun `normal paging accepts every settled position`() {
		assertTrue(shouldAcceptReaderPosition(position = 13, requestedPosition = null))
	}

	@Test
	fun `pending page key follows the same page after a chapter prepend`() {
		val requestedPageKey = 103L
		val pagesAfterPrepend = listOf(1L, 2L, 101L, 102L, requestedPageKey, 104L)

		assertEquals(4, resolvePageKeyPosition(pagesAfterPrepend, requestedPageKey))
	}

	@Test
	fun `settled webtoon key remains accepted after chapter window expands`() {
		val expandedWindow = listOf(101L, 102L) + (200L..230L)

		assertTrue(
			shouldAcceptReaderPageKey(
				pageKeys = expandedWindow,
				pageKey = 220L,
				requestedPageKey = null,
				currentPageKey = 202L,
				initialPageKey = 200L,
			),
		)
	}

	@Test
	fun `stale webtoon key cannot override pending stable target after prepend`() {
		val pagesAfterPrepend = listOf(1L, 2L, 101L, 102L, 103L, 104L)

		assertFalse(
			shouldAcceptReaderPageKey(
				pageKeys = pagesAfterPrepend,
				pageKey = 101L,
				requestedPageKey = 103L,
				currentPageKey = 101L,
				initialPageKey = 101L,
			),
		)
	}

	@Test
	fun `repeated navigation continues from the pending target`() {
		val pageKeys = listOf(101L, 102L, 103L, 104L)

		assertEquals(
			2,
			resolvePageNavigationBasePosition(
				pageKeys = pageKeys,
				requestedPageKey = 103L,
				settledPosition = 0,
			),
		)
	}

	@Test
	fun `twenty navigation commands accumulate without waiting for settled callbacks`() {
		val pageKeys = (100L..130L).toList()
		var requestedPageKey: Long? = null
		val settledPosition = 0

		repeat(20) {
			val base = resolvePageNavigationBasePosition(pageKeys, requestedPageKey, settledPosition)
			val target = resolvePageNavigationTarget(base, delta = 1, pageStep = 1)
			requestedPageKey = pageKeys[target]
		}

		assertEquals(120L, requestedPageKey)
	}
}
