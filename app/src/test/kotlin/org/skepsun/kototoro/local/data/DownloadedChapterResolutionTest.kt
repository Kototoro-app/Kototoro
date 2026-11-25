package org.skepsun.kototoro.local.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import androidx.core.net.toUri
import com.hippo.unifile.FakeSafDirUniFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.looksLikeLocalNovelContent
import org.skepsun.kototoro.core.model.looksLikeNovelUrl
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.details.ui.mapChapters
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.output.LocalContentDirOutput
import org.skepsun.kototoro.local.data.output.createPreservingArchiveExtension
import org.skepsun.kototoro.local.epub.EpubStorageManager
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadedChapterResolutionTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uri(firstArg()) }
        every { Uri.fromFile(any()) } answers { uri(firstArg<File>().toURI().toString()) }
        every { Uri.encode(any()) } answers {
            URLEncoder.encode(firstArg<String>(), Charsets.UTF_8.name()).replace("+", "%20")
        }
    }

    @AfterEach
    fun tearDown() {
        LocalPdfPages.clearPdfRenderers()
        unmockkStatic(Uri::class)
    }

    @Test
    fun `file and SAF parsers stop reporting externally deleted CBZs from polluted indexes`() = runBlocking {
        val source = ContentSource("TEST_MANGA")
        val chapters = (1L..3L).map { sampleChapter(it, source) }
        val manga = sampleContent(42L, "Sample Manga", source, chapters)
        val safRoot = FakeSafDirUniFile(Uri.parse("content://books/tree/1"), tempDir)
        val index = ContentIndex(null).apply {
            setContentInfo(manga.copy(chapters = null))
            chapters.forEachIndexed { order, chapter ->
                val name = "${chapter.id}.cbz"
                val child = checkNotNull(safRoot.createFile(name))
                writeArchive(File(tempDir, name), mapOf("001.jpg" to "page"))
                val url = if (chapter.id == 1L) child.uri.toString() else chapter.url
                addChapter(IndexedValue(order, chapter.copy(url = url)), name)
            }
        }
        File(tempDir, "index.json").writeText(index.toString())
        val parsers = listOf(LocalContentParser(tempDir), LocalContentParser(safRoot, tempDir))
        for (parser in parsers) {
            assertEquals(3, parser.getContent(withDetails = true).manga.chapters?.count { it.source.isLocal })
        }

        assertTrue(File(tempDir, "1.cbz").delete())
        assertTrue(File(tempDir, "2.cbz").delete())
        for (parser in parsers) {
            val local = parser.getContent(withDetails = true)
            val remaining = local.manga.chapters.orEmpty()
            assertEquals(listOf(2L, 3L), remaining.map { it.id })
            assertEquals(chapters[1].url, remaining.first().url)
            val details = ContentDetails(manga).withLocalContent(local)
            assertEquals(listOf(false, false, true), details.rows().map { it.isDownloaded })
            assertEquals(listOf(3L), details.rows(downloadedOnly = true).map { it.chapter.id })
            val cleared = details.withLocalContent(null)
            assertEquals(listOf(false, false, false), cleared.rows().map { it.isDownloaded })
            assertTrue(cleared.rows(downloadedOnly = true).isEmpty())
        }
    }

    @Test
    fun `single CBZ needs a matching page entry even when the chapter URL is local`() = runBlocking {
        val source = ContentSource("TEST_MANGA")
        val missing = sampleChapter(1L, source).copy(url = "file+zip:///books/book.cbz")
        val present = sampleChapter(2L, source)
        val manga = sampleContent(42L, "Sample Manga", source)
        val index = ContentIndex(null).apply {
            setContentInfo(manga)
            addChapter(IndexedValue(0, missing), null)
            addChapter(IndexedValue(1, present), null)
        }
        val archive = File(tempDir, "book.cbz")
        writeArchive(
            archive,
            mapOf("index.json" to index.toString(), "00000000_00010000/" to "", "00000000_00020000.jpg" to "page"),
        )
        val local = LocalContentParser(archive).getContent(withDetails = true)
        assertEquals(listOf(2L), local.manga.chapters?.map { it.id })
        assertTrue(local.manga.chapters.orEmpty().single().source.isLocal)
    }

    @Test
    fun `last chapter deletion removes multi CBZ directory and indexed path`() = runBlocking {
        assertLastChapterCleanup(inDirectory = true)
    }

    @Test
    fun `last chapter deletion removes single CBZ and indexed path`() = runBlocking {
        assertLastChapterCleanup(inDirectory = false)
    }

    @Test
    fun `last chapter cleanup retains unrelated files in the directory`() = runBlocking {
        assertLastChapterCleanup(inDirectory = true, retainNotes = true)
    }

    private suspend fun assertLastChapterCleanup(inDirectory: Boolean, retainNotes: Boolean = false) {
        val source = ContentSource("TEST_MANGA")
        val chapter = sampleChapter(1L, source)
        val manga = sampleContent(42L, "Sample Manga", source, listOf(chapter))
        val root = File(tempDir, if (inDirectory) "book" else "book.cbz")
        val index = ContentIndex(null).apply {
            setContentInfo(manga)
            addChapter(IndexedValue(0, chapter), if (inDirectory) "1.cbz" else null)
            setCoverEntry("cover.jpg")
        }
        if (inDirectory) {
            check(root.mkdir())
            writeArchive(File(root, "1.cbz"), mapOf("001.jpg" to "page"))
            File(root, "cover.jpg").writeText("cover")
            File(root, "index.json").writeText(index.toString())
            if (retainNotes) File(root, "notes.txt").writeText("personal notes")
        } else {
            writeArchive(root, mapOf(
                "index.json" to index.toString(),
                "cover.jpg" to "cover",
                "00000000_00010000.jpg" to "page",
            ))
        }
        val storage = checkNotNull(UniFile.fromFile(root))
        val cached = LocalContent(manga, root, storage.uri)
        val localIndex = mockk<LocalContentIndex>()
        coEvery { localIndex.get(manga.id, false) } returns cached
        coEvery { localIndex.delete(manga.id) } returns Unit
        val changes = MutableSharedFlow<LocalContent?>(replay = 1)
        val repository = LocalMangaRepository(mockk(), mockk(), localIndex, changes, mockk(), ContentLock(), mockk())
        val previousApp = App.getInstance()
        object : App() {
            override fun getCacheDir() = tempDir
        }
        mockkStatic(UniFile::class)
        every { UniFile.fromUri(any(), cached.toUri()) } returns storage
        try {
            repository.deleteChapters(manga, setOf(chapter.id))
            if (retainNotes) {
                assertEquals("personal notes", File(root, "notes.txt").readText())
                assertFalse(File(root, "1.cbz").exists())
            } else {
                assertFalse(root.exists())
            }
            coVerify(exactly = 1) { localIndex.delete(manga.id) }
            assertEquals(listOf<LocalContent?>(null), changes.replayCache)
        } finally {
            unmockkStatic(UniFile::class)
            App::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, previousApp)
        }
    }

    @Test
    fun `details applies fresh partial snapshot and clears the removed root`() = runBlocking {
        val source = ContentSource("TEST_MANGA")
        val remote = (1L..2L).map { sampleChapter(it, source) }
        val manga = sampleContent(42L, "Sample Manga", source, remote)
        val downloaded = remote.map { it.copy(url = "file:///books/${it.id}.cbz", source = LocalMangaSource) }
        val local = LocalContent(manga.copy(chapters = downloaded), tempDir, Uri.fromFile(tempDir))
        val repository = mockk<LocalMangaRepository>()
        val interactor = DetailsInteractor(mockk(), mockk(), repository, mockk(), mockk(), emptySet())
        val details = ContentDetails(manga).withLocalContent(local)
        val partial = local.copy(manga = manga.copy(chapters = downloaded.drop(1)))

        val updated = checkNotNull(interactor.updateLocal(details, partial))
        assertEquals(listOf(false, true), updated.rows().map { it.isDownloaded })
        coVerify(exactly = 0) { repository.readLocalContent(any()) }
        coEvery { repository.readLocalContent(partial) } returns null
        val cleared = checkNotNull(interactor.updateLocal(updated, null))
        assertNull(cleared.local)
        assertEquals(remote.map { it.url }, cleared.rows().map { it.chapter.url })
        assertTrue(cleared.rows(downloadedOnly = true).isEmpty())
    }

    private fun writeArchive(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, value) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun ContentDetails.rows(downloadedOnly: Boolean = false) = mapChapters(
        currentChapterId = 0,
        newCount = 0,
        branch = null,
        bookmarks = emptyList(),
        isGrid = false,
        isDownloadedOnly = downloadedOnly,
    )

    private fun sampleChapter(id: Long, source: ParserContentSource) = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "https://example.org/ch/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = source,
    )

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
        assertTrue("file:///storage/emulated/0/novels/book.epub#chapter/0".looksLikeNovelUrl())
        assertTrue("file:///storage/emulated/0/novels/ch1.txt#text".looksLikeNovelUrl())
        assertFalse("content+zip://authority/doc#00001.jpg".looksLikeNovelUrl())
        assertFalse("file+pdf:///storage/emulated/0/book.pdf#0".looksLikeNovelUrl())
        assertFalse("https://mangasite.example/manga/891#chapter/1".looksLikeNovelUrl())
        assertFalse("https://mangasite.example/manga/891#text".looksLikeNovelUrl())
    }

    @Test
    fun `EpubStorageManager findPageEbookFile locates legacy chapter pdf and cbz files`() {
        val rootDir = File(tempDir, "novel_root").apply { mkdirs() }
        val localStorageManager = mockk<LocalStorageManager> {
            every { getPreferredNovelDownloadDir() } returns rootDir
        }
        val manager = EpubStorageManager(
            context = mockk<Context>(relaxed = true),
            localStorageManager = localStorageManager,
        )
        assertNull(manager.findPageEbookFile(101L, 9001L))

        val mangaEpubDir = File(rootDir, "epub/101").apply { mkdirs() }
        File(mangaEpubDir, "chapter_9001.pdf").writeBytes("%PDF-1.7\n".toByteArray())
        File(mangaEpubDir, "chapter_9002.cbz").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        File(mangaEpubDir, "chapter_9003.pdf").createNewFile()

        assertEquals("chapter_9001.pdf", manager.findPageEbookFile(101L, 9001L)?.name)
        assertEquals("chapter_9002.cbz", manager.findPageEbookFile(101L, 9002L)?.name)
        assertNull(manager.findPageEbookFile(101L, 9003L))
    }

    @Test
    fun `deleting downloaded PDF chapter or parent folder evicts cached PdfRenderers`() = runBlocking {
        val backingDir = File(tempDir, "pdf_evict_dir").apply { mkdirs() }
        val cacheDir = File(tempDir, "pdf_evict_cache").apply { mkdirs() }
        val safRootUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga%2FWork1",
        )
        val safRoot = FakeSafDirUniFile(
            treeUri = safRootUri,
            backingDir = backingDir,
        )
        val chapter15 = ContentChapter(
            id = 1500L,
            title = "Chapter 15",
            number = 15f,
            volume = 0,
            url = "https://example.org/ch/15",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = ContentSource("TEST_MANGA"),
        )
        val manga = sampleContent(
            id = 42L,
            title = "Sample Manga",
            source = ContentSource("TEST_MANGA"),
            chapters = listOf(chapter15),
        )
        val output = LocalContentDirOutput(safRoot, manga, cacheDir)
        val pdfSource = File(tempDir, "source_evict.pdf").apply {
            writeBytes("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray())
        }
        output.addPageEbookChapter(IndexedValue(0, chapter15), pdfSource, "pdf")

        val storedPdfFile = File(backingDir, "0_Chapter_15.pdf")
        assertTrue(storedPdfFile.isFile)
        val safChapterUri = checkNotNull(safRoot.findFile("0_Chapter_15.pdf")).uri

        val safRenderer = mockk<PdfRenderer>(relaxed = true)
        val fileRenderer = mockk<PdfRenderer>(relaxed = true)
        LocalPdfPages.putRendererForTest(safChapterUri, safRenderer)
        LocalPdfPages.putRendererForTest(storedPdfFile.toUri(), fileRenderer)
        assertTrue(LocalPdfPages.hasCachedRenderer(safChapterUri))
        assertTrue(LocalPdfPages.hasCachedRenderer(storedPdfFile.toUri()))

        output.deleteChapters(setOf(1500L))

        assertFalse(storedPdfFile.exists())
        assertFalse(LocalPdfPages.hasCachedRenderer(safChapterUri))
        assertFalse(LocalPdfPages.hasCachedRenderer(storedPdfFile.toUri()))
        verify(exactly = 1) { safRenderer.close() }
        verify(exactly = 1) { fileRenderer.close() }

        val childUri = Uri.parse("${safRootUri}%2F1_Chapter_16.pdf")
        val siblingUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AManga/document/primary%3AManga%2FWork10%2F1_Chapter_16.pdf",
        )
        val childRenderer = mockk<PdfRenderer>(relaxed = true)
        val siblingRenderer = mockk<PdfRenderer>(relaxed = true)
        LocalPdfPages.putRendererForTest(childUri, childRenderer)
        LocalPdfPages.putRendererForTest(siblingUri, siblingRenderer)

        LocalPdfPages.evictPdfRenderersUnder(safRootUri)

        assertFalse(LocalPdfPages.hasCachedRenderer(childUri))
        assertTrue(LocalPdfPages.hasCachedRenderer(siblingUri))
        verify(exactly = 1) { childRenderer.close() }
        verify(exactly = 0) { siblingRenderer.close() }
    }

    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return mockk<Uri>().also { result ->
            every { result.toString() } returns value
            every { result.scheme } returns parsed.scheme
            every { result.authority } returns parsed.authority
            every { result.encodedAuthority } returns parsed.rawAuthority
            every { result.path } returns parsed.path
            every { result.encodedPath } returns parsed.rawPath
            every { result.query } returns parsed.query
            every { result.encodedQuery } returns parsed.rawQuery
            every { result.schemeSpecificPart } returns parsed.schemeSpecificPart
            every { result.encodedSchemeSpecificPart } returns parsed.rawSchemeSpecificPart
            every { result.fragment } returns parsed.fragment
            every { result.encodedFragment } returns parsed.rawFragment
            every { result.buildUpon() } answers {
                var scheme: String? = parsed.scheme
                var encodedFragment: String? = parsed.rawFragment
                mockk<Uri.Builder>().also { builder ->
                    every { builder.scheme(any()) } answers {
                        scheme = args[0] as String?
                        builder
                    }
                    every { builder.fragment(any()) } answers {
                        encodedFragment = (args[0] as String?)?.let { raw ->
                            URI("x", "//a", raw).rawFragment
                        }
                        builder
                    }
                    every { builder.encodedFragment(any()) } answers {
                        encodedFragment = args[0] as String?
                        builder
                    }
                    every { builder.build() } answers {
                        uri(
                            buildString {
                                if (!scheme.isNullOrEmpty()) {
                                    append(scheme).append(':')
                                }
                                append(parsed.rawSchemeSpecificPart)
                                if (encodedFragment != null) {
                                    append('#').append(encodedFragment)
                                }
                            },
                        )
                    }
                }
            }
        }
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
