package org.skepsun.kototoro.details.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter

class ProgressUpdateUseCaseTest {

	@Test
	fun `chapter progress only counts chapters in the current branch`() {
		val progress = calculateGroupedChapterProgress(
			chapters = listOf(
				chapter(1, "English"),
				chapter(2, "Spanish"),
				chapter(3, "English"),
				chapter(4, "Spanish"),
			),
			chapterId = 3,
			chapterPercent = 0.5f,
		)

		assertEquals(0.75f, requireNotNull(progress).percent, 0.000001f)
		assertEquals(2, progress.chaptersCount)
	}

	@Test
	fun `chapter progress treats null branch as its own group`() {
		val progress = calculateGroupedChapterProgress(
			chapters = listOf(
				chapter(1, null),
				chapter(2, "English"),
				chapter(3, null),
			),
			chapterId = 3,
			chapterPercent = 0f,
		)

		assertEquals(0.5f, requireNotNull(progress).percent, 0.000001f)
		assertEquals(2, progress.chaptersCount)
	}

	@Test
	fun `chapter progress rejects a chapter outside the supplied catalogue`() {
		assertNull(calculateGroupedChapterProgress(listOf(chapter(1, "English")), 2, 0.5f))
	}

	private fun chapter(id: Long, branch: String?) = ContentChapter(
		id = id,
		title = "Chapter $id",
		number = id.toFloat(),
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = TestContentSource,
	)
}
