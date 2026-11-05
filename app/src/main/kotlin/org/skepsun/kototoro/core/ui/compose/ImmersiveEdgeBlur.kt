package org.skepsun.kototoro.core.ui.compose

import android.os.Build
import androidx.compose.animation.core.EaseIn
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

enum class ImmersiveEdge { TOP, BOTTOM }

/** The edge blur radius for this device, or 0.dp when it is off or the platform has no RenderEffect (API < 31). */
@Composable
fun immersiveEdgeBlurRadius(): Dp {
    if (!isRenderEffectSupported()) return 0.dp
    return (LocalGlassPrefs.current?.edgeBlurRadiusDp ?: 0).coerceAtLeast(0).dp
}

fun List<Color>.scaledImmersiveTint(scale: Float): List<Color> = map { it.copy(alpha = it.alpha * scale) }

/**
 * One step of the progressive blur. Positions run from the screen edge (0) to the content side of the band (1): the
 * step is fully present up to [fullUntil] and eases out (smoothstep) by [fadeEnd].
 */
private class ImmersiveEdgeBlurStep(val radiusFraction: Float, val fullUntil: Float, val fadeEnd: Float)

/**
 * Android 12's stepped stand-in for the progressive profile: stronger steps cover less of the band, so every position
 * sees roughly one radius dominate and the content side only meets the lightest blur.
 */
private val ImmersiveEdgeBlurSteps = listOf(
    ImmersiveEdgeBlurStep(radiusFraction = 1f / 3f, fullUntil = 0.20f, fadeEnd = 1.00f),
    ImmersiveEdgeBlurStep(radiusFraction = 2f / 3f, fullUntil = 0.10f, fadeEnd = 0.70f),
    ImmersiveEdgeBlurStep(radiusFraction = 1f, fullUntil = 0f, fadeEnd = 0.45f),
)

private const val ImmersiveEdgeBlurMaskSamples = 8

/** The [step]'s alpha mask as gradient stops, sampled along a smoothstep so the fade has no visible knee. */
private fun immersiveEdgeBlurMaskStops(step: ImmersiveEdgeBlurStep, edge: ImmersiveEdge): Array<Pair<Float, Color>> {
    val span = (step.fadeEnd - step.fullUntil).coerceAtLeast(0.001f)
    val stops = (0..ImmersiveEdgeBlurMaskSamples).map { index ->
        val t = index.toFloat() / ImmersiveEdgeBlurMaskSamples
        val position = step.fullUntil + span * t
        val alpha = 1f - t * t * (3f - 2f * t)
        position to Color.Black.copy(alpha = alpha)
    }
    val fromEdge = listOf(0f to Color.Black) + stops + listOf(1f to Color.Transparent)
    val clamped = fromEdge.filter { it.first in 0f..1f }
    return when (edge) {
        ImmersiveEdge.TOP -> clamped
        ImmersiveEdge.BOTTOM -> clamped.map { (position, color) -> 1f - position to color }.reversed()
    }.toTypedArray()
}

private const val ImmersiveEdgeBlurRadiusSamples = 15

/**
 * The exact progressive profile, after Haze's default `HazeProgressive.verticalGradient` (`EaseIn`, intensity 1 -> 0):
 * the radius stays near its full [radius] across the bar and drops off towards the content side, sampled into
 * [BlurStop]s (the platform interpolates linearly between them, 16 stops at most).
 */
private fun immersiveEdgeBlurRadiusSpec(edge: ImmersiveEdge, radius: Dp): BlurRadiusSpec =
    BlurRadiusSpec.verticalGradient(
        (0..ImmersiveEdgeBlurRadiusSamples).map { index ->
            val t = index.toFloat() / ImmersiveEdgeBlurRadiusSamples
            val strength = 1f - EaseIn.transform(t)
            val fraction = when (edge) {
                ImmersiveEdge.TOP -> t
                ImmersiveEdge.BOTTOM -> 1f - t
            }
            BlurStop(fraction = fraction, radius = radius * strength)
        },
    )

/**
 * Haze's "progressive + mask" pairing, with the mask aligned to the immersive feather: the band is fully opaque over
 * the bar ([fadeStart] is where the bar ends within the band) and fades out (smoothstep) only across the feather that
 * the tint gradient also uses. A fade spanning the whole band left text under the bar barely blurred. The fade also
 * hides the reduced-resolution copy where it meets full-resolution content, which otherwise reads as a softness step
 * as sharp text leaves the band.
 */
private fun immersiveEdgeBlurFadeStops(edge: ImmersiveEdge, fadeStart: Float): Array<Pair<Float, Color>> {
    val start = fadeStart.coerceIn(0f, 1f)
    val fromEdge = listOf(0f to Color.Black) + (0..ImmersiveEdgeBlurMaskSamples).map { index ->
        val t = index.toFloat() / ImmersiveEdgeBlurMaskSamples
        start + (1f - start) * t to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
    }
    return when (edge) {
        ImmersiveEdge.TOP -> fromEdge
        ImmersiveEdge.BOTTOM -> fromEdge.map { (position, color) -> 1f - position to color }.reversed()
    }.toTypedArray()
}

/**
 * A progressive blur of [backdrop] under one immersive edge: the radius itself falls off continuously from the screen
 * edge to nothing at the content side, like iOS's scroll edge effect, so the band never cross-fades a sharp copy with a
 * strongly blurred one.
 *
 * Android 13+ uses Compose's spatially varying [BlurRadiusSpec] (a separable per-pixel-radius AGSL blur) as a Backdrop
 * effect, so it runs on Backdrop's layer at the user's blur quality, and fades out across the feather per
 * [immersiveEdgeBlurFadeStops]. Android 12 has no runtime shaders and falls back to [ImmersiveEdgeBlurSteps].
 */
@Composable
fun BoxScope.ImmersiveEdgeBlur(
    backdrop: Backdrop,
    edge: ImmersiveEdge,
    height: Dp,
    radius: Dp,
    fadeStart: Float,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val spec = remember(edge, radius) { immersiveEdgeBlurRadiusSpec(edge, radius) }
        val effects: BackdropEffectScope.() -> Unit = remember(spec) {
            { effect(spec.createRenderEffect(size, this)) }
        }
        val drawFade: DrawScope.() -> Unit = remember(edge, fadeStart) {
            val stops = immersiveEdgeBlurFadeStops(edge, fadeStart)
            val mask: DrawScope.() -> Unit = {
                drawRect(
                    brush = Brush.verticalGradient(colorStops = stops, startY = 0f, endY = size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            mask
        }
        Box(
            modifier = modifier
                .height(height)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = effects,
                    onDrawFront = drawFade,
                ),
        )
    } else {
        ImmersiveEdgeSteppedBlur(backdrop, edge, height, radius, modifier)
    }
}

/**
 * The Android 12 approximation: a few uniformly blurred copies whose `DstIn` masks retreat towards the screen edge as
 * their radius grows. Backdrop's surface is an offscreen layer, so each mask only cuts its own blurred copy.
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
