package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.reader.core.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

class DesktopTileDecoderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `PNG and JPEG long pages decode only the requested sampled region with correct pixels`() = runBlocking<Unit> {
        val original = BufferedImage(1200, 12000, BufferedImage.TYPE_INT_RGB)
        val graphics = original.createGraphics()
        try {
            graphics.color = Color.RED; graphics.fillRect(0, 0, 1200, 6000)
            graphics.color = Color.GREEN; graphics.fillRect(0, 6000, 1200, 6000)
        } finally { graphics.dispose() }
        try {
            for (format in listOf("png", "jpeg")) {
                val path = directory.resolve("long.$format")
                assertTrue(ImageIO.write(original, format, path.toFile()))
                assertTrue(DesktopTileDecoder.supported(path))
                val grid = TileGrid(PageId(1), ImageSourceGeometry(IntSize(1200, 12000)),
                    tileDimension = IntSize(1024, 1024), sampleSize = 2, outputGutterPx = 0)
                val spec = grid.tileAt(0, 10)!!
                val tile = DesktopTileDecoder.decode(path, spec)
                try {
                    assertEquals(spec.decodedSize.width, tile.width)
                    assertEquals(spec.decodedSize.height, tile.height)
                    assertTrue(tile.width <= 528 && tile.height <= 528)
                    val color = Color(tile.getRGB(tile.width / 2, tile.height / 2))
                    assertTrue(color.green > 240 && color.red < 15 && color.blue < 15)
                } finally { tile.flush() }
            }
        } finally { original.flush() }
    }

    @Test
    fun `oversized tile requests are rejected before opening the decoder`() = runBlocking<Unit> {
        val spec = TileSpec(TileKey(PageId(1), sampleSize = 1, col = 0, row = 0),
            IntRect(0, 0, 10000, 10000), IntRect(0, 0, 10000, 10000), 1)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DesktopTileDecoder.decode(directory.resolve("unopened.png"), spec) }
        }
    }
}
