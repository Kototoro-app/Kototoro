package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopAppTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Android styled light and dark themes render covers and persist across an offline restart`() {
        val root = directory.resolve("中文 appearance restart")
        probe("appearance-write", root)
        probe("appearance-read", root)
    }

    @Test
    fun `Windows reader bookmarks restore another chapter page and pixel offset in a second offline process`() {
        val root = directory.resolve("中文 bookmark restart")
        probe("bookmarks-write", root)
        probe("bookmarks-read", root)
    }

    @Test
    fun `real Tsundoku APK installs converts and reads a novel over the live network`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("kototoro.dex.real.apks") != null, "no -PrealApkDirectory")
        probe("real-novel", directory.resolve("中文 real novel"), 180)
    }
    @Test
    fun `reader pages are upscaled by the installed ncnn programs and the choice persists`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("kototoro.ncnn.dir") != null, "no -PncnnDirectory")
        probe("upscale", directory.resolve("中文 upscale"), 300)
    }
    @Test
    fun `an anime episode plays in the window switches quality continues and records history`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("kototoro.libmpv.dir") != null, "no -PlibmpvDirectory")
        probe("video", directory.resolve("中文 video"), 240)
    }
    @Test
    fun `a Cloudstream plugin imports browses opens an episode for the player and survives a restart`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.isRegularFile(Path.of(System.getProperty("kototoro.desktop.cloudstream.fixture"))),
            "Android SDK with d8 is required to build the Cloudstream fixture plugin")
        val root = directory.resolve("中文 cloudstream")
        probe("cloudstream-write", root)
        probe("cloudstream-read", root)
    }
    @Test
    fun `real Aniyomi APK installs converts' and reaches playable streams over the live network`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("kototoro.dex.real.apks") != null, "no -PrealApkDirectory")
        probe("real-anime", directory.resolve("中文 real anime"), 240)
    }
    @Test
    fun `kototoro kotatsu and UMA plugins browse page read and favourite through the UI and survive a restart`() {
        val root = directory.resolve("中文 parser plugins")
        probe("parsers-write", root, 90)
        probe("parsers-read", root)
    }

    @Test
    fun `shared novel directory locates grouped chapters and keeps original indices in wide and narrow windows`() {
        probe("novel-directory", directory.resolve("中文 novel directory wide"))
        probe("novel-directory-narrow", directory.resolve("中文 novel directory narrow"))
    }

    @Test
    fun `tablet layout and immersive reader panels preserve progress in wide and narrow windows`() {
        probe("tablet", directory.resolve("中文 tablet wide"))
        probe("tablet-narrow", directory.resolve("中文 tablet narrow"))
        probe("tablet-boundary", directory.resolve("中文 tablet expanded boundary"))
        probe("tablet-boundary-compact", directory.resolve("中文 tablet medium boundary"))
    }

    @Test
    fun `Windows repository add install update and app-owned extensions persist in another offline process`() {
        val root = directory.resolve("中文 repository install")
        probe("repositories-write", root)
        probe("repositories-read", root)
    }

    @Test
    fun `Windows backup preview cancel merge and exported library survive another process without installed extensions`() {
        val root = directory.resolve("中文 backup restore")
        probe("backups-write", root)
        probe("backups-read", root)
    }

    @Test
    fun `Windows rendered source details reader settings and shared state survive a second process`() {
        val root = directory.resolve("中文 Windows space")
        probe("write", root)
        probe("read", root)
    }

    @Test
    fun `Windows chapter downloads pause resume and render after restart with page and image sources offline`() {
        val root = directory.resolve("中文 chapter downloads")
        probe("downloads-write", root)
        probe("downloads-read", root)
    }

    @Test
    fun `Windows filters preserve native types defaults pagination failure and source isolation`() {
        probe("filters", directory.resolve("中文 native filters"))
        probe("filters-narrow", directory.resolve("中文 narrow native filters"))
    }

    @Test
    fun `Windows covers use extension requests persist across offline restart and survive cancellation`() {
        val root = directory.resolve("中文 cover cache")
        probe("cover-write", root)
        probe("cover-read", root)
    }

    @Test
    fun `Windows automatic chapter reading preserves failures branches RTL boundaries and restart settings`() {
        val root = directory.resolve("中文 automatic chapters")
        probe("reader-chapters-write", root)
        probe("reader-chapters-read", root)
    }

    @Test
    fun `long Windows pages use region tiles in paged and continuous readers without new image requests`() {
        probe("reader-tiles", directory.resolve("中文 long region reader"))
    }

    @Test
    fun `large Windows WebP pages render bounded pixels and restore geometry offline in another process`() {
        val root = directory.resolve("中文 bounded WebP reader")
        probe("reader-bounded-write", root)
        probe("reader-bounded-read", root)
    }

    @Test
    fun `continuous Windows reader loads visible pages retries errors and restores pixel progress in a second process`() {
        val root = directory.resolve("中文 continuous reader")
        probe("reader-scroll-write", root)
        probe("reader-scroll-read", root)
    }

    @Test
    fun `paged reader prefetches neighbours turns by drag tap and wheel with a persisted animation`() {
        val root = directory.resolve("中文 reader gestures")
        probe("reader-gestures-write", root)
        probe("reader-gestures-read", root)
    }
    @Test
    fun `Windows reader camera handles mouse touch fit overflow and persisted settings without advancing progress`() {
        val root = directory.resolve("中文 reader camera")
        probe("reader-camera-write", root)
        probe("reader-camera-read", root)
    }

    @Test
    fun `shared reader scenes handle spreads direction keyboard errors chapters and persisted progress`() {
        val root = directory.resolve("中文 paged reader")
        probe("reader-write", root)
        probe("reader-read", root)
    }

    @Test
    fun `Windows browser UI opens an interactive page synchronizes cookies and keeps its live session`() {
        assumeTrue(Files.isRegularFile(Path.of(System.getProperty("kototoro.desktop.bridge.exe"))),
            "Windows WebView2 bridge must be built")
        probe("browser", directory.resolve("中文 interactive browser"))
    }

    @Test
    fun `actual extension challenge prompts resume cancel and close through the Windows UI`() {
        assumeTrue(Files.isRegularFile(Path.of(System.getProperty("kototoro.desktop.bridge.exe"))),
            "Windows WebView2 bridge must be built")
        probe("challenge", directory.resolve("中文 source challenge"))
    }

    @Test
    fun `production Windows window closes during startup and after import and releases storage`() {
        probe("window-early", directory.resolve("中文 early close"))
        probe("window", directory.resolve("中文 normal window"))
    }

    private fun probe(mode: String, root: Path, seconds: Long = 60) {
        val output = Files.createDirectories(Path.of("build/reports/desktop-smoke")).resolve("$mode.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Xms32m", "-Xmx768m",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            *System.getProperties().stringPropertyNames()
                .filter { it == "kototoro.dex.real.apks" || it == "kototoro.desktop.cloudstream.fixture" || it == "kototoro.libmpv.dir" || it == "kototoro.desktop.anime.fixture.jar" || it == "kototoro.ncnn.dir" ||
                    it.startsWith("kototoro.real.") }
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
