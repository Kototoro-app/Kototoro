package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdaptiveBackdropContentColorTest {

    @Test
    fun `bright background changes white content to black`() {
        assertEquals(
            Color.Black,
            adaptiveBackdropContentColor(0.75f, Color.White),
        )
    }

    @Test
    fun `dark background changes black content to white`() {
        assertEquals(
            Color.White,
            adaptiveBackdropContentColor(0.25f, Color.Black),
        )
    }

    @Test
    fun `hysteresis keeps white content in middle luminance range`() {
        assertEquals(
            Color.White,
            adaptiveBackdropContentColor(0.50f, Color.White),
        )
    }

    @Test
    fun `hysteresis keeps black content in middle luminance range`() {
        assertEquals(
            Color.Black,
            adaptiveBackdropContentColor(0.50f, Color.Black),
        )
    }

    @Test
    fun `hysteresis preserves an arbitrary dark foreground color`() {
        val currentColor = Color(0xFF222222)

        assertEquals(
            currentColor,
            adaptiveBackdropContentColor(0.50f, currentColor),
        )
    }

    @Test
    fun `surface tint follows the foreground polarity`() {
        assertEquals(
            Color.Black.copy(alpha = 0.12f),
            adaptiveBackdropSurfaceColor(Color.Black),
        )
        assertEquals(
            Color.White.copy(alpha = 0.12f),
            adaptiveBackdropSurfaceColor(Color.White),
        )
    }
}
