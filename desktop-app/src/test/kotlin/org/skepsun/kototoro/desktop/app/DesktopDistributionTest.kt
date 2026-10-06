package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Opt-in integration gate: moved image, native EXE, bundled JVM, real bridge and production Window. */
class DesktopDistributionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `moved Windows image initializes without developer runtime and closes actual native windows`() {
        val original = Path.of(System.getProperty("kototoro.desktop.distribution"))
        // CJK and Thai never share one ANSI code page, so the launcher must cope with paths outside it on any machine.
        val image = directory.resolve("中文 ไทย Windows portable image")
        Files.walk(original).use { paths -> paths.forEach { source ->
            val target = image.resolve(original.relativize(source))
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        } }
        val app = image.resolve("app")
        val cfg = Files.readAllLines(app.resolve("Kototoro.cfg"))
        val classpath = cfg.filter { it.startsWith("app.classpath=") }.map {
            assertTrue(it.startsWith("app.classpath=\$APPDIR\\"), "Classpath must be relative to the installed image")
            val name = it.substringAfter("\$APPDIR\\")
            val path = app.resolve(name).normalize()
            assertTrue(path.startsWith(app), "Classpath escaped the installed image")
            assertTrue(Files.isRegularFile(path), "Missing packaged classpath: $name")
            path
        }
        assertTrue(classpath.indexOfFirst { it.fileName.toString().startsWith("000-AndroidCompat-") } <
            classpath.indexOfFirst { it.fileName.toString().startsWith("002-android-jar-") })
        assertFalse(cfg.any { it.contains("kt-compat36") || it.contains("gradle-home") ||
            it.contains("--import") || it.contains("--data-dir") })
        val fixture = Path.of(System.getProperty("kototoro.desktop.fixture.jar"))
        val root = directory.resolve("中文 ไทย native EXE data")
        val report = directory.resolve("check.properties")
        execute(listOf(image.resolve("Kototoro.exe").toString(), "--data-dir", root.toString(), "--import", fixture.toString(),
            "--check-runtime", report.toString()), "native-exe")
        val result = Properties().apply { Files.newBufferedReader(report).use { load(it) } }
        assertEquals("ok", result.getProperty("status"))
        assertEquals("84", result.getProperty("schema"))
        assertEquals("ok", result.getProperty("backup"))
        assertEquals("ok", result.getProperty("image"))
        assertEquals("ok", result.getProperty("browser"))
        assertEquals("1", result.getProperty("sources"))
        assertTrue(Path.of(result.getProperty("java.home")).startsWith(image.resolve("runtime")))
        assertTrue(Path.of(result.getProperty("browser.executable")).startsWith(app.resolve("resources")))
        // Playback and page super-resolution run from the components shipped in the image, not from the user's setup.
        assertEquals("ok", result.getProperty("player"))
        assertTrue(Path.of(result.getProperty("player.library")).startsWith(app.resolve("resources")))
        assertEquals("ok", result.getProperty("upscale"))
        listOf("realcugan", "realesrgan").forEach {
            assertTrue(Path.of(result.getProperty("upscale.$it")).startsWith(app.resolve("resources")), it)
        }
        assertTrue(Files.isRegularFile(app.resolve("resources/THIRD_PARTY_PLAYBACK.md")))
        awaitRelease(root)
        // A failing check must end the native process instead of leaving the launcher hung.
        val failedReport = directory.resolve("failed-check.properties")
        val failedExit = execute(listOf(image.resolve("Kototoro.exe").toString(), "--data-dir", directory.resolve("中文 ไทย failed data").toString(),
            "--import", directory.resolve("missing.jar").toString(), "--check-runtime", failedReport.toString()), "native-exe-failure",
            expectSuccess = false)
        assertNotEquals(0, failedExit)
        assertEquals("failed", Properties().apply { Files.newBufferedReader(failedReport).use { load(it) } }.getProperty("status"))
        val bundledJava = image.resolve("runtime/bin/java.exe")
        assertTrue(Files.isRegularFile(bundledJava))
        val probes = System.getProperty("kototoro.desktop.probe.classes")
        for (mode in listOf("source-browse", "window-early", "window")) {
            execute(listOf(bundledJava.toString(), "-Dfile.encoding=UTF-8", "-Dcompose.application.resources.dir=${app.resolve("resources")}",
                "-Dskiko.library.path=$app", "-cp", classpath.joinToString(";") + ";" + probes,
                DesktopDistributionWindowProbe::class.java.name, mode, directory.resolve("中文 $mode").toString(),
                fixture.toString()), mode)
        }
        Files.createDirectories(Path.of("build/reports/windows-distribution"))
        Files.copy(report, Path.of("build/reports/windows-distribution/runtime-check.properties"),
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun execute(arguments: List<String>, name: String, expectSuccess: Boolean = true): Int {
        val log = Files.createDirectories(Path.of("build/reports/windows-distribution")).resolve("$name.log")
        val builder = ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        val processTemporary = Files.createDirectories(directory.resolve("process-temp"))
        builder.environment()["TEMP"] = processTemporary.toString()
        builder.environment()["TMP"] = processTemporary.toString()
        for (key in listOf("JAVA_HOME", "CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            builder.environment().remove(key)
        }
        builder.environment()["PATH"] = Path.of(System.getenv("SystemRoot"), "System32").toString()
        val process = builder.start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Packaged process timed out: ${Files.readString(log)}")
            if (expectSuccess) assertEquals(0, process.exitValue(), Files.readString(log))
            return process.exitValue()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun awaitRelease(root: Path) {
        val released = root.resolveSibling("${root.fileName}-released")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            try { Files.move(root, released); Files.move(released, root); return }
            catch (error: IOException) { if (System.nanoTime() >= deadline) throw error; Thread.sleep(50) }
        }
    }
}
