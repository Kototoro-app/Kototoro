package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class StaleExtensionCleanupTest {

    @Test
    fun `stale build of the same package is removed`(@TempDir tempDir: Path) {
        val pluginDir = tempDir.toFile()
        val keep = pluginDir.withFile("org.skepsun.kototoro.parsers.v703.jar")
        val stale = pluginDir.withFile("org.skepsun.kototoro.parsers.jar")
        val sibling = pluginDir.withFile("org.skepsun.kototoro.parsers.extra.jar")
        val unrelated = pluginDir.withFile("uma.jar")

        val removed = deleteStaleExtensionFiles(pluginDir, keep, "org.skepsun.kototoro.parsers")

        assertEquals(listOf(stale.name), removed.map { it.name })
        assertFalse(stale.exists())
        assertTrue(keep.exists())
        assertTrue(sibling.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `read only stale build is removed`(@TempDir tempDir: Path) {
        val pluginDir = tempDir.toFile()
        val keep = pluginDir.withFile("org.skepsun.kototoro.parsers.v703.jar")
        val stale = pluginDir.withFile("org.skepsun.kototoro.parsers-134.jar")
        check(stale.setReadOnly()) { "Cannot make test file read-only" }

        val removed = deleteStaleExtensionFiles(pluginDir, keep, "org.skepsun.kototoro.parsers")

        assertEquals(listOf(stale.name), removed.map { it.name })
        assertFalse(stale.exists())
    }

    @Test
    fun `package without a version marker keeps its own file`(@TempDir tempDir: Path) {
        val pluginDir = tempDir.toFile()
        val keep = pluginDir.withFile("uma.jar")
        val other = pluginDir.withFile("kotatsu-parsers-redo.jar")

        val removed = deleteStaleExtensionFiles(pluginDir, keep, "uma")

        assertTrue(removed.isEmpty())
        assertTrue(keep.exists())
        assertTrue(other.exists())
    }

    private fun File.withFile(name: String): File = File(this, name).apply { writeText(name) }
}
