package org.skepsun.kototoro.reader.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Levels a tiled page is painted from, as a pure function of source size, viewport and camera.
 *
 * The device measurement behind these expectations: a 6000x9000 page shown at fit scale in a
 * 1280x2772 viewport was painted from level zero (1 px of image per px of screen for a page drawn at
 * 0.31), which needed 43 tiles - about 3.55M screen px / 0.31^2 = 45M image px per frame. The fit
 * level needs a sixteenth of those pixels.
 */
class TileLadderTest {

    @Test
    fun `a page shown at fit scale has no target layer`() {
        val ladder = resolveTileLadder(sourceContentWidthPx = 6000, viewportWidthPx = 1280, cameraScale = 1f)

        assertEquals(4, ladder.baseSampleSize)
        assertNull(ladder.targetSampleSize)
    }

    @Test
    fun `magnifying adds exactly the level the camera demands`() {
        // 6000px across a 1280px viewport at 1.15x tolerance: 2.5x resolves sample size 2, 4x -> 1.
        assertEquals(2, resolveTileLadder(6000, 1280, 2.5f).targetSampleSize)
        assertEquals(1, resolveTileLadder(6000, 1280, 4f).targetSampleSize)
    }

    @Test
    fun `the base layer never follows the camera into the zoom`() {
        // The base is what a page that is *not* the zoomed one shows, so no camera scale may turn it
        // into a level-zero grid: that is the frame that painted 160-200MB of tiles for one screen.
        for (scale in listOf(1f, 1.5f, 2f, 2.5f, 4f, 8f)) {
            val ladder = resolveTileLadder(6000, 1280, scale)

            assertEquals(4, ladder.baseSampleSize, "base at scale=$scale")
            assertTrue((ladder.targetSampleSize ?: 4) <= 4, "target at scale=$scale")
        }
    }

    @Test
    fun `zooming out past fit coarsens the base instead of keeping a sharp level`() {
        // At half of fit the page spans half the viewport, so the fit decode is 4x more pixels than
        // the screen can show; the base steps one level coarser rather than staying at fit.
        val ladder = resolveTileLadder(6000, 1280, 0.5f)

        assertEquals(8, ladder.baseSampleSize)
        assertNull(ladder.targetSampleSize)
    }

    @Test
    fun `a camera at or below fit never asks for a finer layer`() {
        for (scale in listOf(0.25f, 0.5f, 1f, 1.2f)) {
            assertNull(resolveTileLadder(6000, 1280, scale).targetSampleSize, "target at scale=$scale")
        }
    }

    @Test
    fun `a camera just over the level boundary keeps the level already in use`() {
        // Hysteresis: feeding the level in use defers the switch by the policy's 15% band, so a pinch
        // hovering on a boundary does not rebuild a target layer on every settle.
        assertEquals(2, resolveTileLadder(6000, 1280, 1.35f).targetSampleSize)
        assertNull(resolveTileLadder(6000, 1280, 1.35f, currentSampleSize = 4).targetSampleSize)
    }

    @Test
    fun `a target layer is withdrawn as soon as the camera is back at fit`() {
        // The hysteresis band must not keep a level-zero target alive at fit scale: the base already
        // carries the fit level, so a target that is only "not yet clearly too sharp" is dead weight.
        // SceneParityRegressionTest caught exactly this when the band was applied to the whole ladder.
        assertNull(resolveTileLadder(1600, 800, 1f, currentSampleSize = 1).targetSampleSize)
    }

    @Test
    fun `a webtoon strip keeps a single level end to end`() {
        // Strip pages are not magnified in the continuous hosts, and their width already fits the
        // viewport, so the ladder has no second rung to add.
        val ladder = resolveTileLadder(sourceContentWidthPx = 1080, viewportWidthPx = 1280, cameraScale = 1f)

        assertEquals(1, ladder.baseSampleSize)
        assertNull(ladder.targetSampleSize)
    }
}
