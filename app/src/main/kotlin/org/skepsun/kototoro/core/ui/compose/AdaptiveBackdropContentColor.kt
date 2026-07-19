package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal const val BackdropLuminanceDarkThreshold = 0.40f
internal const val BackdropLuminanceLightThreshold = 0.60f

internal fun adaptiveBackdropContentColor(
    luminance: Float,
    currentColor: Color,
): Color {
    val isBlackForeground = currentColor != Color.White && currentColor.luminance() < 0.5f
    return when {
        isBlackForeground && luminance < BackdropLuminanceDarkThreshold -> Color.White
        !isBlackForeground && luminance > BackdropLuminanceLightThreshold -> Color.Black
        else -> currentColor
    }
}

internal fun adaptiveBackdropSurfaceColor(contentColor: Color): Color =
    (if (contentColor.luminance() < 0.5f) Color.Black else Color.White).copy(alpha = 0.12f)

@Stable
internal class AdaptiveBackdropContentColorState internal constructor(
    private val layer: GraphicsLayer,
    initialColor: Color,
) {
    private val animation = Animatable(if (initialColor.luminance() < 0.5f) 1f else 0f)

    val color: Color
        get() = androidx.compose.ui.graphics.lerp(Color.White, Color.Black, animation.value)

    fun record(drawScope: DrawScope, drawBackdrop: DrawScope.() -> Unit) {
        with(drawScope) {
            drawBackdrop()
        }
        layer.record(
            density = drawScope,
            layoutDirection = drawScope.layoutDirection,
            size = IntSize(
                width = drawScope.size.width.roundToInt(),
                height = drawScope.size.height.roundToInt(),
            ),
        ) {
            drawBackdrop()
        }
    }

    suspend fun sampleLoop() {
        while (true) {
            val luminance = try {
                sampleLuminance(layer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (luminance != null) {
                val stableColor = if (animation.targetValue == 1f) Color.Black else Color.White
                val targetColor = adaptiveBackdropContentColor(luminance, stableColor)
                val targetValue = if (targetColor == Color.Black) 1f else 0f
                if (targetValue != animation.targetValue) {
                    animation.animateTo(targetValue, tween(450))
                }
            }
            delay(250L)
        }
    }
}

@Composable
internal fun rememberAdaptiveBackdropContentColor(
    fallback: Color,
): AdaptiveBackdropContentColorState {
    val layer = rememberGraphicsLayer()
    val state = remember(layer, fallback) {
        AdaptiveBackdropContentColorState(layer = layer, initialColor = fallback)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.sampleLoop()
        }
    }
    return state
}

private suspend fun sampleLuminance(layer: GraphicsLayer): Float {
    val imageBitmap = withContext(Dispatchers.Main.immediate) {
        layer.toImageBitmap()
    }
    if (imageBitmap.width == 0 || imageBitmap.height == 0) return 0.5f

    return withContext(Dispatchers.Default) {
        val source = imageBitmap.asAndroidBitmap()
        val thumbnail = source.scale(5, 5, false)
        try {
            var total = 0f
            repeat(5) { y ->
                repeat(5) { x ->
                    val pixel = thumbnail.getPixel(x, y)
                    total += Color(
                        red = ((pixel shr 16) and 0xFF) / 255f,
                        green = ((pixel shr 8) and 0xFF) / 255f,
                        blue = (pixel and 0xFF) / 255f,
                        alpha = ((pixel ushr 24) and 0xFF) / 255f,
                    ).luminance()
                }
            }
            total / 25f
        } finally {
            if (!thumbnail.isRecycled) thumbnail.recycle()
        }
    }
}
