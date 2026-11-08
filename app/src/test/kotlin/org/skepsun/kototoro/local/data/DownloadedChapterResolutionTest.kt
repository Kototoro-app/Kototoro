package org.skepsun.kototoro.local.data

import android.net.Uri
import com.hippo.unifile.FakeSafDirUniFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.looksLikeLocalNovelContent
import org.skepsun.kototoro.core.model.looksLikeNovelUrl
import org.skepsun.kototoro.local.data.output.LocalContentDirOutput
import org.skepsun.kototoro.local.data.output.createPreservingArchiveExtension
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadedChapterResolutionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `extensionless file with PK zip header is recognized as zip archive`() {
        val extensionlessCbz = File(tempDir, "0_Chapter_14")
        ZipOutputStream(extensionlessCbz.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("00001.jpg"))
            zos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            zos.closeEntry()
        }
        assertTrue(extensionlessCbz.hasZipSignature())
        assertTrue(extensionlessCbz.isZipArchive)

        val htmlErrorPage = File(tempDir, "1_Chapter_15").apply {
            writeText("<html><body>403 Forbidden</body></html>")
        }
        assertFalse(htmlErrorPage.hasZipSignature())
        assertFalse(htmlErrorPage.isZipArchive)

        val pdfWithoutZipHeader = File(tempDir, "2_Chapter_16").apply {
            writeBytes("%PDF-1.7\n".toByteArray())
        }
        assertFalse(pdfWithoutZipHeader.hasZipSignature())
        assertFalse(pdfWithoutZipHeader.isZipArchive)

        val truncatedFile = File(tempDir, "3_Chapter_17").apply {
            writeBytes(byteArrayOf(0x50, 0x4B))
        }
        assertFalse(truncatedFile.hasZipSignature())
        assertFalse(truncatedFile.isZipArchive)
    }

    @Test
    fun `createPreservingArchiveExtension preserves cbz suffix on content scheme`() {
        val safDir = FakeSafDirUniFile(
            treeUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga",
            ),
            backingDir = tempDir,
            stripCbzOnCreateFile = true,
        )
        var capturedMime: String? = null
        var capturedName: String? = null
        val created = safDir.createPreservingArchiveExtension("14_Chapter_14.cbz") { _, mime, name ->
            capturedMime = mime
            capturedName = name
            safDir.registerCreatedChild(name)
        }
        assertNotNull(created)
        assertEquals("14_Chapter_14.cbz", created!!.name)
        assertEquals("application/octet-stream", capturedMime)
        assertEquals("14_Chapter_14.cbz", capturedName)
    }

    @Test
    fun `LocalContentDirOutput syncs actual provider file name and stores page ebook chapters`() = runBlocking {
        val backingDir = File(tempDir, "manga_dir").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val strippingSafRoot = FakeSafDirUniFile(
            treeUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga",
            ),
            backingDir = backingDir,
            stripCbzOnCreateFile = true,
        )
        val manga = sampleContent(
            id = 42L,
            title = "Sample Manga",
            source = ContentSource("TEST_MANGA"),
        )
        val chapter14 = ContentChapter(
            id = 1400L,
            title = "Chapter 14",
            number = 14f,
            volume = 0,
            url = "https://example.org/ch/14",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = ContentSource("TEST_MANGA"),
        )
        val output = LocalContentDirOutput(strippingSafRoot, manga, cacheDir)
        val samplePage = File(tempDir, "page1.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        output.addPage(IndexedValue(0, chapter14), samplePage, 0, null)
        assertTrue(output.flushChapter(chapter14))

        val indexFile = File(backingDir, "index.json")
        assertTrue(indexFile.isFile)
        val reloadedIndex = ContentIndex(indexFile.readText())
        assertEquals("0_Chapter_14", reloadedIndex.getChapterFileName(1400L))

        val chapter15 = chapter14.copy(id = 1500L, title = "Chapter 15", number = 15f)
        val pdfSource = File(tempDir, "source.pdf").apply {
            writeBytes("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray())
        }
        output.addPageEbookChapter(IndexedValue(1, chapter15), pdfSource, "pdf")
        val updatedIndex = ContentIndex(indexFile.readText())
        assertEquals("1_Chapter_15.pdf", updatedIndex.getChapterFileName(1500L))
        assertTrue(File(backingDir, "1_Chapter_15.pdf").isFile)
    }

    @Test
    fun `LocalPdfPages encodes and parses file and content pdf page URIs with special characters`() {
        val pdfFile = File(tempDir, "Vol #1 [Special] 100%.pdf").apply {
            writeBytes("%PDF-1.5\n".toByteArray())
        }
        assertTrue(pdfFile.isPdfDocument)

        val filePageUri = pdfFile.toPdfPageUri(13)
        assertTrue(filePageUri.isPdfPageUri())
        assertTrue(filePageUri.isFilePdfUri())
        assertEquals(13, filePageUri.pdfPageIndex())
        assertEquals(pdfFile.canonicalPath, File(requireNotNull(filePageUri.path)).canonicalPath)

        val safDocUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ABooks" +
                "/document/primary%3ABooks%2FVol%20%231.pdf",
        )
        val contentPageUri = safDocUri.toContentPdfPageUri(0)
        assertTrue(contentPageUri.isPdfPageUri())
        assertTrue(contentPageUri.isContentPdfUri())
        assertEquals(0, contentPageUri.pdfPageIndex())
        assertEquals(safDocUri.toString(), contentPageUri.toSourceContentPdfUri().toString())
    }

    @Test
    fun `ContentIndex persists and recovers novel content type while preserving remote source name`() {
        val dynamicNovelSource = object : ParserContentSource {
            override val name: String = "JSON_JS_CUSTOM_NOVEL_SITE"
            override val locale: String = "en"
            override val contentType: ContentType = ContentType.NOVEL
        }
        val novelChapter = ContentChapter(
            id = 501L,
            title = "Chapter 1",
            number = 1f,
            volume = 0,
            url = "https://novel.example.org/read/1",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = dynamicNovelSource,
        )
        val novel = sampleContent(
            id = 777L,
            title = "Downloaded Web Novel",
            source = dynamicNovelSource,
            chapters = listOf(novelChapter),
        )
        val index = ContentIndex(null)
        index.setContentInfo(novel)
        index.addChapter(IndexedValue(0, novelChapter), "0_Chapter_1.cbz", null)

        val serialized = index.toString()
        val parsed = checkNotNull(ContentIndex(serialized).getContentInfo())
        assertEquals("JSON_JS_CUSTOM_NOVEL_SITE", parsed.source.name)
        assertEquals(ContentType.NOVEL, parsed.source.getContentType())
    }

    @Test
    fun `legacy novel index without content_type recovers novel type via source prefix or html urls`() {
        val legacyLegadoJson = """
            {
              "id": 888,
              "title": "Legacy Legado Novel",
              "url": "https://legado.example/book/888",
              "public_url": "https://legado.example/book/888",
              "source": "JSON_LEGADO_ABC123",
              "tags": [],
              "chapters": {
                "1": {
                  "number": 1,
                  "volume": 0,
                  "url": "https://legado.example/book/888/1.html",
                  "name": "Ch 1",
                  "file": "0_Ch_1.cbz"
                }
              }
            }
        """.trimIndent()
        val parsedLegado = checkNotNull(ContentIndex(legacyLegadoJson).getContentInfo())
        assertEquals("JSON_LEGADO_ABC123", parsedLegado.source.name)
        assertEquals(ContentType.NOVEL, parsedLegado.source.getContentType())

        val legacyJsEpubNovelJson = """
            {
              "id": 889,
              "title": "Legacy JS EPUB Novel",
              "url": "https://jsnovel.example/book/889",
              "public_url": "https://jsnovel.example/book/889",
              "source": "JSON_JS_OLD_NOVEL",
              "tags": [],
              "chapters": {
                "1": {
                  "number": 1,
                  "volume": 0,
                  "url": "localepub://889/chapter/0",
                  "name": "Ch 1",
                  "file": "book.epub"
                }
              }
            }
        """.trimIndent()
        val parsedJsNovel = checkNotNull(ContentIndex(legacyJsEpubNovelJson).getContentInfo())
        assertEquals("JSON_JS_OLD_NOVEL", parsedJsNovel.source.name)
        assertEquals(ContentType.NOVEL, parsedJsNovel.source.getContentType())

        val remoteHtmlMangaJson = """
            {
              "id": 891,
              "title": "Manga With Html Chapter Urls",
              "url": "https://mangasite.example/manga/891.html",
              "public_url": "https://mangasite.example/manga/891.html",
              "source": "JSON_JS_MANGA_SITE",
              "tags": [],
              "chapters": {
                "1": {
                  "number": 1,
                  "volume": 0,
                  "url": "https://mangasite.example/manga/891/chapter-1.html",
                  "name": "Ch 1",
                  "file": "0_Ch_1.cbz"
                }
              }
            }
        """.trimIndent()
        val parsedRemoteHtmlManga = checkNotNull(ContentIndex(remoteHtmlMangaJson).getContentInfo())
        assertFalse(parsedRemoteHtmlManga.looksLikeLocalNovelContent())

        val legacyLocalMangaSourceNovelJson = """
            {
              "id": 890,
              "title": "Imported Local Novel",
              "url": "file:///storage/emulated/0/novels/book",
              "public_url": "",
              "source": "${LocalMangaSource.name}",
              "tags": [],
              "chapters": {
                "1": {
                  "number": 1,
                  "volume": 0,
                  "url": "file:///storage/emulated/0/novels/book/ch1.txt",
                  "name": "Ch 1",
                  "file": "ch1.txt"
                }
              }
            }
        """.trimIndent()
        val parsedLocalNovel = checkNotNull(ContentIndex(legacyLocalMangaSourceNovelJson).getContentInfo())
        assertEquals(LocalNovelSource, parsedLocalNovel.source)
        assertTrue(parsedLocalNovel.looksLikeLocalNovelContent())

        assertTrue("file+zip:///storage/emulated/0/novel/0_Ch_1.cbz#00000000.html".looksLikeNovelUrl())
        assertTrue("content+zip://authority/doc#chapter.xhtml".looksLikeNovelUrl())
        assertFalse("content+zip://authority/doc#00001.jpg".looksLikeNovelUrl())
        assertFalse("file+pdf:///storage/emulated/0/book.pdf#0".looksLikeNovelUrl())
    }

    @Test
    fun `EpubStorageManager findPageEbookFile locates legacy chapter pdf and cbz files`() {
        val rootDir = File(tempDir, "novel_root").apply { mkdirs() }
        val manager = org.skepsun.kototoro.local.epub.EpubStorageManager(
            context = android.content.Context(),
            localStorageManager = LocalStorageManager(rootDir),
        )
        assertNull(manager.findPageEbookFile(101L, 9001L))

        val mangaEpubDir = File(rootDir, "epub/101").apply { mkdirs() }
        File(mangaEpubDir, "chapter_9001.pdf").writeBytes("%PDF-1.7\n".toByteArray())
        File(mangaEpubDir, "chapter_9002.cbz").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        File(mangaEpubDir, "chapter_9003.pdf").createNewFile() // empty 0-byte file

        assertEquals("chapter_9001.pdf", manager.findPageEbookFile(101L, 9001L)?.name)
        assertEquals("chapter_9002.cbz", manager.findPageEbookFile(101L, 9002L)?.name)
        assertNull(manager.findPageEbookFile(101L, 9003L))
    }

    private fun sampleContent(
        id: Long,
        title: String,
        source: ParserContentSource,
        chapters: List<ContentChapter>? = null,
    ) = Content(
        id = id,
        title = title,
        altTitles = emptySet(),
        url = "https://example.org/item/$id",
        publicUrl = "https://example.org/item/$id",
        rating = -1f,
        contentRating = ContentRating.SAFE,
        coverUrl = null,
        tags = emptySet(),
        state = ContentState.ONGOING,
        authors = emptySet(),
        largeCoverUrl = null,
        description = null,
        chapters = chapters,
        source = source,
    )
}
