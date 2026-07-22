package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

class ComposeReaderPageTransformationTest {

	@Test
	fun `cache key distinguishes crop state and split side`() {
		val standard = ComposeReaderPageTransformation(isCropEnabled = false, ReaderPageSplit.NONE)
		val cropped = ComposeReaderPageTransformation(isCropEnabled = true, ReaderPageSplit.NONE)
		val left = ComposeReaderPageTransformation(isCropEnabled = false, ReaderPageSplit.LEFT)
		val right = ComposeReaderPageTransformation(isCropEnabled = false, ReaderPageSplit.RIGHT)

		assertNotEquals(standard.cacheKey, cropped.cacheKey)
		assertNotEquals(left.cacheKey, right.cacheKey)
	}
}
