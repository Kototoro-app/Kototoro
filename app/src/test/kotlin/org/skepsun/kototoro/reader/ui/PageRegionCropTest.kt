package org.skepsun.kototoro.reader.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class PageRegionCropTest {

    @Test
    fun `a frame on a downsampled stitched strip maps to full source pixels`() {
        // A 1432 x 5436 strip shown at half size, 716 x 2718, placed at (10, 20) on screen.
        val region = cropRegionInSource(
            cropLeft = 10f, cropTop = 20f + 1359f, cropRight = 10f + 716f, cropBottom = 20f + 2718f,
            imageLeft = 10f, imageTop = 20f, imageRight = 10f + 716f, imageBottom = 20f + 2718f,
            sourceWidth = 1432,
            sourceHeight = 5436,
        )

        region shouldBe PixelRegion(left = 0, top = 2718, right = 1432, bottom = 5436)
    }

    @Test
    fun `a frame covering the whole image is the whole source`() {
        val region = cropRegionInSource(
            cropLeft = 0.3f, cropTop = -0.2f, cropRight = 499.8f, cropBottom = 1000.4f,
            imageLeft = 0f, imageTop = 0f, imageRight = 500f, imageBottom = 1000f,
            sourceWidth = 1440,
            sourceHeight = 2880,
        )

        region?.isWhole(1440, 2880) shouldBe true
    }

    @Test
    fun `a frame off the image gives no region`() {
        cropRegionInSource(
            cropLeft = 600f, cropTop = 0f, cropRight = 700f, cropBottom = 100f,
            imageLeft = 0f, imageTop = 0f, imageRight = 500f, imageBottom = 1000f,
            sourceWidth = 1000,
            sourceHeight = 2000,
        ) shouldBe null
    }

    @Test
    fun `an image turned by its orientation is not taken as upright`() {
        isSameOrientation(716, 2718, 1432, 5436) shouldBe true
        isSameOrientation(2718, 716, 1432, 5436) shouldBe false
    }

    @Test
    fun `quantization tables scaled by libjpeg give back their quality`() {
        for (quality in listOf(50, 75, 85, 90, 95)) {
            jpegQualityOf(libjpegTable(quality)) shouldBe quality
        }
    }

    @Test
    fun `the quality is read from the luminance table of a JPEG file`(@TempDir dir: File) {
        val file = File(dir, "page.jpg")
        file.writeBytes(jpegHeader(luminance = libjpegTable(82), chrominance = IntArray(64) { 99 }))

        estimateJpegQuality(file) shouldBe 82
    }

    @Test
    fun `a file that is not a JPEG has no quality`(@TempDir dir: File) {
        val file = File(dir, "page.png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))

        estimateJpegQuality(file) shouldBe null
    }

    private fun libjpegTable(quality: Int): IntArray {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        return STANDARD.map { ((it * scale + 50) / 100).coerceIn(1, 255) }.toIntArray()
    }

    /** SOI, an APP0 segment to skip, one DQT segment holding chrominance then luminance, then SOS. */
    private fun jpegHeader(luminance: IntArray, chrominance: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2))
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte()))
        val length = 2 + 2 * 65
        out.write(length shr 8)
        out.write(length and 0xFF)
        out.write(1)
        chrominance.forEach { out.write(it) }
        out.write(0)
        luminance.forEach { out.write(it) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2))
        return out.toByteArray()
    }

    private companion object {

        val STANDARD = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99,
        )
    }
}
