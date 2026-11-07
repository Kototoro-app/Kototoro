package org.skepsun.kototoro.reader.novel

import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.local.epub.EpubFileManager
import org.skepsun.kototoro.local.epub.buildEpubChapterUrl
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubInternalChapterLoaderTest {

	@TempDir
	lateinit var tempDir: File

	@BeforeEach
	fun setUp() {
		mockkStatic(Uri::class)
		every { Uri.parse(any()) } answers {
			val raw = firstArg<String>()
			val parsed = runCatching { URI(raw) }.getOrNull()
			mockk<Uri>().also { uri ->
				every { uri.toString() } returns raw
				every { uri.scheme } returns (parsed?.scheme ?: raw.substringBefore(':', "").ifEmpty { null })
				every { uri.path } returns parsed?.path
				every { uri.fragment } returns (parsed?.fragment ?: raw.substringAfter('#', "").ifEmpty { null })
			}
		}
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(Uri::class)
	}

	@Test
	fun `persisted mapping index wins over remote internal chapter URL`() {
		val remoteUrl = "https://n.novelia.cc/api/wenku/book/file/volume.epub?model=fixture#chapter/2"

		assertEquals(7, resolveEpubChapterIndex(remoteUrl, mappedChapterIndex = 7))
	}

	@Test
	fun `chapter URL index is used when no persisted mapping exists`() {
		val localUrl = "file:///data/epub/volume.epub#chapter/2"

		assertEquals(2, resolveEpubChapterIndex(localUrl, mappedChapterIndex = null))
	}

	@Test
	fun `loads internal chapter from EPUB paths with spaces and special characters`() = runBlocking {
		val folder = File(tempDir, "Series Name (Book 1) #1").apply { mkdirs() }
		val epub = createSampleEpub(File(folder, "Series Name (Book 1) #1 100%.epub"))
		val mappingDao = mockk<EpubChapterMappingDao>()
		coEvery { mappingDao.getById(any()) } returns null
		val loader = EpubInternalChapterLoader(
			context = mockk<Context>(relaxed = true),
			epubFileManager = mockk<EpubFileManager>(relaxed = true),
			epubChapterMappingDao = mappingDao,
		)

		val encodedChapter = ContentChapter(
			id = 1L,
			title = "Chapter 1",
			number = 1f,
			volume = 0,
			url = buildEpubChapterUrl(epub.absolutePath, 0),
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = LocalNovelSource,
		)
		val legacyChapter = encodedChapter.copy(
			id = 2L,
			url = "localepub://${epub.absolutePath}#chapter/0",
		)

		val encodedResult = loader.loadEpubInternalChapter(encodedChapter).getOrThrow()
		val legacyResult = loader.loadEpubInternalChapter(legacyChapter).getOrThrow()

		assertTrue(encodedResult.content.contains("The chapter body text."))
		assertEquals(epub.canonicalFile, encodedResult.epubFile.canonicalFile)
		assertTrue(legacyResult.content.contains("The chapter body text."))
		assertEquals(epub.canonicalFile, legacyResult.epubFile.canonicalFile)
	}

	private fun createSampleEpub(target: File): File {
		val entries = mapOf(
			"mimetype" to "application/epub+zip",
			"META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
				<rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
			</container>""",
			"OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
				<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Book</dc:title>
				<dc:identifier id="id">fixture</dc:identifier><dc:language>en</dc:language></metadata>
				<manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
				<spine><itemref idref="chapter"/></spine>
			</package>""",
			"OEBPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head>
				<body><p>The chapter body text.</p></body></html>""",
		)
		ZipOutputStream(target.outputStream()).use { zip ->
			entries.forEach { (name, text) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(text.toByteArray())
				zip.closeEntry()
			}
		}
		return target
	}
}
