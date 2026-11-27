package org.skepsun.kototoro.core.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveEntrySizeGuardTest {

    @Test
    fun `an entry below the limit is returned unchanged`() {
        val payload = "legado book source".repeat(1000).toByteArray()

        val bytes = ByteArrayInputStream(payload).readEntryBytes("source.json")

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `an entry at exactly the limit is returned unchanged`() {
        val payload = ByteArray(64) { it.toByte() }

        val bytes = ByteArrayInputStream(payload).readEntryBytes("source.json", maxBytes = 64)

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `an entry past the limit is refused with its path and the limit`() {
        val payload = ByteArray(65)

        val error = assertThrows(IOException::class.java) {
            ByteArrayInputStream(payload).readEntryBytes("huge.json", maxBytes = 64)
        }

        assertEquals("Archive entry too large: huge.json (limit 64 bytes)", error.message)
    }

    @Test
    fun `copy refuses as soon as the limit would be crossed instead of buffering it`() {
        val output = ByteArrayOutputStream()

        val error = assertThrows(IOException::class.java) {
            ByteArrayInputStream(ByteArray(1_000_000)).copyEntryTo(output, "bomb.txt", maxBytes = 8192)
        }

        assertEquals("Archive entry too large: bomb.txt (limit 8192 bytes)", error.message)
        // At most one chunk was written before the limit was crossed, never the whole entry.
        assertEquals(8192, output.size())
    }

    @Test
    fun `a declared size past the limit is refused before any data is read`() {
        val error = assertThrows(IOException::class.java) {
            checkArchiveEntryDeclaredSize("declared.json", declaredSize = 128, maxBytes = 64)
        }

        assertEquals("Archive entry too large: declared.json (limit 64 bytes)", error.message)
    }

    @Test
    fun `an archive entry read through ZipInputStream keeps its content and is bounded`() {
        val small = zipOf("small.json" to "{}".repeat(100).toByteArray(), "big.bin" to ByteArray(8192))
        val fromZip = readEntryFromZip(small, "small.json", maxBytes = 4096)
        assertArrayEquals("{}".repeat(100).toByteArray(), fromZip)

        val error = assertThrows(IOException::class.java) {
            readEntryFromZip(small, "big.bin", maxBytes = 4096)
        }
        assertEquals("Archive entry too large: big.bin (limit 4096 bytes)", error.message)
    }

    /** The call-site shape: pick the requested entry, then bound the read. */
    private fun readEntryFromZip(archive: ByteArray, path: String, maxBytes: Long): ByteArray? =
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { entry ->
                checkArchiveEntryDeclaredSize(entry.name, entry.size, maxBytes)
                zip.readEntryBytes(entry.name, maxBytes)
            }
        }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

}
