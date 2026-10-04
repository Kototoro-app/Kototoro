package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopWebViewProviderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual platform WebView preserves requests cookies main callbacks ordering errors and stop recovery`() {
        val log = Files.createDirectories(Path.of("build/reports/browser-provider")).resolve("provider.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", System.getProperty("kototoro.compat.test.classpath"),
            DesktopWebViewProviderProbe::class.java.name, directory.resolve("中文 provider space").toString(),
            System.getProperty("kototoro.compat.bridge.exe"))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Provider probe timed out")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("WEBVIEW_PROVIDER_OK"), output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
