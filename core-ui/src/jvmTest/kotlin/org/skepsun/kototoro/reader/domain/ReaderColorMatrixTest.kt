package org.skepsun.kototoro.reader.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderColorMatrixTest {
    /** What the matrix does to one opaque pixel (channels 0..255), as both platforms apply it. */
    private fun FloatArray.apply(r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { row ->
        this[row * 5] * r + this[row * 5 + 1] * g + this[row * 5 + 2] * b + this[row * 5 + 3] * 255f + this[row * 5 + 4]
    }

    @Test
    fun `empty settings leave pages untouched`() {
        assertTrue(ReaderColorMatrix.isEmpty(0f, 0f, false, false, false))
        assertArrayEquals(ReaderColorMatrix.identity(), ReaderColorMatrix.of(0f, 0f, false, false, false), 1e-6f)
        assertFalse(ReaderColorMatrix.isEmpty(.1f, 0f, false, false, false))
    }

    @Test
    fun `brightness scales and contrast pivots on mid grey`() {
        assertArrayEquals(floatArrayOf(150f, 30f, 0f), ReaderColorMatrix.of(.5f, 0f, false, false, false).apply(100f, 20f, 0f), 1e-3f)
        // Contrast +1 doubles the distance from 127.5.
        assertArrayEquals(floatArrayOf(255f, 0f, 127.5f), ReaderColorMatrix.of(0f, 1f, false, false, false)
            .apply(191.25f, 63.75f, 127.5f), 1e-3f)
    }

    @Test
    fun `inversion runs before brightness like Android's postConcat chain`() {
        // Android's inversion is R' = A - R + 1: 100 -> 156, then brighten by 50 % -> 234 (the other order gives 106).
        assertEquals(234f, ReaderColorMatrix.of(.5f, 0f, true, false, false).apply(100f, 100f, 100f)[0], 1e-3f)
    }

    @Test
    fun `grayscale uses Android's luminance weights and book mode dims blue`() {
        val grey = ReaderColorMatrix.of(0f, 0f, false, true, false).apply(255f, 0f, 0f)
        assertArrayEquals(floatArrayOf(.213f * 255, .213f * 255, .213f * 255), grey, 1e-3f)
        val book = ReaderColorMatrix.of(0f, 0f, false, false, true).apply(255f, 255f, 255f)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f * ReaderColorMatrix.BOOK_BLUE_FACTOR), book, 1e-3f)
    }
}
