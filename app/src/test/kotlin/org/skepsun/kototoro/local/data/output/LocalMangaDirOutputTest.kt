package org.skepsun.kototoro.local.data.output

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File

class LocalMangaDirOutputTest {

	@TempDir
	lateinit var root: File

	@Test
	fun `deleting one chapter preserves other downloaded chapters`() = runTest {
		val chapters = listOf(chapter(1L), chapter(2L))
		val manga = content(chapters)
		val firstFile = File(root, "chapter_1.cbz").apply { writeText("first") }
		val secondFile = File(root, "chapter_2.cbz").apply { writeText("second") }
		val index = ContentIndex(null).apply {
			setContentInfo(manga)
			addChapter(chapters[0].withIndex(0), firstFile.name)
			addChapter(chapters[1].withIndex(1), secondFile.name)
		}
		File(root, LocalContentOutput.ENTRY_NAME_INDEX).writeText(index.toString())

		LocalContentDirOutput(root, manga).use { output ->
			output.deleteChapters(setOf(chapters[0].id))
			output.finish()
		}

		assertFalse(firstFile.exists())
		assertTrue(secondFile.isFile)
		val updatedIndex = ContentIndex(File(root, LocalContentOutput.ENTRY_NAME_INDEX).readText())
		assertTrue(updatedIndex.getChapterFileName(chapters[0].id) == null)
		assertTrue(updatedIndex.getChapterFileName(chapters[1].id) == secondFile.name)
	}

	private fun chapter(id: Long) = ContentChapter(
		id = id,
		title = "Chapter $id",
		number = id.toFloat(),
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = TestSource,
	)

	private fun content(chapters: List<ContentChapter>) = Content(
		id = 42L,
		title = "Test manga",
		altTitles = emptySet(),
		url = "/manga/42",
		publicUrl = "https://example.com/manga/42",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		chapters = chapters,
		source = TestSource,
	)

	private fun <T> T.withIndex(index: Int) = IndexedValue(index, this)

	private data object TestSource : ContentSource {
		override val name = "TEST"
		override val locale = "en"
		override val contentType = ContentType.MANGA
	}
}
