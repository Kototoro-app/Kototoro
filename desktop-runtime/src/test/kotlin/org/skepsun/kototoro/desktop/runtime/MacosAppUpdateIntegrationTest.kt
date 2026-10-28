package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Opt-in, actual signed DMG and bundle, with every replacement confined to this test's temporary directory. */
class MacosAppUpdateIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `real DMG stages replaces restarts a bundle and rolls back a failed restart`() = runBlocking {
        val archive = System.getProperty("kototoro.macos.update.dmg")
        val original = System.getProperty("kototoro.macos.update.image")
        assumeTrue(DesktopPlatform.current == DesktopPlatform.MACOS && archive != null && original != null,
            "supply -PmacosUpdateDmg and -PmacosUpdateImage on macOS")
        val source = Path.of(original!!)
        val parent = Files.createDirectories(directory.resolve("中文 O'Reilly ${'$'}bundle"))
        val installed = parent.resolve("Kototoro.app")
        run(listOf("/usr/bin/ditto", source.toString(), installed.toString()))
        val version = Files.newDirectoryStream(source.resolve("Contents/app"), "*.jar").use { jars ->
            jars.firstNotNullOf { jar -> ZipFile(jar.toFile()).use { zip ->
                zip.getEntry("desktop-version.properties")?.let { entry ->
                    Properties().apply { zip.getInputStream(entry).use { load(it) } }.getProperty("versionName")
                }
            } }
        }
        val dmg = Path.of(archive!!)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(dmg).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        val release = DesktopUpdateRelease(DesktopPackageVersion.parse(version), "local", "", null, null,
            emptyMap(), null)
        val download = DesktopDownloadedUpdate(release, DesktopUpdatePackage.DMG, dmg,
            digest.digest().joinToString("") { "%02x".format(it) })
        val executable = installed.resolve("Contents/MacOS/Kototoro")
        val layout = requireNotNull(locateInstallLayout(executable.toString(), registryQuery = { null }))
        assertEquals(DesktopInstallKind.MACOS_APP, layout.kind)
        val installer = DesktopUpdateInstaller(layout)
        val staged = installer.stage(download, directory.resolve("staging"))
        assertTrue(Files.isExecutable(staged.packagePath.resolve("Contents/app/resources/browser/kototoro-webview-bridge")))
        assertThrows(IllegalArgumentException::class.java) {
            MacosAppUpdater.validate(staged.packagePath, installed, DesktopPackageVersion.parse("999.0.0"))
        }
        val userData = Files.writeString(directory.resolve("user-data.txt"), "untouched")
        fun key() = Files.readAttributes(installed, BasicFileAttributes::class.java).fileKey()
        val before = key()
        val owner = ProcessBuilder("/bin/sleep", "1").start()
        val script = directory.resolve("replace.sh")
        val log = directory.resolve("update.log")
        val restarted = directory.resolve("restarted.properties")
        Files.writeString(script, macosUpdateScript(staged, log, true, ownerPid = owner.pid(),
            restartArguments = listOf("--data-dir", directory.resolve("restarted-data").toString(),
                "--check-runtime", restarted.toString())))
        run(listOf("/bin/sh", script.toString()))
        val deadline = System.nanoTime() + 60_000_000_000L
        var state: Properties? = null
        while (System.nanoTime() < deadline) {
            state = runCatching {
                Properties().apply { Files.newBufferedReader(restarted).use { load(it) } }
            }.getOrNull()
            if (state?.getProperty("status") != null) break
            Thread.sleep(50)
        }
        assertEquals("ok", state?.getProperty("status"), "Restarted bundle did not pass runtime checks: $state")
        assertTrue(Path.of(requireNotNull(state).getProperty("java.home")).toRealPath()
            .startsWith(installed.resolve("Contents/runtime").toRealPath()))
        assertNotEquals(before, key())
        assertEquals("untouched", Files.readString(userData))
        assertTrue(Files.isExecutable(installed.resolve("Contents/app/resources/browser/kototoro-webview-bridge")))
        assertTrue(Files.readString(log).contains("Update installed"))
        val installedKey = key()
        // No real application is launched: /usr/bin/false exercises the failure after the candidate was swapped in.
        Files.writeString(script, macosUpdateScript(staged, log, true, ownerPid = owner.pid(),
            restartCommand = listOf("/usr/bin/false")))
        run(listOf("/bin/sh", script.toString()), success = false)
        assertEquals(installedKey, key(), "Rollback must restore the exact previous bundle")
        assertTrue(Files.readString(log).contains("previous application restored"))
        assertEquals("untouched", Files.readString(userData))
    }

    private fun run(arguments: List<String>, success: Boolean = true) {
        val log = Files.createTempFile(directory, "native-", ".log")
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Native command timed out: $arguments")
            assertEquals(success, process.exitValue() == 0, Files.readString(log))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
