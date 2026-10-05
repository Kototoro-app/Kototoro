package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderEdgeDetectionTest {
    private val white = -0x1
    private val ink = 0xFF202020.toInt()

    /** A [width]×[height] page, white except an inked rectangle; read at [sampleSize] like a downsampled decode. */
    private fun detect(width: Int, height: Int, content: IntRect?, sampleSize: Int = 1): IntRect? {
        val bitmapWidth = width / sampleSize
        val bitmapHeight = height / sampleSize
        fun pixel(x: Int, y: Int): Int {
            val sx = x * sampleSize
            val sy = y * sampleSize
            return if (content != null && sx >= content.left && sx < content.right && sy >= content.top && sy < content.bottom) ink
            else white
        }
        return ReaderEdgeDetection.contentBounds(width, height, sampleSize, bitmapWidth, bitmapHeight) { out, x, y, w, h ->
            for (row in 0 until h) for (column in 0 until w) out[row * w + column] = pixel(x + column, y + row)
        }
    }

    @Test
    fun `plain white margins are found on every side`() {
        assertEquals(IntRect(150, 100, 850, 700), detect(1000, 800, IntRect(150, 100, 850, 700)))
        // A downsampled scan finds the same bounds within the sample step.
        assertEquals(IntRect(150, 100, 2850, 2300), detect(3000, 2400, IntRect(150, 100, 2850, 2300), sampleSize = 2))
    }

    @Test
    fun `pages without margins or with a margin over a third are left alone`() {
        assertNull(detect(1000, 800, IntRect(0, 0, 1000, 800)))
        assertNull(detect(1000, 800, null))
        assertNull(detect(1000, 800, IntRect(450, 100, 900, 700)))
    }

    @Test
    fun `large pages are scanned downsampled like Android`() {
        assertEquals(1, ReaderEdgeDetection.sampleSize(1000, 800))
        assertEquals(1, ReaderEdgeDetection.sampleSize(2000, 1400))
        assertEquals(2, ReaderEdgeDetection.sampleSize(3000, 2000))
        assertEquals(4, ReaderEdgeDetection.sampleSize(800, 12000))
    }

    @Test
    fun `auto scroll timing follows Android's curve`() {
        assertEquals(.1f + .24f * 10f, ReaderAutoScroll.speedMultiplier(ReaderAutoScroll.DEFAULT_SPEED), 1e-6f)
        assertEquals(13L, ReaderAutoScroll.scrollDelayMs(ReaderAutoScroll.DEFAULT_SPEED))
        assertEquals(4000L, ReaderAutoScroll.pageSwitchDelayMs(ReaderAutoScroll.DEFAULT_SPEED))
        assertEquals(320L, ReaderAutoScroll.scrollDelayMs(0f))
        assertEquals(990L, ReaderAutoScroll.pageSwitchDelayMs(1f))
    }
}
