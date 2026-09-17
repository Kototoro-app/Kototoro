package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId

class TileGridTest {

    private val pageId = PageId(1L)

    @Test
    fun `full-width strip builds single column lattice`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 2048),
        )

        assertEquals(1, grid.columns)
        assertEquals(8, grid.rows)
        assertEquals(8, grid.tileCount)
        assertEquals(IntSize(800, 16000), grid.pageSize)

        val first = grid.tileAt(0, 0)!!
        assertEquals(IntRect(0, 0, 800, 2048), first.logicalRect)
        // Gutter 128 expands and clamps at top/left edges of the encoded image.
        assertEquals(IntRect(0, 0, 800, 2176), first.decodeRegion)

        val last = grid.tileAt(0, 7)!!
        assertEquals(IntRect(0, 14336, 800, 16000), last.logicalRect)
        assertEquals(IntRect(0, 14208, 800, 16000), last.decodeRegion)

        assertNull(grid.tileAt(0, 8))
        assertNull(grid.tileAt(1, 0))
    }

    @Test
    fun `wide source builds 2D lattice`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(3000, 3000)),
            tileDimension = IntSize(1024, 1024),
        )

        assertEquals(3, grid.columns)
        assertEquals(3, grid.rows)
        val corner = grid.tileAt(2, 2)!!
        assertEquals(IntRect(2048, 2048, 3000, 3000), corner.logicalRect)
    }

    @Test
    fun `crop content offset shifts decode regions into encoded space`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(
                encodedSize = IntSize(800, 6200),
                contentRect = IntRect.fromLtwh(100, 200, 600, 5800),
            ),
            tileDimension = IntSize(600, 2048),
        )

        assertEquals(IntSize(600, 5800), grid.pageSize)
        val tile = grid.tileAt(0, 0)!!
        assertEquals(IntRect(0, 0, 600, 2048), tile.logicalRect)
        // [100,200,700,2248] expanded by 128 then clamped to [0,0,800,6200]
        assertEquals(IntRect(0, 72, 800, 2376), tile.decodeRegion)
    }

    @Test
    fun `split RIGHT maps page half onto second half of content`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            split = TileSplit.RIGHT,
            tileDimension = IntSize(400, 2048),
        )

        assertEquals(IntSize(400, 16000), grid.pageSize)
        val tile = grid.tileAt(0, 0)!!
        // Logical [0,0,400,2048] -> content [400,0,800,2048] -> encoded + gutter, clamped.
        assertEquals(IntRect(272, 0, 800, 2176), tile.decodeRegion)
    }

    @Test
    fun `split LEFT maps page half onto first half of content`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            split = TileSplit.LEFT,
            tileDimension = IntSize(400, 2048),
        )

        val tile = grid.tileAt(0, 0)!!
        assertEquals(IntRect(0, 0, 400, 2048), tile.logicalRect)
        // Same as unsplit left half: gutter clamps at the encoded left edge.
        assertEquals(IntRect(0, 0, 528, 2176), tile.decodeRegion)
    }

    @Test
    fun `orientation 90 rotates logical regions into encoded space`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(
                encodedSize = IntSize(1000, 800),
                orientationDegrees = 90,
            ),
            tileDimension = IntSize(400, 500),
        )

        // Logical page is 800x1000 (transposed content).
        assertEquals(IntSize(800, 1000), grid.pageSize)
        val tile = grid.tileAt(0, 0)!!
        assertEquals(IntRect(0, 0, 400, 500), tile.logicalRect)
        // Logical [0,0,400,500] --90deg--> [0,400,500,800], + gutter 128 clamped to [0,0,1000,800].
        assertEquals(IntRect(0, 272, 628, 800), tile.decodeRegion)
    }

    @Test
    fun `gutter scales with sampleSize to suppress seams`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(
                encodedSize = IntSize(800, 6200),
                contentRect = IntRect.fromLtwh(100, 200, 600, 5800),
            ),
            tileDimension = IntSize(600, 2048),
            sampleSize = 4,
        )

        val tile = grid.tileAt(0, 0)!!
        // Source gutter = 128 * 4 = 512: [100,200,700,2248] -> [-412,-312,1212,2760] -> clamped.
        assertEquals(IntRect(0, 0, 800, 2760), tile.decodeRegion)
        assertEquals(IntSize(200, 690), tile.decodedSize)
        assertEquals(200L * 690L * 4L, tile.estimatedBytes)
    }

    @Test
    fun `tilesIntersecting expands the query by the output gutter`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 512),
        )

        // Visible [0,0,800,512] + gutter 128 -> effective bottom 640 -> rows 0..1
        val tiles = grid.tilesIntersecting(IntRect(0, 0, 800, 512))
        assertEquals(listOf(0, 1), tiles.map { it.key.row })

        // Zero-gutter grid returns exactly the intersecting row.
        val noGutterGrid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 512),
            outputGutterPx = 0,
        )
        assertEquals(listOf(0), noGutterGrid.tilesIntersecting(IntRect(0, 0, 800, 512)).map { it.key.row })
    }

    @Test
    fun `tilesIntersecting returns empty when region is beyond the page`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 2048),
        )

        assertTrue(grid.tilesIntersecting(IntRect(0, 17000, 800, 18000)).isEmpty())
        assertTrue(grid.tilesIntersecting(IntRect(0, 0, 800, 0)).isEmpty())
    }

    @Test
    fun `tileOf resolves keys of the same grid and rejects foreign ones`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 2048),
            sampleSize = 1,
        )

        val key = grid.tileAt(0, 3)!!.key
        assertEquals(grid.tileAt(0, 3), grid.tileOf(key))
        assertNull(grid.tileOf(key.copy(sampleSize = 2)))
        assertNull(grid.tileOf(key.copy(pageId = PageId(99L))))
        assertNull(grid.tileOf(key.copy(kind = TileKind.OVERVIEW)))
    }

    @Test
    fun `overviewTile spans the whole page with OVERVIEW kind`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 16000)),
            tileDimension = IntSize(800, 2048),
        )

        val overview = grid.overviewTile(overviewSampleSize = 8)
        assertEquals(TileKind.OVERVIEW, overview.key.kind)
        assertEquals(8, overview.key.sampleSize)
        assertEquals(IntRect(0, 0, 800, 16000), overview.logicalRect)
        // Gutter 128 * 8 = 1024 clamps back to the full encoded bounds.
        assertEquals(IntRect(0, 0, 800, 16000), overview.decodeRegion)
        assertEquals(8, overview.sampleSize)

        // Overview key never collides with lattice keys.
        assertTrue(grid.tileAt(0, 0)!!.key != overview.key)

        assertThrows(IllegalArgumentException::class.java) { grid.overviewTile(overviewSampleSize = 3) }
    }

    @Test
    fun `allTiles covers page exactly once without overlap`() {
        val grid = TileGrid(
            pageId = pageId,
            geometry = ImageSourceGeometry(IntSize(800, 5000)),
            tileDimension = IntSize(800, 2048),
        )

        val tiles = grid.allTiles()
        assertEquals(3, tiles.size)
        var area = 0L
        tiles.forEach { area += it.logicalRect.width.toLong() * it.logicalRect.height }
        assertEquals(800L * 5000L, area)
        assertEquals(listOf(0, 1, 2), tiles.map { it.key.row })
    }

    @Test
    fun `invalid construction is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TileGrid(pageId, ImageSourceGeometry(IntSize(800, 16000)), tileDimension = IntSize(0, 2048))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileGrid(
                pageId,
                ImageSourceGeometry(IntSize(800, 16000)),
                tileDimension = IntSize(800, 2048),
                sampleSize = 3,
            )
        }
    }
}
