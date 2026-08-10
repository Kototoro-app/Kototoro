package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelParagraph
import org.skepsun.kototoro.reader.novel.NovelParagraphType
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode

class NovelComposeDocumentTest {

	@Test
	fun `formats every source line as an indented paragraph`() {
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
	fun `keeps chapter titles flush while indenting body paragraphs`() {
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
	fun `inserts the selected number of blank paragraph lines`() {
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
	fun `keeps block images as independent declarative blocks`() {
		val document = buildNovelComposeDocument("Opening\n\n📷 [图片: https://example.com/cover.jpg]\n\nEnding")

		assertEquals(3, document.size)
		assertEquals("Opening", assertInstanceOf(NovelComposeBlock.Text::class.java, document[0]).original)
		assertEquals("https://example.com/cover.jpg", assertInstanceOf(NovelComposeBlock.Image::class.java, document[1]).path)
		assertEquals("Ending", assertInstanceOf(NovelComposeBlock.Text::class.java, document[2]).original)
	}

	@Test
	fun `associates incremental translations without changing source text`() {
		val source = "Original paragraph"
		val translation = NovelChapterTranslation(
			chapterIndex = 0,
			paragraphs = listOf(NovelParagraph(0, NovelParagraphType.TEXT, source)),
			translations = mapOf(0 to "Translated paragraph"),
			displayMode = NovelTranslationDisplayMode.BILINGUAL,
		)

		val block = assertInstanceOf(
			NovelComposeBlock.Text::class.java,
			buildNovelComposeDocument(source, translation).single(),
		)

		assertEquals(source, block.original)
		assertEquals("Translated paragraph", block.translation)
		assertEquals(NovelTranslationDisplayMode.BILINGUAL, block.displayMode)
	}

	@Test
	fun `keeps inline image paths with their paragraph tokens`() {
		val block = assertInstanceOf(
			NovelComposeBlock.Text::class.java,
			buildNovelComposeDocument("Before <img src=\"https://example.com/inline.jpg\"> after").single(),
		)

		assertEquals("https://example.com/inline.jpg", block.inlineImages["[INLINE_IMAGE_0]"])
	}

	@Test
	fun `repeated block image urls keep distinct source identities`() {
		val url = "https://example.com/repeated.jpg"
		val document = buildNovelComposeDocument("📷 [图片: $url]\n\n📷 [图片: $url]")
		val images = document.filterIsInstance<NovelComposeBlock.Image>()

		assertEquals(listOf("image-0", "image-1"), images.map(NovelComposeBlock.Image::key))
		assertEquals(listOf(url, url), images.map(NovelComposeBlock.Image::path))
	}
}
