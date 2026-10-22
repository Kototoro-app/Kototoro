package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopAppUpdateClient
import org.skepsun.kototoro.desktop.runtime.DesktopInstallLayout
import org.skepsun.kototoro.desktop.runtime.DesktopPackageVersion
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel
import org.skepsun.kototoro.desktop.runtime.DesktopUpdatePackage
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateRelease
import org.skepsun.kototoro.desktop.runtime.locateInstallLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * `--check-update <report>`: what this copy believes it is, where it runs from, and what its release channel
 * publishes, written to one properties file and nowhere else.
 *
 * The window's own panel asks the same questions, but it can only be reached from a desktop session, and a packaged
 * image (the only place where the update can actually be installed) is exactly where diagnosing a failed check is
 * hardest. This path answers it from the installed image, and it is also how the packaging scripts prove that the
 * stamp, the install layout and the release lookup all agree.
 */
internal object DesktopAppUpdateCheck {
    /**
     * Runs one check and writes [report]; returns the process exit code: 0 when the channel answered (with or
     * without an update), 1 when it could not be asked or this copy has no version to compare.
     */
    fun run(report: Path, channelOverride: String? = null): Int {
        val stamped = DesktopAppVersion.load()
        val version = if (channelOverride == null) stamped else stamped.copy(
            channel = DesktopUpdateChannel.from(channelOverride))
        val layout = runCatching { locateInstallLayout() }.getOrNull()
        val result = Properties()
        var published: DesktopUpdateRelease? = null
        var failure: Throwable? = null
        try {
            require(version.version != DesktopPackageVersion.UNKNOWN) {
                "This copy carries no version stamp (desktop-version.properties), so it cannot compare itself"
            }
            published = runBlocking { DesktopAppUpdateClient(version.version, version.channel).newest() }
        } catch (error: Throwable) {
            failure = error
        } finally {
            desktopUpdateReport(result, version, layout, published, failure)
            report.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.newBufferedWriter(report).use { result.store(it, "Kototoro Windows update check") }
        }
        val error = failure ?: return 0
        System.err.println("update check failed: ${error.message ?: error.javaClass.name}")
        return 1
    }

    /**
     * The report for one check, kept apart from [run] so the mapping can be tested without a network or a packaged
     * image.
     *
     * It reports what the channel publishes rather than only what [DesktopAppUpdateClient.check] would install: asked
     * about another channel (`--channel nightly` from a release build), the honest answer is "N20261021 is published
     * and it is not newer than 2.3.1", not "up to date". A copy that cannot recognise its own install layout says so
     * through `install.supported=false`, and no version stamp becomes `unknown` instead of a made-up 0.0.0.
     */
    internal fun desktopUpdateReport(
        target: Properties,
        version: DesktopAppVersion,
        layout: DesktopInstallLayout?,
        published: DesktopUpdateRelease?,
        error: Throwable?,
    ) {
        target.setProperty("version.name", version.versionName.ifEmpty { "unknown" })
        target.setProperty("version.package", version.packageVersion.ifEmpty { "unknown" })
        target.setProperty("version.channel", version.channel.assetSegment)
        target.setProperty("version.parsed", version.version.text.ifEmpty { "unknown" })
        target.setProperty("install.supported", (layout != null).toString())
        target.setProperty("install.kind", layout?.kind?.name?.lowercase() ?: "none")
        layout?.let {
            target.setProperty("install.root", it.root.toString())
            target.setProperty("install.executable", it.executable.toString())
        }
        if (published != null) {
            target.setProperty("channel.newest", published.version.text)
            target.setProperty("channel.newest.tag", published.tag)
        }
        when {
            error != null -> {
                target.setProperty("status", "failed")
                target.setProperty("error", error.message ?: error.javaClass.name)
            }
            published == null -> target.setProperty("status", "unpublished")
            published.version > version.version -> {
                target.setProperty("status", "available")
                target.setProperty("available.version", published.version.text)
                target.setProperty("available.tag", published.tag)
                target.setProperty("available.size", published.sizeBytes())
                target.setProperty("available.packages",
                    published.packages.keys.joinToString(",") { it.name.lowercase() })
                published.checksumsUrl?.let { target.setProperty("available.checksums", it) }
                published.packageOf(DesktopUpdatePackage.PORTABLE_ZIP)?.let {
                    target.setProperty("available.asset", it.name)
                    target.setProperty("available.url", it.downloadUrl)
                }
            }
            else -> target.setProperty("status", "upToDate")
        }
    }

    /** The package the installer would fetch, and so the one whose size the report names. */
    private fun DesktopUpdateRelease.sizeBytes(): String =
        (packageOf(DesktopUpdatePackage.PORTABLE_ZIP) ?: packages.values.firstOrNull())?.sizeBytes?.toString() ?: "0"
}
