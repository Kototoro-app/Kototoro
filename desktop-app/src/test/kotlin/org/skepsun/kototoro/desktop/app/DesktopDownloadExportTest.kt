package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile

class DesktopDownloadExportTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val revision = "a".repeat(64)
    private val chapter = SourceChapter(Long.MAX_VALUE, "第 1 话: 序", 1f, 0, "/1", null, 0, null, source)
    private val pages = (0..2).map { SourcePage(Long.MIN_VALUE + it, "/$it", null, source,
        requestContext = SourcePageRequestContext(it, "/origin-$it", "/image-$it#opaque")) }
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")
    private fun artifact(files: FileSourceImageStore, page: SourcePage) = files.materialize(page.source, page.id,
        ByteArrayInputStream(png), png.size.toLong(), {})

    @Test
    fun `export writes one entry per page and replaces an earlier file`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                cache.prepareDownload(page, hash, known)
            })
            queue.enqueue(12, chapter, "作品").join()
            val record = requireNotNull(queue.state.value.firstOrNull { it.record.chapter == chapter }).record
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.first().status)

            val target = directory.resolve("out").also { Files.createDirectories(it) }
            val exporter = DesktopDownloadExporter(files.directory)
            val file = exporter.export(record, target)
            assertEquals("作品 - 第 1 话_ 序.cbz", file.fileName.toString())
            ZipFile(file.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(listOf("0001.png", "0002.png", "0003.png"), names)
                assertEquals(List(3) { png.size.toLong() }, names.map { zip.getInputStream(zip.getEntry(it)).use { input ->
                    input.readBytes().size.toLong()
                } })
            }
            // Exporting the same chapter again overwrites instead of failing or leaving a second file.
            exporter.export(record, target)
            assertEquals(1L, Files.list(target).use { it.count() })
        }
    }

    @Test
    fun `export refuses an incomplete chapter and a vanished folder`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val target = directory.resolve("out").also { Files.createDirectories(it) }
        val exporter = DesktopDownloadExporter(files.directory)
        val partial = SourceChapterDownload(12, chapter, revision, listOf(SourceDownloadPage(pages[0])))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { exporter.export(partial, target) } }
        val complete = SourceChapterDownload(12, chapter, revision, pages.map {
            SourceDownloadPage(it, SourceImageArtifact(source, it.id, "$revision.img", revision, 10, "image/png"), 10, 10)
        })
        // The manifest points at a blob that was never written, so the export must not produce a half archive.
        assertThrows(java.io.IOException::class.java) {
            runBlocking { exporter.export(complete, target) }
        }
        assertTrue(Files.list(target).use { stream -> stream.count() } == 0L)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { exporter.export(complete, directory.resolve("missing")) }
        }
    }

    @Test
    fun `file names lose the characters Windows rejects`() {
        assertEquals("作品 - 第 1 话_ 序.cbz", DesktopDownloadExporter(directory)
            .fileName(SourceChapterDownload(12, chapter, revision, contentTitle = "作品")))
        // A record restored without its work title still gets a usable name instead of " .cbz".
        assertEquals("chapter - 第 1 话_ 序.cbz", DesktopDownloadExporter(directory)
            .fileName(SourceChapterDownload(12, chapter, revision)))
        assertEquals("a_b_c", desktopExportName("a/b:c"))
        assertEquals("trailing", desktopExportName("trailing."))
        assertEquals("chapter", desktopExportName("  "))
        assertEquals(80, desktopExportName("字".repeat(200)).length)
        assertEquals("jpg", desktopImageExtension("image/jpeg"))
        assertEquals("jpg", desktopImageExtension("IMAGE/JPEG; charset=binary"))
        assertEquals("png", desktopImageExtension("image/png"))
        assertEquals("img", desktopImageExtension("application/octet-stream"))
    }
}
