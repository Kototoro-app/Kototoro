package org.skepsun.kototoro.reader.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageStitcherTest {

    @Test
    fun `pages keep their resolution and neighbours scale to the page width`() {
        val layout = stitchLayout(
            sizes = listOf(400 to 600, 800 to 3000, 1600 to 2000),
            baseWidth = 800,
            maxPixels = 40_000_000L,
        )

        layout shouldBe StitchLayout(width = 800, heights = listOf(1200, 3000, 1000))
        layout.height shouldBe 5200
    }

    @Test
    fun `a stitch over the pixel budget shrinks as a whole`() {
        val layout = stitchLayout(
            sizes = listOf(1000 to 4000, 1000 to 4000),
            baseWidth = 1000,
            maxPixels = 2_000_000L,
        )

        layout shouldBe StitchLayout(width = 500, heights = listOf(2000, 2000))
    }

    @Test
    fun `a stitch longer than the side limit shrinks to fit it`() {
        val layout = stitchLayout(
            sizes = listOf(100 to 600, 100 to 600),
            baseWidth = 100,
            maxPixels = 40_000_000L,
            maxSide = 600,
        )

        layout shouldBe StitchLayout(width = 50, heights = listOf(300, 300))
    }
}
