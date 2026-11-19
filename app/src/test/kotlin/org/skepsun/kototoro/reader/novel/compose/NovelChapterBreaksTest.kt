package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelChapterBreaksTest {

	private val config = NovelChapterBreakConfig(
		chapterCount = 4,
		titleOf = { "Title $it" },
		idOf = { 100L + it },
	)

	private fun text(chapterIndex: Int, n: Int = 1) = List(n) {
		NovelComposePage.Text(AnnotatedString("t"), 100L + chapterIndex, chapterIndex, it, it)
	}

	private fun List<NovelComposePage>.shape() = map {
		when (it) {
			is NovelComposePage.Break -> "B${it.finishedChapterIndex}${if (it.spread == BreakSpread.NONE) "" else it.spread.name[0]}"
			else -> "T${it.chapterIndex}"
		}
	}

	@Test
	fun `null config leaves pages untouched`() {
		val pages = text(1, 2)
		assertEquals(pages, insertNovelChapterBreaks(pages, null, dualPage = false))
	}

	@Test
	fun `unloaded neighbours still get a page on both edges`() {
		val result = insertNovelChapterBreaks(text(1, 2), config, dualPage = false)

		assertEquals(listOf("B0", "T1", "T1", "B1"), result.shape())
		val leading = result.first() as NovelComposePage.Break
		assertEquals("Title 0", leading.finishedTitle)
		assertEquals("Title 1", leading.nextTitle)
		assertEquals(100L, leading.finishedChapterId)
		assertEquals("Title 2", (result.last() as NovelComposePage.Break).nextTitle)
	}

	@Test
	fun `first chapter has no leading page`() {
		val result = insertNovelChapterBreaks(text(0, 2), config, dualPage = false)

		assertEquals(listOf("T0", "T0", "B0"), result.shape())
	}

	@Test
	fun `last chapter ends with the end of book page`() {
		val result = insertNovelChapterBreaks(text(3), config, dualPage = false)

		assertEquals(listOf("B2", "T3", "B3"), result.shape())
		assertNull((result.last() as NovelComposePage.Break).nextTitle)
	}

	@Test
	fun `loaded neighbour turns the edge page into the middle page with the same key`() {
		val edge = insertNovelChapterBreaks(text(1), config, dualPage = false)
		val stitched = insertNovelChapterBreaks(text(0) + text(1), config, dualPage = false)

		assertEquals(listOf("B0", "T1", "B1"), edge.shape())
		assertEquals(listOf("T0", "B0", "T1", "B1"), stitched.shape())
		assertEquals(edge.first(), stitched[1])
	}

	@Test
	fun `loaded titles win over the book's`() {
		val result = insertNovelChapterBreaks(text(1), config, dualPage = false) { if (it == 1) "Loaded 1" else null }

		assertEquals("Loaded 1", (result.first() as NovelComposePage.Break).nextTitle)
		assertEquals("Loaded 1", (result.last() as NovelComposePage.Break).finishedTitle)
	}

	@Test
	fun `dual page cards start on even positions`() {
		val even = insertNovelChapterBreaks(text(1, 2), config, dualPage = true)
		val odd = insertNovelChapterBreaks(text(1, 3), config, dualPage = true)

		assertEquals(listOf("B0L", "B0R", "T1", "T1", "B1L", "B1R"), even.shape())
		assertEquals(listOf("B0L", "B0R", "T1", "T1", "T1", "B1P", "B1L", "B1R"), odd.shape())
		listOf(even, odd).forEach { pages ->
			pages.forEachIndexed { index, page ->
				if ((page as? NovelComposePage.Break)?.spread == BreakSpread.LEFT) assertEquals(0, index % 2)
			}
		}
	}

	@Test
	fun `no page request resolves to a break page`() {
		val result = insertNovelChapterBreaks(text(1, 2), config, dualPage = false)
		val target = resolveNovelPageRequest(
			request = NovelPageRequest(1L, 101L, 1, 0),
			pages = result.map { NovelPageIdentity(it.chapterId, it.chapterIndex) },
		)

		assertEquals(1, target)
	}
}
