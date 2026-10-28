package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopAppUpdateTest {
    @Test
    fun `versions parse like the Android client orders them`() {
        assertEquals(DesktopPackageVersion(1, 9, 9, "", 0, "1.9.9"), DesktopPackageVersion.parse("1.9.9"))
        assertEquals(DesktopPackageVersion(1, 9, 9, "", 0, "v1.9.9"), DesktopPackageVersion.parse(" v1.9.9 "))
        val nightly = DesktopPackageVersion.parse("N20261022")
        assertTrue(nightly.isNightly)
        assertEquals(20261022, nightly.build)
        assertEquals(nightly, DesktopPackageVersion.parse("nightly-20261022"))
        assertEquals("beta", DesktopPackageVersion.parse("1.10.0-beta1").variantType)
        assertEquals(1, DesktopPackageVersion.parse("1.10.0-beta1").variantNumber)
        assertEquals(DesktopPackageVersion.UNKNOWN, DesktopPackageVersion.parse(""))
        assertEquals(DesktopPackageVersion.UNKNOWN, DesktopPackageVersion.parse("not a version"))
        // A pre-release sorts below its release, and a nightly below any release of the same date.
        assertTrue(DesktopPackageVersion.parse("1.10.0-beta1") < DesktopPackageVersion.parse("1.10.0"))
        assertTrue(DesktopPackageVersion.parse("1.9.9") < DesktopPackageVersion.parse("1.10.0"))
        assertTrue(DesktopPackageVersion.parse("N20261022") < DesktopPackageVersion.parse("1.9.9"))
        assertTrue(!DesktopPackageVersion.parse("1.9.9").isStable.not())
    }

    @Test
    fun `the channel picks its repository and asset suffix`() {
        assertEquals(DESKTOP_UPDATE_REPOSITORY, desktopUpdateRepository(DesktopUpdateChannel.RELEASE))
        assertEquals(DESKTOP_NIGHTLY_REPOSITORY, desktopUpdateRepository(DesktopUpdateChannel.NIGHTLY))
        assertEquals("release", DesktopUpdateChannel.RELEASE.assetSegment)
        assertEquals(DesktopUpdateChannel.NIGHTLY, DesktopUpdateChannel.from("nightly"))
        assertEquals(DesktopUpdateChannel.RELEASE, DesktopUpdateChannel.from(null))
    }

    @Test
    fun `release parsing keeps this channel's packages and its checksum file`() {
        val releases = parseReleases(RELEASES, DesktopUpdateChannel.RELEASE)
        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals("1.10.0", release.version.text)
        assertEquals("v1.10.0", release.tag)
        assertEquals(setOf(DesktopUpdatePackage.PORTABLE_ZIP, DesktopUpdatePackage.MSI), release.packages.keys)
        assertEquals("Kototoro-1.10.0-windows-x64-release.zip",
            release.packageOf(DesktopUpdatePackage.PORTABLE_ZIP)!!.name)
        assertEquals("https://example.invalid/Kototoro-1.10.0-windows-x64-release.sha256", release.checksumsUrl)
        assertTrue(release.notes.startsWith("Fixes"))
    }

    @Test
    fun `a draft or a foreign release is not offered`() {
        assertTrue(parseReleases(RELEASES.replace("\"draft\": false", "\"draft\": true"), DesktopUpdateChannel.RELEASE).isEmpty())
        // The nightly channel sees the nightly asset of the same release payload; the release channel ignores it.
        assertTrue(parseReleases(NIGHTLY_RELEASES, DesktopUpdateChannel.RELEASE).isEmpty())
        val nightly = parseReleases(NIGHTLY_RELEASES, DesktopUpdateChannel.NIGHTLY).single()
        assertEquals("N20261022", nightly.version.text)
        assertEquals(DesktopUpdatePackage.PORTABLE_ZIP, nightly.packages.keys.single())
    }

    @Test
    fun `selection prefers the highest newer release the channel may install`() {
        val stable = parseReleases(RELEASES, DesktopUpdateChannel.RELEASE)
        assertEquals("1.10.0", selectUpdate(stable, DesktopPackageVersion.parse("1.9.9"), DesktopUpdateChannel.RELEASE)!!.version.text)
        assertNull(selectUpdate(stable, DesktopPackageVersion.parse("1.10.0"), DesktopUpdateChannel.RELEASE))
        assertNull(selectUpdate(stable, DesktopPackageVersion.parse("2.0.0"), DesktopUpdateChannel.RELEASE))
        // A stable installation is never offered a pre-release, even when it is newer.
        val beta = parseReleases(RELEASES.replace("1.10.0", "1.11.0-beta1"), DesktopUpdateChannel.RELEASE)
        assertNull(selectUpdate(beta, DesktopPackageVersion.parse("1.9.9"), DesktopUpdateChannel.RELEASE))
    }

    @Test
    fun `the newest published release is reported even when this copy already has it`() {
        // `--check-update` asks "what is published"; selectUpdate answers "is there work to do".
        val stable = parseReleases(RELEASES, DesktopUpdateChannel.RELEASE)
        assertEquals("1.10.0", newestRelease(stable, DesktopUpdateChannel.RELEASE)!!.version.text)
        assertNull(selectUpdate(stable, DesktopPackageVersion.parse("1.10.0"), DesktopUpdateChannel.RELEASE))
        val nightly = parseReleases(NIGHTLY_RELEASES, DesktopUpdateChannel.NIGHTLY)
        assertEquals("N20261022", newestRelease(nightly, DesktopUpdateChannel.NIGHTLY)!!.version.text)
        // Nothing of the other channel is ever reachable, so a channel with no packages reports none.
        assertNull(newestRelease(parseReleases(NIGHTLY_RELEASES, DesktopUpdateChannel.RELEASE), DesktopUpdateChannel.RELEASE))
    }

    @Test
    fun `the changelog lists offerable releases newest first and skips pre-releases for a stable copy`() {
        val newest = parseReleases(RELEASES, DesktopUpdateChannel.RELEASE).single()
        val older = newest.copy(tag = "v1.9.0", version = DesktopPackageVersion.parse("1.9.0"))
        val beta = newest.copy(tag = "v1.11.0-beta1", version = DesktopPackageVersion.parse("1.11.0-beta1"))
        val nightly = parseReleases(NIGHTLY_RELEASES, DesktopUpdateChannel.NIGHTLY).single()
        val payload = listOf(older, beta, newest, nightly)

        // A stable installation reads the stable versions only, highest first.
        assertEquals(listOf("1.10.0", "1.9.0"),
            changelogReleases(payload, DesktopUpdateChannel.RELEASE).map { it.version.text })
        // The nightly channel keeps its own pre-releases, and still sorts them newest first.
        assertEquals(listOf("1.11.0-beta1", "1.10.0", "1.9.0", "N20261022"),
            changelogReleases(payload, DesktopUpdateChannel.NIGHTLY).map { it.version.text })
        // A release with no downloadable package never reaches the page, whatever the channel.
        val uninstallable = newest.copy(packages = emptyMap())
        assertTrue(changelogReleases(listOf(uninstallable), DesktopUpdateChannel.RELEASE).isEmpty())
    }

    @Test
    fun `checksum files parse in the published sha256sum format`() {
        val checksums = parseChecksums(
            """
            |# generated by the release workflow
            |2c7c1b9e6b1a5a1f6a7f7d2ff1b8b0a7cbb1f9a8f4f4d3e2c1b0a9f8e7d6c5b4  Kototoro-1.10.0-windows-x64-release.zip
            |
            |2c7c1b9e6b1a5a1f6a7f7d2ff1b8b0a7cbb1f9a8f4f4d3e2c1b0a9f8e7d6c5b4 *Kototoro-1.10.0-windows-x64-release.msi
            |not-a-hash  Kototoro-ignored.zip
            """.trimMargin(),
        )
        assertEquals(2, checksums.size)
        assertEquals("2c7c1b9e6b1a5a1f6a7f7d2ff1b8b0a7cbb1f9a8f4f4d3e2c1b0a9f8e7d6c5b4",
            checksums["Kototoro-1.10.0-windows-x64-release.zip"])
        assertEquals(checksums["Kototoro-1.10.0-windows-x64-release.msi"],
            checksums["Kototoro-1.10.0-windows-x64-release.zip"])
        assertTrue(parseChecksums("").isEmpty())
    }

    @Test
    fun `a malformed payload is reported, not guessed at`() {
        assertThrows<DesktopUpdateException> { parseReleases("{not json", DesktopUpdateChannel.RELEASE) }
        assertNull(versionOf("Kototoro-1.10.0-windows-arm64-release.zip"))
    }

    @Test
    fun `update settings round trip`() {
        assertEquals(DesktopUpdateSettings(), DesktopUpdateSettings.from(emptyMap()))
        val settings = DesktopUpdateSettings(autoCheck = false, autoDownload = true, installOnExit = true)
        val stored = settings.toPreferences().mapValues { it.value!! }
        assertEquals(SourcePreferenceValue.Toggle(true), stored["app_update_auto_download"])
        assertEquals(settings, DesktopUpdateSettings.from(stored))
    }

    @Test
    fun `the expanded package's image is found one level down`(@TempDir tempDir: Path) {
        assertNull(findImageRoot(tempDir))
        val nested = tempDir.resolve("Kototoro")
        Files.createDirectories(nested.resolve("app"))
        Files.createDirectories(nested.resolve("runtime"))
        assertNull(findImageRoot(tempDir))
        Files.writeString(nested.resolve("Kototoro.exe"), "")
        assertEquals(nested, findImageRoot(tempDir))
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `an MSI registration is told apart from an unpacked image`() {
        val output = """
            |HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{8F1A-2E44}
            |    DisplayName    REG_SZ    Kototoro
            |    InstallLocation    REG_SZ    C:\Users\me\AppData\Local\Kototoro-App\
            |
        """.trimMargin()
        val root = Path.of("C:/Users/me/AppData/Local/Kototoro-App")
        assertEquals(root, parseMsiInstallLocation(output))
        assertNull(parseMsiInstallLocation("nothing here"))
    }

    @Test
    fun `the update script waits for this process and swaps the image`(@TempDir tempDir: Path) {
        val staged = DesktopStagedUpdate(
            release = parseReleases(RELEASES, DesktopUpdateChannel.RELEASE).single(),
            kind = DesktopInstallKind.PORTABLE,
            packagePath = tempDir.resolve("Kototoro's image"),
            root = tempDir.resolve("Kototoro-App"),
            executable = tempDir.resolve("Kototoro-App/Kototoro.exe"),
            sha256 = "0".repeat(64),
        )
        val script = updateScript(staged, tempDir.resolve("update.log"), restart = true)
        assertTrue(script.contains("WaitForExit"))
        assertTrue(script.contains(ProcessHandle.current().pid().toString()))
        assertTrue(script.contains("robocopy.exe"))
        assertTrue(script.contains("Kototoro''s image"), "single quotes are escaped for PowerShell")
        assertTrue(script.contains("Start-Process -FilePath"))
        assertFalse(script.contains("\${"))
        assertFalse(updateScript(staged.copy(kind = DesktopInstallKind.MSI), tempDir.resolve("update.log"), false)
            .contains("robocopy"))
        assertTrue(updateScript(staged.copy(kind = DesktopInstallKind.MSI), tempDir.resolve("update.log"), false)
            .contains("msiexec.exe"))
    }

    @Test
    fun `a zip without an application image is rejected before anything is installed`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("package.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { archive ->
            archive.putNextEntry(ZipEntry("readme.txt"))
            archive.write("not an image".toByteArray())
            archive.closeEntry()
        }
        val installer = DesktopUpdateInstaller(DesktopInstallLayout(tempDir, tempDir.resolve("Kototoro.exe"), DesktopInstallKind.PORTABLE))
        val download = DesktopDownloadedUpdate(
            parseReleases(RELEASES, DesktopUpdateChannel.RELEASE).single(),
            DesktopUpdatePackage.PORTABLE_ZIP, zip, "0".repeat(64),
        )
        assertThrows<DesktopUpdateException> {
            kotlinx.coroutines.runBlocking { installer.stage(download, tempDir.resolve("staging")) }
        }
        // Outside an installed package the updater refuses to pretend it can replace itself.
        assertFalse(DesktopUpdateInstaller(null).canSelfUpdate)
    }

    /**
     * The payloads GitHub actually served on 2026-10-22 for both repositories, so the asset names and flags are not
     * only checked against a hand-written sample. The nightly repository marks its releases `prerelease: true`, which
     * must not hide them from the nightly channel.
     */
    @Test
    fun `the published payloads parse as GitHub serves them`() {
        val stable = parseReleases(resource("kototoro-releases.json"), DesktopUpdateChannel.RELEASE)
        val newest = stable.maxBy { it.version }
        assertTrue(newest.version >= DesktopPackageVersion.parse("2.3.1"), "newest was ${newest.version.text}")
        assertEquals(setOf(DesktopUpdatePackage.PORTABLE_ZIP, DesktopUpdatePackage.MSI), newest.packages.keys)
        assertEquals("Kototoro-${newest.version.text}-windows-x64-release.zip",
            newest.packageOf(DesktopUpdatePackage.PORTABLE_ZIP)!!.name)
        assertTrue(newest.checksumsUrl!!.endsWith(".sha256"))
        assertTrue(selectUpdate(stable, DesktopPackageVersion.parse("1.9.9"), DesktopUpdateChannel.RELEASE) != null)
        assertNull(selectUpdate(stable, newest.version, DesktopUpdateChannel.RELEASE))

        val nightlyPayload = resource("kototoro-nightly-releases.json")
        assertTrue(parseReleases(nightlyPayload, DesktopUpdateChannel.RELEASE).isEmpty())
        val nightly = parseReleases(nightlyPayload, DesktopUpdateChannel.NIGHTLY).single()
        assertTrue(nightly.version.isNightly, "version was ${nightly.version.text}")
        assertEquals(setOf(DesktopUpdatePackage.PORTABLE_ZIP), nightly.packages.keys)
        assertTrue(nightly.checksumsUrl!!.endsWith(".sha256"))
    }

    private fun resource(name: String): String =
        requireNotNull(DesktopAppUpdateTest::class.java.getResourceAsStream("/github/$name")) { name }
            .use { it.readBytes().decodeToString() }

    private fun versionOf(assetName: String): DesktopPackageVersion? {
        val json = RELEASES.replace("Kototoro-1.10.0-windows-x64-release.zip", assetName)
            .replace("Kototoro-1.10.0-windows-x64-release.msi", assetName)
            .replace("Kototoro-1.10.0-windows-x64-release.sha256", assetName)
        val release = parseReleases(json, DesktopUpdateChannel.RELEASE).firstOrNull() ?: return null
        return release.version
    }

    private companion object {
        /** A trimmed `releases?per_page=10` payload: one published release with both Windows packages. */
        val RELEASES = """
            [
              {
                "tag_name": "v1.10.0",
                "name": "1.10.0",
                "body": "Fixes the reader pager.\n",
                "published_at": "2026-10-20T10:00:00Z",
                "html_url": "https://example.invalid/releases/v1.10.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                  { "name": "Kototoro-1.10.0-windows-x64-release.zip",
                    "browser_download_url": "https://example.invalid/Kototoro-1.10.0-windows-x64-release.zip",
                    "size": 104857600 },
                  { "name": "Kototoro-1.10.0-windows-x64-release.msi",
                    "browser_download_url": "https://example.invalid/Kototoro-1.10.0-windows-x64-release.msi",
                    "size": 104857600 },
                  { "name": "Kototoro-1.10.0-windows-x64-release.sha256",
                    "browser_download_url": "https://example.invalid/Kototoro-1.10.0-windows-x64-release.sha256",
                    "size": 200 },
                  { "name": "Kototoro-1.10.0-release.apk",
                    "browser_download_url": "https://example.invalid/Kototoro-1.10.0-release.apk",
                    "size": 10485760 },
                  { "name": "Kototoro-1.10.0-windows-arm64-release.zip",
                    "browser_download_url": "https://example.invalid/Kototoro-1.10.0-windows-arm64-release.zip",
                    "size": 104857600 }
                ]
              }
            ]
        """.trimIndent()

        val NIGHTLY_RELEASES = """
            [
              {
                "tag_name": "nightly-20261022",
                "name": "Nightly 20261022",
                "body": "Nightly build",
                "published_at": "2026-10-22T02:00:00Z",
                "html_url": "https://example.invalid/releases/nightly-20261022",
                "draft": false,
                "assets": [
                  { "name": "Kototoro-N20261022-windows-x64-nightly.zip",
                    "browser_download_url": "https://example.invalid/Kototoro-N20261022-windows-x64-nightly.zip",
                    "size": 104857600 },
                  { "name": "Kototoro-N20261022-windows-x64-nightly.sha256",
                    "browser_download_url": "https://example.invalid/Kototoro-N20261022-windows-x64-nightly.sha256",
                    "size": 200 }
                ]
              }
            ]
        """.trimIndent()
    }
}
