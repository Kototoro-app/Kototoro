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
        val books = copies(6) { index -> now - 600_000L + index }
        val keep = books.last()

        LocalSafEpubCache.trim(directory, keep = keep, now = now)

        assertTrue(keep.isFile, "The book that was just materialized must stay readable")
        assertEquals(4, copies().size, "Retention must not keep one copy per book")
        assertTrue(!books[0].exists() && !books[1].exists(), "The least recently used copies are reclaimed")
    }

    @Test
    fun `trim never removes a copy another reader may still have open`() {
        val now = 10_000_000L
        val books = copies(6) { index -> now - 1_000L + index }

        LocalSafEpubCache.trim(directory, keep = null, now = now)

        assertEquals(books.size, copies().size, "Recently used copies are protected from trimming")
    }

    @Test
    fun `clearAll reclaims idle copies but keeps the active one`() {
        val now = 10_000_000L
        val idle = copies(2) { now - 600_000L }
        val active = File(LocalSafEpubCache.directory(directory), "active.epub").apply { writeText("book") }
        LocalSafEpubCache.markInUse(active, now)

        LocalSafEpubCache.clearAll(directory, keep = active, now = now)

        assertEquals(listOf(active), copies(), "Idle copies ${idle.map(File::getName)} are reclaimed")
    }

    @Test
    fun `clearAll reclaims copies of a previous process even though their timestamps are old`() {
        // A leftover copy carries the source's modification time, so the age of the file says
        // nothing about the age of the copy; only this process's own tracking can protect one.
        val leftover = File(LocalSafEpubCache.directory(directory).apply { mkdirs() }, "0.epub").apply {
            writeText("book")
            setLastModified(1L)
        }

        LocalSafEpubCache.clearAll(directory, now = 50L)

        assertTrue(!leftover.exists(), "Copies nobody touched in this process are leftovers")
    }

    private fun copies(count: Int, usedAt: (Int) -> Long): List<File> = List(count) { index ->
        File(LocalSafEpubCache.directory(directory).apply { mkdirs() }, "$index.epub").apply {
            writeText("x".repeat(32))
            setLastModified(1L)
            LocalSafEpubCache.markInUse(this, usedAt(index))
        }
    }

    private fun copies(): List<File> = LocalSafEpubCache.directory(directory).listFiles().orEmpty().toList()
}
