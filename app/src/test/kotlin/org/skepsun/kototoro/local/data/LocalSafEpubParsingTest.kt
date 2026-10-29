package org.skepsun.kototoro.local.data

import android.content.ContentResolver
import android.net.Uri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okio.FileSystem
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
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSafEpubParsingTest {

    @TempDir
    lateinit var directory: File

    private var previousApp: App? = null

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
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ContentZipArchive.Companion)
        App::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, previousApp)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `seekable EPUB documents retain novel chapters instead of becoming image archives`() = runBlocking {
        checkEpub(seekable = true)
    }

    @Test
    fun `streamed EPUB documents retain novel chapters`() = runBlocking {
        checkEpub(seekable = false)
    }

    private suspend fun checkEpub(seekable: Boolean) {
        val book = createEpub()
        val documentUri = uri("content://books/document/1")
        val document = mockk<UniFile>()
        every { document.uri } returns documentUri
        every { document.filePath } returns null
        every { document.name } returns "Book.EPUB"
        every { document.isFile } returns true
        every { document.length() } returns book.length()
        every { document.lastModified() } returns book.lastModified()
        every { document.openInputStream() } answers { book.inputStream() }
        FileSystem.SYSTEM.openZip(book.toOkioPath()).use { fileSystem ->
            val archive = mockk<ContentZipArchive>(relaxed = true)
            every { archive.fileSystem } returns fileSystem
            every { ContentZipArchive.open(any(), documentUri) } returns if (seekable) archive else null

            val parsed = LocalContentParser(document, directory).getContent(withDetails = true)

            assertEquals(LocalNovelSource, parsed.manga.source)
            assertEquals(documentUri.toString(), parsed.manga.url)
            val chapter = parsed.manga.chapters.orEmpty().single()
            assertEquals(LocalNovelSource, chapter.source)
            assertEquals("content://books/document/1#chapter/0", chapter.url)
        }
        val text = LocalEpubParser(book).getChapterContent(0).orEmpty()
        assertTrue(text.contains("The chapter text."), text)
        assertTrue(LocalSafArchiveCache.directory(directory).listFiles().orEmpty().isEmpty())
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
