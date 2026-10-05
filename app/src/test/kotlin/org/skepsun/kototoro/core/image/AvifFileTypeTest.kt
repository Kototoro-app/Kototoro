package org.skepsun.kototoro.core.image

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvifFileTypeTest {

    @Test
    fun `major avif brand is recognised`() {
        assertTrue(isAvifFileType(ftyp("avif", "mif1", "miaf")))
        assertTrue(isAvifFileType(ftyp("avis", "msf1")))
    }

    @Test
    fun `avif listed only as a compatible brand is recognised`() {
        assertTrue(isAvifFileType(ftyp("mif1", "avif", "miaf", "MA1B")))
        assertTrue(isAvifFileType(ftyp("msf1", "avis")))
    }

    @Test
    fun `other ftyp brands and non-ftyp data are rejected`() {
        assertFalse(isAvifFileType(ftyp("heic", "mif1", "heix")))
        assertFalse(isAvifFileType(ftyp("isom", "mp41")))
        assertFalse(isAvifFileType("GIF89a......".toByteArray()))
        assertFalse(isAvifFileType(ByteArray(4)))
    }

    @Test
    fun `brands after the ftyp box are ignored`() {
        val header = ftyp("mif1", "miaf") + box("avif")
        assertFalse(isAvifFileType(header))
    }

    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + compatible.size * 4
        return int(size) + "ftyp".toByteArray() + major.toByteArray() + ByteArray(4) +
            compatible.joinToString("").toByteArray()
    }

    private fun box(type: String): ByteArray = int(8) + type.toByteArray()

    private fun int(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
