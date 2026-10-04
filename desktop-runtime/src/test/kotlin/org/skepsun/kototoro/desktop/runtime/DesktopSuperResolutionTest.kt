package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class DesktopSuperResolutionTest {
    @TempDir lateinit var root: Path

    @Test
    fun `archives are refused unless they are the pinned release and nothing runs without a program`() = runBlocking<Unit> {
        val upscale = DesktopSuperResolution(root)
        val fake = root.resolve("fake.zip")
        ZipOutputStream(Files.newOutputStream(fake)).use { zip ->
            zip.putNextEntry(ZipEntry("realcugan-ncnn-vulkan.exe")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        val refused = assertThrows(DesktopUpscaleException::class.java) {
            runBlocking { upscale.installArchive(DesktopUpscaleTool.REALCUGAN, fake) }
        }
        assertTrue(refused.message!!.contains("SHA-256"))
        assertNull(upscale.executable(DesktopUpscaleTool.REALCUGAN))
        val page = page(root.resolve("page.png"), 40, 60)
        assertNull(upscale.upscale(page, 40, 60, DesktopUpscaleSetting(null)))
        // Oversized pages are left alone before any program is needed.
        assertNull(upscale.upscale(page, 4000, 4000, DesktopUpscaleSetting(DesktopUpscaleModel.REALESRGAN_4X_ANIME)))
        assertThrows(DesktopUpscaleException::class.java) {
            runBlocking { upscale.upscale(page, 40, 60, DesktopUpscaleSetting(DesktopUpscaleModel.REALCUGAN_2X)) }
        }
    }

    /** Opt-in: `-PncnnDirectory=<dir with the two official windows zips>`; needs a Vulkan GPU. */
    @Test
    fun `the official programs upscale a page by the model's factor and cache the result`() = runBlocking<Unit> {
        val directory = System.getProperty("kototoro.ncnn.dir")
        assumeTrue(directory != null, "no -PncnnDirectory")
        val upscale = DesktopSuperResolution(root)
        for (tool in DesktopUpscaleTool.entries) upscale.installArchive(tool, Path.of(directory!!, tool.asset))
        val input = page(root.resolve("page-without-extension"), 120, 180)
        for ((model, noise) in listOf(DesktopUpscaleModel.REALCUGAN_2X to 1, DesktopUpscaleModel.REALESRGAN_4X_ANIME to -1,
            DesktopUpscaleModel.REALESR_ANIMEVIDEO_2X to -1)) {
            val setting = DesktopUpscaleSetting(model, noise)
            val started = System.nanoTime()
            val output = requireNotNull(upscale.upscale(input, 120, 180, setting))
            val image = ImageIO.read(output.toFile())
            assertEquals(120 * model.scale, image.width, model.name)
            assertEquals(180 * model.scale, image.height, model.name)
            println("$model ${(System.nanoTime() - started) / 1_000_000} ms")
            val again = System.nanoTime()
            assertEquals(output, upscale.upscale(input, 120, 180, setting))
            assertTrue((System.nanoTime() - again) / 1_000_000 < 500, "cached result reused")
        }
    }

    private fun page(path: Path, width: Int, height: Int): Path {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE; fillRect(0, 0, width, height)
            color = Color.BLACK; drawString("漫画 Kototoro", 5, height / 2); drawOval(10, 10, width - 20, height - 40)
            dispose()
        }
        ImageIO.write(image, "png", path.toFile())
        return path
    }
}