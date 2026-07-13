package org.skepsun.kototoro.reader.ui.pager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderAutoBackgroundTest {

	@Test
	fun `uses dark background for a page with dark edges`() {
		assertEquals(BLACK, resolve { _, _ -> BLACK })
	}

	@Test
	fun `uses light background when only page content is dark`() {
		assertEquals(
			WHITE,
			resolve { x, y -> if (x in 10..21 && y in 10..21) BLACK else WHITE },
		)
	}

	@Test
	fun `treats transparent page edges as light`() {
		assertEquals(WHITE, resolve { _, _ -> TRANSPARENT })
	}

	@Test
	fun `uses a restrained representative color for uniform colored edges`() {
		val result = resolve { _, _ -> GREEN }

		assertNotEquals(GREEN, result)
		assertNotEquals(BLACK, result)
		assertNotEquals(WHITE, result)
		assertTrue(result.green > result.red)
		assertTrue(result.green > result.blue)
	}

	@Test
	fun `falls back to light for mixed high contrast edges`() {
		assertEquals(WHITE, resolve { x, y -> if ((x + y) % 2 == 0) BLACK else WHITE })
	}

	@Test
	fun `merges similar page colors into one restrained spread color`() {
		val result = ReaderAutoBackground.merge(GREEN, DARK_GREEN)

		assertNotEquals(GREEN, result)
		assertNotEquals(DARK_GREEN, result)
		assertTrue(result.green > result.red)
		assertTrue(result.green > result.blue)
	}

	@Test
	fun `prefers a restrained page color over white for a spread`() {
		val result = ReaderAutoBackground.merge(WHITE, GREEN)

		assertNotEquals(WHITE, result)
		assertNotEquals(GREEN, result)
		assertTrue(result.green > result.red)
	}

	@Test
	fun `uses light fallback for a black and white spread`() {
		assertEquals(WHITE, ReaderAutoBackground.merge(BLACK, WHITE))
	}

	private fun resolve(pixelAt: (Int, Int) -> Int): Int {
		return ReaderAutoBackground.resolve(width = 32, height = 32, pixelAt = pixelAt)
	}

	private companion object {
		const val BLACK = -0x1000000
		const val WHITE = -0x1
		const val TRANSPARENT = 0x00000000
		const val GREEN = -0xC07095
		const val DARK_GREEN = -0xD077A9
	}

	private val Int.red: Int get() = this shr 16 and 0xFF
	private val Int.green: Int get() = this shr 8 and 0xFF
	private val Int.blue: Int get() = this and 0xFF
}
