package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.DesktopInstallKind
import org.skepsun.kototoro.desktop.runtime.DesktopInstallLayout
import org.skepsun.kototoro.desktop.runtime.DesktopPackageVersion
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateAsset
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel
import org.skepsun.kototoro.desktop.runtime.DesktopUpdatePackage
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateRelease
import java.nio.file.Path
import java.util.Properties

/** The `--check-update` report: what an installed image tells CI (or a user) about its own update. */
class DesktopAppUpdateCheckTest {
    @Test
    fun `macOS report names the DMG and its native installation kind`() {
        val app = layout.root.resolve("Kototoro.app")
        val mac = layout.copy(root = app, executable = app.resolve("Contents/MacOS/Kototoro"),
            kind = DesktopInstallKind.MACOS_APP)
        val report = report(version, mac, release("9.0.0", DesktopUpdatePackage.DMG), null)
        assertEquals("macos_app", report.getProperty("install.kind"))
        assertEquals("dmg", report.getProperty("available.packages"))
        assertTrue(report.getProperty("available.asset").endsWith("macos-arm64-release.dmg"))
    }
    private val version = DesktopAppVersion("2.3.1", "2.3.1", DesktopUpdateChannel.RELEASE)
    private val layout = DesktopInstallLayout(
        root = Path.of("C:/Users/me/AppData/Local/Kototoro-App"),
        executable = Path.of("C:/Users/me/AppData/Local/Kototoro-App/Kototoro.exe"),
        kind = DesktopInstallKind.PORTABLE,
    )

    @Test
    fun `a published release newer than this copy reports its package and target`() {
        val report = report(version, layout, release("2.3.2"), null)
        assertEquals("available", report.getProperty("status"))
        assertEquals("2.3.1", report.getProperty("version.name"))
        assertEquals("release", report.getProperty("version.channel"))
        assertEquals("true", report.getProperty("install.supported"))
        assertEquals("portable", report.getProperty("install.kind"))
        assertEquals(layout.executable.toString(), report.getProperty("install.executable"))
        assertEquals("2.3.2", report.getProperty("channel.newest"))
        assertEquals("2.3.2", report.getProperty("available.version"))
        assertEquals("v2.3.2", report.getProperty("available.tag"))
        assertEquals("Kototoro-2.3.2-windows-x64-release.zip", report.getProperty("available.asset"))
        assertEquals("https://example.invalid/Kototoro-2.3.2-windows-x64-release.zip",
            report.getProperty("available.url"))
        assertEquals("portable_zip,msi", report.getProperty("available.packages"))
        assertEquals("2048", report.getProperty("available.size"))
        assertEquals("https://example.invalid/Kototoro-2.3.2-windows-x64-release.sha256",
            report.getProperty("available.checksums"))
    }

    @Test
    fun `the same build being published again is up to date, not an update`() {
        val report = report(version, layout, release("2.3.1"), null)
        assertEquals("upToDate", report.getProperty("status"))
        assertEquals("2.3.1", report.getProperty("channel.newest"))
        assertNull(report.getProperty("available.version"))
        assertNull(report.getProperty("error"))
    }

    @Test
    fun `another channel's build is reported even when it is not newer`() {
        // `--channel nightly` from a release build: `upToDate` alone would hide that a nightly exists at all.
        val report = report(version, layout, release("N20261021"), null)
        assertEquals("upToDate", report.getProperty("status"))
        assertEquals("N20261021", report.getProperty("channel.newest"))
        assertEquals("vN20261021", report.getProperty("channel.newest.tag"))
    }

    @Test
    fun `a channel that publishes nothing is distinguished from being up to date`() {
        val report = report(version, layout, null, null)
        assertEquals("unpublished", report.getProperty("status"))
        assertNull(report.getProperty("channel.newest"))
    }

    @Test
    fun `a failed check keeps the error and still says where it runs from`() {
        val report = report(version, layout, null, IllegalStateException("HTTP 503 from the release channel"))
        assertEquals("failed", report.getProperty("status"))
        assertEquals("HTTP 503 from the release channel", report.getProperty("error"))
        assertEquals("portable", report.getProperty("install.kind"))
    }

