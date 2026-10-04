package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class DesktopImageDecoderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `ordinary page remains full resolution and pixels survive Compose ownership transfer`() = runBlocking<Unit> {
        val path = page(160, 240, "png")
        val header = DesktopImageDecoder.header(path)
        assertEquals(160, header.width)
        assertEquals(240, header.height)
        assertTrue(header.regionSupported)
        DesktopImageDecoder.decode(path).use { bitmap ->
            val composed = bitmap.asComposeImageBitmap()
            assertSame(bitmap, composed.asSkiaBitmap())
            assertTrue(bitmap.isImmutable)
            assertEquals(160, composed.width)
            assertEquals(240, composed.height)
            val pixels = IntArray(1)
            composed.readPixels(pixels, startX = 80, startY = 120, width = 1, height = 1)
            assertEquals(org.jetbrains.skia.Color.RED, pixels.single())
            assertEquals(160, bitmap.width)
            assertEquals(240, bitmap.height)
            assertEquals(org.jetbrains.skia.Color.RED, bitmap.getColor(80, 120))
        }
        // The decoder disposes source images and streams before returning.
        Files.move(path, path.resolveSibling("released.png"))
    }

    @Test
    fun `large PNG JPEG and WebP keep original scene geometry but allocate bounded destination pixels`() = runBlocking<Unit> {
        val png = page(3000, 2000, "png")
        val jpeg = page(3000, 2000, "jpeg")
        val webp = directory.resolve("page.webp")
        Image.makeFromEncoded(Files.readAllBytes(png)).use { image ->
            requireNotNull(image.encodeToData(EncodedImageFormat.WEBP)).use { Files.write(webp, it.bytes) }
        }
        for (path in listOf(png, jpeg, webp)) {
            val header = DesktopImageDecoder.header(path)
            assertEquals(3000, header.width)
            assertEquals(2000, header.height)
            assertEquals(path != webp, header.regionSupported)
            DesktopImageDecoder.decode(path).use { bitmap ->
                assertTrue(bitmap.width < header.width && bitmap.height < header.height)
                assertTrue(bitmap.width.toLong() * bitmap.height <= DesktopImageDecoder.MAXIMUM_PIXELS)
                assertTrue(bitmap.computeByteSize() <= 16 * 1024 * 1024)
                val color = bitmap.getColor(bitmap.width / 2, bitmap.height / 2)
                assertTrue(org.jetbrains.skia.Color.getR(color) > 240)
                assertTrue(org.jetbrains.skia.Color.getG(color) < 15 && org.jetbrains.skia.Color.getB(color) < 15)
            }
        }
    }

    @Test
    fun `PNG dimensions do not require reading image pixel payload`() {
        val original = page(128, 24000, "png")
        val headerOnly = directory.resolve("header-only.png")
        Files.write(headerOnly, Files.readAllBytes(original).copyOf(33))
        val header = DesktopImageDecoder.header(headerOnly)
        assertEquals(128, header.width)
        assertEquals(24000, header.height)
        assertTrue(header.tiled)
        Files.move(headerOnly, directory.resolve("header-released.png"))
    }

    @Test
    fun `bounded native conversion preserves transparent page pixels`() = runBlocking<Unit> {
        val path = directory.resolve("alpha.png")
        val original = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        try {
            for (y in 0 until 100) for (x in 0 until 100) original.setRGB(x, y, 0x80FF0000.toInt())
            assertTrue(ImageIO.write(original, "png", path.toFile()))
        } finally { original.flush() }
        DesktopImageDecoder.decode(path).use { bitmap ->
            val color = bitmap.getColor(50, 50)
            assertEquals(128, org.jetbrains.skia.Color.getA(color))
            assertEquals(255, org.jetbrains.skia.Color.getR(color))
            assertEquals(0, org.jetbrains.skia.Color.getG(color))
            assertEquals(0, org.jetbrains.skia.Color.getB(color))
        }
    }

    @Test
    fun `corrupt pixels fail and waiting decode cancellation never consumes a permit`() = runBlocking<Unit> {
        val path = directory.resolve("invalid.img")
        Files.write(path, byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { DesktopImageDecoder.decode(path) } }
        val good = page(160, 240, "png")
        repeat(2) { DesktopImageDecoder.permits.acquire() }
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { DesktopImageDecoder.decode(good).close() }
            assertTrue(job.isActive)
            job.cancelAndJoin()
            assertEquals(0, DesktopImageDecoder.permits.availablePermits)
        } finally { repeat(2) { DesktopImageDecoder.permits.release() } }
        DesktopImageDecoder.decode(good).use { assertEquals(160, it.width) }
        assertEquals(2, DesktopImageDecoder.permits.availablePermits)
    }

    private fun page(width: Int, height: Int, format: String): Path {
        val path = directory.resolve("${width}x$height.$format")
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.fillRect(0, 0, width, height)
            assertTrue(ImageIO.write(image, format, path.toFile()))
        } finally { graphics.dispose(); image.flush() }
        return path
    }
}
