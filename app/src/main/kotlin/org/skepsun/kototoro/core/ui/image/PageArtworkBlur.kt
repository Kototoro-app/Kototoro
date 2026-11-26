package org.skepsun.kototoro.core.ui.image

import android.graphics.Bitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Cache the center-cropped, blurred page in Coil. Both the page and its glass can then draw the same bitmap. */
internal class PageArtworkBlur(
    private val graphicsContext: GraphicsContext,
    private val viewport: IntSize,
    private val radiusPx: Float,
) {
    fun cacheKey(source: MemoryCache.Key) = MemoryCache.Key(
        source.key,
        source.extras + ("kototoro:page-artwork-blur" to "${viewport.width}x${viewport.height}:$radiusPx"),
    )

    suspend fun render(input: Bitmap): Bitmap = withContext(Dispatchers.Main.immediate) {
        val layer = graphicsContext.createGraphicsLayer()
        try {
            val image = input.asImageBitmap()
            val scale = maxOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
            val scaled = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
            val offset = Alignment.Center.align(scaled, viewport, LayoutDirection.Ltr)
            layer.renderEffect = BlurEffect(radiusPx, radiusPx)
            // Clip to the same viewport as the live filter so the blur samples the same crop at the edges.
            layer.clip = true
            layer.record(Density(1f), LayoutDirection.Ltr, viewport) {
                drawImage(image, dstOffset = offset, dstSize = scaled)
            }
            layer.toImageBitmap().asAndroidBitmap()
        } finally {
            graphicsContext.releaseGraphicsLayer(layer)
        }
    }
}
