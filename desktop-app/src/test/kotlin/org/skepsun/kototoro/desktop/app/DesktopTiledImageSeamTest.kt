package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.VisibleNode
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/** Issue #602: a tiled long page drawn at a fractional scale showed the background along every tile edge. */
@OptIn(ExperimentalTestApi::class)
class DesktopTiledImageSeamTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `tile edges at fractional scales stay covered by the page`() {
        val width = 1200
        val height = 6000
        val path = directory.resolve("tall.png")
        val source = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        try {
            val graphics = source.createGraphics()
            try { graphics.color = java.awt.Color.WHITE; graphics.fillRect(0, 0, width, height) } finally { graphics.dispose() }
            check(ImageIO.write(source, "png", path.toFile()))
        } finally { source.flush() }
        val image = DesktopReaderImage(path, width, height, regionSupported = true)
        // 1200 -> 777 px wide: tile edges (every 512 source px) land on fractional screen positions.
        val shownWidth = 777f
        val shownHeight = shownWidth * height / width
        val node = VisibleNode(PageId(1), FloatRect(0f, 0f, shownWidth, shownHeight),
            FloatRect(0f, 0f, shownWidth, 900f))
        runDesktopComposeUiTest(width = 800, height = 900) {
            setContent {
                val density = LocalDensity.current
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    with(density) {
                        DesktopTiledImage(image, node, modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                            .size(shownWidth.toDp(), shownHeight.toDp()))
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) {
                (0..2).all { row -> (0..2).all { col ->
                    onAllNodes(hasTestTag("reader-tile:$row:$col")).fetchSemanticsNodes().isNotEmpty() } }
            }
            waitForIdle()
            val pixels = onRoot().captureToImage().toPixelMap()
            val scale = shownWidth / width
            val seams = (1..2).map { (it * 512 * scale) }
            var darkest = 1f
            for (seam in seams) {
                val column = seam.toInt()
                for (x in column - 1..column + 1) for (y in 10 until 890 step 7) {
                    darkest = minOf(darkest, pixels[x, y].red)
                }
                for (y in column - 1..column + 1) for (x in 10 until 760 step 7) {
                    darkest = minOf(darkest, pixels[x, y].red)
                }
            }
            assertTrue(darkest > .97f, "tile seam lets the background through: darkest red $darkest")
        }
    }
}
