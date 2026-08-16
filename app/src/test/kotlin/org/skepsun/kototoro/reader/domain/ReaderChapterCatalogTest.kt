package org.skepsun.kototoro.reader.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

class ReaderChapterCatalogTest {

	@Test
	fun `disabled merge keeps adjacent chapters in current branch`() {
		val englishOne = chapter(id = 1L, number = 1f, branch = "English", uploadDate = 100L)
		val chineseOne = chapter(id = 2L, number = 1f, branch = "Chinese", uploadDate = 200L)
		val englishTwo = chapter(id = 3L, number = 2f, branch = "English", uploadDate = 100L)
		val chineseTwo = chapter(id = 4L, number = 2f, branch = "Chinese", uploadDate = 200L)
		val details = details(listOf(englishOne, chineseOne, englishTwo, chineseTwo))

		val result = resolveReaderChapterCatalog(
			manga = details,
			currentChapter = englishOne,
			mergeBranches = false,
		)

		assertEquals(listOf(1L, 3L), result.map(ContentChapter::id))
	}

	@Test
	fun `enabled merge combines repeated chapters across branches`() {
		val englishOne = chapter(id = 1L, number = 1f, branch = "English", uploadDate = 100L)
		val chineseOne = chapter(id = 2L, number = 1f, branch = "Chinese", uploadDate = 200L)
		val englishTwo = chapter(id = 3L, number = 2f, branch = "English", uploadDate = 100L)
		val chineseTwo = chapter(id = 4L, number = 2f, branch = "Chinese", uploadDate = 200L)
		val details = details(listOf(englishOne, chineseOne, englishTwo, chineseTwo))

		val result = resolveReaderChapterCatalog(
			manga = details,
			currentChapter = englishOne,
			mergeBranches = true,
		)

		assertEquals(listOf(2L, 4L), result.map(ContentChapter::id))
	}

	private fun details(chapters: List<ContentChapter>) = ContentDetails(
		Content(
			id = 1L,
			title = "Test",
			altTitles = emptySet(),
			url = "/test",
			publicUrl = "https://example.org/test",
			rating = 0f,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			chapters = chapters,
			source = TestContentSource,
		),
	)

	private fun chapter(
		id: Long,
		number: Float,
		branch: String?,
		uploadDate: Long,
	) = ContentChapter(
		id = id,
		title = "Chapter $number",
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = uploadDate,
		branch = branch,
		source = TestContentSource,
	)
}
