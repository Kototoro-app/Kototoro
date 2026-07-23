package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelComposeChapterWindowTest {

	@Test
	fun `continuous window appends and prepends adjacent chapters`() {
		val middle = chapter(4)

		val appended = mergeContinuousChapterWindow(listOf(middle), chapter(5), continuous = true)
		val prepended = mergeContinuousChapterWindow(appended, chapter(3), continuous = true)

		assertEquals(listOf(3, 4, 5), prepended.map { it.chapterIndex })
	}

	@Test
	fun `continuous window replaces existing chapter without changing order`() {
		val existing = listOf(chapter(3), chapter(4), chapter(5))
		val updated = chapter(4).copy(content = "updated")

		val result = mergeContinuousChapterWindow(existing, updated, continuous = true)

		assertEquals(listOf(3, 4, 5), result.map { it.chapterIndex })
		assertEquals("updated", result[1].content)
	}

	@Test
	fun `non adjacent jump starts a new continuous window`() {
		val existing = listOf(chapter(3), chapter(4))

		val result = mergeContinuousChapterWindow(existing, chapter(8), continuous = true)

		assertEquals(listOf(8), result.map { it.chapterIndex })
	}

	@Test
	fun `paged mode keeps only the current chapter`() {
		val result = mergeContinuousChapterWindow(listOf(chapter(3)), chapter(4), continuous = false)

		assertEquals(listOf(4), result.map { it.chapterIndex })
	}

	private fun chapter(index: Int) = NovelComposeChapterContent(
		chapterIndex = index,
		chapterTitle = "Chapter $index",
		content = "Content $index",
		translation = null,
	)
}
