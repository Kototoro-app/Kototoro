package org.skepsun.kototoro.core.ui.compose

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.blur.BlurStop
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.isRenderEffectSupported
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs

/**
 * The recorded page behind the main shell's immersive edges. iOS style hands over the liquid-glass route composite;
 * Material records its own layer only while the edge blur is on, so Material glass components never see a backdrop.
 */
internal val LocalImmersiveEdgeBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** How much of the tint gradient stays once the page under it is blurred; the blur carries the rest of the contrast. */
const val ImmersiveEdgeBlurTintScale = 0.55f

/** The edge blur radius for this device, or 0.dp when it is off or the platform has no RenderEffect (API < 31). */
@Composable
fun immersiveEdgeBlurRadius(): Dp {
    if (!isRenderEffectSupported()) return 0.dp
    return (LocalGlassPrefs.current?.edgeBlurRadiusDp ?: 0).coerceAtLeast(0).dp
}

/**
 * One step of the progressive blur. Positions run from the screen edge (0) to the content side of the band (1): the
 * step is fully present up to [fullUntil] and fades linearly to zero by [fadeEnd].
 */
internal class ImmersiveEdgeBlurStep(val radiusFraction: Float, val fullUntil: Float, val fadeEnd: Float)

/**
 * Android 12 mixes adjacent radii over equal thirds of the band. The sharp base keeps the composite opaque, so the
 * original page is replaced rather than showing through a second opacity fade.
 */
internal val ImmersiveEdgeBlurSteps = listOf(
    ImmersiveEdgeBlurStep(radiusFraction = 0f, fullUntil = 1f, fadeEnd = 1f),
    ImmersiveEdgeBlurStep(radiusFraction = 1f / 3f, fullUntil = 2f / 3f, fadeEnd = 1f),
    ImmersiveEdgeBlurStep(radiusFraction = 2f / 3f, fullUntil = 1f / 3f, fadeEnd = 2f / 3f),
    ImmersiveEdgeBlurStep(radiusFraction = 1f, fullUntil = 0f, fadeEnd = 1f / 3f),
)

/** Each mask cross-fades neighbouring radii while the opaque base keeps the total opacity fixed. */
internal fun immersiveEdgeBlurMaskStops(step: ImmersiveEdgeBlurStep, edge: ImmersiveEdge): Array<Pair<Float, Color>> {
    if (step.radiusFraction == 0f) {
        return arrayOf(0f to Color.Black, 1f to Color.Black)
    }
    val fromEdge = listOf(
        0f to Color.Black,
        step.fullUntil to Color.Black,
        step.fadeEnd to Color.Transparent,
        1f to Color.Transparent,
    ).distinctBy { it.first }
    return when (edge) {
        ImmersiveEdge.TOP -> fromEdge
        ImmersiveEdge.BOTTOM -> fromEdge.map { (position, color) -> 1f - position to color }.reversed()
    }.toTypedArray()
}

/** Two endpoints let the platform interpolate the radius linearly across the entire band. */
internal fun immersiveEdgeBlurRadiusStops(edge: ImmersiveEdge, radius: Dp): List<BlurStop> =
    when (edge) {
        ImmersiveEdge.TOP -> listOf(BlurStop(0f, radius), BlurStop(1f, 0.dp))
        ImmersiveEdge.BOTTOM -> listOf(BlurStop(0f, 0.dp), BlurStop(1f, radius))
    }

/**
 * A progressive blur of [backdrop] under one immersive edge: the radius itself falls off continuously from the screen
 * edge to nothing at the content side. The blurred copy stays opaque: fading it would mix sharp text back into the
 * blur and leave a readable outline. The separate tint gradient controls the surface opacity.
 *
 * Android 13+ uses Compose's spatially varying [BlurRadiusSpec] (a separable per-pixel-radius AGSL blur) as a Backdrop
 * effect. Its layer retains the source resolution so the zero-radius end meets the original page without a sampling
 * seam. Android 12 has no runtime shaders and falls back to [ImmersiveEdgeBlurSteps].
 */
@Composable
fun BoxScope.ImmersiveEdgeBlur(
    backdrop: Backdrop,
    edge: ImmersiveEdge,
    height: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val spec = remember(edge, radius) { BlurRadiusSpec.verticalGradient(immersiveEdgeBlurRadiusStops(edge, radius)) }
        val effects: BackdropEffectScope.() -> Unit = remember(spec) {
            {
                requiresFullResolution = true
                effect(spec.createRenderEffect(size, this))
            }
        }
        Box(
            modifier = modifier
                .height(height)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = effects,
                ),
        )
    } else {
        ImmersiveEdgeSteppedBlur(backdrop, edge, height, radius, modifier)
    }
}

/**
 * Android 12 approximates the radius with linear cross-fades between neighbouring uniform blurs. Their composite
 * stays opaque; only the radius approaches zero at the content side.
 */
@Composable
private fun ImmersiveEdgeSteppedBlur(
    backdrop: Backdrop,
    edge: ImmersiveEdge,
    height: Dp,
    radius: Dp,
    modifier: Modifier,
) {
    Box(modifier = modifier.height(height)) {
        ImmersiveEdgeBlurSteps.forEach { step ->
            val effects: BackdropEffectScope.() -> Unit = remember(radius, step) {
                { blur((radius * step.radiusFraction).toPx()) }
            }
            val drawMask: DrawScope.() -> Unit = remember(edge, step) {
                val stops = immersiveEdgeBlurMaskStops(step, edge)
                val mask: DrawScope.() -> Unit = {
                    drawRect(
                        brush = Brush.verticalGradient(colorStops = stops, startY = 0f, endY = size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                mask
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawPlainBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = effects,
                        onDrawFront = drawMask,
                    ),
            )
        }
    }
}
