package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class DesktopCoversTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_9007199254740996", "zh", "MANGA")
    private val content = SourceContent(Long.MAX_VALUE, "cover", emptySet(), "/work", "https://fixture.invalid/work",
        -1f, null, "https://fixture.invalid/cover.png", emptySet(), null, emptySet(), source)
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")

    private fun artifact(store: FileSourceImageStore, item: SourceContent = content) = store.materializeCover(
        item.source, item.id, ByteArrayInputStream(png), png.size.toLong(), {},
    )

    @Test
    fun `shared waiters keep one request alive after one consumer cancels`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            DesktopCovers(files.directory, prefs.open("covers")) { _, _ ->
                count.incrementAndGet(); started.complete(Unit); release.await(); artifact(files)
            }.use { cache ->
                val first = async(start = CoroutineStart.UNDISPATCHED) { cache.load(content) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { cache.load(content) }
                withTimeout(2000) { started.await() }
                first.cancelAndJoin()
                assertTrue(second.isActive)
                release.complete(Unit)
                val path = withTimeout(2000) { second.await() }
                assertEquals(path, cache.load(content))
                assertEquals(1, count.get())
            }
        }
    }

    @Test
    fun `last waiter cancellation stops its producer and allows retry`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val cache = DesktopCovers(files.directory, prefs.open("covers")) { _, _ ->
                if (count.incrementAndGet() == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else artifact(files)
            }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { cache.load(content) }
                withTimeout(2000) { started.await() }
                first.cancelAndJoin()
                withTimeout(2000) { cancelled.await() }
                assertTrue(Files.isRegularFile(withTimeout(2000) { cache.load(content) }))
                assertEquals(2, count.get())
            } finally { cache.close() }
            cache.close()
            assertThrows(IllegalStateException::class.java) { runBlocking { cache.load(content) } }
        }
    }

    @Test
    fun `close stops the active producer before returning without persisting`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val entries = prefs.open("covers")
            val cache = DesktopCovers(files.directory, entries) { _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { released.complete(Unit) }
            }
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.load(content) } }
            withTimeout(2000) { started.await() }
            cache.close()
            assertTrue(released.isCompleted)
            assertTrue(withTimeout(2000) { request.await() }.exceptionOrNull() is CancellationException)
            assertTrue(entries.snapshot().isEmpty())
        }
    }

    @Test
    fun `persistent covers tolerate missing locale and repair corrupt files`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val preferences = directory.resolve("prefs")
        var saved: Path? = null
        FileSourcePreferenceStore(preferences).use { prefs ->
            DesktopCovers(files.directory, prefs.open("covers")) { _, _ -> artifact(files) }.use {
                saved = it.load(content)
            }
        }
        FileSourcePreferenceStore(preferences).use { prefs ->
            DesktopCovers(files.directory, prefs.open("covers")) { _, _ -> error("Valid persisted cover fetched") }.use {
                assertEquals(saved, it.load(content.copy(source = source.copy(locale = ""))))
            }
        }
        val corrupted = png.copyOf().also { it[0] = 0 }
        Files.write(requireNotNull(saved), corrupted)
        val count = AtomicInteger()
        FileSourcePreferenceStore(preferences).use { prefs ->
            DesktopCovers(files.directory, prefs.open("covers")) { _, _ -> count.incrementAndGet(); artifact(files) }.use {
                assertEquals(saved, it.load(content))
                assertEquals(1, count.get())
                assertArrayEquals(png, Files.readAllBytes(requireNotNull(saved)))
            }
        }
    }

    @Test
    fun `failed loads retry with bounded metadata and deduplicated blobs`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { prefs ->
            val entries = prefs.open("covers")
            var fail = true
            DesktopCovers(files.directory, entries) { item, _ ->
                if (fail) throw IOException("authored failure") else artifact(files, item)
            }.use { cache ->
                assertThrows(IOException::class.java) { runBlocking { cache.load(content) } }
                assertTrue(entries.snapshot().isEmpty())
                fail = false
                for (index in 0..259) cache.load(content.copy(id = index.toLong(), coverUrl = "https://fixture.invalid/$index"))
                assertEquals(256, entries.snapshot().size)
                Files.list(files.directory).use { blobs -> assertEquals(1L, blobs.count()) }
            }
        }
    }
}
