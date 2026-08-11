package org.skepsun.kototoro.video.data

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PngWrappedHlsTest {

    @Test
    fun `png wrapper is removed before transport stream payload`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        val transportStream = byteArrayOf(0x47, 0x40, 0x00, 0x10)

        val result = unwrapPngPrefixedStream(ByteArrayInputStream(png + transportStream))

        assertTrue(result.wasUnwrapped)
        assertArrayEquals(transportStream, result.stream.readBytes())
    }

    @Test
    fun `non png stream is preserved`() {
        val transportStream = byteArrayOf(0x47, 0x40, 0x00, 0x10)

        val result = unwrapPngPrefixedStream(ByteArrayInputStream(transportStream))

        assertFalse(result.wasUnwrapped)
        assertArrayEquals(transportStream, result.stream.readBytes())
    }
}
