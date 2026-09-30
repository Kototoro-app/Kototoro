package org.skepsun.kototoro.reader.translate.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Anime4kSizeEvaluatorTest {

	private val sizes = mapOf(
		"MAIN" to (100 to 40),
		"LUMA" to (30 to 10),
	)

	@Test
	fun `literal is returned as is`() {
		assertEquals(7, Anime4kSizeEvaluator.evaluate("7", sizes))
	}

	@Test
	fun `texture width and height are looked up`() {
		assertEquals(100, Anime4kSizeEvaluator.evaluate("MAIN.w", sizes))
		assertEquals(40, Anime4kSizeEvaluator.evaluate("MAIN.height", sizes))
		assertEquals(0, Anime4kSizeEvaluator.evaluate("MISSING.w", sizes))
	}

	@Test
	fun `each operator pops operands in postfix order`() {
		assertEquals(130, Anime4kSizeEvaluator.evaluate("MAIN.w LUMA.w +", sizes))
		assertEquals(70, Anime4kSizeEvaluator.evaluate("MAIN.w LUMA.w -", sizes))
		assertEquals(200, Anime4kSizeEvaluator.evaluate("MAIN.w 2 *", sizes))
		assertEquals(20, Anime4kSizeEvaluator.evaluate("MAIN.h 2 /", sizes))
	}

	@Test
	fun `nested expression evaluates left to right`() {
		// (MAIN.w * 2) - (LUMA.h + 5)
		assertEquals(185, Anime4kSizeEvaluator.evaluate("MAIN.w 2 * LUMA.h 5 + -", sizes))
	}

	@Test
	fun `empty expression yields zero`() {
		assertEquals(0, Anime4kSizeEvaluator.evaluate("", sizes))
	}

	@Test
	fun `operator without operands fails with NoSuchElementException`() {
		assertThrows(NoSuchElementException::class.java) {
			Anime4kSizeEvaluator.evaluate("+", sizes)
		}
	}
}
