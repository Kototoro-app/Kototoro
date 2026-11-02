package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The replace-over-open-file cases describe the Android file system: a rename publishes the new inode while a
 * reader keeps streaming the old one. Windows refuses to move over a file another handle still has open, and the
 * tests below open the old file on purpose, so they only run where that rename semantics holds.
 */
class SyncedExtensionFileTest {

    @TempDir
    lateinit var directory: File

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `reader loading old jar never sees partially replaced bytes`() {
        val target = File(directory, "source.jar").apply { writeText("old jar") }
        target.inputStream().use { existingReader ->
            val replacement = "new restored jar".toByteArray()
            writeSyncedExtension(target, replacement, replacement.size.toLong())
            assertEquals("old jar", existingReader.readBytes().decodeToString())
            assertArrayEquals(replacement, target.readBytes())
        }
        assertEquals(listOf("source.jar"), directory.list()?.toList())
    }

    @Test
    fun `incomplete payload cannot overwrite installed extension`() {
        val target = File(directory, "source.jar").apply { writeText("installed") }
        assertThrows(IllegalArgumentException::class.java) {
            writeSyncedExtension(target, byteArrayOf(1), expectedSize = 20)
        }
        assertEquals("installed", target.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `managed apk is replaced atomically and published read only`() {
        val target = File(directory, "source.apk").apply { writeText("installed apk"); setReadOnly() }
        target.inputStream().use { existingReader ->
            val replacement = "restored apk".toByteArray()
            writeSyncedExtension(target, replacement, replacement.size.toLong(), readOnly = true)
            assertEquals("installed apk", existingReader.readBytes().decodeToString())
            assertArrayEquals(replacement, target.readBytes())
            assertFalse(target.canWrite())
        }
        assertEquals(listOf("source.apk"), directory.list()?.toList())
    }
}
