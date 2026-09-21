package org.skepsun.kototoro.local.data.input

import okio.Path
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which files a chapter's pages come from.
 *
 * The regression these cover: the local scan writes the downloader's naming pattern into the
 * content index (`%08d_%04d\d{4}`), but imported or hand-copied folders keep their own file names,
 * so the pattern matched nothing, the chapter resolved to zero pages and the reader opened blank.
 */
class ChapterEntriesSelectionTest {

	private val root: Path = "/manga/importtest".toPath()
	private val downloadPattern = Regex("00000000_0001\\d{4}")

	private fun entry(relative: String): Path = root / relative

	private fun select(entries: List<Path>, pattern: Regex?) =
		selectChapterEntries(entries = entries.asSequence(), rootPath = root, pattern = pattern) { path ->
			path.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
		}

	@Test
	fun `uses the index mapping when it matches the downloaded files`() {
		val entries = listOf(entry("00000000_00010001.jpg"), entry("00000000_00010002.jpg"), entry("notes.txt"))

		val selected = select(entries, downloadPattern)

		assertEquals(listOf(entry("00000000_00010001.jpg"), entry("00000000_00010002.jpg")), selected)
	}

	@Test
	fun `falls back to the chapter directory when the index pattern matches nothing`() {
		val entries = listOf(entry("01.jpg"), entry("02.jpg"), entry("03.png"), entry("readme.txt"))

		val selected = select(entries, downloadPattern)

		assertEquals(listOf(entry("01.jpg"), entry("02.jpg"), entry("03.png")), selected)
	}

	@Test
	fun `uses the chapter directory when the index has no mapping for the chapter`() {
		val entries = listOf(entry("page_1.jpg"), entry("page_2.jpg"))

		val selected = select(entries, pattern = null)

		assertEquals(listOf(entry("page_1.jpg"), entry("page_2.jpg")), selected)
	}

	@Test
	fun `does not pull pages from nested directories when falling back`() {
		val entries = listOf(entry("01.jpg"), entry("chapter2/01.jpg"), entry("chapter2/02.jpg"))

		val selected = select(entries, downloadPattern)

		assertEquals(listOf(entry("01.jpg")), selected)
	}

	@Test
	fun `an empty chapter stays empty`() {
		val selected = select(entries = emptyList(), pattern = downloadPattern)

		assertEquals(emptyList<Path>(), selected)
	}
}
