package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.*
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangularShape

@Immutable
data class GlassStyle(
    val containerAlpha: Float,
    val borderAlpha: Float,
    val tonalElevation: Dp,
    val shadowElevation: Dp,
    val minimumContainerAlpha: Float = 0f,
)

enum class GlassComponentRole {
    Surface,
    ContentOverlay,
    TopBar,
    BottomBar,
    PillControl,
    BottomPanel,
    Menu,
    Dialog,
    Sheet,
}

/**
 * Chrome surface tint following the upstream catalog's LiquidBottomTabs:
 * a fixed high-luminance-contrast color (near-white in light, near-black in
 * dark) instead of a low-chroma Material surface container, so the chrome
 * always reads as a distinct surface over a busy backdrop.
 */
val ChromeTintLight = Color(0xFFFAFAFA)
val ChromeTintDark = Color(0xFF121212)

fun chromeBackdropTint(isDark: Boolean): Color =
    if (isDark) ChromeTintDark else ChromeTintLight

fun GlassComponentRole.allowsAmoledBackdrop(): Boolean =
    this == GlassComponentRole.ContentOverlay ||
        this == GlassComponentRole.TopBar ||
        this == GlassComponentRole.BottomBar ||
        this == GlassComponentRole.PillControl ||
        this == GlassComponentRole.BottomPanel

fun resolveGlassPressProgress(enabled: Boolean, progress: Float): Float =
    if (enabled) progress.coerceIn(0f, 1f) else 0f

fun shouldTrackGlassPress(
    componentRole: GlassComponentRole,
    pressFeedbackEnabled: Boolean,
): Boolean = componentRole != GlassComponentRole.TopBar && pressFeedbackEnabled

fun shouldApplyGlassLens(enabled: Boolean, heightDp: Float, amountDp: Float): Boolean =
    enabled && heightDp > 0f && amountDp > 0f

/**
 * Shadow elevation for the Material fallback surface.
 *
 * A translucent container colour cannot hide the surface's own shadow: the shadow is
 * drawn behind the shape, so it reads through the fill as a dark rim hugging the inside
 * of the outline and leaves a smaller, brighter plate in the middle. Over artwork every
 * glass role is deliberately translucent (chrome 0.65-0.92, cards/surfaces 0.40-0.74),
 * which turned each top-bar pill into a light island inside a darker capsule — an
 * artifact no amount of tint/alpha tuning removes, because it is drawn by the shadow
 * rather than by the tint. Flat surfaces (dialogs, and any translucent container) draw
 * no shadow at all; over artwork the hairline border already carries the edge.
 */
fun resolveFallbackShadowElevation(
    styleShadowElevation: Dp,
    containerAlpha: Float,
    flat: Boolean,
): Dp = if (flat || containerAlpha < 1f) 0.dp else styleShadowElevation

/**
 * Maps the [GlassTuningParam.HIGHLIGHT_STYLE] option value to the Kyant
 * [HighlightStyle]: 0 = Default, 1 = Ambient, 2 = Plain.
 */
fun resolveGlassHighlightStyle(value: Int, angle: Float): HighlightStyle = when (value) {
    1 -> HighlightStyle.Ambient()
    2 -> HighlightStyle.Plain()
    else -> HighlightStyle.Default(angle = angle, falloff = 2f)
}

data class GlassLensParameters(
    val refractionHeight: Float,
    val refractionAmount: Float,
)

/**
 * Backdrop's lens shader requires refractionHeight to stay within the
 * surface's minimum corner radius and refractionAmount within its shortest
 * side. Clamping here keeps strong presets (e.g. Control Center lens 24/24)
 * from painting arc artifacts on small capsules, pills and group
 * controls while leaving large bars and panels untouched.
 */
fun resolveGlassLensParameters(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
    requestedHeight: Float,
    requestedAmount: Float,
): GlassLensParameters? {
    if (
        !requestedHeight.isFinite() ||
        !requestedAmount.isFinite() ||
        requestedHeight <= 0f ||
        requestedAmount <= 0f ||
        !size.width.isFinite() ||
        !size.height.isFinite() ||
        size.width <= 0f ||
        size.height <= 0f
    ) {
        return null
    }

    val cornerRadii = shape.liquidLensCornerRadii(size, layoutDirection, density) ?: return null
    val minCornerRadius = cornerRadii.minOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
    val shortestSide = size.minDimension
    return GlassLensParameters(
        refractionHeight = requestedHeight.coerceAtMost(minCornerRadius),
        refractionAmount = requestedAmount.coerceAtMost(shortestSide),
    )
}

private fun Shape.liquidLensCornerRadii(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): List<Float>? =
    when (this) {
        is RoundedRectangularShape -> {
            val corners = corners(size, layoutDirection, density)
            listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
        }

        is CornerBasedShape -> {
            val maxRadius = size.minDimension / 2f
            val isLtr = layoutDirection == LayoutDirection.Ltr
            listOf(
                (if (isLtr) topStart else topEnd).toPx(size, density).coerceAtMost(maxRadius),
                (if (isLtr) topEnd else topStart).toPx(size, density).coerceAtMost(maxRadius),
                (if (isLtr) bottomEnd else bottomStart).toPx(size, density).coerceAtMost(maxRadius),
                (if (isLtr) bottomStart else bottomEnd).toPx(size, density).coerceAtMost(maxRadius),
            )
        }

        else -> null
    }
