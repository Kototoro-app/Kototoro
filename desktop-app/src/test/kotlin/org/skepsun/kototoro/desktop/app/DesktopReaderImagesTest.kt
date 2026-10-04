package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class DesktopReaderImagesTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_9007199254740995", "zh", "MANGA")
    private val page = SourcePage(Long.MAX_VALUE, "https://fixture.invalid/page", null, source,
        requestContext = SourcePageRequestContext(12, "/original", "/image#opaque"))
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")
    private fun artifact(store: FileSourceImageStore, item: SourcePage) = store.materialize(
        item.source, item.id, ByteArrayInputStream(png), png.size.toLong(), {},
    )

    @Test
    fun `verified pages survive closing and reopening the actual preference store without a source request`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val count = AtomicInteger()
        lateinit var expected: DesktopReaderImage
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val cache = DesktopReaderImages(files.directory, prefs.open("pages")) { item ->
                count.incrementAndGet(); artifact(files, item)
            }
            expected = cache.load(page, "revision1")
            assertEquals(expected, cache.load(page, "revision1"))
            assertEquals(1, count.get())
        }
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val offline = DesktopReaderImages(files.directory, prefs.open("pages")) { throw IOException("offline") }
            assertEquals(expected, offline.load(page, "revision1"))
            assertEquals(1, expected.width)
            assertEquals(1, expected.height)
        }
    }

    @Test
    fun `reopened geometry hints preserve request identity but never bypass visible artifact verification`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            DesktopReaderImages(files.directory, prefs.open("pages")) { artifact(files, it) }.load(page, "revision")
        }
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val count = AtomicInteger()
            val cache = DesktopReaderImages(files.directory, prefs.open("pages")) { item ->
                count.incrementAndGet(); artifact(files, item)
            }
            val hint = mapOf(page.id to IntSize(1, 1))
            assertEquals(hint, cache.geometry(listOf(page), "revision"))
            assertTrue(cache.geometry(listOf(page), "changed-revision").isEmpty())
            assertTrue(cache.geometry(listOf(page.copy(headers = mapOf("Referer" to "changed"))), "revision").isEmpty())
            assertEquals(0, count.get())
            val image = cache.load(page, "revision")
            assertEquals(0, count.get())
            Files.write(image.path, png.copyOf().also { it[it.lastIndex - 2] = 0 })
            assertEquals(hint, cache.geometry(listOf(page), "revision"))
            assertEquals(image, cache.load(page, "revision"))
            assertArrayEquals(png, Files.readAllBytes(image.path))
            assertEquals(1, count.get())
            Files.write(image.path, byteArrayOf(0))
            assertTrue(cache.geometry(listOf(page), "revision").isEmpty())
        }
    }

    @Test
    fun `same size corrupted blob is refetched and failed refresh preserves the last good record`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val count = AtomicInteger()
            var fail = false
            val cache = DesktopReaderImages(files.directory, prefs.open("pages")) { item ->
                count.incrementAndGet()
                if (fail) throw IOException("authored failure")
                artifact(files, item)
            }
            val first = cache.load(page, "revision")
            Files.write(first.path, png.copyOf().also { it[it.lastIndex - 2] = 0 })
            assertEquals(first, cache.load(page, "revision"))
            assertArrayEquals(png, Files.readAllBytes(first.path))
            assertEquals(2, count.get())
            fail = true
            assertThrows(IOException::class.java) { runBlocking { cache.load(page, "revision", refresh = true) } }
            assertEquals(first, cache.load(page, "revision"))
            assertEquals(3, count.get())
            fail = false
            cache.load(page, "revision", refresh = true)
            assertEquals(4, count.get())
        }
    }

    @Test
    fun `revision source request context and headers separate page cache identities`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val count = AtomicInteger()
            val cache = DesktopReaderImages(files.directory, prefs.open("pages")) { item ->
                count.incrementAndGet(); artifact(files, item)
            }
            cache.load(page, "revision1")
            cache.load(page, "revision2")
            cache.load(page.copy(source = source.copy(name = "MIHON_other")), "revision1")
            cache.load(page.copy(requestContext = page.requestContext!!.copy(imageUrl = "/image#changed")), "revision1")
            cache.load(page.copy(headers = mapOf("Referer" to "different")), "revision1")
            cache.load(page, "revision1")
            assertEquals(5, count.get())
        }
    }

    @Test
    fun `index eviction is bounded and leaves immutable files available to existing reader nodes`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val preference = prefs.open("pages")
            val count = AtomicInteger()
            val cache = DesktopReaderImages(files.directory, preference, maximumRecords = 2) { item ->
                count.incrementAndGet(); artifact(files, item)
            }
            val pages = (0..2).map { page.copy(id = page.id - it, url = "/$it") }
            val images = pages.map { cache.load(it, "revision") }
            assertEquals(2, preference.snapshot().size)
            assertTrue(images.all { Files.isRegularFile(it.path) })
            val evicted = pages.single {
                imageKey(("revision\u0000" + SourceProtocolJson.encodeToString(it)).toByteArray()) !in preference.snapshot()
            }
            cache.load(evicted, "revision")
            assertEquals(4, count.get())
            assertEquals(2, preference.snapshot().size)
        }
    }

    @Test
    fun `malformed persisted records recover through the source and wrong source identity is rejected`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val preference = prefs.open("pages")
            val key = imageKey(("revision\u0000" + SourceProtocolJson.encodeToString(page)).toByteArray())
            preference.edit(SourcePreferenceEdit(changes = mapOf(
                key to SourcePreferenceValue.Text("{broken"),
                "old1" to SourcePreferenceValue.Text("bad"), "old2" to SourcePreferenceValue.Text("bad"),
            )))
            val cache = DesktopReaderImages(files.directory, preference, maximumRecords = 2) { artifact(files, it) }
            assertEquals(1, cache.load(page, "revision").width)
            assertEquals(2, preference.snapshot().size)
            val wrong = DesktopReaderImages(files.directory, preference) {
                artifact(files, page.copy(source = source.copy(name = "other")))
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { wrong.load(page, "revision", true) } }
            assertEquals(1, cache.load(page, "revision").width)
        }
    }

    @Test
    fun `caller cancellation ends the actual fetch and publishes no cache record`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val preference = prefs.open("pages")
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val cache = DesktopReaderImages(files.directory, preference) {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            val request = async { cache.load(page, "revision") }
            withTimeout(2000) { started.await() }
            request.cancelAndJoin()
            withTimeout(2000) { stopped.await() }
            assertTrue(preference.snapshot().isEmpty())
        }
    }
}
