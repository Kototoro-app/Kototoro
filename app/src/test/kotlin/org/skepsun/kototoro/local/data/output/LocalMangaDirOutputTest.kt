package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(chapters[0].id))
			output.finish()
		}

		assertFalse(firstFile.exists())
		assertTrue(secondFile.isFile)
		val updatedIndex = ContentIndex(File(root, LocalContentOutput.ENTRY_NAME_INDEX).readText())
		assertTrue(updatedIndex.getChapterFileName(chapters[0].id) == null)
		assertTrue(updatedIndex.getChapterFileName(chapters[1].id) == secondFile.name)
	}

	@Test
	fun `deleting incomplete chapter with remote id url does not require file uri`() = runTest {
		val incompleteChapter = chapter(7009L).copy(url = "7009")
		val manga = content(listOf(incompleteChapter))
		val index = ContentIndex(null).apply {
			setContentInfo(manga)
		}
		File(root, LocalContentOutput.ENTRY_NAME_INDEX).writeText(index.toString())

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(incompleteChapter.id))
			output.finish()
		}

		assertTrue(File(root, LocalContentOutput.ENTRY_NAME_INDEX).isFile)
	}

	@Test
	fun `deleting one epub chapter preserves file until its final chapter is deleted`() = runTest {
		val chapters = listOf(chapter(11L), chapter(12L))
		val manga = content(chapters)
		val epub = File(root, "volume.epub").apply { writeText("epub") }
		writeIndex(manga, chapters.associateWith { epub.name })

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(chapters[0].id))
			output.finish()
		}

		assertTrue(epub.isFile)
		val afterFirstDelete = readIndex()
		assertNull(afterFirstDelete.getChapterFileName(chapters[0].id))
		assertTrue(afterFirstDelete.getChapterFileName(chapters[1].id) == epub.name)

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(chapters[1].id))
			output.finish()
		}

		assertFalse(epub.exists())
		assertNull(readIndex().getChapterFileName(chapters[1].id))
	}

	@Test
	fun `deleting nested chapter removes its directory`() = runTest {
		val selected = chapter(21L)
		val manga = content(listOf(selected))
		val chapterDirectory = File(root, "volume/chapter_1").apply { mkdirs() }
		File(chapterDirectory, "page.jpg").writeText("page")
		writeIndex(manga, mapOf(selected to "volume/chapter_1"))

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(selected.id))
			output.finish()
		}

		assertFalse(chapterDirectory.exists())
	}

	@Test
	fun `deleting root image chapter removes only root images`() = runTest {
		val selected = chapter(31L)
		val manga = content(listOf(selected))
		val firstPage = File(root, "001.jpg").apply { writeText("first") }
		val secondPage = File(root, "002.png").apply { writeText("second") }
		val unrelated = File(root, "notes.txt").apply { writeText("keep") }
		writeIndex(manga, mapOf(selected to ""))
		assertEquals("", readIndex().getChapterFileName(selected.id))

		LocalContentDirOutput(checkNotNull(UniFile.fromFile(root)), manga, root).use { output ->
			output.deleteChapters(setOf(selected.id))
			output.finish()
		}

		assertFalse(firstPage.exists())
		assertFalse(secondPage.exists())
		assertTrue(unrelated.isFile)
	}

	private fun writeIndex(manga: Content, chapters: Map<ContentChapter, String>) {
		val index = ContentIndex(null).apply {
			setContentInfo(manga.copy(chapters = null))
			chapters.entries.forEachIndexed { position, (chapter, fileName) ->
				addChapter(chapter.withIndex(position), fileName)
			}
		}
		File(root, LocalContentOutput.ENTRY_NAME_INDEX).writeText(index.toString())
	}

	private fun readIndex(): ContentIndex =
		ContentIndex(File(root, LocalContentOutput.ENTRY_NAME_INDEX).readText())

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
