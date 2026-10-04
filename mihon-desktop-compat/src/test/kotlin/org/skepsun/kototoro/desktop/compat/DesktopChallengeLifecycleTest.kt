package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopChallengeLifecycleTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `an abandoned visible challenge times out and releases its profile`() = probe("timeout")

    @Test
    fun `cancelling a queued HTTP call preserves the currently active challenge`() = probe("queued-cancel")

    private fun probe(mode: String) {
        assumeTrue(Files.isRegularFile(Path.of(System.getProperty("kototoro.compat.bridge.exe"))),
            "Windows WebView2 bridge must be built")
        val output = Files.createDirectories(Path.of("build/reports/browser-challenges")).resolve("$mode.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", System.getProperty("kototoro.compat.test.classpath"),
            DesktopChallengeLifecycleProbe::class.java.name, mode, directory.toString(),
            System.getProperty("kototoro.compat.bridge.exe"))
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Challenge probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertTrue(Files.readString(output).contains("CHALLENGE_LIFECYCLE_OK=$mode"), Files.readString(output))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
