package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class DesktopDownloadCleanupTest {
    @TempDir lateinit var root: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val hash = "a".repeat(64)
    private val chapter = SourceChapter(1, "章节", 1f, 0, "/1", null, 0, null, source)
    private val page = SourcePage(Long.MIN_VALUE, "/page", null, source)
    private fun record(ready: Boolean) = SourceChapterDownload(Long.MAX_VALUE, chapter, hash, listOf(
        if (!ready) SourceDownloadPage(page) else SourceDownloadPage(page,
            SourceImageArtifact(source, page.id, "$hash.img", hash, 10, "image/png"), 1, 1)))

    @Test
    fun `confirmed cleanup removes only obsolete chunks and saving a retired version republishes it`() {
        FileSourcePreferenceStore(root).use { files ->
            val store = DesktopDownloadStore(files)
            store.save(record(false))
            store.save(record(true))
            files.open("other").edit(SourcePreferenceEdit(changes = mapOf("keep" to SourcePreferenceValue.Toggle(true))))
            val plan = store.previewCleanup(files)
            assertEquals(1, plan.candidates.size)
            val result = store.cleanup(plan, files)
            assertEquals(1, result.removed)
            assertEquals(plan.reclaimableBytes, result.bytes)
            assertEquals(record(true), store.records.value.single())
            assertTrue(files.open("other").snapshot().containsKey("keep"))
            assertTrue(store.previewCleanup(files).candidates.isEmpty())
            store.save(record(false))
            assertEquals(record(false), store.records.value.single())
        }
        FileSourcePreferenceStore(root).use { files ->
            val store = DesktopDownloadStore(files)
            assertTrue(store.errors.isEmpty())
            assertEquals(record(false), store.records.value.single())
        }
    }

    @Test
    fun `confirmation rechecks references and never deletes new garbage not included in the approved plan`() {
        FileSourcePreferenceStore(root).use { files ->
            val store = DesktopDownloadStore(files)
            store.save(record(false))
            store.save(record(true))
            val plan = store.previewCleanup(files)
            store.save(record(false))
            val result = store.cleanup(plan, files)
            assertEquals(0, result.removed)
            assertEquals(1, result.skipped)
            assertEquals(1, store.previewCleanup(files).candidates.size)
            assertEquals(record(false), store.records.value.single())
        }
    }

    @Test
    fun `unindexed published roots protect their chunks and corrupt graphs block removal`() {
        FileSourcePreferenceStore(root).use { files ->
            val store = DesktopDownloadStore(files)
            store.save(record(false))
            store.save(record(true))
            val identity = DesktopDownloadStore.key(Long.MAX_VALUE, chapter)
            val manifest = SourceProtocolJson.decodeFromString<SourceDownloadManifest>(
                (files.open("desktop_download_chapter_v2_$identity").snapshot()["manifest"] as SourcePreferenceValue.Text).value)
            files.open("desktop_download_index_v2_${identity.first()}").edit(SourcePreferenceEdit(clear = true))
            val plan = store.previewCleanup(files)
            assertEquals(1, plan.candidates.size)
            assertFalse(plan.candidates.any { it.namespace.endsWith(manifest.chunks.single()) })
            val chunk = files.open(DesktopDownloadCleanup.CHUNKS + manifest.chunks.single())
            val text = (chunk.snapshot()["pages"] as SourcePreferenceValue.Text).value
            chunk.edit(SourcePreferenceEdit(changes = mapOf("pages" to SourcePreferenceValue.Text(text + " "))))
            assertThrows(Exception::class.java) { store.cleanup(plan, files) }
            assertTrue(files.snapshots().any { it == plan.candidates.single() })
        }
    }
}
