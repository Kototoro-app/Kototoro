package org.skepsun.kototoro.local.data

import android.content.ContentResolver
import android.net.Uri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.coEvery
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import okio.Path.Companion.toOkioPath
import okio.openZip
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.util.ContentZipArchive
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.epub.LocalEpubParser
import org.skepsun.kototoro.local.novel.LocalNovelRepository
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSafEpubParsingTest {

    @TempDir
    lateinit var directory: File

    private var previousApp: App? = null
    private val documents = mutableMapOf<String, UniFile>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uri(firstArg()) }
        every { Uri.fromFile(any()) } answers { uri(firstArg<File>().toURI().toString()) }
        previousApp = App.getInstance()
        val resolver = mockk<ContentResolver>()
        object : App() {
            override fun getContentResolver() = resolver
            override fun getCacheDir() = directory
        }
        mockkObject(ContentZipArchive.Companion)
        mockkStatic(UniFile::class)
        every { UniFile.fromUri(any(), any()) } answers { documents[secondArg<Uri>().toString()] }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ContentZipArchive.Companion)
        App::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, previousApp)
        unmockkStatic(Uri::class)
        unmockkStatic(UniFile::class)
    }

    @Test
    fun `seekable EPUB documents retain novel chapters instead of becoming image archives`() = runBlocking {
        checkEpub(seekable = true)
    }

    @Test
    fun `streamed EPUB documents retain novel chapters`() = runBlocking {
        checkEpub(seekable = false)
    }

    @Test
    fun `seekable EPUB list scans read metadata without copying the book or opening chapter bodies`() = runBlocking {
        checkEpub(seekable = true, withDetails = false)
    }

    @Test
    fun `folder metadata scans do not publish an incomplete chapter index`() = runBlocking {
        val book = createEpub()
        val child = document(book, "Book.EPUB")
        val childUri = child.uri
        val folder = mockk<UniFile>()
        every { folder.uri } returns uri("content://books/folder/1")
        every { folder.name } returns "Folder"
        every { folder.filePath } returns null
        every { folder.isFile } returns false
        every { folder.isDirectory } returns true
        every { folder.listFiles() } returns arrayOf(child)
        every { folder.findFile(any()) } returns null
        val index = mockk<UniFile>()
        every { folder.createFile(any()) } returns index
        every { index.openOutputStream() } answers { ByteArrayOutputStream() }
        FileSystem.SYSTEM.openZip(book.toOkioPath()).use { fileSystem ->
            val archive = mockk<ContentZipArchive>(relaxed = true)
            every { archive.fileSystem } returns fileSystem
            every { ContentZipArchive.open(any(), childUri) } returns archive
            val parser = LocalContentParser(folder, directory)

            val metadata = parser.getContent(withDetails = false)

            assertEquals(null, metadata.manga.chapters)
            assertEquals("content+zip://books/document/1#OEBPS/picture.jpg", metadata.manga.coverUrl)
            verify(exactly = 0) { child.openInputStream() }
            verify(exactly = 0) { folder.createFile(any()) }
            val detailed = parser.getContent(withDetails = true)
            assertEquals(metadata.manga.id, detailed.manga.id)
            assertEquals("content://books/document/1#chapter/0", detailed.manga.chapters.orEmpty().single().url)
            verify(exactly = 1) { folder.createFile(any()) }
        }
    }

    @Test
    fun `novel directories list standalone TXT without reading text until a chapter is opened`() = runBlocking {
        val book = File(directory, "Novel.TXT").apply { writeText("第一段 <text>\n\n第二段") }
        val document = document(book, "Novel.TXT")
        val folder = mockk<UniFile>()
        every { folder.listFiles() } returns arrayOf(document)
        val root = mockk<LocalStorageRoot>()
        every { root.file } returns folder
        val storage = mockk<LocalStorageManager>()
        coEvery { storage.getNovelReadableRoots() } returns listOf(root)
        val repository = LocalNovelRepository(storage)

        val listed = repository.getList(0, null, null)

        assertEquals(1, listed.size)
        assertEquals("Novel", listed.single().title)
        assertEquals(LocalNovelSource, listed.single().source)
        assertEquals(null, listed.single().chapters)
        verify(exactly = 0) { document.openInputStream() }

        val details = repository.getDetails(listed.single())
        assertEquals(listed.single().id, details.id)
        val chapter = details.chapters.orEmpty().single()
        assertEquals(document.uri.toString(), chapter.url)
        assertEquals(document.uri.toString(), repository.getPages(chapter, null).single().url)
        verify(exactly = 0) { document.openInputStream() }
        val html = checkNotNull(repository.getChapterContent(chapter)).html
        assertTrue(html.contains("第一段 &lt;text&gt;"), html)
        assertTrue(html.contains("第二段"), html)
        verify(exactly = 1) { document.openInputStream() }
    }

    private suspend fun checkEpub(seekable: Boolean, withDetails: Boolean = true) {
        val book = createEpub()
        val document = document(book, "Book.EPUB")
        val documentUri = document.uri
        FileSystem.SYSTEM.openZip(book.toOkioPath()).use { fileSystem ->
            val reads = mutableListOf<String>()
            val monitored = object : ForwardingFileSystem(fileSystem) {
                override fun source(file: Path): Source {
                    reads += file.toString()
                    return super.source(file)
                }
            }
            val archive = mockk<ContentZipArchive>(relaxed = true)
            every { archive.fileSystem } returns monitored
            every { ContentZipArchive.open(any(), documentUri) } returns if (seekable) archive else null

            val parsed = LocalContentParser(document, directory).getContent(withDetails = withDetails)

            assertEquals(LocalNovelSource, parsed.manga.source)
            assertEquals(documentUri.toString(), parsed.manga.url)
            if (!withDetails) {
                assertEquals("Book", parsed.manga.title)
                assertEquals("content+zip://books/document/1#OEBPS/picture.jpg", parsed.manga.coverUrl)
                assertEquals(null, parsed.manga.chapters)
                verify(exactly = 0) { document.openInputStream() }
                assertTrue(reads.isNotEmpty(), "Read the real ZIP metadata, not just the file name")
                assertTrue(reads.none { it.endsWith("chapter.xhtml") || it.endsWith("picture.jpg") }, reads.toString())
                val detailed = LocalContentParser(document, directory).getContent(withDetails = true)
                assertEquals(parsed.manga.id, detailed.manga.id, "Metadata scans must keep the stored work identity")
                return
            }
            val chapter = parsed.manga.chapters.orEmpty().single()
            assertEquals(LocalNovelSource, chapter.source)
            assertEquals("content://books/document/1#chapter/0", chapter.url)
        }
        val text = LocalEpubParser(book).getChapterContent(0).orEmpty()
        assertTrue(text.contains("The chapter text."), text)
        assertTrue(LocalSafArchiveCache.directory(directory).listFiles().orEmpty().isEmpty())
    }

    private fun document(book: File, name: String): UniFile {
        val documentUri = uri("content://books/document/1")
        return mockk<UniFile>().also { document ->
            every { document.uri } returns documentUri
            every { document.filePath } returns null
            every { document.name } returns name
            every { document.isFile } returns true
            every { document.isDirectory } returns false
            every { document.length() } returns book.length()
            every { document.lastModified() } returns book.lastModified()
            every { document.openInputStream() } answers { book.inputStream() }
            documents[documentUri.toString()] = document
        }
    }

    private fun createEpub(): File {
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/>
                </rootfiles></container>""",
            "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Book</dc:title>
                <dc:identifier id="id">fixture</dc:identifier><dc:language>en</dc:language></metadata>
                <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="picture" href="picture.jpg" media-type="image/jpeg"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>""",
            "OEBPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head>
                <body><p>The chapter text.</p><img src="picture.jpg"/></body></html>""",
            "OEBPS/picture.jpg" to "picture",
        )
        return File(directory, "fixture.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return mockk<Uri>().also { result ->
            every { result.toString() } returns value
            every { result.scheme } returns parsed.scheme
            every { result.path } returns parsed.path
            every { result.fragment } returns parsed.fragment
            every { result.encodedFragment } returns parsed.rawFragment
            every { result.buildUpon() } answers {
                var scheme = parsed.scheme
                var fragment = parsed.rawFragment
                mockk<Uri.Builder>().also { builder ->
                    every { builder.scheme(any()) } answers { scheme = firstArg(); builder }
                    every { builder.fragment(any()) } answers { fragment = firstArg(); builder }
                    every { builder.encodedFragment(any()) } answers { fragment = firstArg(); builder }
                    every { builder.build() } answers {
                        uri(URI(scheme, parsed.authority, parsed.path, parsed.query, fragment).toString())
                    }
                }
            }
        }
    }
}
