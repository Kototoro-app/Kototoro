package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform
import org.skepsun.kototoro.desktop.player.MpvLocator
import org.skepsun.kototoro.desktop.player.MpvPlayer
import org.skepsun.kototoro.desktop.player.MpvStream
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopPlatform
import org.skepsun.kototoro.desktop.runtime.DesktopSuperResolution
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel
import org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting
import org.skepsun.kototoro.backups.domain.BackupSection
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.imageio.ImageIO

/** Local support check for the installed JVM/native/runtime assembly, without source network requests. */
internal object DesktopRuntimeCheck {
    fun run(root: Path, report: Path, imports: List<Path>) {
        val result = Properties()
        result.setProperty("java.home", System.getProperty("java.home"))
        result.setProperty("java.version", System.getProperty("java.version"))
        val platform = DesktopPlatform.current
        result.setProperty("platform", platform.displayName)
        try {
            runBlocking {
                DesktopSession.open(root).use { session ->
                    check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
                    for (jar in imports) session.importJar(jar)
                    result.setProperty("sources", session.registry.installed().sumOf { it.sources.size }.toString())
                    val owner = Class.forName("android.os.Looper").protectionDomain.codeSource.location
                    check(Path.of(owner.toURI()).fileName.toString().matches(Regex("(?:[0-9]{3}-)?AndroidCompat-.*\\.jar"))) {
                        "Android classes must come from AndroidCompat: $owner"
                    }
                    result.setProperty("android.owner", Path.of(owner.toURI()).fileName.toString())
                    result.setProperty("schema", session.storage.storageInfo().schemaVersion.toString())
                    val backup = root.resolve("runtime-check-backup-${java.util.UUID.randomUUID()}.zip")
                    var exported = false
                    try {
                        val backups = DesktopLibraryBackup(session.storage.database)
                        backups.export(backup)
                        exported = true
                        backups.preview(backup).use { archive ->
                            check(archive.counts.keys == setOf(BackupSection.CONTENTS, BackupSection.CATEGORIES,
                                BackupSection.FAVOURITES, BackupSection.HISTORY, BackupSection.SOURCES,
                                BackupSection.BOOKMARKS, BackupSection.STATS, BackupSection.SOURCE_ORIGINS,
                                BackupSection.EXTENSION_REPOS, BackupSection.TRACKS, BackupSection.TRACK_LOGS)) {
                                "Unexpected backup sections: ${archive.counts.keys}"
                            }
                            check(archive.otherEntries.isEmpty())
                        }
                        result.setProperty("backup", "ok")
                    } finally { if (exported) Files.deleteIfExists(backup) }
                    val path = root.resolve("runtime-check.png")
                    val original = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
                    try {
                        for (y in 0..2) for (x in 0..1) original.setRGB(x, y, 0xFF00FF00.toInt())
                        check(ImageIO.write(original, "png", path.toFile()))
                    } finally { original.flush() }
                    val header = DesktopImageDecoder.header(path)
                    check(header.width == 2 && header.height == 3)
                    DesktopImageDecoder.decode(path).use {
                        check(it.getColor(1, 2) == 0xFF00FF00.toInt())
                    }
                    result.setProperty("image", "ok")
                    // Plays a generated stream without a window or a device: the locator, the loader and the demuxer
                    // all have to work for the position to move.
                    suspend fun playTestStream(library: Path) {
                        MpvPlayer(library).use { player ->
                            player.load(MpvStream("av://lavfi:testsrc=duration=5:size=64x48:rate=10"))
                            withTimeout(20_000) {
                                while (player.state.value.position < 0.3) delay(50)
                            }
                        }
                    }
                    // libmpv is never shipped: on Linux the distribution provides it, elsewhere the user installs it.
                    // Probing here keeps `player=ok` honest instead of a static claim, and a missing library is
                    // reported as such rather than failed — the video page still offers the external player.
                    suspend fun probePlayer() {
                        val library = MpvLocator.find(root)
                        result.setProperty("player.library", library?.toString() ?: "unavailable")
                        if (library == null) {
                            result.setProperty("player", "unavailable")
                            result.setProperty(
                                "player.hint",
                                if (MpvLocator.packagedByDistribution) {
                                    "install the libmpv package (Debian/Ubuntu: libmpv2)"
                                } else {
                                    "put the libmpv library in the mpv folder of the data directory"
                                },
                            )
                            return
                        }
                        playTestStream(library)
                        result.setProperty("player", "ok")
                    }
                    if (platform != DesktopPlatform.WINDOWS) {
                        result.setProperty("browser", "unsupported")
                        // An AWT canvas is an X11 window on Linux (XWayland under Wayland), which is what mpv's wid
                        // takes; macOS has no such handle and needs the render API instead.
                        result.setProperty(
                            "player.embedded",
                            if (platform == DesktopPlatform.LINUX) "x11-window" else "unsupported",
                        )
                        // Windows bundles the programs; Linux downloads them on first use, so the capability is
                        // there and whether they are installed yet is what the probe reports.
                        result.setProperty(
                            "upscale.native",
                            if (platform.supportsNativeUpscaling) "on-demand" else "unsupported",
                        )
                        probePlayer()
                        if (platform.supportsNativeUpscaling) {
                            for ((key, value) in probeUpscaling(root, session.superResolution, false)) {
                                result.setProperty(key, value)
                            }
                        }
                        return@use
                    }
                    val bridge = MihonDesktopPlatform.defaultBridgeExecutable()
                    result.setProperty("browser.executable", bridge?.absolutePath ?: "unavailable")
                    val browser = requireNotNull(session.browser) { "Packaged WebView2 bridge is unavailable" }
                    withTimeout(30_000) {
                        check(browser.evaluate("<html><head><title>runtime-ready</title></head></html>",
                            "document.title") == "\"runtime-ready\"")
                    }
                    result.setProperty("browser", "ok")
                    // Video: the bundled libmpv decodes and plays a generated stream without any window or device.
                    val library = requireNotNull(MpvLocator.find(root)) { "libmpv is unavailable" }
                    result.setProperty("player.library", library.toString())
                    playTestStream(library)
                    result.setProperty("player", "ok")
                    // Page super-resolution: every bundled program upscales a small page.
                    for ((key, value) in probeUpscaling(root, session.superResolution, required = true)) {
                        result.setProperty(key, value)
                    }
                }
            }
            result.setProperty("status", "ok")
        } catch (error: Throwable) {
            result.setProperty("status", "failed")
            result.setProperty("error", error.message ?: error.javaClass.name)
            throw error
        } finally {
            report.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.newBufferedWriter(report).use { result.store(it, "Kototoro local runtime check") }
        }
    }

