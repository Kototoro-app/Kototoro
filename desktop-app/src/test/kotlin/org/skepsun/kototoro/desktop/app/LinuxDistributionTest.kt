package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.db.DATABASE_VERSION
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Exercise the relocated Linux image with its own JVM, without developer runtime or compatibility paths. */
class LinuxDistributionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `relocated Linux image checks runtime restores sources and closes production windows`() {
        val original = Path.of(System.getProperty("kototoro.desktop.distribution"))
        val image = directory.resolve("中文 Linux portable image")
        Files.walk(original).use { paths -> paths.forEach { source ->
            val target = image.resolve(original.relativize(source))
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
            else Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
        } }
        val app = image.resolve("lib/app")
        val cfg = Files.readAllLines(app.resolve("Kototoro.cfg"))
        val classpath = cfg.filter { it.startsWith("app.classpath=") }.flatMap { line ->
            line.substringAfter("app.classpath=").split(java.io.File.pathSeparator)
        }.map { entry ->
            assertTrue(entry.startsWith("\$APPDIR/"), "Classpath must be relative to the image: $entry")
            val path = app.resolve(entry.removePrefix("\$APPDIR/")).normalize()
            assertTrue(path.startsWith(image), "Classpath escaped the image")
            assertTrue(Files.isRegularFile(path), "Missing packaged classpath: $path")
            path
        }
        val ownerIndex = classpath.indexOfFirst { it.fileName.toString().startsWith("000-AndroidCompat-") }
        val stubIndex = classpath.indexOfFirst { it.fileName.toString().startsWith("002-android-jar-") }
        assertTrue(ownerIndex >= 0 && stubIndex > ownerIndex, "AndroidCompat must precede the Android stubs")
        assertFalse(cfg.any { it.contains("--data-dir") || it.contains("--import") || it.contains(".gradle/") })
        assertTrue(Files.isRegularFile(app.resolve("resources/PROJECT_LICENSE")))
        assertTrue(Files.isRegularFile(app.resolve("resources/LINUX_PREVIEW.md")))
        assertTrue(Files.isRegularFile(app.resolve("resources/windows-runtime-pins.properties")))
        val launcher = image.resolve("bin/Kototoro")
        assertTrue(Files.isExecutable(launcher))
        val fixture = System.getProperty("kototoro.desktop.fixture.jar")
        val root = directory.resolve("中文 persistent data")
        for (mode in listOf("write", "read")) {
            val report = directory.resolve("$mode.properties")
            val arguments = mutableListOf(launcher.toString(), "--data-dir", root.toString(),
                "--check-runtime", report.toString())
            if (mode == "write") arguments.addAll(listOf("--import", fixture))
            assertEquals(0, execute(arguments, mode))
            val result = properties(report)
            assertEquals("ok", result.getProperty("status"))
            assertEquals("Linux", result.getProperty("platform"))
            assertEquals(DATABASE_VERSION.toString(), result.getProperty("schema"))
            assertEquals("ok", result.getProperty("backup"))
            assertEquals("ok", result.getProperty("image"))
            assertEquals("1", result.getProperty("sources"))
            assertEquals("unsupported", result.getProperty("browser"))
            assertEquals("unbundled", result.getProperty("player.embedded"))
            assertEquals("unsupported", result.getProperty("upscale.native"))
            assertTrue(Path.of(result.getProperty("java.home")).startsWith(image.resolve("lib/runtime")))
            Files.copy(report, reports().resolve("$mode.properties"), StandardCopyOption.REPLACE_EXISTING)
        }
        val failedReport = directory.resolve("failed.properties")
        val failureArguments = listOf(launcher.toString(), "--data-dir", directory.resolve("failed-data").toString(),
            "--import", directory.resolve("missing.jar").toString(), "--check-runtime", failedReport.toString())
        assertNotEquals(0, execute(failureArguments, "failure"))
        assertEquals("failed", properties(failedReport).getProperty("status"))
        val bundledJava = image.resolve("lib/runtime/bin/java")
        assertTrue(Files.isExecutable(bundledJava))
        val probeClasspath = (classpath.map(Path::toString) +
            System.getProperty("kototoro.desktop.probe.classes")).joinToString(java.io.File.pathSeparator)
        for (mode in listOf("source-browse", "window-early", "window")) {
            assertEquals(0, execute(listOf(bundledJava.toString(), "-Dfile.encoding=UTF-8",
                "-Dcompose.application.resources.dir=${app.resolve("resources")}", "-Dskiko.library.path=$app",
                "-cp", probeClasspath, DesktopDistributionWindowProbe::class.java.name,
                mode, directory.resolve("中文 $mode").toString(), fixture), mode))
        }
    }

    private fun reports(): Path = Files.createDirectories(Path.of("build/reports/linux-distribution"))

    private fun properties(path: Path): Properties = Properties().apply {
        Files.newBufferedReader(path).use { load(it) }
    }

    private fun execute(arguments: List<String>, name: String): Int {
        val log = reports().resolve("$name.log")
        val builder = ProcessBuilder(arguments).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile())
        for (key in listOf("JAVA_HOME", "CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            builder.environment().remove(key)
        }
        builder.environment()["PATH"] = "/usr/bin:/bin"
        builder.environment()["TMPDIR"] = Files.createDirectories(directory.resolve("process-temp")).toString()
        val process = builder.start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Packaged process timed out: ${Files.readString(log)}")
            val exitCode = process.exitValue()
            if (name != "failure") assertEquals(0, exitCode, Files.readString(log))
            return exitCode
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
