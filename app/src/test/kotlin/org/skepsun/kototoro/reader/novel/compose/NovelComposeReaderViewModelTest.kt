package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelParagraph
import org.skepsun.kototoro.reader.novel.NovelParagraphType
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode

class NovelComposeReaderViewModelTest {

	@Test
	fun `publishing chapter resets stale image context and keeps current position`() {
		val viewModel = NovelComposeReaderViewModel()
		val position = NovelReadingPosition(7L, 2, 8, 0.25f)
		viewModel.publishPosition(position)
		viewModel.publishImageContext(
			NovelComposeImageContext(
				epubFilePath = "/books/old.epub",
				chapterPath = "Text/old.xhtml",
			),
		)

		viewModel.publishChapter(3, "Chapter 4", "Content", NovelReaderSettings(), null)

		val state = viewModel.uiState.value
		assertEquals(3, state.chapterIndex)
		assertEquals("Chapter 4", state.chapterTitle)
		assertEquals("Content", state.content)
		assertEquals(position, state.position)
		assertEquals(NovelComposeImageContext(), state.imageContext)
	}

	@Test
	fun `translation and image context updates preserve chapter state`() {
		val viewModel = NovelComposeReaderViewModel()
		val settings = NovelReaderSettings()
		viewModel.publishChapter(1, "Chapter 2", "Original", settings, null)
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
}
