package org.skepsun.kototoro.core.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AvifAnimatedDecodeSizeTest {

    @Test
    fun `small animation retains its requested dimensions`() {
        assertEquals(32 to 32, resolveAvifAnimatedDecodeSize(32, 32, 2, 4, 8192))
    }

    @Test
    fun `long animation is sampled instead of discarded at the old one gigabyte ceiling`() {
        val budget = 64L * 1024 * 1024
        val size = resolveAvifAnimatedDecodeSize(1080, 8000, 60, 4, budget)
        assertNotNull(size)
        val (width, height) = requireNotNull(size)
        assertTrue(width < 1080 && height < 8000)
        assertTrue(width.toLong() * height * 60 * 4 <= budget)
        assertTrue(abs(width.toDouble() / height - 1080.0 / 8000) < 0.002)
    }

    @Test
    fun `extreme aspect ratios still fit a minimal frame budget`() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val size = resolveAvifAnimatedDecodeSize(width, height, 2, 4, 8)
            assertEquals(1 to 1, size)
        }
    }

    @Test
    fun `large dimensions and frame count do not overflow the cost calculation`() {
        val size = resolveAvifAnimatedDecodeSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 4, Long.MAX_VALUE)
        val (width, height) = requireNotNull(size)
        assertTrue(width.toLong() * height <= Long.MAX_VALUE / 4 / Int.MAX_VALUE)
    }

    @Test
    fun `static fallback is needed only when the budget cannot fit one pixel per frame`() {
        assertNull(resolveAvifAnimatedDecodeSize(32, 32, 3, 4, 11))
        assertEquals(1 to 1, resolveAvifAnimatedDecodeSize(32, 32, 3, 4, 12))
    }
}
