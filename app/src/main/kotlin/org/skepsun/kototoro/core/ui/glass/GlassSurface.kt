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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
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
    BottomPanel,
    Menu,
    Dialog,
    Sheet,
}

internal fun GlassComponentRole.allowsAmoledBackdrop(): Boolean =
    this == GlassComponentRole.ContentOverlay ||
        this == GlassComponentRole.TopBar ||
        this == GlassComponentRole.BottomBar ||
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
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isArtworkBackground = LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
    val isNavigationChrome = componentRole == GlassComponentRole.TopBar ||
        componentRole == GlassComponentRole.BottomBar
    val fallbackColor = if (dialogSurface && isArtworkBackground) {
        colors.surfaceContainer.copy(alpha = 1f)
    } else if (!isIosStyle && isArtworkBackground) {
        colors.surfaceContainer.copy(alpha = 1f)
    } else if (isIosStyle) {
        colors.surfaceContainer.copy(alpha = if (dialogSurface) 0.98f else 0.94f)
    } else {
        colors.surfaceContainer
    }
    val fallbackBorder = if (!dialogSurface && !isNavigationChrome && style.borderAlpha > 0f) {
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
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val glassEnabled = rememberGlassPrefsOrFallback().isGlassEffectEnabled
    val amoledCanvas = LocalAmoledTheme.current
    val allowsBackdrop = !amoledCanvas || componentRole.allowsAmoledBackdrop()
    if (LocalInterfaceStyle.current != InterfaceStyle.IOS || !glassEnabled || backdrop == null || !allowsBackdrop) {
        StableGlassFallback(
            modifier = modifier,
            style = style,
            shape = shape,
            content = content,
        )
        return
    }

    val exportedBackdrop = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    val isDark = colors.isDarkTheme()
    val isNavigationChrome = componentRole == GlassComponentRole.TopBar ||
        componentRole == GlassComponentRole.BottomBar
    val surfaceAlpha = style.backdropSurfaceAlpha(
        componentRole = componentRole,
        amoledCanvas = amoledCanvas,
    )
    val tint = when (componentRole) {
        GlassComponentRole.TopBar,
        GlassComponentRole.BottomBar,
        -> colors.surfaceContainerHigh.copy(alpha = surfaceAlpha)
        else -> colors.surfaceContainer.copy(alpha = surfaceAlpha)
    }

    // Persistent glass follows the upstream Control Center pattern: an
    // always-on specular highlight whose angle tracks the device gravity (the
    // sensor mirrors iOS "specular highlight responding to device motion");
    // a touch additionally boosts exposure. Navigation chrome (top/bottom bars)
    // deliberately renders without the edge highlight. Callers may opt out of
    // the idle highlight (highlightOnIdle = false) so large static info panels
    // render clean while idle and only brighten while pressed.
    val uiSensor = if (isNavigationChrome || !highlightOnIdle) null else UiSensor.remember()
    val pressProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val pressTracking = if (isNavigationChrome) {
        Modifier
    } else {
        // Pure observer: never consumes, so nested controls keep their own
        // gestures; any touch landing on the glass boosts its exposure.
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
                        vibrancy()
                        blur(8.dp.toPx())
                        // lens() requires a CornerBasedShape or the kyant
                        // RoundedRectangularShape; guard so callers passing a
                        // plain RectangleShape (edge-to-edge top chrome) degrade
                        // to blur instead of crashing during composition.
                        if (shape is CornerBasedShape || shape is RoundedRectangularShape) {
                            lens(
                                refractionHeight = 16.dp.toPx(),
                                refractionAmount = 24.dp.toPx(),
                                depthEffect = !isNavigationChrome,
                                chromaticAberration = false,
                            )
                        }
                    },
                    highlight = {
                        // iOS navigation chrome (top bar + bottom bar) stays
                        // without the edge highlight. Content glass defaults to
                        // the upstream Control Center always-on specular highlight
                        // that follows device gravity; surfaces with
                        // highlightOnIdle = false (large static info panels)
                        // render without an idle edge and brighten only while
                        // pressed, mirroring the library's own controls.
                        if (isNavigationChrome) {
                            null
                        } else if (!highlightOnIdle) {
                            if (pressProgress.value == 0f) null
                            else {
                                Highlight(
                                    style = HighlightStyle.Default(
                                        angle = 45f,
                                        falloff = 2f,
                                    ),
                                    alpha = pressProgress.value,
                                )
                            }
                        } else {
                            Highlight(
                                style = HighlightStyle.Default(
                                    angle = uiSensor?.gravityAngle ?: 45f,
                                    falloff = 2f,
                                ),
                                alpha = 0.65f + 0.35f * pressProgress.value,
                            )
                        }
                    },
                    shadow = if (style.shadowElevation > 0.dp) {
                        {
                            // Light floating shadow, matching the library's own
                            // controls (Shadow.Default is a heavy 24.dp drop that
                            // reads as a hard ring on glass); use only a subtle
                            // separation cue per the upstream glass bottom bar.
                            Shadow(
                                radius = 4.dp,
                                offset = DpOffset(0.dp, 2.dp),
                                color = Color.Black.copy(
                                    alpha = if (isNavigationChrome) 0.10f else 0.06f,
                                ),
                            )
                        }
                    } else {
                        null
                    },
                    onDrawSurface = {
                        drawRect(tint)
                    },
                )
                .then(
                    if (isNavigationChrome) {
                        Modifier
                    } else {
                        Modifier.border(
                            width = 1.dp,
                            color = if (isDark) {
                                Color.White.copy(alpha = style.borderAlpha)
                            } else {
                                colors.outlineVariant.copy(alpha = style.borderAlpha)
                            },
                            shape = shape,
                        )
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
        -> if (amoledCanvas) {
            (materialDensity * 0.66f).coerceIn(0.52f, 0.60f)
        } else {
            (materialDensity * 0.48f).coerceIn(0.30f, 0.46f)
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
