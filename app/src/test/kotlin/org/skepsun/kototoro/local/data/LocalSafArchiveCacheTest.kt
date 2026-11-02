package org.skepsun.kototoro.local.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LocalSafArchiveCacheTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `indexing multiple archives does not leave complete copies in cache`() = runTest {
        val original = archive()
        val originalBytes = original.readBytes()
        repeat(3) { index ->
            useArchive(original, "$index.cbz") { copy ->
                ZipFile(copy).use { zip ->
                    assertEquals("page", zip.getInputStream(zip.getEntry("1.jpg")).readBytes().decodeToString())
                }
            }
            assertTrue(copies().isEmpty(), "Completed indexing must release the archive copy")
        }
        assertArrayEquals(originalBytes, original.readBytes())
    }

    @Test
    fun `failed parsing releases its copy`() = runTest {
        val original = archive()
        val failure = runCatching { useArchive(original) { error("Invalid index") } }.exceptionOrNull()
        assertEquals("Invalid index", failure?.message)
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `directory removal clears legacy copies but retains an active parser file`() = runTest {
        val legacy = File(directory, "local_saf/old.cbz").apply { parentFile?.mkdirs(); writeText("old") }
        val original = archive()
        useArchive(original) { active ->
            LocalSafArchiveCache.clearUnused(directory)
            assertTrue(active.isFile)
            assertTrue(!legacy.exists(), "Removing a directory must reclaim inactive legacy copies")
            ZipFile(active).use { assertEquals(1, it.size()) }
        }
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `concurrent scans of one archive retain their copy until each scan completes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val original = archive()
        val first = async {
            useArchive(original) { copy ->
                entered.complete(Unit)
                finishFirst.await()
                assertTrue(copy.isFile)
            }
        }
        entered.await()
        val second = async { useArchive(original) { copy -> assertTrue(copy.isFile) } }
        LocalSafArchiveCache.clearUnused(directory)
        finishFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `cancelling a directory scan also releases its active copy`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val original = archive()
        val scan = async {
            useArchive(original) {
                entered.complete(Unit)
                neverFinishes.await()
            }
        }
        entered.await()
        scan.cancelAndJoin()
        assertTrue(copies().isEmpty())
        assertTrue(original.isFile)
    }

    @Test
    fun `large archives are copied one at a time`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val original = archive()
        val first = async {
            useArchive(original, "first.cbz", largeThresholdBytes = 0L) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = async { useArchive(original, "second.cbz", largeThresholdBytes = 0L) { secondEntered.complete(Unit) } }
        assertTrue(!secondEntered.isCompleted, "A second large copy must wait for the first one to be released")
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `an archive is not copied when it cannot fit in the remaining space`() = runTest {
        val original = archive()
        val failure = runCatching {
            useArchive(original, availableBytes = { 1024L }) { error("Must not reach the copy") }
        }.exceptionOrNull()
        assertTrue(failure is IOException, "Expected an explicit storage failure, got $failure")
        assertTrue(failure?.message.orEmpty().contains("free space"))
        assertTrue(copies().isEmpty())
        assertTrue(original.isFile)
    }

    private suspend fun <T> useArchive(
        source: File,
        fileName: String = "source.cbz",
        availableBytes: (() -> Long)? = null,
        largeThresholdBytes: Long = 96L * 1024 * 1024,
        action: suspend (File) -> T,
    ): T = LocalSafArchiveCache.withArchive(
        cacheDir = directory,
        fileName = fileName,
        sourceLength = source.length(),
        sourceModified = source.lastModified(),
        availableBytes = availableBytes,
        largeThresholdBytes = largeThresholdBytes,
        openInput = source::inputStream,
        action = action,
    )

    private fun copies() = File(directory, "local_saf").listFiles().orEmpty().toList()

    private fun archive() = File(directory, "original.cbz").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("1.jpg"))
            zip.write("page".toByteArray())
            zip.closeEntry()
        }
    }
}
