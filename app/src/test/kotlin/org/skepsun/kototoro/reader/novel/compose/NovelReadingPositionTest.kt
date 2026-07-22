package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelReadingPositionTest {

	@Test
	fun `converts normalized progress to persisted reader state`() {
		val state = NovelReadingPosition(
			chapterId = 42L,
			page = 3,
			pageCount = 10,
			chapterProgress = 0.625f,
		).toReaderState()

		assertEquals(42L, state.chapterId)
		assertEquals(3, state.page)
		assertEquals(6_250, state.scroll)
	}

	@Test
	fun `clamps out of range progress before persistence`() {
		assertEquals(10_000, NovelReadingPosition(1L, 0, 1, 3f).toReaderState().scroll)
	}
}
