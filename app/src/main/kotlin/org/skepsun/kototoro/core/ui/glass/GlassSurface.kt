package org.skepsun.kototoro.core.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.shape.CornerBasedShape
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalAmoledTheme
import org.skepsun.kototoro.core.ui.theme.isDarkTheme

@Immutable
data class GlassSurfaceColors(
    val containerColor: Color,
    val baseTintColor: Color,
    val blurRadius: Dp,
    val noiseFactor: Float,
    val border: BorderStroke,
)

@Composable
fun rememberGlassPrefs(settings: AppSettings): GlassPrefs {
    val prefs by settings.observeAsState(
        AppSettings.KEY_GLASS_EFFECT_ENABLED,
        AppSettings.KEY_REDUCED_VISUAL_EFFECTS,
        AppSettings.KEY_GLASS_IMMERSIVE_STRENGTH,
    ) {
        GlassPrefs(
            isGlassEffectEnabled = isGlassEffectEnabled && !isReducedVisualEffectsEnabled,
            isReducedVisualEffectsEnabled = isReducedVisualEffectsEnabled,
            immersiveStrengthPercent = glassImmersiveStrengthPercent,
        )
    }
    return prefs
}

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
internal val ChromeTintLight = Color(0xFFFAFAFA)
internal val ChromeTintDark = Color(0xFF121212)

internal fun chromeBackdropTint(isDark: Boolean): Color =
    if (isDark) ChromeTintDark else ChromeTintLight

internal fun GlassComponentRole.allowsAmoledBackdrop(): Boolean =
    this == GlassComponentRole.ContentOverlay ||
        this == GlassComponentRole.TopBar ||
        this == GlassComponentRole.BottomBar ||
        this == GlassComponentRole.PillControl ||
        this == GlassComponentRole.BottomPanel

object GlassDefaults {
    val shape: Shape = RoundedRectangle(28.dp)
    val navigationShadowElevation: Dp = 4.dp

    @Composable
    fun subtleStyle() = GlassStyle(0.72f, 0.18f, 0.dp, 0.dp)

    @Composable
    fun regularStyle() = GlassStyle(0.82f, 0.24f, 0.dp, 6.dp)

    @Composable
    fun prominentStyle() = GlassStyle(0.88f, 0.30f, 0.dp, 10.dp)

    @Composable
    fun topBarChromeStyle() = GlassStyle(0.88f, 0.20f, 0.dp, navigationShadowElevation)

    @Composable
    fun bottomBarChromeStyle() = GlassStyle(0.84f, 0.10f, 0.dp, navigationShadowElevation)

    /**
     * High-luminance-contrast base tint for navigation chrome (top/bottom bars
     * and pill controls), matching the official catalog's container color so
     * the glass surface stays clearly recognizable over any backdrop.
     */
    @Composable
    fun chromeBackdropTint(): Color = chromeBackdropTint(MaterialTheme.colorScheme.isDarkTheme())

    @Composable
    fun nestedCardColor(): Color {
        val colors = MaterialTheme.colorScheme
        return if (colors.isDarkTheme()) {
            colors.surfaceContainerHigh.copy(alpha = 0.78f)
        } else {
            colors.surface
        }
    }

    @Composable
    fun nestedCardBorderColor(): Color {
        val colors = MaterialTheme.colorScheme
        val alpha = if (colors.isDarkTheme()) 0.28f else 0.18f
        return colors.outlineVariant.copy(alpha = alpha)
    }
}

internal fun resolveGlassPressProgress(enabled: Boolean, progress: Float): Float =
    if (enabled) progress.coerceIn(0f, 1f) else 0f

internal fun shouldApplyGlassLens(enabled: Boolean, heightDp: Float, amountDp: Float): Boolean =
    enabled && heightDp > 0f && amountDp > 0f

/**
 * Maps the [GlassTuningParam.HIGHLIGHT_STYLE] option value to the Kyant
 * [HighlightStyle]: 0 = Default, 1 = Ambient, 2 = Plain.
 */
internal fun resolveGlassHighlightStyle(value: Int, angle: Float): HighlightStyle = when (value) {
    1 -> HighlightStyle.Ambient()
    2 -> HighlightStyle.Plain()
    else -> HighlightStyle.Default(angle = angle, falloff = 2f)
}

internal data class GlassLensParameters(
    val refractionHeight: Float,
    val refractionAmount: Float,
)

/**
 * Backdrop's lens shader requires refractionHeight to stay within the
 * surface's minimum corner radius and refractionAmount within its shortest
 * side. Clamping here keeps strong presets (e.g. Control Center lens 24/24)
 * from painting internal arc artifacts on small capsules, pills and group
 * controls while leaving large bars and panels untouched.
 */
