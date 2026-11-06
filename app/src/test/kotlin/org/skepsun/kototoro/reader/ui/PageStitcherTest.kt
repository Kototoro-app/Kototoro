package org.skepsun.kototoro.reader.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderMode

class PageStitcherTest {

    @Test
    fun `stacked pages keep their resolution and neighbours scale to the page width`() {
        val layout = stitchLayout(
            sizes = listOf(400 to 600, 800 to 3000, 1600 to 2000),
            baseIndex = 1,
            horizontal = false,
            maxPixels = 40_000_000L,
        )

        layout shouldBe StitchLayout(width = 800, height = 5200, extents = listOf(1200, 3000, 1000))
    }

    @Test
    fun `pages side by side scale to the page height`() {
        val layout = stitchLayout(
            sizes = listOf(600 to 900, 400 to 600, 1200 to 1200),
            baseIndex = 1,
            horizontal = true,
            maxPixels = 40_000_000L,
        )

        layout shouldBe StitchLayout(width = 1400, height = 600, extents = listOf(400, 400, 600))
    }

    @Test
    fun `a stitch over the pixel budget shrinks as a whole`() {
        val layout = stitchLayout(
            sizes = listOf(1000 to 4000, 1000 to 4000),
            baseIndex = 0,
            horizontal = false,
            maxPixels = 2_000_000L,
        )

        layout shouldBe StitchLayout(width = 500, height = 4000, extents = listOf(2000, 2000))
    }

    @Test
    fun `a stitch longer than the side limit shrinks to fit it`() {
        val layout = stitchLayout(
            sizes = listOf(600 to 100, 600 to 100),
            baseIndex = 0,
            horizontal = true,
            maxPixels = 40_000_000L,
            maxSide = 600,
        )

        layout shouldBe StitchLayout(width = 600, height = 50, extents = listOf(300, 300))
    }

    @Test
    fun `direction follows the reader mode`() {
        StitchDirection.forReaderMode(ReaderMode.STANDARD, false) shouldBe StitchDirection.LEFT_TO_RIGHT
        StitchDirection.forReaderMode(ReaderMode.REVERSED, false) shouldBe StitchDirection.RIGHT_TO_LEFT
        StitchDirection.forReaderMode(ReaderMode.WEBTOON, true) shouldBe StitchDirection.TOP_TO_BOTTOM
        StitchDirection.forReaderMode(ReaderMode.VERTICAL, false) shouldBe StitchDirection.TOP_TO_BOTTOM
        StitchDirection.forReaderMode(ReaderMode.CONTINUOUS_HORIZONTAL, false) shouldBe
            StitchDirection.LEFT_TO_RIGHT
        StitchDirection.forReaderMode(ReaderMode.CONTINUOUS_HORIZONTAL, true) shouldBe
            StitchDirection.RIGHT_TO_LEFT
        StitchDirection.forReaderMode(null, false) shouldBe StitchDirection.TOP_TO_BOTTOM
    }
}
