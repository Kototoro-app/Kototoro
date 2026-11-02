package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourceImageStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.Base64

class DesktopDownloadMaintenanceTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("fixture", "zh", "MANGA")
    private val revision = "a".repeat(64)
    private val chapter = SourceChapter(Long.MAX_VALUE, "一", 1f, 0, "/1", null, 0, null, source)
    private val pages = (0..2).map { SourcePage(Long.MIN_VALUE + it, "/$it", null, source,
        requestContext = SourcePageRequestContext(it, "/origin-$it", "/image-$it#opaque")) }
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=")
    private fun artifact(files: FileSourceImageStore, page: SourcePage) = files.materialize(page.source, page.id,
        ByteArrayInputStream(png), png.size.toLong(), {})

    @Test
    fun `the sweep reclaims superseded blocks and keeps the chapter readable`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                cache.prepareDownload(page, hash, known)
            })
            queue.enqueue(12, chapter, "作品").join()
            // Incremental saves leave their previous blocks behind, so the first sweep has work to do.
            assertTrue(store.previewCleanup(preferences).candidates.isNotEmpty())

            val maintenance = DesktopDownloadMaintenance(store, preferences,
                preferences.open("desktop_download_maintenance"))
            assertTrue(maintenance.automatic)
            val result = requireNotNull(maintenance.reclaim()) { "the first sweep should find garbage" }
            assertTrue(result.removed > 0)
            assertEquals(0, store.previewCleanup(preferences).candidates.size)
            assertNull(maintenance.reclaim(), "nothing is unreachable any more")
            // The manifest and its images are untouched by the sweep.
            val record = requireNotNull(store.records.value.singleOrNull())
            assertEquals(3, record.pages.size)
            assertTrue(record.isComplete)
            record.pages.forEach { page -> assertNotNull(store.page(page.page)) }
        }
    }

    @Test
    fun `the switch is persisted and off means no sweep`() = runBlocking<Unit> {
        val files = FileSourceImageStore(directory.resolve("images"))
        FileSourcePreferenceStore(directory.resolve("prefs")).use { preferences ->
            val store = DesktopDownloadStore(preferences)
            val cache = DesktopReaderImages(files.directory, preferences.open("cache")) { artifact(files, it) }
            val queue = DesktopDownloads(store, this, { revision }, { pages }, { page, hash, known ->
                cache.prepareDownload(page, hash, known)
            })
            queue.enqueue(12, chapter, "作品").join()
            val maintenance = DesktopDownloadMaintenance(store, preferences,
                preferences.open("desktop_download_maintenance"))
            maintenance.automatic = false
            assertNull(maintenance.reclaim())
            assertTrue(store.previewCleanup(preferences).candidates.isNotEmpty())
            // A second instance reads the saved value, so the switch survives a restart.
            val restored = DesktopDownloadMaintenance(store, preferences,
                preferences.open("desktop_download_maintenance"))
            assertFalse(restored.automatic)
            restored.automatic = true
            assertNotNull(maintenance.reclaim())
        }
    }
}
