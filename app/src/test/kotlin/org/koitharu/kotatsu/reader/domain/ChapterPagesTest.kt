package org.skepsun.kototoro.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.random.Random

class ChapterPagesTest {

	@Test
	fun getChaptersSize() {
		val pages = ChapterPages()
		pages.addFirst(1L, List(12) { page(1L) })
		pages.addFirst(2L, List(17) { page(2L) })
		assertEquals(2, pages.chaptersSize)
	}

	@Test
	fun removeFirst() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.removeFirst()
		assertEquals(2, pages.chaptersSize)
		assertEquals(17 + 2, pages.size)
	}

	@Test
	fun removeLast() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.removeLast()
		assertEquals(2, pages.chaptersSize)
		assertEquals(12 + 17, pages.size)
	}

	@Test
	fun clear() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.clear()
		assertEquals(0, pages.chaptersSize)
		assertEquals(0, pages.size)
		assertEquals(0, pages.size(1L))
		assertEquals(0, pages.size(2L))
		assertEquals(0, pages.size(4L))
	}

	@Test
	fun subList() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addFirst(4L, List(2) { page(4L) })
		val subList = pages.subList(2L)
		assertEquals(17, subList.size)
		assertEquals(2L, subList.first().chapterId)
		assertEquals(2L, subList.last().chapterId)
		assertTrue(subList.all { it.chapterId == 2L })
		assertEquals(subList.size, pages.size(2L))
	}

	@Test
	fun readerWindowKeepsCurrentChapterAndBoundedAdjacentPages() {
		val pages = ChapterPages()
		pages.addLast(1L, List(5) { page(1L, it) })
		pages.addLast(2L, List(4) { page(2L, it) })
		pages.addLast(3L, List(6) { page(3L, it) })

		val window = pages.readerWindow(currentChapterId = 2L, adjacentPageCount = 2)

		assertEquals(listOf(3, 4), window.filter { it.chapterId == 1L }.map { it.index })
		assertEquals(listOf(0, 1, 2, 3), window.filter { it.chapterId == 2L }.map { it.index })
		assertEquals(listOf(0, 1), window.filter { it.chapterId == 3L }.map { it.index })
	}

	@Test
	fun readerWindowCanPromoteBackToPreviousChapter() {
		val pages = ChapterPages()
		pages.addLast(1L, List(5) { page(1L, it) })
		pages.addLast(2L, List(4) { page(2L, it) })
		pages.addLast(3L, List(6) { page(3L, it) })

		val window = pages.readerWindow(currentChapterId = 1L, adjacentPageCount = 2)

		assertEquals(listOf(0, 1, 2, 3, 4), window.filter { it.chapterId == 1L }.map { it.index })
		assertEquals(listOf(0, 1), window.filter { it.chapterId == 2L }.map { it.index })
		assertTrue(window.none { it.chapterId == 3L })
	}

	private fun page(chapterId: Long, index: Int = Random.nextInt()) = ReaderPage(
		id = Random.nextLong(),
		url = "http://localhost",
		preview = null,
		headers = null,
		chapterId = chapterId,
		index = index,
		source = TestContentSource,
	)
}
