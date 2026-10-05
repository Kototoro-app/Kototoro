package org.skepsun.kototoro.desktop.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Mihon's default Cloudflare solver on a real WebView2 (separate JVM: the SDK platform owns process-wide state). */
class DesktopClearanceSolveTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a managed challenge is solved in the hidden browser and the request retried`() = probe("managed")

    @Test
    fun `a lasting checkbox challenge falls back to the manual prompt`() = probe("interactive")

    private fun probe(mode: String) {
        assumeTrue(Files.isRegularFile(Path.of(System.getProperty("kototoro.compat.bridge.exe"))),
            "Windows WebView2 bridge must be built")
        val output = Files.createDirectories(Path.of("build/reports/clearance-solve")).resolve("$mode.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", System.getProperty("kototoro.compat.test.classpath"),
            DesktopClearanceSolveProbe::class.java.name, mode, directory.toString(),
            System.getProperty("kototoro.compat.bridge.exe"))
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Clearance probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertTrue(Files.readString(output).contains("CLEARANCE_SOLVE_OK=$mode"), Files.readString(output))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
