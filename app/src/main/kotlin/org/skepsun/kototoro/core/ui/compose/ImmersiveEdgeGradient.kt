package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val ImmersiveEdgeFeatherExtension = 24.dp
val ImmersiveTopGradientStops = listOf(0f, 0.30f, 0.58f, 0.78f, 1f)
val ImmersiveBottomGradientStops = listOf(0f, 0.34f, 0.70f, 1f)

internal fun resolveTopImmersiveAlpha(
    contentScrollAlpha: Float,
    chromeAlpha: Float,
): Float = maxOf(contentScrollAlpha, chromeAlpha).coerceIn(0f, 1f)

@Composable
fun BoxScope.ImmersiveEdgeGradient(
    height: Dp,
    colors: List<Color>,
    stops: List<Float>? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .drawWithCache {
                val brush = if (stops != null && stops.size == colors.size) {
                    Brush.verticalGradient(
                        colorStops = Array(colors.size) { index -> stops[index] to colors[index] },
                        startY = 0f,
                        endY = size.height,
                    )
                } else {
                    Brush.verticalGradient(
                        colors = colors,
                        startY = 0f,
                        endY = size.height,
                    )
                }
                onDrawBehind {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                    )
                }
            },
    )
}

fun Color.toTransparentImmersiveColor(): Color = copy(alpha = 0f)
