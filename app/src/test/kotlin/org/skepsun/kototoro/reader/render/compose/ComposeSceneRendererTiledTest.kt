package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.image.ImageSourceGeometry
import org.skepsun.kototoro.reader.image.TileGrid
import org.skepsun.kototoro.reader.image.TileSplit
import org.skepsun.kototoro.reader.core.IntSize as CoreIntSize

class ComposeSceneRendererTiledTest {

    @Test
    fun `computeTileDrawParams accurately crops gutter for interior tile`() {
        val pageId = PageId(1L)
        val geometry = ImageSourceGeometry(CoreIntSize(800, 3000))
        val grid = TileGrid(
            pageId = pageId,
            geometry = geometry,
            tileDimension = CoreIntSize(800, 1000),
            sampleSize = 1,
            outputGutterPx = 16,
            seamPaddingPx = 16,
        )

        // Tile row 1: logicalRect is y in [1000, 2000]
        val spec = grid.tileAt(0, 1)!!
        assertEquals(0, spec.logicalRect.left)
        assertEquals(1000, spec.logicalRect.top)
        assertEquals(800, spec.logicalRect.width)
        assertEquals(1000, spec.logicalRect.height)

        // decodeRegion has 16px gutter on top (y=984) and bottom (y=2016)
        assertEquals(984, spec.decodeRegion.top)
        assertEquals(1032, spec.decodeRegion.height)

        val params = TiledPageDrawMath.computeTileDrawParams(
            grid = grid,
            spec = spec,
            tileBitmapWidth = 800,
            tileBitmapHeight = 1032,
            splitOriginX = 0,
            screenLeft = 0f,
            screenTop = 50f,
            toScreenX = 1f,
            toScreenY = 1f,
        )

        assertNotNull(params)
        // srcOffset must crop the top gutter (16px)
        assertEquals(IntOffset(0, 16), params!!.srcOffset)
        // srcSize must be exactly the logical content size (800x1000), ignoring gutters
        assertEquals(IntSize(800, 1000), params.srcSize)
        // dstOffset reflects screenTop (50) + logical top (1000)
        assertEquals(IntOffset(0, 1050), params.dstOffset)
        assertEquals(IntSize(800, 1000), params.dstSize)
    }

    @Test
    fun `computeTileDrawParams handles sampleSize downscaling`() {
        val pageId = PageId(2L)
        val geometry = ImageSourceGeometry(CoreIntSize(800, 2000))
        val grid = TileGrid(
            pageId = pageId,
            geometry = geometry,
            tileDimension = CoreIntSize(800, 1000),
            sampleSize = 2,
            outputGutterPx = 16,
            seamPaddingPx = 16,
        )

        val spec = grid.tileAt(0, 1)!!
        // For sampleSize=2, decodeRegion gutter in raw coords is 16 * 2 = 32px
        // rawSrcTop = 32px
        val params = TiledPageDrawMath.computeTileDrawParams(
            grid = grid,
            spec = spec,
            tileBitmapWidth = 400,
            tileBitmapHeight = 532,
            splitOriginX = 0,
            screenLeft = 0f,
            screenTop = 0f,
            toScreenX = 1f,
            toScreenY = 1f,
        )

        assertNotNull(params)
        // srcOffset.y = 32 / 2 = 16
        assertEquals(IntOffset(0, 16), params!!.srcOffset)
        // srcSize = 800 / 2 = 400, 1000 / 2 = 500
        assertEquals(IntSize(400, 500), params.srcSize)
    }

    @Test
    fun `computeTileDrawParams translates coordinates for right-split double spread`() {
        val pageId = PageId(3L)
        val geometry = ImageSourceGeometry(CoreIntSize(1600, 2000))
        val grid = TileGrid(
            pageId = pageId,
            geometry = geometry,
            split = TileSplit.RIGHT,
            tileDimension = CoreIntSize(800, 1000),
            sampleSize = 1,
            outputGutterPx = 0,
            // This case is about split translation, so the bitmap carries no padding.
            seamPaddingPx = 0,
        )

        assertEquals(800, grid.pageSize.width)
        val spec = grid.tileAt(0, 0)!!

        val splitOriginX = grid.contentLogicalSize.width - grid.pageSize.width // 1600 - 800 = 800
        val params = TiledPageDrawMath.computeTileDrawParams(
            grid = grid,
            spec = spec,
            tileBitmapWidth = 800,
            tileBitmapHeight = 1000,
            splitOriginX = splitOriginX,
            screenLeft = 0f,
            screenTop = 0f,
            toScreenX = 1f,
            toScreenY = 1f,
        )

        assertNotNull(params)
        assertEquals(IntOffset(0, 0), params!!.srcOffset)
        assertEquals(IntSize(800, 1000), params.srcSize)
    }
}
