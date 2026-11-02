package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterDownload
import org.skepsun.kototoro.core.source.SourceDownloadPage
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePageRequestContext
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.desktopReadSavedChapters
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

/**
 * The cleanup page's "delete read chapters" against the real download store: the chapter the history stopped at and
 * everything after it must survive, the records before it must be gone, and a work with no history row must keep all of
 * its chapters (Android's `DeleteReadChaptersUseCase` returns early there).
 */
class DesktopReadChaptersCleanupTest {
    @TempDir lateinit var directory: Path

    private val source = SourceRef("fixture", "zh", "MANGA")
    private val revision = "a".repeat(64)
    private val contentId = Long.MIN_VALUE

    private fun chapter(number: Int) =
        SourceChapter(contentId + number, "第 $number 话", number.toFloat(), 0, "/$number", null, 0L, null, source)

    private fun record(number: Int): SourceChapterDownload {
        val chapter = chapter(number)
        // A per-chapter blob identity: the artifact requires `sha256` of 64 hex chars and the path `"<sha>.img"`.
        val sha = number.toString().last().toString().repeat(64)
        val page = SourcePage(chapter.id, "/page/$number", null, source,
            requestContext = SourcePageRequestContext(0, "/original", "/image#opaque"))
        return SourceChapterDownload(
            contentId = contentId,
            chapter = chapter,
            extensionRevision = revision,
            pages = listOf(SourceDownloadPage(page,
                SourceImageArtifact(source, page.id, "$sha.img", sha, 100, "image/png"), 400, 600)),
            contentTitle = "作品",
        )
    }

    @Test
    fun `the sweep drops only the chapters before the history position`() {
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            (1..4).forEach { store.save(record(it)) }
            assertEquals(4, store.records.value.size)

            val downloaded = store.records.value.map { it.chapter }
            val read = desktopReadSavedChapters(
                saved = emptyList(),
                downloaded = downloaded,
                currentChapterId = chapter(3).id,
            )
            assertEquals(listOf(1, 2), read.map { it.number.toInt() })

            // What the controller does after computing the list: forget the record, keep the rest.
            for (item in read) {
                assertTrue(store.delete(contentId, item, preferences), "chapter ${item.number} should be deleted")
            }
            assertEquals(listOf(3, 4), store.records.value.map { it.chapter.number.toInt() }.sorted())
        }
        // Reopening proves the deletion reached the persisted manifests, not just the in-memory flow.
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            assertTrue(store.errors.isEmpty(), store.errors.toString())
            assertEquals(listOf(3, 4), store.records.value.map { it.chapter.number.toInt() }.sorted())
        }
    }

    @Test
    fun `a work whose history is gone keeps every saved chapter`() {
        FileSourcePreferenceStore(directory).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            (1..2).forEach { store.save(record(it)) }

            val read = desktopReadSavedChapters(
                saved = emptyList(),
                downloaded = store.records.value.map { it.chapter },
                // Android returns before touching anything when the history row is missing; no id means no read set.
                currentChapterId = Long.MAX_VALUE,
            )

            assertTrue(read.isEmpty())
            assertEquals(2, store.records.value.size)
            assertFalse(store.records.value.first().chapter.id == Long.MAX_VALUE)
        }
    }
}
