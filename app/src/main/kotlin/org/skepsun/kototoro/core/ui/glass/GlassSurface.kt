package org.skepsun.kototoro.core.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.shapes.RoundedRectangle
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalAmoledTheme
import org.skepsun.kototoro.core.ui.theme.ArtworkSurfaceRole
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.core.ui.theme.isDarkTheme
import org.skepsun.kototoro.core.ui.theme.popupMenuContainerColor

/**
 * Specular highlight angle for every glass surface.
 *
 * It used to follow the accelerometer; that was removed because the low-pass filter never
 * converged, so each sensor event wrote a new float and the window never stopped re-recording
 * (measured ~135 frames/s on an untouched page, ~9 ms of full-window draw record per frame).
 * Revisit here if a tilt response is ever wanted again — it must publish quantized, converging
 * values so an untouched device stays idle.
 */
private const val GlassStaticHighlightAngleDeg = 45f

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

/**
 * Shared control surface.
 *
 * Material 3 always receives a stable semantic surface. iOS uses Backdrop only
 * when a same-window backdrop is available; dialogs and unsupported contexts
 * intentionally fall back to an opaque surface, while menus retain only a
 * restrained amount of artwork translucency to protect text readability.
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
    backdropOverride: Backdrop? = null,
    exportedBackdrop: LayerBackdrop? = null,
    @Suppress("UNUSED_PARAMETER") debugLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val backdrop = backdropOverride ?: LocalLiquidGlassBackdrop.current
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
            backdropOverride = backdrop,
            exportedBackdrop = exportedBackdrop,
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
    // Map the glass component role onto the artwork layering so the Material fallback
    // stops rendering opaque stickers over the image and instead joins the same
    // hierarchy the iOS liquid path uses.
    val artworkRole = when (componentRole) {
        GlassComponentRole.TopBar, GlassComponentRole.BottomBar -> ArtworkSurfaceRole.Chrome
        GlassComponentRole.PillControl, GlassComponentRole.Menu -> ArtworkSurfaceRole.PillControl
        GlassComponentRole.ContentOverlay, GlassComponentRole.BottomPanel -> ArtworkSurfaceRole.Card
        else -> ArtworkSurfaceRole.Surface
    }
    // Chrome roles (top bar, bottom bar, pill controls) use the same high-contrast
    // tint on the Material fallback as the iOS liquid path — near-black in dark,
    // near-white in light — so chrome reads as a distinct translucent surface over any
    // backdrop instead of the flat surfaceContainer light slab. Content roles keep
    // surfaceContainer. This is what the bottom nav already does; aligning the top bar
    // chrome to it removes the "nested light capsule" artifact on non-white themes.
    val isChromeRole = componentRole == GlassComponentRole.TopBar ||
        componentRole == GlassComponentRole.BottomBar ||
        componentRole == GlassComponentRole.PillControl
    val isDark = colors.isDarkTheme()
    val fallbackColor = if (componentRole == GlassComponentRole.Menu && isArtworkBackground) {
        colors.popupMenuContainerColor()
    } else if (dialogSurface && isArtworkBackground) {
        colors.surfaceContainer.copy(alpha = 1f)
    } else if (isArtworkBackground) {
        val base = if (isChromeRole) chromeBackdropTint(isDark) else colors.surfaceContainer
        base.artworkAwareContainerColor(artworkRole)
    } else if (isIosStyle) {
        colors.surfaceContainer.copy(alpha = if (dialogSurface) 0.98f else 0.94f)
    } else {
        colors.surfaceContainer
    }
    // Hairline is the standard edge cue for floating chrome — pill controls
    // and the floating bottom bar — while full-width top bars stay borderless;
    // the fallback mirrors the liquid path. Over artwork every translucent surface
    // gets the hairline so its edge stays defined against the image.
    val fallbackBorder = if (
        !dialogSurface &&
        (componentRole != GlassComponentRole.TopBar || isArtworkBackground) &&
        style.borderAlpha > 0f
    ) {
        BorderStroke(1.dp, colors.outlineVariant.copy(alpha = style.borderAlpha))
    } else {
        null
    }
    // Over artwork every role is deliberately translucent, so this is what used to
    // turn each top-bar pill into a light island inside a darker capsule. See
    // resolveFallbackShadowElevation above for why the shadow cannot simply stay.
    val effectiveShadowElevation = resolveFallbackShadowElevation(
        styleShadowElevation = style.shadowElevation,
        containerAlpha = fallbackColor.alpha,
        flat = dialogSurface,
    )
    CompositionLocalProvider(LocalAbsoluteTonalElevation provides 0.dp) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = fallbackColor,
            contentColor = colors.onSurface,
            border = fallbackBorder,
            tonalElevation = if (dialogSurface) 0.dp else style.tonalElevation,
            shadowElevation = effectiveShadowElevation,
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
    backdropOverride: Backdrop? = null,
    exportedBackdrop: LayerBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = backdropOverride ?: LocalLiquidGlassBackdrop.current
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

    SharedLiquidGlassSurface(
        backdrop = backdrop,
        tuning = tuning,
        style = style,
        shape = shape,
        componentRole = componentRole,
        modifier = modifier,
        highlightOnIdle = highlightOnIdle,
        lensEnabled = lensEnabled,
        pressFeedbackEnabled = pressFeedbackEnabled,
        exportedBackdrop = exportedBackdrop,
        content = content,
    )
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
