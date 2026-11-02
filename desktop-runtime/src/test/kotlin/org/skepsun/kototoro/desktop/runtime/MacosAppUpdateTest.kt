package org.skepsun.kototoro.desktop.runtime

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class MacosAppUpdateTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `release parsing isolates CPU OS channel and version in a mixed release`() {
        val names = listOf("Kototoro-9.0.0-windows-x64-release.zip",
            "Kototoro-2.0.0-macos-arm64-release.dmg", "Kototoro-2.0.0-macos-arm64-release.sha256",
            "Kototoro-3.0.0-macos-x64-release.dmg", "Kototoro-3.0.0-macos-x64-release.sha256",
            "Kototoro-N20261028-macos-arm64-nightly.dmg", "Kototoro-N20261028-macos-arm64-nightly.sha256",
            "Kototoro-2.0.0-macos-arm64-release.msi")
        val json = payload(names)
        val arm = parseReleases(json, DesktopUpdateChannel.RELEASE, DesktopUpdateTarget.MACOS_ARM64).single()
        assertEquals("2.0.0", arm.version.text)
        assertEquals(setOf(DesktopUpdatePackage.DMG), arm.packages.keys)
        assertTrue(arm.checksumsUrl!!.endsWith("macos-arm64-release.sha256"))
        assertEquals("3.0.0", parseReleases(json, DesktopUpdateChannel.RELEASE,
            DesktopUpdateTarget.MACOS_X64).single().version.text)
        assertEquals("N20261028", parseReleases(json, DesktopUpdateChannel.NIGHTLY,
            DesktopUpdateTarget.MACOS_ARM64).single().version.text)
        assertEquals("9.0.0", parseReleases(json, DesktopUpdateChannel.RELEASE).single().version.text)
        assertEquals(DesktopUpdateTarget.MACOS_ARM64, DesktopUpdateTarget.current(DesktopPlatform.MACOS, "aarch64"))
        assertEquals(DesktopUpdateTarget.MACOS_X64, DesktopUpdateTarget.current(DesktopPlatform.MACOS, "x86_64"))
        assertNull(DesktopUpdateTarget.current(DesktopPlatform.LINUX, "amd64"))
        assertNull(DesktopUpdateTarget.current(DesktopPlatform.MACOS, "unknown"))
        assertTrue(parseReleases(payload(listOf("Kototoro-N20261028/../../escape-macos-arm64-nightly.dmg")),
            DesktopUpdateChannel.NIGHTLY, DesktopUpdateTarget.MACOS_ARM64).isEmpty())
    }

    @Test
    fun `Mach-O CPU detection supports thin and universal launchers without developer tools`() {
        val file = directory.resolve("launcher")
        val arm = 0x0100000c
        val intel = 0x01000007
        val thin = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(0xfeedfacf.toInt()).putInt(arm).array()
        Files.write(file, thin)
        assertEquals(setOf(arm), macosExecutableArchitectures(file))
        val fat = java.nio.ByteBuffer.allocate(48).putInt(0xcafebabe.toInt()).putInt(2)
        fat.putInt(8, arm)
        fat.putInt(28, intel)
        Files.write(file, fat.array())
        assertEquals(setOf(arm, intel), macosExecutableArchitectures(file))
        Files.write(file, fat.array().copyOf(10))
        assertThrows(IllegalArgumentException::class.java) { macosExecutableArchitectures(file) }
    }

    @Test
    fun `DMG download verifies hash size and UDIF signature and removes failed partial files`() = runBlocking {
        val bytes = ByteArray(1024).apply { "koly".toByteArray().copyInto(this, size - 512) }
        val name = "Kototoro-2.0.0-macos-arm64-release.dmg"
        var checksum = digest(bytes)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/package") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/checksums") { exchange ->
            val data = "$checksum  $name\n".toByteArray()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val asset = DesktopUpdateAsset(name, "$base/package", bytes.size.toLong(), DesktopUpdatePackage.DMG)
            val release = DesktopUpdateRelease(DesktopPackageVersion.parse("2.0.0"), "v2", "", null, null,
                mapOf(DesktopUpdatePackage.DMG to asset), "$base/checksums")
            val update = DesktopAppUpdate(DesktopPackageVersion.parse("1.0.0"), release, true, "", bytes.size.toLong())
            val client = DesktopAppUpdateClient(update.current, DesktopUpdateChannel.RELEASE,
                target = DesktopUpdateTarget.MACOS_ARM64)
            val downloaded = client.download(update, DesktopUpdatePackage.DMG, directory.resolve("good"))
            assertArrayEquals(bytes, Files.readAllBytes(downloaded.file))
            val unsafe = update.copy(available = release.copy(packages = mapOf(
                DesktopUpdatePackage.DMG to asset.copy(name = "../escape.dmg"))))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                client.download(unsafe, DesktopUpdatePackage.DMG, directory.resolve("unsafe"))
            } }
            checksum = "0".repeat(64)
            assertThrows(Exception::class.java) { runBlocking {
                client.download(update, DesktopUpdatePackage.DMG, directory.resolve("bad-hash"))
            } }
            assertFalse(Files.exists(directory.resolve("bad-hash/$name.part")))
            bytes[bytes.size - 512] = 0
            checksum = digest(bytes)
            assertThrows(DesktopUpdateException::class.java) { runBlocking {
                client.download(update, DesktopUpdatePackage.DMG, directory.resolve("bad-container"))
            } }
            assertFalse(Files.exists(directory.resolve("bad-container/$name")))
        } finally { server.stop(0) }
    }

    private fun payload(names: List<String>) = """[{"tag_name":"mixed","assets":[${names.joinToString {
        """{"name":"$it","browser_download_url":"https://example.invalid/$it","size":1024}"""
    }}]}]"""

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
