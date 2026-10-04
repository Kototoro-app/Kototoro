package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopDataPathsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Windows default selects LocalAppData without creating files`() {
        val local = directory.resolve("Local App Data 中文")
        val selected = DesktopDataPaths.defaultRoot(mapOf("LOCALAPPDATA" to local.toString()),
            directory.toString(), "Windows 11")
        assertEquals(local.resolve("Kototoro"), selected)
        assertFalse(Files.exists(local))
    }

    @Test
    fun `missing LocalAppData uses persistent user home and generic JVM fallback stays isolated`() {
        assertEquals(directory.resolve("AppData/Local/Kototoro"),
            DesktopDataPaths.defaultRoot(emptyMap(), directory.toString(), "Windows 10"))
        assertEquals(directory.resolve(".kototoro"),
            DesktopDataPaths.defaultRoot(emptyMap(), directory.toString(), "Linux"))
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDataPaths.defaultRoot(mapOf("LOCALAPPDATA" to "relative"), directory.toString(), "Windows 11")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDataPaths.defaultRoot(emptyMap(), "relative", "Windows 11")
        }
    }

    @Test
    fun `explicit Unicode space directory is canonical and existing file is not overwritten`() {
        val root = directory.resolve("桌面 data/../桌面 data")
        val paths = DesktopDataPaths.create(root)
        assertTrue(paths.root.isAbsolute)
        assertEquals(root.toAbsolutePath().normalize().toRealPath(), paths.root)
        assertEquals(paths.root.resolve("kototoro.db"), paths.database)
        assertEquals(paths.root.resolve("images"), paths.images)
        val file = directory.resolve("existing-file")
        Files.writeString(file, "preserve")
        assertThrows(IOException::class.java) { DesktopDataPaths.create(file) }
        assertEquals("preserve", Files.readString(file))
    }
}
