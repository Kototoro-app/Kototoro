package org.skepsun.kototoro.core.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImmersiveEdgeGradientTest {

    @Test
    fun `content coverage keeps gradient visible while chrome is hidden`() {
        assertEquals(
            0.8f,
            resolveTopImmersiveAlpha(contentScrollAlpha = 0.8f, chromeAlpha = 0.1f),
        )
    }

    @Test
    fun `visible chrome keeps gradient during reverse browsing`() {
        assertEquals(
            0.9f,
            resolveTopImmersiveAlpha(contentScrollAlpha = 0.2f, chromeAlpha = 0.9f),
        )
    }

    @Test
    fun `gradient alpha remains within valid range`() {
        assertEquals(
            1f,
            resolveTopImmersiveAlpha(contentScrollAlpha = 1.4f, chromeAlpha = -0.2f),
        )
    }
}
