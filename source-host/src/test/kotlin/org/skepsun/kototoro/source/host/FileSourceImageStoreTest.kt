package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceRef
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class FileSourceImageStoreTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_1", "zh", "MANGA")

    @Test
    fun `unknown length chunked stream preserves bytes digest and source identity`() {
        val stream = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 3))
        }
        var checkpoints = 0
        val store = FileSourceImageStore(directory)
        val image = store.materialize(source, Long.MIN_VALUE, stream, -1) { checkpoints++ }
        assertEquals(source, image.source)
        assertEquals(Long.MIN_VALUE, image.pageId)
        assertEquals(png.size.toLong(), image.byteSize)
        assertEquals("image/png", image.contentType)
        assertEquals(digest(png), image.sha256)
        assertEquals("${digest(png)}.img", image.relativePath)
        assertArrayEquals(png, Files.readAllBytes(directory.resolve(image.relativePath)))
        assertTrue(checkpoints > 10)
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `identical blobs deduplicate across page and source while corrupt blobs are repaired`() {
        val store = FileSourceImageStore(directory)
        val first = store.materialize(source, 1, png.inputStream(), png.size.toLong()) {}
        val second = store.materialize(source.copy(name = "MIHON_2"), 2, png.inputStream(), -1) {}
        assertEquals(first.relativePath, second.relativePath)
        assertEquals(2, second.pageId)
        assertNotEquals(first.source, second.source)
        Files.write(directory.resolve(first.relativePath), ByteArray(png.size))
        assertEquals(first, store.materialize(source, 1, png.inputStream(), png.size.toLong()) {})
        assertArrayEquals(png, Files.readAllBytes(directory.resolve(first.relativePath)))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `empty nonimage and mismatched lengths reject without publishing or leaving staging files`() {
        val store = FileSourceImageStore(directory)
        for ((bytes, length) in listOf(ByteArray(0) to -1L, "<html>error</html>".toByteArray() to -1L,
                png to (png.size + 1L), png to (png.size - 1L))) {
            assertThrows(IOException::class.java) { store.materialize(source, 1, bytes.inputStream(), length) {} }
            assertEquals(0L, Files.list(directory).use { it.count() })
        }
    }

    @Test
    fun `declared and streaming size limits reject before publication`() {
        val store = FileSourceImageStore(directory, png.size - 1L)
        for (length in listOf(png.size.toLong(), -1L)) {
            assertThrows(IOException::class.java) { store.materialize(source, 1, png.inputStream(), length) {} }
            assertEquals(0L, Files.list(directory).use { it.count() })
        }
        assertThrows(IllegalArgumentException::class.java) { FileSourceImageStore(directory, 0) }
    }

    @Test
    fun `stream failures and cancellation remove staging files and preserve existing artifacts`() {
        val store = FileSourceImageStore(directory)
        val image = store.materialize(source, 1, png.inputStream(), -1) {}
        var calls = 0
        assertThrows(CancellationException::class.java) {
            store.materialize(source, 2, png.inputStream(), -1) {
                if (++calls == 3) throw CancellationException("fixture cancellation")
            }
        }
        val broken = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("fixture failure")
        }
        assertThrows(IOException::class.java) { store.materialize(source, 3, broken, -1) {} }
        assertArrayEquals(png, Files.readAllBytes(directory.resolve(image.relativePath)))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `parallel publishers produce one complete immutable blob`() {
        val store = FileSourceImageStore(directory)
        val workers = Executors.newFixedThreadPool(4)
        val anotherStore = FileSourceImageStore(directory)
        try {
            val results = (1L..8L).map { page ->
                workers.submit<org.skepsun.kototoro.core.source.SourceImageArtifact> {
                    (if (page % 2 == 0L) store else anotherStore).materialize(source, page, png.inputStream(), -1) {}
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.relativePath }.distinct().size)
            assertArrayEquals(png, Files.readAllBytes(directory.resolve(results.first().relativePath)))
            assertEquals(1L, Files.list(directory).use { it.count() })
        } finally { workers.shutdownNow() }
    }

    @Test
    fun `artifact directory occupying hash path is rejected without modifying it`() {
        Files.createDirectory(directory.resolve("${digest(png)}.img"))
        assertThrows(IOException::class.java) {
            FileSourceImageStore(directory).materialize(source, 1, png.inputStream(), -1) {}
        }
        assertTrue(Files.isDirectory(directory.resolve("${digest(png)}.img")))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `signature metadata covers image containers without trusting HTTP content type`() {
        fun hex(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()
        val signatures = mapOf(
            "image/jpeg" to hex(0xff, 0xd8, 0xff), "image/gif" to "GIF89a".toByteArray(),
            "image/webp" to "RIFF0000WEBP".toByteArray(), "image/bmp" to "BM".toByteArray(),
            "image/tiff" to hex(0x49, 0x49, 0x2a, 0), "image/jxl" to hex(0xff, 0x0a),
            "image/avif" to (hex(0, 0, 0, 24) + "ftypavif".toByteArray() + ByteArray(4)),
            "image/heic" to (hex(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(4)),
            "image/heif" to (hex(0, 0, 0, 24) + "ftypmif1".toByteArray() + ByteArray(4)),
        )
        val store = FileSourceImageStore(directory)
        for ((type, prefix) in signatures) {
            assertEquals(type, store.materialize(source, 1, prefix.inputStream(), -1) {}.contentType)
        }
        // Header recognition is metadata, not proof these deliberately short fixtures decode as images.
        assertThrows(IOException::class.java) {
            store.materialize(source, 1, (hex(0, 0, 0, 24) + "ftypisom".toByteArray()).inputStream(), -1) {}
        }
    }

    companion object {
        val png: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=",
        )
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
