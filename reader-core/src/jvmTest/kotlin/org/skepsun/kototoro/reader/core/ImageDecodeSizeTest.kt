package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImageDecodeSizeTest {
    @Test
    fun `ordinary pages retain original pixels and large pages retain aspect`() {
        assertEquals(IntSize(800, 1200), ImageDecodeSize.resolve(IntSize(800, 1200), 4194304, 16384))
        val size = ImageDecodeSize.resolve(IntSize(3000, 2000), 4194304, 16384)
        assertTrue(size.width.toLong() * size.height <= 4194304)
        assertTrue(kotlin.math.abs(size.width.toDouble() / size.height - 1.5) < .002)
        assertTrue(size.width > 2400 && size.height > 1600)
    }

    @Test
    fun `extreme dimensions and thin pages cannot overflow or exceed either limit`() {
        for (source in listOf(IntSize(Int.MAX_VALUE, Int.MAX_VALUE), IntSize(1, Int.MAX_VALUE),
            IntSize(Int.MAX_VALUE, 1), IntSize(10000, 10000))) {
            for (budget in listOf(1L, 17L, 4194304L)) {
                val size = ImageDecodeSize.resolve(source, budget, 16384)
                assertTrue(size.width in 1..minOf(source.width, 16384))
                assertTrue(size.height in 1..minOf(source.height, 16384))
                assertTrue(size.width.toLong() * size.height <= budget)
            }
        }
    }

    @Test
    fun `invalid source or allocation limits fail`() {
        for (source in listOf(IntSize(0, 1), IntSize(1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { ImageDecodeSize.resolve(source, 10, 10) }
        }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeSize.resolve(IntSize(1, 1), 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeSize.resolve(IntSize(1, 1), 10, 0) }
    }
}
