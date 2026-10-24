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
import java.nio.file.Path
import java.util.Base64

class DesktopDownloadsBatchTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val revision = "a".repeat(64)
    private val first = SourceChapter(Long.MAX_VALUE, "一", 1f, 0, "/1", null, 0, null, source)
    private val second = SourceChapter(Long.MAX_VALUE - 1, "二", 2f, 0, "/2", null, 0, null, source)
    private val pages = (0..2).map { SourcePage(Long.MIN_VALUE + it, "/$it", null, source,
        requestContext = SourcePageRequestContext(it, "/origin-$it", "/image-$it#opaque")) }
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")
    private fun artifact(files: FileSourceImageStore, page: SourcePage) = files.materialize(page.source, page.id,
        ByteArrayInputStream(png), png.size.toLong(), {})

    /** Each chapter needs its own pages, otherwise both chapters would share one content-addressed block. */
    private fun chapterPages(chapter: SourceChapter) = pages.mapIndexed { index, page ->
        val offset = if (chapter == second) 10L else 0L
        page.copy(id = page.id + offset, url = "/${chapter.id}/$index",
            requestContext = SourcePageRequestContext(index, "/origin-${chapter.id}-$index",
                "/image-${chapter.id}-$index#opaque"))
    }
    private val secondMiddle: SourcePage get() = chapterPages(second)[1]
    private fun key(chapter: SourceChapter) = DesktopDownloadStore.key(12, chapter)

    private fun state(status: DesktopDownloadStatus, contentId: Long = 12, chapter: SourceChapter = first) =
        DesktopDownloadState(SourceChapterDownload(contentId, chapter, revision), status)

    @Test
    fun `usage counts saved chapters, completed ones, pages and artifact bytes`() {
        val completed = SourceChapterDownload(12, first, revision, pages.map {
            SourceDownloadPage(it, SourceImageArtifact(source, it.id, "$revision.img", revision, 1000, "image/png"), 1000, 1500)
        })
        val partial = SourceChapterDownload(12, second, revision, listOf(
            SourceDownloadPage(pages[0], SourceImageArtifact(source, pages[0].id, "$revision.img", revision, 500, "image/png"), 500, 750),
            SourceDownloadPage(pages[1])))
        val usage = desktopDownloadUsage(listOf(completed, partial))
        assertEquals(2, usage.chapters)
        assertEquals(1, usage.completeChapters)
        assertEquals(4, usage.pages)
        assertEquals(3500L, usage.bytes)
        assertEquals(DesktopDownloadUsage(0, 0, 0, 0L), desktopDownloadUsage(emptyList()))
    }

    @Test
    fun `batch selectors take only the rows each action can act on`() {
        val states = listOf(state(DesktopDownloadStatus.QUEUED), state(DesktopDownloadStatus.DOWNLOADING),
            state(DesktopDownloadStatus.PAUSED), state(DesktopDownloadStatus.COMPLETE), state(DesktopDownloadStatus.FAILED))
        assertEquals(setOf(DesktopDownloadStatus.QUEUED, DesktopDownloadStatus.DOWNLOADING),
            DesktopDownloadBatch.pausable(states).map { it.status }.toSet())
        assertEquals(listOf(DesktopDownloadStatus.PAUSED), DesktopDownloadBatch.resumable(states).map { it.status })
        assertEquals(listOf(DesktopDownloadStatus.FAILED), DesktopDownloadBatch.retryable(states).map { it.status })
        // Removal waits for running chapters: a queued row is skipped even when it is selected.
        val selected = states.map { it.key }.toSet()
        assertEquals(3, DesktopDownloadBatch.removable(states, selected).size)
        assertTrue(DesktopDownloadBatch.removable(states, emptySet()).isEmpty())
    }

    @Test
    fun `selection toggles, selects all and forgets keys that disappear`() {
        val keys = listOf("a", "b", "c")
        var selection = DesktopDownloadSelection.toggle(emptySet(), "a")
        assertEquals(setOf("a"), selection)
        selection = DesktopDownloadSelection.toggle(selection, "a")
        assertTrue(selection.isEmpty())
        assertEquals(keys.toSet(), DesktopDownloadSelection.all(keys))
        assertEquals(setOf("b"), DesktopDownloadSelection.retain(setOf("a", "b"), listOf("b", "c")))
        assertTrue(DesktopDownloadSelection.retain(setOf("a"), emptyList()).isEmpty())
    }

    @Test
    fun `batch pause stops every queued chapter and leaves finished ones complete`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            // The first chapter blocks inside its page list, so the second one is still waiting when the pause lands.
            val release = CompletableDeferred<Unit>()
            val queue = DesktopDownloads(store, this, { revision }, { chapter ->
                if (chapter == first) release.await()
                chapterPages(chapter)
            }, { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            val running = queue.enqueue(12, first, "作品")
            val waiting = queue.enqueue(12, second, "作品")
            assertEquals(2, DesktopDownloadBatch.pausable(queue.state.value).size)
            queue.pauseAll()
            running.join()
            waiting.join()
            assertEquals(listOf(DesktopDownloadStatus.PAUSED, DesktopDownloadStatus.PAUSED),
                queue.state.value.map { it.status })
            assertTrue(queue.state.value.none { it.status == DesktopDownloadStatus.COMPLETE })
            // Resuming the paused rows completes both, and each one is downloaded exactly once.
            release.complete(Unit)
            DesktopDownloadBatch.resumable(queue.state.value).map {
                queue.enqueue(it.record.contentId, it.record.chapter, it.record.contentTitle)
            }.forEach { it.join() }
            assertEquals(2, queue.state.value.count { it.status == DesktopDownloadStatus.COMPLETE })
        }
    }

    @Test
    fun `retry picks up a failed chapter without touching completed ones`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        var fail = true
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { page ->
                if (fail && page == secondMiddle) throw IOException("authored failure")
                artifact(files, page)
            }
            val queue = DesktopDownloads(store, this, { revision }, { chapterPages(it) },
                { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            queue.enqueue(12, first).join()
            queue.enqueue(12, second).join()
            val failed = queue.state.value.single { it.status == DesktopDownloadStatus.FAILED }
            assertEquals(key(second), failed.key)
            assertEquals(listOf(failed), DesktopDownloadBatch.retryable(queue.state.value))
            assertTrue(DesktopDownloadBatch.resumable(queue.state.value).isEmpty())
            fail = false
            DesktopDownloadBatch.retryable(queue.state.value).map {
                queue.enqueue(it.record.contentId, it.record.chapter, it.record.contentTitle)
            }.forEach { it.join() }
            assertEquals(2, queue.state.value.count { it.status == DesktopDownloadStatus.COMPLETE })
            assertNull(queue.state.value.first { it.key == key(second) }.error)
        }
    }

    @Test
    fun `removing a chapter drops its index and manifest so a restart cannot restore it`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        val storagePath = directory.resolve("prefs")
        FileSourcePreferenceStore(storagePath).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { chapterPages(it) },
                { page, hash, known -> cache.prepareDownload(page, hash, known) }, serialQueueSettings())
            queue.enqueue(12, first, "作品").join()
            queue.enqueue(12, second, "作品").join()
            assertEquals(2, store.records.value.size)
            // Each save publishes a fresh block, so superseded ones are already reclaimable garbage.
            val before = store.previewCleanup(preferences)
            val beforeBlocks = before.candidates.map { it.namespace }.toSet()
            assertTrue(beforeBlocks.isNotEmpty())
            queue.pause(key(first))
            queue.settle(key(first))
            assertTrue(store.delete(12, first, preferences))
            assertFalse(store.delete(12, first, preferences))
            queue.forget(listOf(key(first)))
            assertEquals(listOf(key(second)), queue.state.value.map { it.key })
            // The one block the forgotten chapter still referenced joins that garbage when its manifest goes away.
            val after = store.previewCleanup(preferences)
            assertEquals(1, (after.candidates.map { it.namespace }.toSet() - beforeBlocks).size)
            assertTrue(after.reclaimableBytes > before.reclaimableBytes)
        }
        FileSourcePreferenceStore(storagePath).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertTrue(store.errors.isEmpty())
            assertEquals(listOf(second), store.records.value.map { it.chapter })
        }
    }
}
