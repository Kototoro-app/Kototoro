package org.skepsun.kototoro.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoverDominantColorTest {

    @BeforeEach
    fun resetCache() {
        CoverDominantColor.clearCache()
    }

    @Test
    fun `extractFromPixels picks dominant hue and favors lower cover half`() {
        val w = 24
        val h = 24
        val pixels = IntArray(w * h)
        // Top half: vivid red; bottom half: vivid blue -> lower half has 1.8x weight.
        val red = 0xFFE52424.toInt()
        val blue = 0xFF245CE5.toInt()
        for (y in 0 until h) {
            val color = if (y < h / 2) red else blue
            for (x in 0 until w) {
                pixels[y * w + x] = color
            }
        }
        val dominant = CoverDominantColor.extractFromPixels(pixels, w, h)
        assertNotNull(dominant)
        // Blue dominates green/red channels.
        assertTrue(dominant!!.blue > dominant.red && dominant.blue > dominant.green)
    }

    @Test
    fun `extractFromPixels returns null for grayscale or transparent covers`() {
        val w = 24
        val h = 24
        val grayPixels = IntArray(w * h) { 0xFF808080.toInt() }
        assertNull(CoverDominantColor.extractFromPixels(grayPixels, w, h))

        val whitePixels = IntArray(w * h) { 0xFFFAFAFA.toInt() }
        assertNull(CoverDominantColor.extractFromPixels(whitePixels, w, h))

        val transparentPixels = IntArray(w * h) { 0x10FF0000 }
        assertNull(CoverDominantColor.extractFromPixels(transparentPixels, w, h))
    }

    @Test
    fun `resolvePageBackground preserves base color under increased contrast or null dominant`() {
        val baseDark = Color(0xFF10131B)
        val baseLight = Color(0xFFFFF8FF)
        val vivid = Color(0xFF1E88E5)

        assertEquals(baseDark, CoverDominantColor.resolvePageBackground(baseDark, null, isDarkTheme = true))
        assertEquals(
            baseLight,
            CoverDominantColor.resolvePageBackground(baseLight, vivid, isDarkTheme = false, increasedContrast = true),
        )
        assertEquals(
            baseDark,
            CoverDominantColor.resolvePageBackground(baseDark, Color(0xFF7A7A7A), isDarkTheme = true),
        )

        val tintedDark = CoverDominantColor.resolvePageBackground(baseDark, vivid, isDarkTheme = true)
        val tintedLight = CoverDominantColor.resolvePageBackground(baseLight, vivid, isDarkTheme = false)
        assertNotEquals(baseDark, tintedDark)
        assertNotEquals(baseLight, tintedLight)
        assertTrue(tintedDark.blue > tintedDark.red)
        assertTrue(tintedLight.blue > tintedLight.red)
    }

    @Test
    fun `cache evicts oldest entries beyond capacity`() {
        repeat(205) { i ->
            CoverDominantColor.putCached("key-$i", Color(0xFF112233))
        }
        assertNull(CoverDominantColor.cached("key-0"))
        assertNotNull(CoverDominantColor.cached("key-204"))
    }
}
