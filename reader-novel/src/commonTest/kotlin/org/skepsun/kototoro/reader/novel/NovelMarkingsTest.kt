package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NovelMarkingsTest {

	@Test
	fun theStoredRangeWinsWhileItsTextStillMatches() {
		val text = "甲乙丙。甲乙丙。"
		assertEquals(4 until 7, relocateNovelMarking(text, "甲乙丙", 4, 7))
	}

	@Test
	fun aHighlightFollowsItsWordsWhenTheTextMoves() {
		// A replacement rule inserted text before the highlight; the nearest occurrence is the moved one.
		val text = "前言已插入。正文开始，重要的一句话，结束。重要的一句话"
		assertEquals(text.indexOf("重要的一句话") until text.indexOf("重要的一句话") + 6,
			relocateNovelMarking(text, "重要的一句话", 5, 11))
	}

	@Test
	fun whitespaceDifferencesStillFindTheWordsAndMissingTextIsNull() {
		assertEquals(2 until 6, relocateNovelMarking("甲\n乙  丙\n丁", "乙 丙", 40, 44))
		assertNull(relocateNovelMarking("只有正文", "不存在", 0, 3))
	}

	@Test
	fun blocksRenderTheirIntersection() {
		assertEquals(0 until 20, resolveNovelMarkingLocalRange(100..149, 80, 120, 50))
		assertNull(resolveNovelMarkingLocalRange(100..149, 150, 180, 50))
	}
}
