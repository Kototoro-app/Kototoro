package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts one UI probe in its own JVM. AndroidCompat's process-global main Looper cannot restart, so every probe is a
 * separate process, and the classpath/fixture paths come from the Gradle test setup.
 */
internal object DesktopProbes {
    val javaExecutable: String
        get() = Path.of(System.getProperty("java.home"), "bin",
            if (org.skepsun.kototoro.desktop.runtime.DesktopPlatform.current ==
                org.skepsun.kototoro.desktop.runtime.DesktopPlatform.WINDOWS) "java.exe" else "java").toString()

    fun run(mode: String, root: Path, seconds: Long = 60) {
        val output = Files.createDirectories(Path.of("build/reports/desktop-smoke")).resolve("$mode.log")
        val process = ProcessBuilder(javaExecutable,
            "-Xms32m", "-Xmx768m",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            // `runTest` gives a UI body 60 s by default, which a wide probe on a busy machine can cross even when
            // nothing is wrong (`UncompletedCoroutinesError`). The wait below is the probe's real limit.
            "-Dkotlinx.coroutines.test.default_timeout=10m",
            *System.getProperties().stringPropertyNames()
                .filter { it == "kototoro.dex.real.apks" || it == "kototoro.desktop.cloudstream.fixture" || it == "kototoro.libmpv.dir" || it == "kototoro.desktop.anime.fixture.jar" || it == "kototoro.ncnn.dir" ||
                    it == "kototoro.desktop.skip.anime4k.pages" ||
                    it == "kototoro.desktop.locale" || it.startsWith("kototoro.real.") ||
                    // The fixture sources read fixture.* switches (tall pages, delays, authored failures).
                    it.startsWith("fixture.") }
                .map { "-D$it=${System.getProperty(it)}" }.toTypedArray(),
            "-cp", System.getProperty("kototoro.desktop.test.classpath"),
            DesktopAppProbe::class.java.name, mode, root.toString(),
            System.getProperty("kototoro.desktop.fixture.jar"),
            Path.of("build/reports/desktop-smoke").toAbsolutePath().toString(),
            System.getProperty("kototoro.desktop.bridge.exe"), System.getProperty("kototoro.desktop.challenge.fixture.jar"),
            System.getProperty("kototoro.desktop.reader.fixture.jar"), System.getProperty("kototoro.desktop.cover.fixture.jar"),
            System.getProperty("kototoro.desktop.filter.fixture.jar"), System.getProperty("kototoro.desktop.parser.fixtures"))
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "UI process did not finish: ${outputText(output)}")
            val log = outputText(output)
            assertEquals(0, process.exitValue(), log)
            assertTrue(log.contains("DESKTOP_UI_OK=$mode"), log)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    // HotSpot native allocation errors can use the Windows locale even with UTF-8 stdout configured.
    private fun outputText(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)
}
