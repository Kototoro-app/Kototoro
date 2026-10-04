package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MihonBridgeLocationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `packaged bridge is discovered relative to Compose resources`() = properties(null) {
        assertEquals(bridge().toFile(), MihonDesktopPlatform.defaultBridgeExecutable())
    }

    @Test
    fun `explicit bridge overrides the packaged location`() {
        val explicit = Files.createFile(directory.resolve("explicit.exe"))
        properties(explicit.toString()) { assertEquals(explicit.toFile(), MihonDesktopPlatform.defaultBridgeExecutable()) }
    }

    @Test
    fun `invalid explicit path stays unavailable and never silently chooses a different bridge`() =
        properties(directory.resolve("missing.exe").toString()) {
            assertNull(MihonDesktopPlatform.defaultBridgeExecutable())
        }

    private fun bridge(): Path {
        val browser = Files.createDirectories(directory.resolve("browser"))
        val path = browser.resolve("kototoro-webview-bridge.exe")
        if (!Files.exists(path)) Files.createFile(path)
        return path
    }

    private fun properties(explicit: String?, block: () -> Unit) {
        val keys = listOf("kototoro.compat.bridge.exe", "compose.application.resources.dir")
        val previous = keys.associateWith(System::getProperty)
        try {
            if (explicit == null) System.clearProperty(keys[0]) else System.setProperty(keys[0], explicit)
            System.setProperty(keys[1], directory.toString())
            bridge()
            block()
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }
}
