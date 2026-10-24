package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class DesktopStorageUsageTest {
    @TempDir
    lateinit var directory: Path

    private fun paths() = DesktopDataPaths.create(directory.resolve("中文 data root"))

    private fun write(path: Path, bytes: Int) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, ByteArray(bytes))
    }

    @Test
    fun `every store under the data root becomes its own slice and nothing is counted twice`() {
        val paths = paths()
        write(paths.database, 1000)
        write(paths.root.resolve("${paths.database.name}-wal"), 500)
        write(paths.images.resolve("chapter-a/blobs/aa.img"), 2000)
        write(paths.preferences.resolve("desktop_network"), 300)
        write(paths.logs.resolve("source-errors.log"), 100)
        // A store this build does not know about still has to show up, or the page would under-report the disk.
        write(paths.root.resolve("updates/pending.zip"), 400)

        val usage = desktopStorageUsage(paths)

        assertEquals(1500L, usage.find(DesktopStorageCategory.DATABASE)?.bytes)
        assertEquals(2000L, usage.find(DesktopStorageCategory.IMAGES)?.bytes)
        assertEquals(300L, usage.find(DesktopStorageCategory.PREFERENCES)?.bytes)
        assertEquals(100L, usage.find(DesktopStorageCategory.LOGS)?.bytes)
        assertEquals(400L, usage.find(DesktopStorageCategory.OTHER)?.bytes)
        assertEquals(4300L, usage.usedBytes)
        // Percentages are of the whole volume, as Android's rows report them; the rest of the disk belongs elsewhere.
        assertTrue(usage.availableBytes > 0L)
        assertEquals(usage.availableBytes, usage.find(DesktopStorageCategory.AVAILABLE)!!.bytes)
        assertTrue(usage.volumeBytes >= usage.usedBytes + usage.availableBytes)
        assertTrue(usage.items.all { it.percent in 0f..100f })
        val free = usage.find(DesktopStorageCategory.AVAILABLE)!!
        assertTrue(free.percent > usage.find(DesktopStorageCategory.IMAGES)!!.percent)
    }

    @Test
    fun `a fresh install reports the volume and available space without inventing empty stores`() {
        val usage = desktopStorageUsage(paths())

        assertEquals(0L, usage.usedBytes)
        assertNull(usage.find(DesktopStorageCategory.DATABASE))
        assertNull(usage.find(DesktopStorageCategory.IMAGES))
        assertNull(usage.find(DesktopStorageCategory.LOGS))
        assertTrue(usage.availableBytes > 0L)
        assertTrue(usage.volumeBytes >= usage.availableBytes)
        assertEquals(listOf(DesktopStorageCategory.AVAILABLE), usage.items.map { it.category })
    }

    @Test
    fun `a symlinked child is not followed out of the data root`() {
        val paths = paths()
        val outside = Files.createDirectories(directory.resolve("outside"))
        write(outside.resolve("huge.img"), 4096)
        Files.createSymbolicLink(paths.root.resolve("linked"), outside)

        val usage = desktopStorageUsage(paths)

        assertNull(usage.find(DesktopStorageCategory.OTHER)?.takeIf { it.bytes >= 4096L })
        assertEquals(0L, usage.usedBytes)
    }
}