    /**
     * Page super-resolution. The ncnn-vulkan programs are official downloads rather than bundled files: Windows images
     * carry them, a Linux user installs them from the reader's upscale row, so [required] says whether a missing
     * program is a packaging bug or just "not installed yet". An installed program has to upscale the 32×48 sample of
     * [root] to 64 pixels wide, which is what makes the report say `upscale=ok`.
     */
    suspend fun probeUpscaling(
        root: Path,
        upscaling: DesktopSuperResolution,
        required: Boolean,
    ): Map<String, String> {
        val page = root.resolve("runtime-check-page.png")
        val sample = BufferedImage(32, 48, BufferedImage.TYPE_INT_RGB)
        try {
            sample.createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, 32, 48)
                color = Color.BLACK
                drawLine(0, 0, 31, 47)
                dispose()
            }
            check(ImageIO.write(sample, "png", page.toFile()))
        } finally {
            sample.flush()
        }
        val report = linkedMapOf<String, String>()
        var ok = true
        for (model in listOf(DesktopUpscaleModel.REALCUGAN_2X, DesktopUpscaleModel.REALESR_ANIMEVIDEO_2X)) {
            val tool = requireNotNull(model.tool)
            val program = upscaling.executable(tool)
            report["upscale.${tool.name.lowercase()}"] = program?.toString() ?: "unavailable"
            if (program == null) {
                if (required) error("${tool.title} is unavailable")
                ok = false
                continue
            }
            val output = requireNotNull(upscaling.upscale(page, 32, 48, DesktopUpscaleSetting(model)))
            check(DesktopImageDecoder.header(output).width == 64) { "${model.title} output size" }
        }
        report["upscale"] = if (ok) "ok" else "unavailable"
        return report
    }
}
