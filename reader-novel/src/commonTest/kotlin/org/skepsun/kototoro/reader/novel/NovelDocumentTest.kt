package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NovelDocumentTest {

	@Test
	fun formatsEverySourceLineAsAnIndentedParagraph() {
		assertEquals(
			"　　First paragraph\n　　Second paragraph",
			formatNovelParagraphText(
				text = "First paragraph\nSecond paragraph",
				indentEnabled = true,
				spacingLines = 0,
			),
		)
	}

	@Test
	fun keepsChapterTitlesFlushWhileIndentingBodyParagraphs() {
		assertEquals(
			"第一章 开始\n　　正文",
			formatNovelParagraphText(
				text = "第一章 开始\n正文",
				indentEnabled = true,
				spacingLines = 0,
			),
		)
	}

	@Test
	fun insertsTheSelectedNumberOfBlankParagraphLines() {
		assertEquals(
			"First\n\n\nSecond",
			formatNovelParagraphText(
				text = "First\nSecond",
				indentEnabled = false,
				spacingLines = 2,
			),
		)
	}

	@Test
	fun keepsBlockImagesAsIndependentDeclarativeBlocks() {
		val document = buildNovelDocument("Opening\n\n📷 [图片: https://example.com/cover.jpg]\n\nEnding")

		assertEquals(3, document.size)
		assertEquals("Opening", assertIs<NovelDocumentBlock.Text>(document[0]).original)
		assertEquals("https://example.com/cover.jpg", assertIs<NovelDocumentBlock.Image>(document[1]).path)
		assertEquals("Ending", assertIs<NovelDocumentBlock.Text>(document[2]).original)
	}

	@Test
	fun associatesIncrementalTranslationsWithoutChangingSourceText() {
		val source = "Original paragraph"
		val translation = NovelChapterTranslation(
			chapterIndex = 0,
			paragraphs = listOf(NovelParagraph(0, NovelParagraphType.TEXT, source)),
			translations = mapOf(0 to "Translated paragraph"),
			displayMode = NovelTranslationDisplayMode.BILINGUAL,
		)

		val block = assertIs<NovelDocumentBlock.Text>(buildNovelDocument(source, translation).single())

		assertEquals(source, block.original)
		assertEquals("Translated paragraph", block.translation)
		assertEquals(NovelTranslationDisplayMode.BILINGUAL, block.displayMode)
	}

	@Test
	fun keepsRepeatedParagraphTranslationsIndependent() {
		val source = "Repeated paragraph\n\nRepeated paragraph"
		val translation = NovelChapterTranslation(
			chapterIndex = 0,
			paragraphs = NovelParagraphSplitter.split(source),
			translations = mapOf(0 to "Machine translation", 1 to "Edited translation"),
			displayMode = NovelTranslationDisplayMode.BILINGUAL,
		)

		val blocks = buildNovelDocument(source, translation).filterIsInstance<NovelDocumentBlock.Text>()
		assertEquals(listOf("Machine translation", "Edited translation"), blocks.map { it.translation })
	}

	@Test
	fun keepsInlineImagePathsWithTheirParagraphTokens() {
		val block = assertIs<NovelDocumentBlock.Text>(
			buildNovelDocument("Before <img src=\"https://example.com/inline.jpg\"> after").single(),
		)

		assertEquals("https://example.com/inline.jpg", block.inlineImages["[INLINE_IMAGE_0]"])
	}

	@Test
	fun repeatedBlockImageUrlsKeepDistinctSourceIdentities() {
		val url = "https://example.com/repeated.jpg"
		val document = buildNovelDocument("📷 [图片: $url]\n\n📷 [图片: $url]")
		val images = document.filterIsInstance<NovelDocumentBlock.Image>()

		assertEquals(listOf("image-0", "image-1"), images.map(NovelDocumentBlock.Image::key))
		assertEquals(listOf(url, url), images.map(NovelDocumentBlock.Image::path))
	}

	@Test
	fun sourceRangesPointBackIntoTheParsedText() {
		val document = buildNovelDocument("First\n\nSecond")

		val blocks = document.map { assertIs<NovelDocumentBlock.Text>(it) }
		assertEquals(listOf("First", "Second"), blocks.map { it.original })
		assertEquals(listOf(0 until 5, 7 until 13), blocks.map { it.sourceRange })
	}
}
