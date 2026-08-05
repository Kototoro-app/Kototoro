package org.skepsun.kototoro.reader.ui.eink

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EInkRefreshPolicyTest {

	private val policy = EInkRefreshPolicy()

	@Test
	fun `initial state does not refresh`() {
		assertFalse(shouldRefresh(previous = null, current = page(1L, 0)))
		assertFalse(shouldRefresh(previous = page(1L, 0), current = null))
	}

	@Test
	fun `missing page identity resets count`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 2))
		assertFalse(shouldRefresh(previous = page(1L, 1), current = null, interval = 2))
		assertFalse(shouldRefresh(previous = page(1L, 1), current = page(1L, 2), interval = 2))
		assertFalse(shouldRefresh(previous = null, current = page(1L, 2), interval = 2))
		assertFalse(shouldRefresh(previous = page(1L, 2), current = page(1L, 3), interval = 2))
		assertTrue(shouldRefresh(previous = page(1L, 3), current = page(1L, 4), interval = 2))
	}

	@Test
	fun `same page does not refresh or advance count`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 2))
		assertFalse(shouldRefresh(previous = page(1L, 1), current = page(1L, 1), interval = 2))
		assertTrue(shouldRefresh(previous = page(1L, 1), current = page(1L, 2), interval = 2))
	}

	@Test
	fun `disabled policy does not refresh and resets count`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 2))
		assertFalse(
			shouldRefresh(
				enabled = false,
				previous = page(1L, 1),
				current = page(1L, 2),
				interval = 2,
			),
		)
		assertFalse(shouldRefresh(previous = page(1L, 2), current = page(1L, 3), interval = 2))
		assertTrue(shouldRefresh(previous = page(1L, 3), current = page(1L, 4), interval = 2))
	}

	@Test
	fun `non paged mode does not refresh and resets count`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 2))
		assertFalse(
			shouldRefresh(
				isPagedMode = false,
				previous = page(1L, 1),
				current = page(1L, 2),
				interval = 2,
			),
		)
		assertFalse(shouldRefresh(previous = page(1L, 2), current = page(1L, 3), interval = 2))
		assertTrue(shouldRefresh(previous = page(1L, 3), current = page(1L, 4), interval = 2))
	}

	@Test
	fun `explicit reset clears pending page count`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 2))

		policy.reset()

		assertFalse(shouldRefresh(previous = page(1L, 1), current = page(1L, 2), interval = 2))
		assertTrue(shouldRefresh(previous = page(1L, 2), current = page(1L, 3), interval = 2))
	}

	@Test
	fun `page changes refresh at configured interval`() {
		assertFalse(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 3))
		assertFalse(shouldRefresh(previous = page(1L, 1), current = page(1L, 2), interval = 3))
		assertTrue(shouldRefresh(previous = page(1L, 2), current = page(1L, 3), interval = 3))
		assertFalse(shouldRefresh(previous = page(1L, 3), current = page(1L, 4), interval = 3))
	}

	@Test
	fun `chapter change counts as page change`() {
		assertTrue(shouldRefresh(previous = page(1L, 9), current = page(2L, 0)))
	}

	@Test
	fun `interval is clamped to supported range`() {
		assertTrue(shouldRefresh(previous = page(1L, 0), current = page(1L, 1), interval = 0))

		repeat(9) { index ->
			assertFalse(
				shouldRefresh(
					previous = page(2L, index),
					current = page(2L, index + 1),
					interval = 11,
				),
			)
		}
		assertTrue(shouldRefresh(previous = page(2L, 9), current = page(2L, 10), interval = 11))
	}

	private fun shouldRefresh(
		enabled: Boolean = true,
		isPagedMode: Boolean = true,
		previous: EInkPageIdentity?,
		current: EInkPageIdentity?,
		interval: Int = 1,
	): Boolean = policy.shouldRefresh(
		enabled = enabled,
		isPagedMode = isPagedMode,
		previous = previous,
		current = current,
		interval = interval,
	)

	private fun page(chapterId: Long, pageIndex: Int) = EInkPageIdentity(chapterId, pageIndex)
}
