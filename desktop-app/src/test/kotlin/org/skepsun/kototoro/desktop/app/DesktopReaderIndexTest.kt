package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

/**
 * Android's "clear pages cache" lands on the reader's own index, so the count the cleanup page shows and the sweep it
 * runs are both the store's: no blobs are touched here, the image budget owns those.
 */
class DesktopReaderIndexTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `clearing the page index drops every record and reports how many it dropped`() {
        FileSourcePreferenceStore(directory.resolve("prefs")).use { storage ->
            val store = storage.open("desktop_reader_images")
            val images = DesktopReaderImages(directory.resolve("images"), store) { error("no fetch expected") }
            store.edit(SourcePreferenceEdit(changes = mapOf(
                "a" to SourcePreferenceValue.Text("{}"),
                "b" to SourcePreferenceValue.Text("{}"),
            )))

            assertEquals(2, images.size())
            assertEquals(2, runBlocking { images.clearIndex() })
            assertEquals(0, images.size())
            // An empty index has nothing to report, so the page's message never claims a second sweep.
            assertEquals(0, runBlocking { images.clearIndex() })
        }
    }
}
