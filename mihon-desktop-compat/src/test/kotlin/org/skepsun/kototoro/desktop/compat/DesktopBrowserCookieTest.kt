package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopBrowserCookieTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual SDK Android CookieManager browser and OkHttp share attributes deletions and persisted tokens`() {
        for (mode in listOf("write", "read", "sdk-clear", "empty", "write", "clear", "empty")) {
            val output = Files.createDirectories(Path.of("build/reports/browser-cookies")).resolve("$mode.log")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", System.getProperty("kototoro.compat.test.classpath"),
                DesktopBrowserCookieProbe::class.java.name, mode, directory.resolve("中文 Cookie space").toString(),
                System.getProperty("kototoro.compat.bridge.exe"))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cookie probe timed out")
                assertEquals(0, process.exitValue(), Files.readString(output))
                assertTrue(Files.readString(output).contains("COOKIE_BRIDGE_OK=$mode"))
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }
}
