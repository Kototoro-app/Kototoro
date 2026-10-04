package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.backups.domain.BackupSection
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
                                BackupSection.BOOKMARKS, BackupSection.STATS))
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
                    val bridge = MihonDesktopPlatform.defaultBridgeExecutable()
                    result.setProperty("browser.executable", bridge?.absolutePath ?: "unavailable")
                    val browser = requireNotNull(session.browser) { "Packaged WebView2 bridge is unavailable" }
                    withTimeout(30_000) {
                        check(browser.evaluate("<html><head><title>runtime-ready</title></head></html>",
                            "document.title") == "\"runtime-ready\"")
                    }
                    result.setProperty("browser", "ok")
                    // Video: the bundled libmpv decodes and plays a generated stream without any window or device.
                    val library = requireNotNull(org.skepsun.kototoro.desktop.player.MpvLocator.find(root)) { "libmpv is unavailable" }
                    result.setProperty("player.library", library.toString())
                    org.skepsun.kototoro.desktop.player.MpvPlayer(library).use { player ->
                        player.load(org.skepsun.kototoro.desktop.player.MpvStream("av://lavfi:testsrc=duration=5:size=64x48:rate=10"))
                        withTimeout(20_000) { while (player.state.value.position < 0.3) kotlinx.coroutines.delay(50) }
                    }
                    result.setProperty("player", "ok")
                    // Page super-resolution: every bundled program upscales a small page.
                    val page = root.resolve("runtime-check-page.png")
                    val sample = BufferedImage(32, 48, BufferedImage.TYPE_INT_RGB)
                    try {
                        sample.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 32, 48); color = java.awt.Color.BLACK; drawLine(0, 0, 31, 47); dispose() }
                        check(ImageIO.write(sample, "png", page.toFile()))
                    } finally { sample.flush() }
                    for (model in listOf(org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel.REALCUGAN_2X,
                        org.skepsun.kototoro.desktop.runtime.DesktopUpscaleModel.REALESR_ANIMEVIDEO_2X)) {
                        val tool = requireNotNull(model.tool)
                        val program = requireNotNull(session.superResolution.executable(tool)) { "${tool.title} is unavailable" }
                        result.setProperty("upscale.${tool.name.lowercase()}", program.toString())
                        val output = requireNotNull(session.superResolution.upscale(page, 32, 48,
                            org.skepsun.kototoro.desktop.runtime.DesktopUpscaleSetting(model)))
                        check(DesktopImageDecoder.header(output).width == 64) { "${model.title} output size" }
                    }
                    result.setProperty("upscale", "ok")                }
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
}
