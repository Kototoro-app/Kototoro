package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NovelPageSegmentsTest {

	private val blocks = buildNovelLineDocument(
		novelHtmlToText("<h1>第一章 开始</h1><p>甲段</p><p>乙段</p><img src=\"x.png\"><p>丙段</p>"),
	)

	@Test
	fun linesAreFormattedLikeTheAndroidReaderAndPicturesStandAlone() {
		val segments = buildNovelPageSegments(blocks, indentEnabled = true, spacingLines = 1)

		assertEquals(3, segments.size)
		assertEquals("第一章 开始\n\n　　甲段\n\n　　乙段", assertIs<NovelPageSegment.Text>(segments[0]).text)
		assertEquals(3, assertIs<NovelPageSegment.Image>(segments[1]).block)
		val last = assertIs<NovelPageSegment.Text>(segments[2])
		assertEquals("　　丙段", last.text)
		assertEquals(4, last.firstBlock)
	}

	@Test
	fun offsetsMapToBlocksAndBack() {
		val first = assertIs<NovelPageSegment.Text>(buildNovelPageSegments(blocks, true, 0).first())
		// "第一章 开始\n　　甲段\n　　乙段"
		assertEquals(0, first.blockAt(0))
		assertEquals(1, first.blockAt(7))
		assertEquals(0, first.offsetInBlock(8))
		assertEquals(1, first.offsetInBlock(10))
		assertEquals(2, first.blockAt(first.text.length))
		assertEquals(first.text.indexOf("乙段") + 1, first.offsetOf(2, 1))
		assertEquals(first.text.length, first.offsetOf(2, 99))
	}

	@Test
	fun documentPositionsFindTheirSegment() {
		val segments = buildNovelPageSegments(blocks, indentEnabled = false, spacingLines = 0)

		assertEquals(NovelSegmentPosition(0, 0), segments.positionOf(0))
		assertEquals(NovelSegmentPosition(1, 0), segments.positionOf(3))
		assertEquals(NovelSegmentPosition(2, 1), segments.positionOf(4, 1))
		assertNull(segments.positionOf(9))
	}

	@Test
	fun translatedBlocksShowTheirTranslationOrBothLines() {
		val only = blocks.withNovelTranslations(mapOf(1 to "Part A"), NovelTranslationDisplayMode.TRANSLATION_ONLY)
		val first = assertIs<NovelPageSegment.Text>(buildNovelPageSegments(only, true, 0).first())
		assertEquals("第一章 开始\n　　Part A\n　　乙段", first.text)
		assertEquals(2, first.blockAt(first.text.indexOf("乙段")))

		val both = blocks.withNovelTranslations(mapOf(1 to "Part A", 2 to " "), NovelTranslationDisplayMode.BILINGUAL)
		val bilingual = assertIs<NovelPageSegment.Text>(buildNovelPageSegments(both, false, 0).first())
		assertEquals("第一章 开始\n甲段\nPart A\n乙段", bilingual.text)
		assertEquals(1, bilingual.blockAt(bilingual.text.indexOf("Part A")))
		// Positions keep the original chapter's offsets.
		assertEquals(blocks[1].sourceRange, both[1].sourceRange)
	}
}
