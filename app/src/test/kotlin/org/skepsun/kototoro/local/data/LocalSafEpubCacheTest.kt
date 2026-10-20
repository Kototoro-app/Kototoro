package org.skepsun.kototoro.local.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalSafEpubCacheTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `trim keeps the materialized book and reclaims the least recently used copies`() {
        val now = 10_000_000L
        val old = copies(6) { index -> now - 600_000L + index }
        val keep = old.last()

        LocalSafEpubCache.trim(directory, keep = keep, now = now)

        assertTrue(keep.isFile, "The book that was just materialized must stay readable")
        assertEquals(4, copies().size, "Retention must not keep one copy per book")
        assertTrue(!old[0].exists() && !old[1].exists(), "The least recently used copies are reclaimed")
    }

    @Test
    fun `trim never removes a copy that another reader may still have open`() {
        val now = 10_000_000L
        val fresh = copies(4) { now - 1_000L }

        LocalSafEpubCache.trim(directory, keep = null, now = now)

        assertEquals(fresh.size, copies().size, "Recent copies are protected from trimming")
    }

    @Test
    fun `clearAll reclaims every idle copy except the active one`() {
        val now = 10_000_000L
        val active = copies(3) { 0L }

        LocalSafEpubCache.clearAll(directory, keep = active.first(), now = now)

        assertEquals(listOf(active.first()), copies())
    }

    private fun copies(count: Int, modifiedAt: (Int) -> Long): List<File> = List(count) { index ->
        File(LocalSafEpubCache.directory(directory).apply { mkdirs() }, "$index.epub").apply {
            writeText("x".repeat(32))
            setLastModified(modifiedAt(index))
        }
    }

    private fun copies(): List<File> = LocalSafEpubCache.directory(directory).listFiles().orEmpty().toList()
}