internal fun resolveGlassLensParameters(
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

/**
 * Shared control surface.
 *
 * Material 3 always receives a stable semantic surface. iOS uses Backdrop only
 * when a same-window backdrop is available; dialogs and unsupported contexts
 * intentionally fall back to an opaque surface.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.regularStyle(),
    shape: Shape = GlassDefaults.shape,
    dialogSurface: Boolean = false,
    componentRole: GlassComponentRole = defaultGlassComponentRole(dialogSurface),
    highlightOnIdle: Boolean = true,
    lensEnabled: Boolean = true,
    pressFeedbackEnabled: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    @Suppress("UNUSED_PARAMETER") debugLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val backdrop = LocalLiquidGlassBackdrop.current
    val glassEnabled = rememberGlassPrefsOrFallback().isGlassEffectEnabled
    val amoledCanvas = LocalAmoledTheme.current
    val allowsBackdrop = !amoledCanvas || componentRole.allowsAmoledBackdrop()
    if (isIosStyle && glassEnabled && backdrop != null && !dialogSurface && allowsBackdrop) {
        LiquidGlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            componentRole = componentRole,
            highlightOnIdle = highlightOnIdle,
            lensEnabled = lensEnabled,
            pressFeedbackEnabled = pressFeedbackEnabled,
            exportedBackdrop = exportedBackdrop,
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isArtworkBackground = LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
    val fallbackColor = if (dialogSurface && isArtworkBackground) {
        colors.surfaceContainer.copy(alpha = 1f)
    } else if (!isIosStyle && isArtworkBackground) {
        colors.surfaceContainer.copy(alpha = 1f)
    } else if (isIosStyle) {
        colors.surfaceContainer.copy(alpha = if (dialogSurface) 0.98f else 0.94f)
    } else {
        colors.surfaceContainer
    }
    // Hairline is the standard edge cue for floating chrome — pill controls
    // and the floating bottom bar — while full-width top bars stay borderless;
    // the fallback mirrors the liquid path.
    val fallbackBorder = if (
        !dialogSurface && componentRole != GlassComponentRole.TopBar && style.borderAlpha > 0f
    ) {
        BorderStroke(1.dp, colors.outlineVariant.copy(alpha = style.borderAlpha))
    } else {
        null
    }
    CompositionLocalProvider(LocalAbsoluteTonalElevation provides 0.dp) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = fallbackColor,
            contentColor = colors.onSurface,
            border = fallbackBorder,
            tonalElevation = if (dialogSurface) 0.dp else style.tonalElevation,
            shadowElevation = if (dialogSurface) 0.dp else style.shadowElevation,
        ) {
            Box(content = content)
        }
    }
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.regularStyle(),
    shape: Shape = GlassDefaults.shape,
    componentRole: GlassComponentRole = GlassComponentRole.Surface,
    highlightOnIdle: Boolean = true,
    lensEnabled: Boolean = true,
    pressFeedbackEnabled: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val glassEnabled = rememberGlassPrefsOrFallback().isGlassEffectEnabled
    val amoledCanvas = LocalAmoledTheme.current
    val allowsBackdrop = !amoledCanvas || componentRole.allowsAmoledBackdrop()
    // Glass Finish Tuner (ADR 0001): resolve every drawer parameter from the
    // per-role scope. The state falls back to exact legacy values when nothing
    // has been tuned, so an untouched install renders pixel-identically.
    val tuning = LocalGlassTuning.current ?: emptyGlassTuningState()
    val tuningScope = GlassTuningScope.fromRole(componentRole)
    if (LocalInterfaceStyle.current != InterfaceStyle.IOS ||
        !glassEnabled ||
        backdrop == null ||
        !allowsBackdrop ||
        !tuning.isOn(tuningScope, GlassTuningParam.GLASS_ENABLED)
    ) {
        StableGlassFallback(
            modifier = modifier,
            style = style,
            shape = shape,
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isDark = colors.isDarkTheme()
    val isNavigationChrome = componentRole == GlassComponentRole.TopBar ||
        componentRole == GlassComponentRole.BottomBar
    // Floating pill controls (search button, filter group, tab rails) are
    // objects rather than bars: they share the chrome look but are bucketed
    // separately so their edge/highlight treatment can evolve independently
    // from real bars (bottom nav, reader toolbars, settings top bar).
    val isPillControl = componentRole == GlassComponentRole.PillControl
    val isFloatingChrome = isNavigationChrome || isPillControl
    // Large glass panels (the details bottom pane) also drop the always-on
    // specular highlight — like pills they prefer a uniform hairline — but keep
    // their own shadow, alpha and depth treatment.
    val suppressPersistentHighlight =
        isFloatingChrome || componentRole == GlassComponentRole.BottomPanel
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

    // Persistent glass follows the upstream Control Center pattern: an
    // always-on specular highlight whose angle tracks the device gravity (the
    // sensor mirrors iOS "specular highlight responding to device motion");
    // a touch additionally boosts exposure. Navigation chrome (bars), top pill
    // controls and large glass panels deliberately render without the
    // persistent edge highlight: bars keep the bar treatment, pills and panels
    // favor a uniform hairline over the uneven specular rim. Callers may opt
    // out of the idle highlight (highlightOnIdle = false) so large static info
    // panels render clean while idle and only brighten while pressed.
    val uiSensor = if (suppressPersistentHighlight || !highlightOnIdle) null else UiSensor.remember()
    val pressProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // Pure observer: never consumes, so nested controls keep their own
    // gestures; any touch landing on the glass boosts its exposure. Bars
    // (bottom nav, reader control shells) opt out of press tracking entirely —
    // a whole-bar brighten reads odd next to the discrete controls they host —
    // while pill controls and content glass track press so they glow while
    // touched.
    val pressTracking = if (isNavigationChrome || !pressFeedbackEnabled) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                coroutineScope.launch { pressProgress.animateTo(1f, tween(90)) }
                try {
                    waitForUpOrCancellation()
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
                        // 0 = Default specular (angle tracks gravity on non-bar chrome),
                        // 1 = Ambient (even edge glow — BiliTV / BiliPai look),
                        // 2 = Plain (uniform tint without a shader).
                        val edgeStyle = resolveGlassHighlightStyle(
                            tuning.value(tuningScope, GlassTuningParam.HIGHLIGHT_STYLE).toInt(),
                            angle = if (isFloatingChrome) 45f else (uiSensor?.gravityAngle ?: 45f),
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

internal fun GlassStyle.backdropSurfaceAlpha(
    componentRole: GlassComponentRole,
    amoledCanvas: Boolean,
): Float {
    val materialDensity = containerAlpha.coerceIn(minimumContainerAlpha, 1f)
    return when (componentRole) {
        GlassComponentRole.TopBar,
        GlassComponentRole.BottomBar,
        GlassComponentRole.PillControl,
        // Raised chrome alpha band (previously 0.30-0.46 non-AMOLED): with the
        // official high-contrast tint this keeps navigation chrome obviously
        // visible while still letting the backdrop blur show through.
        -> if (amoledCanvas) {
            (materialDensity * 0.72f).coerceIn(0.58f, 0.66f)
        } else {
            (materialDensity * 0.60f).coerceIn(0.45f, 0.55f)
        }
        GlassComponentRole.BottomPanel,
        GlassComponentRole.Sheet,
        -> (materialDensity * 0.50f).coerceIn(0.42f, 0.48f)
        else -> (materialDensity * 0.25f).coerceIn(0.14f, 0.28f)
    }
}

@Composable
fun Modifier.glassContainerShadow(
    shape: Shape,
    elevation: Dp = GlassDefaults.navigationShadowElevation,
): Modifier {
    if (elevation <= 0.dp) return this
    return shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.28f),
    )
}

@Composable
private fun StableGlassFallback(
    modifier: Modifier,
    style: GlassStyle,
    shape: Shape,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        tonalElevation = style.tonalElevation,
        shadowElevation = style.shadowElevation,
    ) {
        Box(content = content)
    }
}

@Composable
fun rememberGlassPrefsOrFallback(): GlassPrefs {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context.applicationContext).settings
    }
    return rememberGlassPrefs(settings)
}

@Composable
fun rememberGlassSurfaceColors(
    style: GlassStyle = GlassDefaults.regularStyle(),
    glassPrefs: GlassPrefs = rememberGlassPrefsOrFallback(),
): GlassSurfaceColors {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.isDarkTheme()
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    return remember(style, glassPrefs, colors, isDark, isIosStyle) {
        val base = when {
            style.shadowElevation >= 10.dp -> colors.surfaceContainerHigh
            style.shadowElevation >= 6.dp -> colors.surfaceContainer
            else -> colors.surfaceContainerLow
        }.let { if (isDark) lerp(it, colors.surfaceBright, 0.08f) else it }
        val alpha = if (isIosStyle) {
            style.containerAlpha.coerceIn(style.minimumContainerAlpha, 1f)
        } else {
            1f
        }
        GlassSurfaceColors(
            containerColor = base.copy(alpha = alpha),
            baseTintColor = base.copy(alpha = if (isDark) 0.30f else 0.22f),
            blurRadius = 8.dp,
            noiseFactor = 0f,
            border = BorderStroke(
                1.dp,
                colors.outlineVariant.copy(alpha = if (isDark) style.borderAlpha else style.borderAlpha.coerceAtMost(0.18f)),
            ),
        )
    }
}

@Composable
fun GlassTopBarContainer(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.topBarChromeStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = style,
        shape = RoundedRectangle(30.dp),
        componentRole = GlassComponentRole.TopBar,
        content = content,
    )
}

@Composable
fun GlassBottomBarContainer(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.bottomBarChromeStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = style,
        shape = RoundedRectangle(32.dp),
        componentRole = GlassComponentRole.BottomBar,
        content = content,
    )
}

private fun defaultGlassComponentRole(dialogSurface: Boolean): GlassComponentRole =
    if (dialogSurface) GlassComponentRole.Dialog else GlassComponentRole.Surface
