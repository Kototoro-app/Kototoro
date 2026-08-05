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

	@Test
	fun `chapters are separated by volume within the same branch`() {
		val chapters = listOf(
			chapter(10, "First", volume = 1, branch = "Original"),
			chapter(20, "Second", volume = 1, branch = "Original"),
			chapter(30, "Third", volume = 2, branch = "Original"),
		)

		val headers = buildChapterItems(chapters, reversed = false, query = "")
			.filterIsInstance<NovelChapterListItem.Header>()
			.map(NovelChapterListItem.Header::title)

		assertEquals(listOf("Original", "Volume 1", "Volume 2"), headers)
	}

	@Test
	fun `reversing also reverses volume sections`() {
		val chapters = listOf(
			chapter(10, "First", volume = 1),
			chapter(20, "Second", volume = 2),
		)

		val headers = buildChapterItems(chapters, reversed = true, query = "")
			.filterIsInstance<NovelChapterListItem.Header>()
			.map(NovelChapterListItem.Header::title)

		assertEquals(listOf("Volume 2", "Volume 1"), headers)
	}

	private fun chapter(
		id: Long,
		title: String,
		volume: Int = 0,
		branch: String? = null,
	) = ContentChapter(
		id = id,
		title = title,
		volume = volume,
		number = 0f,
		url = "chapter/$id",
		scanlator = null,
		uploadDate = 0,
		branch = branch,
		source = UnknownContentSource,
	)
}
