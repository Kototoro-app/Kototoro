package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.zip.ZipOutput
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File
import java.util.zip.ZipFile

class LocalMangaZipOutputTest {

	@TempDir
	lateinit var root: File

	@Test
	fun `deleting chapter through UniFile rewrites archive index`() = runTest {
		val chapters = listOf(chapter(1L), chapter(2L))
		val manga = content(chapters)
		val index = ContentIndex(null).apply {
			setContentInfo(manga)
			chapters.forEachIndexed { position, chapter ->
				addChapter(IndexedValue(position, chapter), null)
			}
		}
		val archive = File(root, "content.cbz")
		val firstPage = "00000000_00010001.jpg"
		val secondPage = "00000000_00020001.jpg"
		ZipOutput(archive).use { output ->
			output.put(LocalContentOutput.ENTRY_NAME_INDEX, index.toString())
			output.put(firstPage, "first")
			output.put(secondPage, "second")
			output.finish()
		}

		LocalContentZipOutput.filterChapters(
			file = checkNotNull(UniFile.fromFile(archive)),
			manga = manga,
			idsToRemove = setOf(chapters.first().id),
			cacheDir = root,
		)

		val updated = ZipFile(archive).use { zip ->
			assertFalse(zip.entries().asSequence().any { it.name == firstPage })
			assertTrue(zip.entries().asSequence().any { it.name == secondPage })
			val entry = checkNotNull(zip.getEntry(LocalContentOutput.ENTRY_NAME_INDEX))
			ContentIndex(zip.getInputStream(entry).bufferedReader().use { it.readText() })
		}
		assertEquals(chapters.map(ContentChapter::id), updated.getContentInfo()?.chapters?.map(ContentChapter::id))
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

	private data object TestSource : ContentSource {
		override val name = "TEST"
		override val locale = "en"
		override val contentType = ContentType.MANGA
	}
}
