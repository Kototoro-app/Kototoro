package org.skepsun.kototoro.core.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.shape.CornerBasedShape
import com.kyant.shapes.RoundedRectangularShape
import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.luminance

private const val GlassStaticHighlightAngleDeg = 45f

/** The Android liquid-glass renderer. Hosts resolve preferences and same-window availability before calling it. */
@Composable
fun SharedLiquidGlassSurface(
    backdrop: Backdrop,
    tuning: GlassTuningState,
    style: GlassStyle,
    shape: Shape,
    componentRole: GlassComponentRole,
    modifier: Modifier = Modifier,
    highlightOnIdle: Boolean = true,
    lensEnabled: Boolean = true,
    pressFeedbackEnabled: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tuningScope = GlassTuningScope.fromRole(componentRole)
    val colors = MaterialTheme.colorScheme
    val isDark = colors.onBackground.luminance() > 0.5f
    // Floating pill controls (search button, filter group, tab rails) are objects rather than
    // bars: they share the chrome tint below but are bucketed separately so their edge and
    // highlight treatment can evolve independently from real bars (bottom nav, reader
    // toolbars, settings top bar).
    val surfaceAlpha = tuning.value(tuningScope, GlassTuningParam.SURFACE_ALPHA)

    val tint = when (componentRole) {
        GlassComponentRole.TopBar,
        GlassComponentRole.BottomBar,
        GlassComponentRole.PillControl,
        // Navigation chrome uses the official high-contrast container tint
        // (near-white / near-black) instead of a low-chroma Material surface
        // container; the higher alpha band keeps it readable over artwork.
        -> chromeBackdropTint(isDark = isDark).copy(alpha = surfaceAlpha)
        else -> colors.surfaceContainer.copy(alpha = surfaceAlpha)
    }

    // Persistent glass follows the upstream Control Center pattern: an always-on specular
    // highlight, with a touch additionally boosting exposure. Navigation chrome (bars), top pill
    // controls and large glass panels deliberately render without the persistent edge highlight:
    // bars keep the bar treatment, pills and panels favor a uniform hairline over the uneven
    // specular rim. Callers may opt out of the idle highlight (highlightOnIdle = false) so large
    // static info panels render clean while idle and only brighten while pressed.
    //
    // The highlight angle is static. It used to track the accelerometer, but the low-pass filter
    // never converged, so every sensor event wrote a new float and the whole window kept
    // re-recording (~135 frames/s with the page untouched, measured). The tilt response is not
    // visible in practice; see GlassStaticHighlightAngleDeg for where to revisit it.
    val pressProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // Pure observer: never consumes, so nested controls keep their own
    // gestures; any touch landing on the glass boosts its exposure. Top bars
    // (settings, reader top chrome) opt out of press tracking entirely, while
    // navigation bars (bottom nav), pill controls, and interactive content glass
    // track press so they react while touched.
    val pressTracking = if (!shouldTrackGlassPress(componentRole, pressFeedbackEnabled)) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                coroutineScope.launch { pressProgress.animateTo(1f, tween(90)) }
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    coroutineScope.launch { pressProgress.animateTo(0f, tween(160)) }
                }
            }
        }
    }

    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(
            modifier = modifier
                .then(pressTracking)
                .drawBackdrop(
                    backdrop = backdrop,
                    exportedBackdrop = exportedBackdrop,
                    shape = { shape },
                    effects = {
                        if (tuning.isOn(tuningScope, GlassTuningParam.VIBRANCY)) {
                            vibrancy()
                        }
                        // Independent color grading on top of the glass: saturation /
                        // brightness as a colorControls pass (SimpMusic / SPICaWeather
                        // recipe). Skipped at neutral values so it never stacks with
                        // vibrancy's own saturation lift.
                        val saturation = tuning.value(tuningScope, GlassTuningParam.SATURATION)
                        val brightness = tuning.value(tuningScope, GlassTuningParam.BRIGHTNESS)
                        if (saturation != 1f || brightness != 0f) {
                            colorControls(brightness = brightness, saturation = saturation)
                        }
                        blur(tuning.value(tuningScope, GlassTuningParam.BLUR_RADIUS_DP).dp.toPx())
                        // lens() requires a CornerBasedShape or the kyant
                        // RoundedRectangularShape; guard so callers passing a
                        // plain RectangleShape (edge-to-edge top chrome) degrade
                        // to blur instead of crashing during composition.
                        if (shape is CornerBasedShape || shape is RoundedRectangularShape) {
                            val press = resolveGlassPressProgress(pressFeedbackEnabled, pressProgress.value)
                            val lensBoost = 1f +
                                tuning.value(tuningScope, GlassTuningParam.PRESS_LENS_STRENGTH) * press
                            val lensHeightDp = tuning.value(
                                tuningScope,
                                GlassTuningParam.LENS_HEIGHT_DP,
                            ) * lensBoost
                            val lensAmountDp = tuning.value(
                                tuningScope,
                                GlassTuningParam.LENS_AMOUNT_DP,
                            ) * lensBoost
                            if (shouldApplyGlassLens(lensEnabled, lensHeightDp, lensAmountDp)) {
                                // Backdrop's lens SDF requires refractionHeight to stay within
                                // the surface's minimum corner radius and refractionAmount within
                                // its shortest side (KeiOS BackdropLensSafety mirrors this
                                // documented library constraint). Unclamped values paint internal
                                // arc artifacts and corner discontinuities on small surfaces —
                                // compact tab rails, pills, group controls.
                                val lensParams = resolveGlassLensParameters(
                                    shape = shape,
                                    size = size,
                                    layoutDirection = layoutDirection,
                                    density = this,
                                    requestedHeight = lensHeightDp.dp.toPx(),
                                    requestedAmount = lensAmountDp.dp.toPx(),
                                )
                                if (lensParams != null) {
                                    lens(
                                        refractionHeight = lensParams.refractionHeight,
                                        refractionAmount = lensParams.refractionAmount,
                                        depthEffect = tuning.isOn(tuningScope, GlassTuningParam.DEPTH_EFFECT),
                                        chromaticAberration = tuning.isOn(
                                            tuningScope,
                                            GlassTuningParam.CHROMATIC_ABERRATION,
                                        ) || (press > 0f && tuning.isOn(
                                            tuningScope,
                                            GlassTuningParam.PRESS_CHROMATIC_ABERRATION,
                                        )),
                                    )
                                }
                            }
                        }
                    },
                    highlight = {
                        val press = resolveGlassPressProgress(pressFeedbackEnabled, pressProgress.value)
                        val pressRimOn = tuningScope in GlassTuning.pressableRoles &&
                            press > 0f &&
                            tuning.value(tuningScope, GlassTuningParam.PRESS_HIGHLIGHT_ALPHA) > 0f
                        val idleRimOn = tuning.isOn(tuningScope, GlassTuningParam.RIM_ENABLED) && highlightOnIdle
                        // 0 = Default specular (static angle, see GlassStaticHighlightAngleDeg),
                        // 1 = Ambient (even edge glow — BiliTV / BiliPai look),
                        // 2 = Plain (uniform tint without a shader).
                        val edgeStyle = resolveGlassHighlightStyle(
                            tuning.value(tuningScope, GlassTuningParam.HIGHLIGHT_STYLE).toInt(),
                            angle = GlassStaticHighlightAngleDeg,
                        )
                        when {
                            pressRimOn -> Highlight(
                                style = edgeStyle,
                                alpha = press * tuning.value(tuningScope, GlassTuningParam.PRESS_HIGHLIGHT_ALPHA),
                            )
                            idleRimOn -> {
                                val rimAlpha = tuning.value(tuningScope, GlassTuningParam.RIM_ALPHA)
                                Highlight(
                                    style = edgeStyle,
                                    alpha = rimAlpha + (1f - rimAlpha) * press,
                                )
                            }
                            else -> null
                        }
                    },
                    shadow = if (tuning.isOn(tuningScope, GlassTuningParam.SHADOW_ENABLED) &&
                        style.shadowElevation > 0.dp
                    ) {
                        {
                            Shadow(
                                radius = tuning.value(tuningScope, GlassTuningParam.SHADOW_RADIUS_DP).dp,
                                offset = DpOffset(
                                    0.dp,
                                    tuning.value(tuningScope, GlassTuningParam.SHADOW_OFFSET_DP).dp,
                                ),
                                color = Color.Black.copy(
                                    alpha = tuning.value(tuningScope, GlassTuningParam.SHADOW_ALPHA),
                                ),
                            )
                        }
                    } else {
                        null
                    },
                    innerShadow = {
                        val press = resolveGlassPressProgress(pressFeedbackEnabled, pressProgress.value)
                        if (tuningScope in GlassTuning.pressableRoles && press > 0f &&
                            tuning.value(tuningScope, GlassTuningParam.PRESS_INNER_SHADOW_ALPHA) > 0f
                        ) {
                            InnerShadow(
                                radius = tuning.value(
                                    tuningScope,
                                    GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP,
                                ).dp * press,
                                alpha = press * tuning.value(
                                    tuningScope,
                                    GlassTuningParam.PRESS_INNER_SHADOW_ALPHA,
                                ),
                            )
                        } else {
                            null
                        }
                    },
                    layerBlock = {
                        val press = resolveGlassPressProgress(pressFeedbackEnabled, pressProgress.value)
                        if (tuningScope in GlassTuning.pressableRoles && press > 0f) {
                            val scale = 1f +
                                tuning.value(tuningScope, GlassTuningParam.PRESS_SCALE_PERCENT) / 100f * press
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = {
                        drawRect(tint)
                    },
                )
                // Hairline is the edge cue for floating chrome — pill controls
                // and the floating bottom bar. Full-width top bars (settings,
                // reader) deliberately stay borderless by default: a hairline
                // around an edge-to-edge panel reads as an unwanted frame at the
                // screen edge rather than a crisp control edge. It is a static
                // separator line (same family as the shadow), not the Liquid
                // Glass specular highlight.
                .then(
                    if (tuning.isOn(tuningScope, GlassTuningParam.HAIRLINE_ENABLED)) {
                        Modifier.border(
                            width = 1.dp,
                            color = if (isDark) {
                                Color.White.copy(alpha = tuning.value(
                                    tuningScope,
                                    GlassTuningParam.HAIRLINE_ALPHA,
                                ))
                            } else {
                                colors.outlineVariant.copy(alpha = tuning.value(
                                    tuningScope,
                                    GlassTuningParam.HAIRLINE_ALPHA,
                                ))
                            },
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )
    }
}
