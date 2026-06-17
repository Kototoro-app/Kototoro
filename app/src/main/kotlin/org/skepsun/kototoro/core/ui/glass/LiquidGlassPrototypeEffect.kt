package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.TrimMemoryLevel
import dev.chrisbanes.haze.VisualEffect
import dev.chrisbanes.haze.VisualEffectContext
import dev.chrisbanes.haze.blur.BlurVisualEffect
import dev.chrisbanes.haze.blur.HazeBlurStyle

enum class GlassVisualTreatment {
    Standard,
    TopBarPrototype,
}

@Immutable
data class LiquidGlassPrototypeSpec(
    val glowAlpha: Float = 0.14f,
    val edgeAlpha: Float = 0.28f,
    val innerEdgeAlpha: Float = 0.16f,
    val edgeWidthMultiplier: Float = 1f,
    val innerEdgeWidthMultiplier: Float = 1f,
)

@OptIn(ExperimentalHazeApi::class)
internal class LiquidGlassPrototypeVisualEffect : VisualEffect {

    private val blur = BlurVisualEffect()

    var blurStyle: HazeBlurStyle = HazeBlurStyle.Unspecified
    var backgroundColor: Color = Color.Unspecified
    var blurredEdgeTreatment: BlurredEdgeTreatment = BlurredEdgeTreatment.Unbounded
    var shape: Shape = RoundedCornerShape(0.dp)
    var spec: LiquidGlassPrototypeSpec = LiquidGlassPrototypeSpec()

    override fun attach(context: VisualEffectContext) {
        syncBlurState()
        blur.attach(context)
    }

    override fun update(context: VisualEffectContext) {
        syncBlurState()
        blur.update(context)
    }

    override fun detach(context: VisualEffectContext) {
        blur.detach(context)
    }

    override fun onTrimMemory(context: VisualEffectContext, level: TrimMemoryLevel) {
        blur.onTrimMemory(context, level)
    }

    override fun shouldDrawContentBehind(context: VisualEffectContext): Boolean {
        return blur.shouldDrawContentBehind(context)
    }

    override fun shouldClipToNodeBounds(): Boolean {
        return blur.shouldClipToNodeBounds()
    }

    override fun shouldPreferClipToAreaBounds(): Boolean {
        return blur.shouldPreferClipToAreaBounds()
    }

    override fun calculateLayerBounds(
        rect: androidx.compose.ui.geometry.Rect,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.geometry.Rect {
        return blur.calculateLayerBounds(rect, density)
    }

    override fun DrawScope.draw(context: VisualEffectContext) {
        syncBlurState()
        with(blur) {
            draw(context)
        }
        drawPrototypeChrome(spec = spec, shape = shape)
    }

    private fun syncBlurState() {
        blur.style = blurStyle
        blur.backgroundColor = backgroundColor
        blur.blurredEdgeTreatment = blurredEdgeTreatment
    }
}

private fun DrawScope.drawPrototypeChrome(
    spec: LiquidGlassPrototypeSpec,
    shape: Shape,
) {
    val height = size.height
    val width = size.width
    if (width <= 0f || height <= 0f) {
        return
    }

    val outline = shape.createOutline(
        size = size,
        layoutDirection = layoutDirection,
        density = this,
    )
    val glowStrokeWidth = 2.2.dp.toPx() * spec.edgeWidthMultiplier
    val edgeStrokeWidth = 1.14.dp.toPx() * spec.edgeWidthMultiplier
    drawOutlineStroke(outline, Color.White.copy(alpha = spec.glowAlpha.coerceIn(0f, 1f)), glowStrokeWidth)
    drawOutlineStroke(outline, Color.White.copy(alpha = spec.edgeAlpha.coerceIn(0f, 1f)), edgeStrokeWidth)
}

private fun DrawScope.drawOutlineStroke(
    outline: Outline,
    color: Color,
    strokeWidth: Float,
) {
    when (outline) {
        is Outline.Rectangle -> drawRect(
            color = color,
            topLeft = outline.rect.topLeft,
            size = outline.rect.size,
            style = Stroke(width = strokeWidth),
        )
        is Outline.Rounded -> drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(outline.roundRect.left, outline.roundRect.top),
            size = androidx.compose.ui.geometry.Size(outline.roundRect.width, outline.roundRect.height),
            cornerRadius = outline.roundRect.topLeftCornerRadius,
            style = Stroke(width = strokeWidth),
        )
        is Outline.Generic -> drawPath(
            path = outline.path,
            color = color,
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
internal fun rememberLiquidGlassPrototypeOverlayModifier(
    spec: LiquidGlassPrototypeSpec,
): Modifier {
    return Modifier.drawWithCache {
        onDrawWithContent {
            drawContent()
            drawPrototypeFallbackChrome(spec)
        }
    }
}

private fun DrawScope.drawPrototypeFallbackChrome(
    spec: LiquidGlassPrototypeSpec,
) {
    val height = size.height
    val width = size.width
    if (width <= 0f || height <= 0f) {
        return
    }

    val glowStrokeWidth = 2.0.dp.toPx() * spec.edgeWidthMultiplier
    val edgeStrokeWidth = 1.08.dp.toPx() * spec.edgeWidthMultiplier
    val radius = (height / 2f).coerceAtLeast(0f)
    val outerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)

    drawRoundRect(
        color = Color.White.copy(alpha = spec.glowAlpha.coerceIn(0f, 1f)),
        cornerRadius = outerRadius,
        style = Stroke(width = glowStrokeWidth),
    )

    drawRoundRect(
        color = Color.White.copy(alpha = spec.edgeAlpha.coerceIn(0f, 1f)),
        cornerRadius = outerRadius,
        style = Stroke(width = edgeStrokeWidth),
    )
}

@OptIn(ExperimentalHazeApi::class)
internal fun HazeEffectScope.liquidGlassPrototypeEffect(
    block: LiquidGlassPrototypeVisualEffect.() -> Unit,
) {
    val effect = visualEffect as? LiquidGlassPrototypeVisualEffect ?: LiquidGlassPrototypeVisualEffect()
    visualEffect = effect
    effect.block()
}
