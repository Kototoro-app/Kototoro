package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
    fun `Windows incognito reader records nothing while a normal read does and stores Android's adult rule`() {
        probe("incognito", directory.resolve("中文 incognito"), 120)
    }

    @Test
    fun `a read stop shows up in the statistics screen and clearing asks first`() {
        probe("stats", directory.resolve("中文 stats"), 150)
    }

    @Test
    fun `the storage hub shows the data root's real sizes and reaches the proxy page`() {
        probe("storage", directory.resolve("中文 storage"), 150)
    }

    @Test
    fun `the data cleanup page clears what this data root holds and arms Android's auto sweep`() {
        probe("cleanup", directory.resolve("中文 cleanup"), 150)
    }

    @Test
    fun `the proxy page routes the real client and its test row reports Android's result`() {
        probe("proxy", directory.resolve("中文 proxy"), 150)
    }

    @Test
    fun `the playback page writes the backdrop decoder and both overlay opacities`() {
        probe("playback", directory.resolve("中文 playback"), 150)
    }

    @Test
    fun `the changelog page lists the channel's published notes and returns to the about card`() {
        assumeTrue(org.skepsun.kototoro.desktop.runtime.DesktopUpdateTarget.current() != null,
            "no package updater on this platform")
        probe("changelog", directory.resolve("中文 changelog"), 180)
    }

    @Test
    fun `the about card switches the unstable update channel and lists Android's support links`() {
        probe("about", directory.resolve("中文 about"), 180)
    }

    @Test
    fun `a real crash writes a report the crash log page lists opens and deletes`() {
        probe("crashlogs", directory.resolve("崩溃 日志"), 180)
    }

    @Test
    fun `replacement rules are edited previewed toggled and reimported from the reading settings page`() {
        probe("replacerules", directory.resolve("替换 规则"), 180)
    }

    @Test
    fun `dictionary rules are seeded from the shipped asset stored toggled and reimported`() {
        probe("dictionaryrules", directory.resolve("词典 规则"), 180)
    }

    @Test
    fun `download settings edit the speed and retry numbers the offline queue reads`() {
        probe("downloadsettings", directory.resolve("下载 设置"), 180)
    }

    @Test
    fun `navigation configuration reorders adds and removes the rail destinations`() {
        probe("navconfig", directory.resolve("导航 配置"), 180)
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
        probe("parsers-write", root, 150)
        probe("parsers-read", root, 150)
    }

    @Test
    fun `source settings reorder the parser deduplication priority and the next start serves the shared source`() {
        val root = directory.resolve("中文 jar priority")
        probe("jarpriority-write", root, 150)
        probe("jarpriority-read", root, 150)
    }

    @Test
    fun `the source reorder editor stores Android's manual order and the rail follows it after a restart`() {
        val root = directory.resolve("中文 source order")
        probe("sourceorder-write", root, 150)
        probe("sourceorder-read", root, 120)
    }

    @Test
    fun `the setup wizard walks the intro appearance and done pages from the source settings row`() {
        probe("wizard", directory.resolve("中文 setup wizard"), 180)
    }

    @Test
    fun `the source test page runs every step against a local CMS site and exports the run`() {
        probe("sourcetest", directory.resolve("中文 source test"), 180)
    }

    @Test
    fun `a parser marked broken stays installed and the show broken sources switch hides and offers it`() {
        val root = directory.resolve("中文 broken sources")
        probe("brokensource-write", root, 150)
        probe("brokensource-read", root, 150)
    }

    @Test
    fun `the install behavior answers the catalogue question switches new sources and persists per kind`() {
        val root = directory.resolve("中文 install behavior")
        probe("installpolicy-write", root, 240)
        probe("installpolicy-read", root, 150)
    }

    @Test
    fun `shared novel directory locates grouped chapters and keeps original indices in wide and narrow windows`() {
        probe("novel-directory", directory.resolve("中文 novel directory wide"))
        probe("novel-directory-narrow", directory.resolve("中文 novel directory narrow"))
    }

    @Test
    fun `shared manga directory locates branches switches grid and preserves current page in wide and narrow windows`() {
        probe("manga-directory", directory.resolve("中文 manga directory wide"))
        probe("manga-directory-narrow", directory.resolve("中文 manga directory narrow"))
    }

    @Test
    fun `a JSON CMS source imports from the extensions page browses and is removed again`() {
        probe("json-sources", directory.resolve("中文 json source"), 120)
        probe("json-sources-narrow", directory.resolve("中文 json source narrow"), 120)
    }

    @Test
    fun `tablet layout and immersive reader panels preserve progress in wide and narrow windows`() {
        probe("tablet", directory.resolve("中文 tablet wide"), 150)
        probe("tablet-narrow", directory.resolve("中文 tablet narrow"), 150)
        probe("tablet-boundary", directory.resolve("中文 tablet expanded boundary"), 150)
        probe("tablet-boundary-compact", directory.resolve("中文 tablet medium boundary"), 150)
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
    fun `recommendation worker switches gate the run on Wi-Fi widen the candidates and announce results`() {
        val root = directory.resolve("中文 suggestion policy")
        probe("suggestionpolicy-write", root, 120)
        probe("suggestionpolicy-read", root, 90)
    }

    @Test
    fun `services page records statistics on demand and hides the reading-time estimate`() {
        val root = directory.resolve("中文 services")
        probe("services-write", root, 180)
        probe("services-read", root, 90)
    }

    @Test
    fun `production Windows window closes during startup and after import and releases storage`() {
        probe("window-early", directory.resolve("中文 early close"))
        probe("window", directory.resolve("中文 normal window"))
    }

    private fun probe(mode: String, root: Path, seconds: Long = 60) = DesktopProbes.run(mode, root, seconds)
}
