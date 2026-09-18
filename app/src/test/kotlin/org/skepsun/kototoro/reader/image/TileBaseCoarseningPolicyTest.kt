package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tile density has to follow how a page is drawn right now, not how it was drawn when it was
 * acquired.
 *
 * A 6000x9000 page magnified to a ~3.6x camera resolves to level-zero tiles. When the reader turns
 * the page, the camera resets to fit scale but the level-zero grid stays, so the viewport reports a
 * whole page height of level-zero tiles as visible - 18 tiles, roughly 72MB, for one page. Coarsening
 * the base grid when the plan says a cheaper level suffices is the symmetric counterpart of adding a
 * finer target layer when the camera magnifies.
 */
class TileBaseCoarseningPolicyTest {

    @Test
    fun `a page planned two levels coarser is rebuilt at that level`() {
        // Magnified level zero, later displayed at fit scale where sampleSize 4 suffices.
        assertEquals(4, coarsenedBaseSampleSize(plannedSampleSize = 4, baseSampleSize = 1))
    }

    @Test
    fun `one power of two coarser is enough to be worth rebuilding`() {
        assertEquals(2, coarsenedBaseSampleSize(plannedSampleSize = 2, baseSampleSize = 1))
    }

    @Test
    fun `a grid already at the planned density is left alone`() {
        assertNull(coarsenedBaseSampleSize(plannedSampleSize = 1, baseSampleSize = 1))
    }

    @Test
    fun `a finer plan never coarsens the base`() {
        // Magnifying is the target layer's job; the base must not be downgraded into a blur.
        assertNull(coarsenedBaseSampleSize(plannedSampleSize = 1, baseSampleSize = 4))
    }
}