    @Test
    fun `a copy started outside an installed image is not updatable`() {
        val report = report(version, null, null, null)
        assertEquals("false", report.getProperty("install.supported"))
        assertEquals("none", report.getProperty("install.kind"))
        assertNull(report.getProperty("install.root"))
    }

    @Test
    fun `a copy without a stamp reports unknown instead of a made up version`() {
        val report = report(DesktopAppVersion.UNKNOWN, layout, null, null)
        assertEquals("unknown", report.getProperty("version.name"))
        assertEquals("unknown", report.getProperty("version.parsed"))
        assertEquals("release", report.getProperty("version.channel"))
    }

    @Test
    fun `a nightly installation sees its own build as current`() {
        val nightly = DesktopAppVersion("N20261021", "2.3.1", DesktopUpdateChannel.NIGHTLY)
        val report = report(nightly, layout, releaseToday("N20261021"), null)
        assertEquals("nightly", report.getProperty("version.channel"))
        assertEquals("N20261021", report.getProperty("version.name"))
        assertEquals("upToDate", report.getProperty("status"))
    }

    @Test
    fun `a newer nightly is offered to a nightly installation`() {
        val nightly = DesktopAppVersion("N20261021", "2.3.1", DesktopUpdateChannel.NIGHTLY)
        val report = report(nightly, layout, release("N20261022"), null)
        assertEquals("available", report.getProperty("status"))
        assertEquals("N20261022", report.getProperty("available.version"))
        assertTrue(report.getProperty("available.asset")!!.endsWith(".zip"))
    }

    @Test
    fun `a nightly-only release still reports the size of what would be downloaded`() {
        val nightly = DesktopAppVersion("N20261021", "2.3.1", DesktopUpdateChannel.NIGHTLY)
        val zipOnly = release("N20261022").let { it.copy(packages = it.packages - DesktopUpdatePackage.MSI) }
        val report = report(nightly, layout, zipOnly, null)
        assertEquals("portable_zip", report.getProperty("available.packages"))
        assertEquals("2048", report.getProperty("available.size"))
    }

    private fun report(
        version: DesktopAppVersion,
        layout: DesktopInstallLayout?,
        published: DesktopUpdateRelease?,
        error: Throwable?,
    ): Properties = Properties().also { target ->
        DesktopAppUpdateCheck.desktopUpdateReport(target, version, layout, published, error)
    }

    /** A nightly release ships the ZIP only. */
    private fun releaseToday(version: String): DesktopUpdateRelease =
        release(version, DesktopUpdatePackage.PORTABLE_ZIP)

    private fun release(
        version: String,
        vararg kinds: DesktopUpdatePackage,
    ): DesktopUpdateRelease {
        val wanted = if (kinds.isEmpty()) listOf(DesktopUpdatePackage.PORTABLE_ZIP, DesktopUpdatePackage.MSI) else kinds.toList()
        val platform = if (DesktopUpdatePackage.DMG in wanted) "macos-arm64" else "windows-x64"
        return DesktopUpdateRelease(
            version = DesktopPackageVersion.parse(version),
            tag = "v$version",
            notes = "",
            publishedAt = null,
            publishedUrl = null,
            packages = wanted.associateWith { kind ->
                val suffix = when (kind) {
                    DesktopUpdatePackage.MSI -> "msi"
                    DesktopUpdatePackage.PORTABLE_ZIP -> "zip"
                    DesktopUpdatePackage.DMG -> "dmg"
                }
                DesktopUpdateAsset(
                    name = "Kototoro-$version-$platform-release.$suffix",
                    downloadUrl = "https://example.invalid/Kototoro-$version-$platform-release.$suffix",
                    sizeBytes = if (kind == DesktopUpdatePackage.MSI) 4096 else 2048,
                    kind = kind,
                )
            },
            checksumsUrl = "https://example.invalid/Kototoro-$version-$platform-release.sha256",
        )
    }
}
