package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter

class ComposeNovelChaptersSheetTest {

	@Test
	fun `reversing preserves original chapter indices`() {
		val chapters = listOf(chapter(10, "First"), chapter(20, "Second"), chapter(30, "Third"))

		val indices = buildChapterItems(chapters, reversed = true, query = "")
			.filterIsInstance<NovelChapterListItem.Chapter>()
			.map(NovelChapterListItem.Chapter::originalIndex)

		assertEquals(listOf(2, 1, 0), indices)
	}

	@Test
	fun `search matches chapter title`() {
		val chapters = listOf(chapter(10, "Arrival"), chapter(20, "Departure"))

		val result = buildChapterItems(chapters, reversed = false, query = "part")
			.filterIsInstance<NovelChapterListItem.Chapter>()

		assertEquals(listOf(1), result.map(NovelChapterListItem.Chapter::originalIndex))
	}

	private fun chapter(id: Long, title: String) = ContentChapter(
		id = id,
		title = title,
		volume = 0,
		number = 0f,
		url = "chapter/$id",
		scanlator = null,
		uploadDate = 0,
		branch = null,
		source = UnknownContentSource,
	)
}
