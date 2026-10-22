package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class DesktopImageBudgetTest {
    @TempDir
    lateinit var directory: Path

    private val megabyte = 1024L * 1024L
    private val now = 1_700_000_000_000L

    /** The store publishes `.img` blobs named after their hash; the budget only ever evicts those files. */
    private fun blob(name: String, bytes: Long = megabyte, ageMinutes: Long = 1): Path {
        val path = directory.resolve(name)
        Files.write(path, ByteArray(bytes.toInt()) { 7 })
        Files.setLastModifiedTime(path, FileTime.fromMillis(now - ageMinutes * 60_000L))
        return path
    }

    private fun name(character: Char) = "$character".repeat(64) + ".img"

    @Test
    fun `enforce evicts the oldest unprotected blob until the directory fits`() = runBlocking {
        val oldest = blob(name('a'), ageMinutes = 30)
        val middle = blob(name('b'), ageMinutes = 20)
        val newest = blob(name('c'), ageMinutes = 10)

        val before = DesktopImageBudget.inspect(directory, emptySet(), 2)
        assertEquals(3 * megabyte, before.usedBytes)
        assertEquals(0, before.protectedBytes)

        val report = DesktopImageBudget.enforce(directory, emptySet(), 2, now = now)

        assertTrue(Files.notExists(oldest), "the least recently used blob is evicted first")
        assertTrue(Files.exists(middle))
        assertTrue(Files.exists(newest))
        assertEquals(1, report.deletedFiles)
        assertEquals(megabyte, report.deletedBytes)
        assertEquals(2 * megabyte, report.usedBytes)
        assertEquals(2 * megabyte, report.budgetBytes)
    }

    @Test
    fun `enforce never deletes a blob a saved chapter references`() = runBlocking {
        val kept = blob(name('d'), ageMinutes = 50)
        val cacheOnly = blob(name('e'), ageMinutes = 40)

        val report = DesktopImageBudget.enforce(directory, setOf(kept.fileName.toString()),
            DesktopImageBudget.UNLIMITED_MB, clear = true, now = now)

        assertTrue(Files.exists(kept), "offline reading keeps its images")
        assertTrue(Files.notExists(cacheOnly))
        assertEquals(1, report.deletedFiles)
        assertEquals(megabyte, report.protectedBytes)
        assertEquals(megabyte, report.usedBytes)
    }

    @Test
    fun `enforce skips blobs a page published inside the grace window`() = runBlocking {
        val fresh = blob(name('f'))
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(now - 1_000L))

        val report = DesktopImageBudget.enforce(directory, emptySet(), 0, clear = true, now = now)

        assertTrue(Files.exists(fresh), "a blob being read or just published is not swept away")
        assertEquals(0, report.deletedFiles)
    }

    @Test
    fun `an unlimited budget keeps the cache and still removes stale staging files`() = runBlocking {
        val cached = blob(name('1'), ageMinutes = 60)
        val staging = directory.resolve(".image-1234.part").also {
            Files.write(it, ByteArray(8))
            Files.setLastModifiedTime(it, FileTime.fromMillis(now - 2 * 60 * 60_000L))
        }

        val report = DesktopImageBudget.enforce(directory, emptySet(), DesktopImageBudget.UNLIMITED_MB, now = now)

        assertTrue(Files.exists(cached), "no limit means no eviction")
        assertTrue(Files.notExists(staging), "a crashed publication leaves pure garbage behind")
        assertEquals(0, report.deletedFiles)
        assertEquals(0, report.budgetBytes)
    }

    @Test
    fun `inspect reports what saved chapters protect`() {
        blob(name('2'), bytes = 5 * megabyte)
        val kept = blob(name('3'), bytes = 7 * megabyte)

        val report = DesktopImageBudget.inspect(directory, setOf(kept.fileName.toString()), DesktopImageBudget.DEFAULT_MB)

        assertEquals(12 * megabyte, report.usedBytes)
        assertEquals(7 * megabyte, report.protectedBytes)
        assertEquals(DesktopImageBudget.DEFAULT_MB * megabyte, report.budgetBytes)
    }

    @Test
    fun `sizes print with stable decimals for the downloads page`() {
        assertEquals("0.0 KB", desktopCacheSize(0))
        // One downloaded page is a few kilobytes; it must not print as an empty "0.0 MB" cache.
        assertEquals("11.2 KB", desktopCacheSize(11_428))
        assertEquals("1.5 MB", desktopCacheSize(megabyte + megabyte / 2))
        assertEquals("2.00 GB", desktopCacheSize(2048L * megabyte))
    }
}
