package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android shows an extension source with the launcher icon of its APK; the desktop install copies it out of the
 * archive before the resources are dropped, so the tile never needs the site's favicon.
 */
class DesktopExtensionIconsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `the declared launcher bitmap wins over other images and densest wins over sparser`() {
        val apk = apk(
            "res/drawable/banner.png" to png(0x11),
            "res/mipmap-mdpi/ic_launcher.png" to png(0x22),
            "res/mipmap-xxxhdpi/ic_launcher.png" to png(0x33),
            "res/mipmap-anydpi-v26/ic_launcher.xml" to "<adaptive-icon/>".toByteArray(),
        )
        val icon = DesktopExtensionIcons.extract(apk)
        assertNotNull(icon)
        assertArrayEquals(png(0x33), icon)
    }

    @Test
    fun `a nine-patch frame, an adaptive icon only package or a non-image entry is not an icon`() {
        assertNull(DesktopExtensionIcons.extract(apk("res/mipmap-xxhdpi/ic_launcher.9.png" to png(0x44))))
        assertNull(DesktopExtensionIcons.extract(apk("res/mipmap-anydpi-v26/ic_launcher.xml" to "<adaptive-icon/>".toByteArray())))
        // A name that promises an icon but whose bytes are not an image (an R8 leftover, a text file).
        assertNull(DesktopExtensionIcons.extract(apk("res/drawable/ic_launcher.png" to "not an image".toByteArray())))
        assertNull(DesktopExtensionIcons.extract(apk("classes.dex" to png(0x55))))
    }

    @Test
    fun `a stored icon is kept beside the artifact and is not rewritten`() {
        val apk = apk("res/mipmap-hdpi/ic_launcher.webp" to webp(0x66), "res/mipmap-xxhdpi/ic_launcher.png" to png(0x77))
        val artifact = Files.createFile(directory.resolve("a".repeat(64) + ".jar"))
        val stored = DesktopExtensionIcons.store(apk, artifact)
        assertNotNull(stored)
        assertTrue(Files.isRegularFile(stored))
        assertArrayEquals(png(0x77), Files.readAllBytes(stored!!))
        // The artifact is immutable and named by its hash, so the second install keeps the file it already has.
        Files.write(stored, png(0x88))
        assertArrayEquals(png(0x88), Files.readAllBytes(DesktopExtensionIcons.store(apk, artifact)!!))
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): Path {
        val path = directory.resolve("extension-${entries.size}.apk")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }

    /** Enough of a PNG for the signature check; the icon pipeline only looks at the header here. */
    private fun png(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, marker.toByte(), 0, 0, 0, 0, 0, 0, 0,
    )

    private fun webp(marker: Int): ByteArray =
        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + byteArrayOf(marker.toByte())
}
