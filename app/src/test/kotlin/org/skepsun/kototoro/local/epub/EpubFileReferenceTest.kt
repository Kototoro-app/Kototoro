package org.skepsun.kototoro.local.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EpubFileReferenceTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun `parses every persisted local EPUB chapter format`() {
		val references = listOf(
			"localepub:///storage/emulated/0/Books/old.epub#chapter/3",
			"file:///storage/emulated/0/Books/old.epub#chapter/3",
			"content://com.android.externalstorage.documents/document/primary%3ABooks%2Fold.epub#chapter/3",
		)

		references.forEach { url ->
			val result = requireNotNull(parseEpubChapterReference(url))
			assertEquals(url.substringBefore("#chapter/"), result.fileReference)
			assertEquals(3, result.chapterIndex)
		}
	}

	@Test
	fun `rejects unrelated and invalid chapter URLs`() {
		assertNull(parseEpubChapterReference("https://example.org/book.epub#chapter/1"))
		assertNull(parseEpubChapterReference("file:///books/book.epub#chapter/-1"))
		assertNull(parseEpubChapterReference("file:///books/book.epub#chapter/not-a-number"))
		assertNull(parseEpubChapterReference("file:///books/chapter.html"))
	}

	@Test
	fun `extracts chapter index from remote EPUB URL`() {
		val remoteUrl = "https://n.novelia.cc/api/wenku/book/file/volume.epub?model=fixture#chapter/2"

		assertEquals(2, parseEpubChapterIndex(remoteUrl))
	}

	@Test
	fun `builds internal URL from downloaded file reference`() {
		val remoteUrl = "https://n.novelia.cc/api/wenku/book/file/volume.epub?model=fixture"
		val localPath = "/data/user/0/org.skepsun.kototoro/files/epub/42/chapter_7.epub"

		val internalUrl = buildEpubChapterUrl(localPath, 2)

		assertFalse(internalUrl.startsWith(remoteUrl))
		assertEquals(File(localPath).toURI().toString(), internalUrl.substringBefore("#chapter/"))
		assertEquals(2, parseEpubChapterReference(internalUrl)?.chapterIndex)
	}

	@Test
	fun `an encoded chapter fragment is normalized to the literal form every reader matches`() {
		// Uri.Builder.fragment("chapter/0") encodes the slash; readers match the literal "#chapter/", so an
		// EPUB inside a folder lost its chapter index and opened as "The chapter is missing".
		val encoded = "file:///storage/emulated/0/Novel/Folder%20Book/Folder%20Book.epub#chapter%2F4"
		val normalized = normalizeEpubChapterUrl(encoded)
		assertEquals("file:///storage/emulated/0/Novel/Folder%20Book/Folder%20Book.epub#chapter/4", normalized)
		assertEquals(4, parseEpubChapterReference(normalized)?.chapterIndex)
		assertEquals("file:///a.epub#chapter/1", normalizeEpubChapterUrl("file:///a.epub#chapter%2f1"))
	}

	@Test
	fun `urls without an encoded chapter fragment are left untouched`() {
		listOf(
			"file:///a.epub#chapter/1",
			"file:///Novel/Title#Chapter%201.txt",
			"content://x/document/primary%3ABooks%2Fchapter%2Fa.epub",
		).forEach { assertEquals(it, normalizeEpubChapterUrl(it)) }
	}

	@Test
	fun `encodes special characters in local EPUB file paths and resolves back to file`() {
		val dir = File(tempDir, "Series Name (Book 1) #1").apply { mkdirs() }
		val epub = File(dir, "Series Name: Book 1 (Special) #1 100%.epub").apply { writeText("epub") }

		val chapterUrl = buildEpubChapterUrl(epub.absolutePath, 11)
		val reference = requireNotNull(parseEpubChapterReference(chapterUrl))

		assertEquals(11, reference.chapterIndex)
		assertEquals(epub.canonicalFile, resolveLocalEpubFile(reference.fileReference)?.canonicalFile)
	}

	@Test
	fun `resolves legacy unencoded localepub and file references with special characters`() {
		val dir = File(tempDir, "Legacy Folder (Book 1)").apply { mkdirs() }
		val epubWithHash = File(dir, "Volume #1 (Revised) 50%.epub").apply { writeText("epub") }
		val epubWithLiteralPercent = File(dir, "Volume%20Literal.epub").apply { writeText("epub") }

		val legacyLocalEpubUrl = "localepub://${epubWithHash.absolutePath}#chapter/3"
		val legacyFileUrl = "file://${epubWithHash.absolutePath}#chapter/3"
		val legacyPercentUrl = "localepub://${epubWithLiteralPercent.absolutePath}#chapter/0"

		val localEpubRef = requireNotNull(parseEpubChapterReference(legacyLocalEpubUrl)).fileReference
		val fileRef = requireNotNull(parseEpubChapterReference(legacyFileUrl)).fileReference
		val percentRef = requireNotNull(parseEpubChapterReference(legacyPercentUrl)).fileReference

		assertEquals(epubWithHash.canonicalFile, resolveLocalEpubFile(localEpubRef)?.canonicalFile)
		assertEquals(epubWithHash.canonicalFile, resolveLocalEpubFile(fileRef)?.canonicalFile)
		assertEquals(epubWithLiteralPercent.canonicalFile, resolveLocalEpubFile(percentRef)?.canonicalFile)
	}
}
