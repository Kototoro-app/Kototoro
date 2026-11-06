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

enum class ImmersiveEdge { TOP, BOTTOM }

/** Two endpoints keep the tint opacity linear over the full height of the immersive band. */
fun immersiveEdgeGradientColors(color: Color, edge: ImmersiveEdge): List<Color> {
    val transparent = color.copy(alpha = 0f)
    return when (edge) {
        ImmersiveEdge.TOP -> listOf(color, transparent)
        ImmersiveEdge.BOTTOM -> listOf(transparent, color)
    }
}

fun resolveTopImmersiveAlpha(
    contentScrollAlpha: Float,
    chromeAlpha: Float,
): Float = maxOf(contentScrollAlpha, chromeAlpha).coerceIn(0f, 1f)

@Composable
fun BoxScope.ImmersiveEdgeGradient(
    height: Dp,
    color: Color,
    edge: ImmersiveEdge,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(height)
            .drawWithCache {
                val brush = Brush.verticalGradient(
                    colors = immersiveEdgeGradientColors(color, edge),
                    startY = 0f,
                    endY = size.height,
                )
                onDrawBehind {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                    )
                }
            },
    )
}
