package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.DesktopDownloadSettings
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class DesktopDownloadsTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MAX_VALUE, "一", 1f, 0, "/1", null, 0, null, source)
    private val revision = "a".repeat(64)
    private val pages = (0..4).map { SourcePage(Long.MIN_VALUE + it, "/$it", null, source,
        requestContext = SourcePageRequestContext(it, "/origin-$it", "/image-$it#opaque")) }
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")
    private fun artifact(files: FileSourceImageStore, page: SourcePage) = files.materialize(page.source, page.id,
        ByteArrayInputStream(png), png.size.toLong(), {})

    @Test
    fun `partial failure persists requests and restart resumes without refetching verified pages`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val requested = mutableListOf<Long>()
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache"), maximumRecords = 2) { page ->
                requested.add(page.id)
                if (page == pages[2]) throw IOException("authored failure")
                artifact(files, page)
            }
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                cache.prepareDownload(page, hash, known)
            }, serialQueueSettings())
            queue.enqueue(12, chapter, "作品").join()
            assertEquals(DesktopDownloadStatus.FAILED, queue.state.value.single().status)
            assertEquals(2, store.records.value.single().completedPages)
        }
        requested.clear()
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache"), maximumRecords = 2) { page ->
                requested.add(page.id); artifact(files, page)
            }
            val queue = DesktopDownloads(store, this, { revision }, { error("Saved page list must be reused") },
                { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            assertEquals(DesktopDownloadStatus.PAUSED, queue.state.value.single().status)
            queue.enqueue(12, chapter).join()
            assertEquals(pages.drop(2).map { it.id }, requested)
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.single().status)
            assertEquals("作品", store.records.value.single().contentTitle)
            assertTrue(store.records.value.single().isComplete)
        }
    }

    @Test
    fun `download references survive reader index eviction above 256 pages and verify same size corruption`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val longPages = (0..256).map { pages[0].copy(id = Long.MIN_VALUE + it, url = "/$it") }
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            longPages.forEach { cache.load(it, revision) }
            val queue = DesktopDownloads(store, this, { revision }, { longPages },
                { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            queue.enqueue(12, chapter).join()
            assertEquals(256, preferences.open("cache").snapshot().size)
            assertEquals(257, store.records.value.single().completedPages)
        }
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            var count = 0
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { count++; artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { error("offline page list") },
                { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            queue.enqueue(12, chapter).join()
            assertEquals(0, count)
            val first = requireNotNull(store.page(longPages[0])?.artifact)
            assertNotNull(cache.openArtifact(longPages[0], first))
            Files.write(files.directory.resolve(first.relativePath), png.copyOf().also { it[it.lastIndex - 2] = 0 })
            assertNull(cache.openArtifact(longPages[0], first))
            queue.enqueue(12, chapter).join()
            assertEquals(1, count)
            assertTrue(store.records.value.single().isComplete)
        }
    }

    @Test
    fun `background transfers do not retain the foreground reader cache gate`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val started = CompletableDeferred<Unit>()
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { page ->
                if (page == pages[2]) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                artifact(files, page)
            }
            val download = async { cache.prepareDownload(pages[2], revision, null) }
            withTimeout(2000) { started.await() }
            assertEquals(1, withTimeout(2000) { cache.load(pages[0], revision) }.width)
            download.cancelAndJoin()
        }
    }

    @Test
    fun `revision changes obtain a fresh page list and persisted completion never ignores disk failure`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            var hash = revision
            var requests = 0
            val queue = DesktopDownloads(store, this, { hash }, { requests++; pages },
                { page, revision, known -> cache.prepareDownload(page, revision, known) })
            queue.enqueue(12, chapter).join()
            hash = "b".repeat(64)
            queue.enqueue(12, chapter).join()
            assertEquals(2, requests)
            assertEquals(hash, store.records.value.single().extensionRevision)
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.single().status)
        }
        val smallRoot = directory.resolve("small-prefs")
        FileSourcePreferenceStore(smallRoot, maximumBytes = 750).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { _, _, _ ->
                error("Page list must be saved before fetching an image")
            })
            queue.enqueue(12, chapter).join()
            assertEquals(DesktopDownloadStatus.FAILED, queue.state.value.single().status)
            assertEquals("下载记录过大，无法保存", queue.state.value.single().error)
            assertFalse(store.records.value.single().isComplete)
        }
        FileSourcePreferenceStore(smallRoot).use { preferences ->
            assertTrue(DesktopDownloadStore(preferences).records.value.single().pages.isEmpty())
        }
    }

    @Test
    fun `pages are fetched up to the thread count at once and all of them land`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val inside = java.util.concurrent.atomic.AtomicInteger(0)
            val peak = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = java.util.concurrent.atomic.AtomicInteger(0)
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                peak.getAndUpdate { current -> maxOf(current, inside.incrementAndGet()) }
                try {
                    delay(40)
                    fetched.incrementAndGet()
                    cache.prepareDownload(page, hash, known)
                } finally {
                    inside.decrementAndGet()
                }
            }, { DesktopDownloadSettings(threads = 3, maxActiveSeries = 1, retryCount = 1) })
            queue.enqueue(12, chapter).join()
            assertEquals(3, peak.get(), "three pages should overlap, not more and not fewer")
            assertEquals(pages.size, fetched.get())
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.single().status)
            assertEquals(pages.size, store.records.value.single().completedPages)
        }
    }

    @Test
    fun `a transient page failure is retried inside the chapter before it is reported`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                if (page == pages[1]) attempts.incrementAndGet()
                if (page == pages[1] && attempts.get() == 1) throw IOException("transient")
                cache.prepareDownload(page, hash, known)
            }, { DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, retryCount = 3, retryDelayMs = 10) })
            queue.enqueue(12, chapter).join()
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.single().status)
            assertEquals(2, attempts.get(), "the failing page is attempted twice: once failing, once succeeding")
        }
    }

    @Test
    fun `a runtime failure reported over the protocol is retried like a network error`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                // What SourceProtocolClient throws: the source caught its own exception, so no IOException arrives.
                if (page == pages[1] && attempts.incrementAndGet() == 1) {
                    throw SourceRemoteException(SourceError(SourceErrorCode.RUNTIME_FAILURE, "Source operation failed"))
                }
                cache.prepareDownload(page, hash, known)
            }, { DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, retryCount = 2, retryDelayMs = 10) })
            queue.enqueue(12, chapter).join()
            assertEquals(DesktopDownloadStatus.COMPLETE, queue.state.value.single().status)
            assertEquals(2, attempts.get(), "a runtime failure is worth a second attempt")
        }
    }

    @Test
    fun `a browser challenge is reported instead of retried`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { _, _, _ ->
                attempts.incrementAndGet()
                throw SourceRemoteException(SourceError(SourceErrorCode.CHALLENGE_REQUIRED, "Browser verification required"))
            }, { DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, retryCount = 5, retryDelayMs = 10) })
            queue.enqueue(12, chapter).join()
            assertEquals(DesktopDownloadStatus.FAILED, queue.state.value.single().status)
            assertEquals(1, attempts.get(), "waiting for the user cannot be retried away")
        }
    }

    @Test
    fun `a failed chapter reports the pages that already landed`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                if (page == pages[2]) throw IOException("gone")
                cache.prepareDownload(page, hash, known)
            }, { DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, retryCount = 1) })
            queue.enqueue(12, chapter).join()
            val state = queue.state.value.single()
            assertEquals(DesktopDownloadStatus.FAILED, state.status)
            assertEquals(2, state.record.completedPages, "the state carries the manifest, not the pre-fetch copy")
            assertEquals(2, store.records.value.single().completedPages)
        }
    }

    @Test
    fun `a chapter over the series cap waits its turn instead of running beside the first`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val release = CompletableDeferred<Unit>()
            val other = chapter.copy(id = 44, url = "/other")
            val queue = DesktopDownloads(store, this, { revision }, { requested ->
                if (requested == chapter) release.await()
                pages
            }, { page, hash, known -> cache.prepareDownload(page, hash, known) },
                { DesktopDownloadSettings(threads = 1, maxActiveSeries = 1, retryCount = 1) })
            val first = queue.enqueue(12, chapter)
            val second = queue.enqueue(12, other)
            delay(200)
            val waiting = queue.state.value.single { it.record.chapter == other }
            assertEquals(DesktopDownloadStatus.QUEUED, waiting.status)
            release.complete(Unit)
            first.join()
            second.join()
            assertEquals(2, queue.state.value.count { it.status == DesktopDownloadStatus.COMPLETE })
        }
    }

    @Test
    fun `pausing active and queued chapters cancels the caller and keeps durable resumable manifests`() = runBlocking<Unit> {
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var calls = 0
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { _, _, _ ->
                calls++
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }, serialQueueSettings())
            val first = queue.enqueue(12, chapter)
            withTimeout(2000) { started.await() }
            val other = chapter.copy(id = 44, url = "/other")
            val second = queue.enqueue(12, other)
            assertSame(first, queue.enqueue(12, chapter))
            queue.pause(DesktopDownloadStore.key(12, other))
            second.join()
            queue.pause(DesktopDownloadStore.key(12, chapter))
            first.join()
            withTimeout(2000) { stopped.await() }
            assertEquals(1, calls)
            assertTrue(queue.state.value.all { it.status == DesktopDownloadStatus.PAUSED })
            assertEquals(2, store.records.value.size)
        }
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            assertEquals(2, DesktopDownloadStore(preferences).records.value.size)
        }
    }
}
