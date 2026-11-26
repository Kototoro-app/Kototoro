package org.skepsun.kototoro.core.ui.image

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PageArtworkBlurTest {
    @Test
    fun `cache key follows the viewport and blur across graphics contexts`() {
        val cover = IntSize(1080, 2520)
        val inner = IntSize(1968, 2184)
        val context = UnusedGraphicsContext()
        val original = PageArtworkBlur(context, cover, 10f).cacheKey

        assertEquals(original, PageArtworkBlur(UnusedGraphicsContext(), cover, 10f).cacheKey)
        assertNotEquals(original, PageArtworkBlur(context, inner, 10f).cacheKey)
        assertNotEquals(original, PageArtworkBlur(context, cover, 15f).cacheKey)
    }

    private class UnusedGraphicsContext : GraphicsContext {
        override fun createGraphicsLayer(): GraphicsLayer = error("Reading a cache key must not allocate a layer")
        override fun releaseGraphicsLayer(layer: GraphicsLayer) = error("No layer was allocated")
    }
}
