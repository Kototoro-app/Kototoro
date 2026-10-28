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

/** Validate the actual relocated .app, including helper mode bits and its bundled JVM. */
class MacosDistributionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `relocated macOS image runs WKWebView checks storage and renders video with its own JVM`() {
        val original = Path.of(System.getProperty("kototoro.desktop.distribution"))
        val image = directory.resolve("中文 macOS preview/Kototoro.app")
        Files.walk(original).use { paths -> paths.forEach { source ->
            val target = image.resolve(original.relativize(source))
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
            else Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
        } }
        val contents = image.resolve("Contents")
        val launcher = contents.resolve("MacOS/Kototoro")
        val resources = contents.resolve("app/resources")
        assertTrue(Files.isExecutable(launcher))
        assertTrue(Files.isExecutable(resources.resolve("browser/kototoro-webview-bridge")), "WKWebView helper lost its executable bit")
        assertTrue(Files.isExecutable(contents.resolve("runtime/Contents/Home/bin/java")))
        assertTrue(Files.isRegularFile(resources.resolve("MACOS_PREVIEW.md")))
        assertTrue(Files.isRegularFile(resources.resolve("PROJECT_LICENSE")))
        val playback = resources.resolve("mpv")
        assertTrue(Files.isRegularFile(playback.resolve("libmpv.2.dylib")))
        assertTrue(Files.isRegularFile(playback.resolve("runtime-manifest.json")))
        assertTrue(Files.isRegularFile(playback.resolve("licenses/mpv-LICENSE.LGPL")))
        // CI hosts without an accelerated OpenGL device check the software render API instead; locally it stays GPU.
        val renderer = System.getProperty("kototoro.desktop.distribution.renderer") ?: "opengl"
        require(renderer in setOf("opengl", "software")) { "Unknown distribution renderer: $renderer" }
        val reports = Files.createDirectories(Path.of("build/reports/macos-distribution"))
        val root = directory.resolve("中文 persistent data")
        for (mode in listOf("write", "read")) {
            val report = directory.resolve("$mode.properties")
            val log = reports.resolve("$mode.log")
            val arguments = mutableListOf(launcher.toString(), "--data-dir", root.toString(),
                "--check-runtime", report.toString())
            if (mode == "write") arguments.addAll(listOf("--import", System.getProperty("kototoro.desktop.fixture.jar")))
            val builder = ProcessBuilder(arguments).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile())
            for (key in listOf("JAVA_HOME", "CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
                    "KOTOTORO_LIBMPV", "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH")) builder.environment().remove(key)
            builder.environment()["PATH"] = "/usr/bin:/bin"
            if (renderer == "software") builder.environment()["JAVA_TOOL_OPTIONS"] = "-Dkototoro.mpv.renderer=software"
            val process = builder.start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Packaged launcher did not exit: $log")
                assertEquals(0, process.exitValue(), Files.readString(log))
            } finally { if (process.isAlive) process.destroyForcibly() }
            val result = Properties().apply { Files.newBufferedReader(report).use { load(it) } }
            assertEquals("ok", result.getProperty("status"))
            assertEquals("macOS", result.getProperty("platform"))
            assertEquals("macos_app", result.getProperty("updates.installation"))
            assertTrue(result.getProperty("updates.target").startsWith("macos-"))
            assertEquals(DATABASE_VERSION.toString(), result.getProperty("schema"))
            assertEquals("1", result.getProperty("sources"))
            assertEquals("ok", result.getProperty("backup"))
            assertEquals("ok", result.getProperty("image"))
            assertEquals("ok", result.getProperty("browser"))
            assertEquals("WKWebView", result.getProperty("browser.engine"))
            assertEquals("render-api-$renderer", result.getProperty("player.embedded"))
            assertEquals("ok", result.getProperty("player"))
            assertEquals("ok", result.getProperty("player.render"))
            assertTrue(Path.of(result.getProperty("player.library")).toRealPath().startsWith(playback.toRealPath()),
                "Playback must use the relocated bundle, not a library from the developer machine")
            assertTrue(Path.of(result.getProperty("java.home")).toRealPath()
                .startsWith(contents.resolve("runtime").toRealPath()))
            Files.copy(report, reports.resolve("$mode.properties"), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
