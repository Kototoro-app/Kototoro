package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelParagraph
import org.skepsun.kototoro.reader.novel.NovelParagraphType
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode

class NovelComposeReaderViewModelTest {

	@Test
	fun `publishing the same chapter keeps its position and image context`() {
		val viewModel = NovelComposeReaderViewModel()
		val position = NovelReadingPosition(7L, 2, 8, 0.25f)
		viewModel.publishChapter(
			chapterId = 7L,
			chapterIndex = 3,
			chapterTitle = "Chapter 4",
			content = "Initial",
			settings = NovelReaderSettings(),
			translation = null,
		)
		viewModel.publishPosition(position)
		viewModel.publishImageContext(
			NovelComposeImageContext(
				epubFilePath = "/books/old.epub",
				chapterPath = "Text/old.xhtml",
			),
		)

		viewModel.publishChapter(
			chapterId = 7L,
			chapterIndex = 3,
			chapterTitle = "Chapter 4",
			content = "Content",
			settings = NovelReaderSettings(),
			translation = null,
		)

		val state = viewModel.uiState.value
		assertEquals(3, state.chapterIndex)
		assertEquals("Chapter 4", state.chapterTitle)
		assertEquals("Content", state.content)
		assertEquals(position, state.position)
		assertEquals("/books/old.epub", state.imageContext.epubFilePath)
	}

	@Test
	fun `translation and image context updates preserve chapter state`() {
		val viewModel = NovelComposeReaderViewModel()
		val settings = NovelReaderSettings()
		viewModel.publishChapter(
			chapterId = 1L,
			chapterIndex = 1,
			chapterTitle = "Chapter 2",
			content = "Original",
			settings = settings,
			translation = null,
		)
		val translation = NovelChapterTranslation(
			chapterIndex = 1,
			paragraphs = listOf(NovelParagraph(0, NovelParagraphType.TEXT, "Original")),
			translations = mapOf(0 to "Translated"),
			displayMode = NovelTranslationDisplayMode.BILINGUAL,
		)
		val imageContext = NovelComposeImageContext(
			epubFilePath = "/books/book.epub",
			chapterPath = "Text/chapter.xhtml",
			headers = mapOf("Referer" to "https://example.com/"),
		)

		viewModel.publishTranslation(translation)
		viewModel.publishImageContext(imageContext)

		val state = viewModel.uiState.value
		assertEquals("Original", state.content)
		assertEquals(settings, state.settings)
		assertEquals(translation, state.translation)
		assertEquals(imageContext, state.imageContext)
		assertNull(state.position)
	}

	@Test
	fun scrollAnchorUpdatesWithoutReplacingPersistedReadingPosition() {
		val viewModel = NovelComposeReaderViewModel()
		val readingPosition = NovelReadingPosition(5L, 1, 4, 0.5f)
		val scrollPosition = NovelComposeScrollPosition(8, 24)

		viewModel.publishPosition(readingPosition)
		viewModel.publishScrollPosition(scrollPosition)

		assertEquals(readingPosition, viewModel.uiState.value.position)
		assertEquals(scrollPosition, viewModel.uiState.value.scrollPosition)
	}

	@Test
	fun `paged position updates the unified reader state`() {
		val viewModel = NovelComposeReaderViewModel()
		viewModel.publishChapter(
			chapterId = 42L,
			chapterIndex = 2,
			chapterTitle = "Chapter",
			content = "Content",
			settings = NovelReaderSettings(),
			translation = null,
		)

		viewModel.publishPagedPosition(
			page = 3,
			pageCount = 10,
			charStart = 120,
			charEnd = 180,
			text = "Current page",
		)

		val state = viewModel.uiState.value
		assertEquals(NovelReadingPosition(42L, 3, 10, 3f / 9f), state.position)
		assertEquals("Current page", state.currentPageText)
		assertEquals(120, state.currentPageStart)
		assertEquals(180, state.currentPageEnd)
		assertEquals("4 / 10", state.progressLabel)
	}

	@Test
	fun `paged position resets bookmark state until the current page observation updates it`() {
		val viewModel = NovelComposeReaderViewModel()
		viewModel.publishCurrentPageBookmarked(true)
		assertTrue(viewModel.uiState.value.isCurrentPageBookmarked)

		viewModel.publishPagedPosition(2, 5, 20, 40, "Page")
		assertFalse(viewModel.uiState.value.isCurrentPageBookmarked)

		viewModel.publishCurrentPageBookmarked(true)
		assertTrue(viewModel.uiState.value.isCurrentPageBookmarked)
	}

	@Test
	fun `paged chapter publication keeps adjacent chapters in the reading window`() {
		val viewModel = NovelComposeReaderViewModel()
		val settings = NovelReaderSettings(readingMode = org.skepsun.kototoro.reader.novel.ReadingMode.PAGED)
		viewModel.publishChapter(
			chapterId = 1L,
			chapterIndex = 0,
			chapterTitle = "Chapter 1",
			content = "First",
			settings = settings,
			translation = null,
		)
		viewModel.publishAdjacentChapter(
			NovelComposeChapterContent(
				chapterId = 2L,
				chapterIndex = 1,
				chapterTitle = "Chapter 2",
				content = "Second",
				translation = null,
			),
		)

		assertEquals(listOf(0, 1), viewModel.uiState.value.continuousChapters.map { it.chapterIndex })
	}

	@Test
	fun `focusing a paged chapter trims distant chapters from the pagination window`() {
		val viewModel = NovelComposeReaderViewModel()
		val settings = NovelReaderSettings(readingMode = org.skepsun.kototoro.reader.novel.ReadingMode.PAGED)
		viewModel.publishChapter(1L, 0, "Chapter 1", "First", settings, null)
		(1..4).forEach { index ->
			viewModel.publishAdjacentChapter(
				NovelComposeChapterContent(
					chapterId = index.toLong() + 1,
					chapterIndex = index,
					chapterTitle = "Chapter ${index + 1}",
					content = "Content $index",
					translation = null,
				),
			)
		}

		viewModel.focusContinuousChapter(3)

		assertEquals(listOf(2, 3, 4), viewModel.uiState.value.continuousChapters.map { it.chapterIndex })
	}

	@Test
	fun `page request is bound to the chapter visible when it was created`() {
		val viewModel = NovelComposeReaderViewModel()
		viewModel.publishChapter(
			chapterId = 42L,
			chapterIndex = 7,
			chapterTitle = "Chapter 8",
			content = "Content",
			settings = NovelReaderSettings(),
			translation = null,
		)

		viewModel.requestPage(3)

		val request = viewModel.uiState.value.pageRequest
		assertEquals(42L, request?.chapterId)
		assertEquals(7, request?.chapterIndex)
		assertEquals(3, request?.page)
	}

	@Test
	fun `consuming a handled page request does not clear a newer request`() {
		val viewModel = NovelComposeReaderViewModel()
		viewModel.requestPage(2)
		val handledRequestId = viewModel.uiState.value.pageRequest!!.id
		viewModel.requestPage(3)

		viewModel.consumePageRequest(handledRequestId)

		assertEquals(3, viewModel.uiState.value.pageRequest?.page)
	}

	@Test
	fun `handled page request is consumed once`() {
		val viewModel = NovelComposeReaderViewModel()
		viewModel.requestPage(2)
		val handledRequestId = viewModel.uiState.value.pageRequest!!.id

		viewModel.consumePageRequest(handledRequestId)

		assertNull(viewModel.uiState.value.pageRequest)
	}
}
