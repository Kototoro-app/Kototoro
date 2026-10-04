package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisibleImageTilesTest {
    @Test
    fun `long page visibility selects only middle tiles with sampling and bounded payload`() {
        val node = VisibleNode(PageId(1), FloatRect(100f, 200f, 600f, 30200f),
            FloatRect(100f, 12200f, 600f, 12900f))
        val tiles = VisibleImageTiles.resolve(node, IntSize(2000, 120000))
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.size <= 4)
        assertTrue(tiles.all { it.sampleSize == 4 && it.estimatedBytes < 2L * 1024 * 1024 })
        assertTrue(tiles.all { it.logicalRect.top >= 45000 && it.logicalRect.bottom <= 53000 })
    }

    @Test
    fun `zoom selects finer tiles and page edges clamp source regions without affecting progress`() {
        val node = VisibleNode(PageId(1), FloatRect(0f, 0f, 500f, 3000f), FloatRect(250f, 2850f, 500f, 3000f))
        val coarse = VisibleImageTiles.resolve(node, IntSize(2000, 12000))
        val detailed = VisibleImageTiles.resolve(node, IntSize(2000, 12000), 4f)
        assertTrue(coarse.all { it.sampleSize == 4 })
        assertTrue(detailed.all { it.sampleSize == 1 && it.decodeRegion.right <= 2000 && it.decodeRegion.bottom <= 12000 })
        assertTrue(detailed.none { it.logicalRect.left < 512 })
        assertThrows(IllegalArgumentException::class.java) { VisibleImageTiles.resolve(node, IntSize(2000, 12000), Float.NaN) }
    }
}
