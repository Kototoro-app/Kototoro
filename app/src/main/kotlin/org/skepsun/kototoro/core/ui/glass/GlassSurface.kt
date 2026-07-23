package org.skepsun.kototoro.core.ui.glass

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.CupertinoMaterials
import dev.chrisbanes.haze.blur.materials.FluentMaterials
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.prefs.resolvePreset
import org.skepsun.kototoro.core.prefs.toFamily
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private const val GLASS_SURFACE_TAG = "GlassSurface"

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
        AppSettings.KEY_GLASS_MATERIAL_PRESET,
        AppSettings.KEY_HAZE_OPACITY,
        AppSettings.KEY_GLASS_BLUR_STRENGTH,
        AppSettings.KEY_GLASS_NOISE_STRENGTH,
        AppSettings.KEY_GLASS_IMMERSIVE_STRENGTH,
    ) {
        GlassPrefs(
            isGlassEffectEnabled = isGlassEffectEnabled && !isReducedVisualEffectsEnabled,
            isReducedVisualEffectsEnabled = isReducedVisualEffectsEnabled,
            materialPreset = glassMaterialPreset,
            hazeOpacityPercent = hazeOpacityPercent,
            blurStrengthPercent = glassBlurStrengthPercent,
            noiseStrengthPercent = glassNoiseStrengthPercent,
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
    TopBar,
    BottomBar,
    Menu,
    Dialog,
    Sheet,
}

object GlassDefaults {
    val shape: Shape = RoundedCornerShape(28.dp)

    @Composable
    fun subtleStyle(): GlassStyle = GlassStyle(
        containerAlpha = 0.72f,
        borderAlpha = 0.18f,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    )

    @Composable
    fun regularStyle(): GlassStyle = GlassStyle(
        containerAlpha = 0.82f,
        borderAlpha = 0.24f,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    )

    @Composable
    fun prominentStyle(): GlassStyle = GlassStyle(
        containerAlpha = 0.88f,
        borderAlpha = 0.30f,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    )

    @Composable
    fun topBarChromeStyle(): GlassStyle = GlassStyle(
        containerAlpha = 0.88f,
        borderAlpha = 0.20f,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    )

    @Composable
    fun bottomBarChromeStyle(): GlassStyle = GlassStyle(
        containerAlpha = 0.84f,
        borderAlpha = 0.10f,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    )

    @Composable
    fun nestedCardColor(): Color {
        val colorScheme = MaterialTheme.colorScheme
        val isDarkTheme = colorScheme.background.luminance() < 0.5f
        return if (isDarkTheme) {
            colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
        } else {
            colorScheme.surface.copy(alpha = 0.42f)
        }
    }

    @Composable
    fun nestedCardBorderColor(): Color {
        val colorScheme = MaterialTheme.colorScheme
        val isDarkTheme = colorScheme.background.luminance() < 0.5f
        return colorScheme.outlineVariant.copy(alpha = if (isDarkTheme) 0.28f else 0.18f)
    }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.regularStyle(),
    shape: Shape = GlassDefaults.shape,
    allowRuntimeHaze: Boolean = true,
    dialogSurface: Boolean = false,
    expandHazeLayerBounds: Boolean = !dialogSurface,
    visualTreatment: GlassVisualTreatment = GlassVisualTreatment.Standard,
    componentRole: GlassComponentRole = defaultGlassComponentRole(
        dialogSurface = dialogSurface,
        visualTreatment = visualTreatment,
    ),
    debugLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glassPrefs = rememberGlassPrefsOrFallback()
    val backdrop = LocalLiquidGlassBackdrop.current
    if (LocalInterfaceStyle.current == InterfaceStyle.IOS && backdrop != null && !dialogSurface) {
        LiquidGlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            componentRole = componentRole,
            content = content,
        )
        return
    }
    val hazeState = LocalHazeState.current
    val interfaceStyle = LocalInterfaceStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    val usesOfficialHazeMaterial = glassPrefs.materialPreset.usesOfficialHazeMaterial()
    val usePrototypeChrome = visualTreatment == GlassVisualTreatment.TopBarPrototype && !usesOfficialHazeMaterial
    val effectiveStyle = if (dialogSurface) {
        style.copy(
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        )
    } else {
        style
    }
    val glassColors = rememberGlassSurfaceColors(style = effectiveStyle, glassPrefs = glassPrefs)

    val useRuntimeHaze = glassPrefs.isGlassEffectEnabled &&
        allowRuntimeHaze &&
        isRuntimeHazeAvailable()
    val shouldUsePrototypeSurfaceFill = useRuntimeHaze &&
        usePrototypeChrome &&
        !dialogSurface &&
        !usesOfficialHazeMaterial
    val hazeStyle = rememberGlassHazeStyle(
        glassPrefs = glassPrefs,
        glassColors = glassColors,
        componentRole = componentRole,
        dialogSurface = dialogSurface,
    )
    val hazeBackgroundColor = rememberGlassHazeBackgroundColor(
        glassPrefs = glassPrefs,
        glassColors = glassColors,
        hazeStyle = hazeStyle,
        componentRole = componentRole,
        dialogSurface = dialogSurface,
    )
    val runtimeChromeFillColor = remember(
        useRuntimeHaze,
        usePrototypeChrome,
        dialogSurface,
        hazeBackgroundColor,
        hazeStyle,
        glassColors,
        shouldUsePrototypeSurfaceFill,
    ) {
        if (!shouldUsePrototypeSurfaceFill) {
            Color.Unspecified
        } else {
            hazeBackgroundColor.takeOrElse {
                hazeStyle.backgroundColor.takeOrElse {
                    glassColors.containerColor
                }
            }
        }
    }
    if (dialogSurface) {
        ApplyDynamicArtworkBlurDialogStyle()
    }
    val backgroundStyle = LocalBackgroundStyle.current
    val surfaceColor = when {
        dialogSurface -> if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            glassColors.containerColor.copy(alpha = 0.86f.coerceAtLeast(glassColors.containerColor.alpha))
        } else {
            glassColors.containerColor
        }
        shouldUsePrototypeSurfaceFill -> runtimeChromeFillColor
        useRuntimeHaze && !dialogSurface -> Color.Transparent
        !useRuntimeHaze && glassPrefs.isGlassEffectEnabled && usesOfficialHazeMaterial -> {
            hazeStyle.backgroundColor.takeOrElse { glassColors.containerColor }
        }
        else -> glassColors.containerColor
    }
    val liquidGlassSpec = remember(
        effectiveStyle,
        glassColors,
        hazeStyle,
        hazeBackgroundColor,
        surfaceColor,
        usesOfficialHazeMaterial,
        isDarkTheme,
    ) {
        val resolvedBaseColor = hazeBackgroundColor.takeOrElse {
            hazeStyle.backgroundColor.takeOrElse {
                surfaceColor.takeOrElse { glassColors.containerColor }
            }
        }
        val baseLuminance = resolvedBaseColor.luminance()
        val lightThemeBoost = if (isDarkTheme) {
            1f
        } else {
            (1f + ((baseLuminance - 0.58f).coerceAtLeast(0f) * 0.9f)).coerceAtMost(1.28f)
        }
        val materialSoftnessBoost = when {
            usesOfficialHazeMaterial -> 1.04f + ((1f - effectiveStyle.containerAlpha) * 0.45f)
            else -> 1f + ((1f - effectiveStyle.containerAlpha) * 0.20f)
        }.coerceAtMost(1.22f)
        val edgeBoost = (lightThemeBoost * materialSoftnessBoost).coerceIn(1f, 1.36f)
        val widthBoost = (1f + ((edgeBoost - 1f) * 0.72f)).coerceIn(1f, 1.26f)
        val innerWidthBoost = (1f + ((edgeBoost - 1f) * 0.48f)).coerceIn(1f, 1.18f)

        LiquidGlassPrototypeSpec(
            glowAlpha = (0.12f * edgeBoost).coerceAtMost(0.22f),
            edgeAlpha = (when {
                effectiveStyle.containerAlpha >= 0.86f -> 0.28f
                else -> 0.24f
            } * edgeBoost).coerceAtMost(0.42f),
            innerEdgeAlpha = (0.12f * edgeBoost).coerceAtMost(0.22f),
            edgeWidthMultiplier = (widthBoost * 0.94f).coerceIn(0.94f, 1.18f),
            innerEdgeWidthMultiplier = (innerWidthBoost * 0.92f).coerceIn(0.92f, 1.10f),
        )
    }
    var lastDebugBounds by remember(debugLabel) { mutableStateOf<String?>(null) }
    var lastDebugConfig by remember(debugLabel) { mutableStateOf<String?>(null) }
    if (BuildConfig.DEBUG && debugLabel != null) {
        val debugConfig =
            "$debugLabel config interfaceStyle=$interfaceStyle useRuntimeHaze=$useRuntimeHaze " +
                "allowRuntimeHaze=$allowRuntimeHaze " +
                "dialogSurface=$dialogSurface material=${glassPrefs.materialPreset} " +
                "componentRole=$componentRole " +
                "opacity=${glassPrefs.hazeOpacityPercent} blurPref=${glassPrefs.blurStrengthPercent} " +
                "noisePref=${glassPrefs.noiseStrengthPercent} " +
                "tonalElevation=${effectiveStyle.tonalElevation} shadowElevation=${effectiveStyle.shadowElevation} " +
                "surfaceAlpha=${surfaceColor.alpha} hazeBgAlpha=${hazeBackgroundColor.alpha} " +
                "blurRadius=${glassColors.blurRadius} noise=${glassColors.noiseFactor} " +
                "styleBgAlpha=${hazeStyle.backgroundColor.alpha} fallbackTint=${hazeStyle.fallbackColorEffect}"
        if (debugConfig != lastDebugConfig) {
            lastDebugConfig = debugConfig
            Log.d(GLASS_SURFACE_TAG, debugConfig)
        }
    }

    CompositionLocalProvider(LocalAbsoluteTonalElevation provides 0.dp) {
        Surface(
            modifier = if (useRuntimeHaze) {
                modifier
                    .debugGlassBounds(debugLabel, lastDebugBounds) { lastDebugBounds = it }
                    .clip(shape)
                    .hazeEffect(hazeState) {
                        inputScale = HazeInputScale.Auto
                        if (usePrototypeChrome) {
                            liquidGlassPrototypeEffect {
                                blurStyle = hazeStyle
                                backgroundColor = hazeBackgroundColor
                                blurredEdgeTreatment = BlurredEdgeTreatment(shape)
                                this.shape = shape
                                spec = liquidGlassSpec
                            }
                        } else {
                            blurEffect {
                                this.style = hazeStyle
                                backgroundColor = hazeBackgroundColor
                                blurredEdgeTreatment = BlurredEdgeTreatment(shape)
                            }
                        }
                        clipToAreasBounds = true
                        expandLayerBounds = expandHazeLayerBounds
                        forceInvalidateOnPreDraw = true
                    }
            } else {
                modifier.debugGlassBounds(debugLabel, lastDebugBounds) { lastDebugBounds = it }
            },
            shape = shape,
            color = surfaceColor,
            contentColor = colorScheme.onSurface,
            tonalElevation = effectiveStyle.tonalElevation,
            shadowElevation = effectiveStyle.shadowElevation,
            border = glassColors.border,
        ) {
            val overlayModifier = if (
                usePrototypeChrome &&
                !useRuntimeHaze &&
                glassPrefs.isGlassEffectEnabled
            ) {
                rememberLiquidGlassPrototypeOverlayModifier(liquidGlassSpec)
            } else {
                Modifier
            }
            Box(
                modifier = overlayModifier,
                content = content,
            )
        }
    }
}

/**
 * A root-layer liquid glass surface for content panels.
 *
 * Backdrop is only used when the current composition owns a same-window
 * backdrop source. Popup/dialog surfaces continue to use the regular GlassSurface
 * fallback because their coordinate space is a separate window.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.regularStyle(),
    shape: Shape = GlassDefaults.shape,
    componentRole: GlassComponentRole = GlassComponentRole.Surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val exportedBackdrop = rememberLayerBackdrop()
    val useBackdrop = LocalInterfaceStyle.current == InterfaceStyle.IOS && backdrop != null
    if (!useBackdrop) {
        GlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            componentRole = componentRole,
            content = content,
        )
        return
    }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surfaceTint = when (componentRole) {
        GlassComponentRole.TopBar,
        GlassComponentRole.BottomBar,
        -> if (isDarkTheme) {
            Color.Black.copy(alpha = 0.20f)
        } else {
            Color.White.copy(alpha = 0.20f)
        }
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.18f)
    }

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop!!,
                exportedBackdrop = exportedBackdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(
                        refractionHeight = 12.dp.toPx(),
                        refractionAmount = 10.dp.toPx(),
                        chromaticAberration = false,
                    )
                },
            )
            .background(surfaceTint, shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
        content = content,
    )
}

private fun Modifier.debugGlassBounds(
    debugLabel: String?,
    lastBounds: String?,
    onBoundsChanged: (String) -> Unit,
): Modifier {
    if (!BuildConfig.DEBUG || debugLabel == null) return this
    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val message = "$debugLabel surface size=${coordinates.size.width}x${coordinates.size.height} " +
            "window=[${bounds.left},${bounds.top} - ${bounds.right},${bounds.bottom}]"
        if (message != lastBounds) {
            onBoundsChanged(message)
            Log.d(GLASS_SURFACE_TAG, message)
        }
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
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    return remember(glassPrefs, isDarkTheme, style, colorScheme) {
        computeGlassColors(
            isGlassEffectEnabled = glassPrefs.isGlassEffectEnabled,
            hazeOpacityPercent = glassPrefs.hazeOpacityPercent,
            blurStrengthPercent = glassPrefs.blurStrengthPercent,
            noiseStrengthPercent = glassPrefs.noiseStrengthPercent,
            isDarkTheme = isDarkTheme,
            style = style,
            colorScheme = colorScheme,
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
        shape = RoundedCornerShape(30.dp),
        visualTreatment = GlassVisualTreatment.TopBarPrototype,
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
    val shape = RoundedCornerShape(32.dp)
    val backdrop = LocalLiquidGlassBackdrop.current
    val exportedBackdrop = rememberLayerBackdrop()
    if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
        Box(
            modifier = modifier
                .background(Color.White.copy(alpha = 0.08f), shape)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            exportedBackdrop = exportedBackdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(8.dp.toPx())
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.24f), shape),
            content = content,
        )
    } else {
        GlassSurface(
            modifier = modifier,
            style = style,
            shape = shape,
            visualTreatment = GlassVisualTreatment.TopBarPrototype,
            componentRole = GlassComponentRole.BottomBar,
            content = content,
        )
    }
}

private fun computeGlassColors(
    isGlassEffectEnabled: Boolean,
    hazeOpacityPercent: Int,
    blurStrengthPercent: Int,
    noiseStrengthPercent: Int,
    isDarkTheme: Boolean,
    style: GlassStyle,
    colorScheme: androidx.compose.material3.ColorScheme,
): GlassSurfaceColors {
    val preferenceAlpha = (hazeOpacityPercent.coerceIn(0, 100)) / 100f
    val effectiveContainerAlpha = resolveContainerAlpha(
        preferenceAlpha = preferenceAlpha,
        styleContainerAlpha = style.containerAlpha,
        minimumContainerAlpha = style.minimumContainerAlpha,
    )
    if (!isGlassEffectEnabled) {
        val fallbackBaseColor = when {
            effectiveContainerAlpha >= 0.86f -> colorScheme.surfaceContainerHigh
            effectiveContainerAlpha >= 0.80f -> colorScheme.surfaceContainer
            style.shadowElevation >= 10.dp -> colorScheme.surfaceContainerHigh
            style.shadowElevation >= 6.dp -> colorScheme.surfaceContainer
            else -> colorScheme.surfaceContainerLow
        }.let { candidate ->
            if (isDarkTheme) lerp(candidate, colorScheme.surfaceBright, 0.08f) else candidate
        }
        val borderAlpha = if (isDarkTheme) {
            style.borderAlpha.coerceIn(0.18f, 0.30f)
        } else {
            style.borderAlpha.coerceAtMost(0.18f)
        }
        return GlassSurfaceColors(
            containerColor = fallbackBaseColor.copy(alpha = effectiveContainerAlpha),
            baseTintColor = fallbackBaseColor.copy(alpha = effectiveContainerAlpha),
            blurRadius = 0.dp,
            noiseFactor = 0f,
            border = BorderStroke(
                width = 1.dp,
                color = colorScheme.outlineVariant.copy(alpha = borderAlpha),
            ),
        )
    }

    val baseColor = when {
        effectiveContainerAlpha >= 0.86f -> colorScheme.surfaceContainerHigh
        effectiveContainerAlpha >= 0.80f -> colorScheme.surfaceContainer
        else -> colorScheme.surfaceContainerLow
    }.let { candidate ->
        if (isDarkTheme) lerp(candidate, colorScheme.surfaceBright, 0.16f) else candidate
    }
    val baseBlurRadius = when {
        style.shadowElevation >= 10.dp -> 28.dp
        style.shadowElevation >= 6.dp -> 24.dp
        else -> 18.dp
    }
    val blurRadius = blurStrengthPercent.coerceIn(0, 80).dp
        .takeIf { it > 0.dp }
        ?: baseBlurRadius
    val noiseFactor = (noiseStrengthPercent.coerceIn(0, 100)) / 100f
    val tintAlpha = ((preferenceAlpha * 0.22f) + (style.containerAlpha * 0.14f)).coerceIn(0.18f, 0.38f)
        .let { alpha ->
            if (isDarkTheme) (alpha + 0.10f).coerceAtMost(0.50f) else alpha
        }
    val border = BorderStroke(
        width = 1.dp,
        color = colorScheme.outlineVariant.copy(
            alpha = if (isDarkTheme) style.borderAlpha.coerceIn(0.16f, 0.28f) else style.borderAlpha.coerceAtMost(0.18f),
        ),
    )
    return GlassSurfaceColors(
        containerColor = baseColor.copy(alpha = effectiveContainerAlpha),
        baseTintColor = baseColor.copy(alpha = tintAlpha),
        blurRadius = blurRadius,
        noiseFactor = noiseFactor,
        border = border,
    )
}

private fun resolveContainerAlpha(
    preferenceAlpha: Float,
    styleContainerAlpha: Float,
    minimumContainerAlpha: Float,
): Float {
    val normalizedPreferenceAlpha = preferenceAlpha.coerceIn(0f, 1f)
    val normalizedStyleAlpha = styleContainerAlpha.coerceIn(0f, 1f)
    val normalizedMinimumAlpha = minimumContainerAlpha.coerceIn(0f, normalizedStyleAlpha)
    val defaultPreferenceAlpha = AppSettings.GlassMaterialDefaults.STYLE_BASELINE_OPACITY_PERCENT / 100f
    return if (normalizedPreferenceAlpha <= defaultPreferenceAlpha) {
        (normalizedPreferenceAlpha / defaultPreferenceAlpha) * normalizedStyleAlpha
    } else {
        val boostProgress = (normalizedPreferenceAlpha - defaultPreferenceAlpha) / (1f - defaultPreferenceAlpha)
        normalizedStyleAlpha + ((1f - normalizedStyleAlpha) * boostProgress)
    }.coerceIn(normalizedMinimumAlpha, 1f)
}

@Composable
fun rememberGlassHazeStyle(
    glassPrefs: GlassPrefs,
    glassColors: GlassSurfaceColors,
    componentRole: GlassComponentRole,
    dialogSurface: Boolean = false,
): HazeBlurStyle {
    val effectivePreset = remember(glassPrefs.materialPreset, componentRole) {
        glassPrefs.materialPreset.toFamily().resolvePreset(componentRole)
    }
    val usesOfficialHazeMaterial = effectivePreset.usesOfficialHazeMaterial()
    val officialContainerColor = rememberOfficialMaterialContainerColor(
        preset = effectivePreset,
        fallbackColor = glassColors.containerColor,
    )
    val baseStyle = when (effectivePreset) {
        AppSettings.GlassMaterialPreset.KOTOTORO,
        AppSettings.GlassMaterialPreset.CUSTOM -> if (dialogSurface) {
            HazeBlurDefaults.style(
                Color.Transparent,
                HazeBlurDefaults.tint(Color.Transparent),
                glassColors.blurRadius,
                glassColors.noiseFactor,
            )
        } else {
            HazeBlurDefaults.style(
                Color.Transparent,
                HazeBlurDefaults.tint(glassColors.baseTintColor),
                glassColors.blurRadius,
                glassColors.noiseFactor,
            )
        }
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN,
        AppSettings.GlassMaterialPreset.HAZE_THIN,
        AppSettings.GlassMaterialPreset.HAZE_REGULAR,
        AppSettings.GlassMaterialPreset.HAZE_THICK,
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK -> resolveOfficialHazeMaterialStyle(
            preset = effectivePreset,
            componentRole = componentRole,
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.CUPERTINO_ULTRA_THIN -> CupertinoMaterials.ultraThin(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.CUPERTINO_THIN -> CupertinoMaterials.thin(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.CUPERTINO_REGULAR -> CupertinoMaterials.regular(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.CUPERTINO_THICK -> CupertinoMaterials.thick(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.FLUENT_THIN_ACRYLIC -> FluentMaterials.thinAcrylic()
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_BASE -> FluentMaterials.accentAcrylicBase()
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_DEFAULT -> FluentMaterials.accentAcrylicDefault()
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_BASE -> FluentMaterials.acrylicBase()
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_DEFAULT -> FluentMaterials.acrylicDefault()
        AppSettings.GlassMaterialPreset.FLUENT_MICA -> FluentMaterials.mica()
        AppSettings.GlassMaterialPreset.FLUENT_MICA_ALT -> FluentMaterials.micaAlt()
    }
    return remember(
        baseStyle,
        glassColors,
        effectivePreset,
        componentRole,
        dialogSurface,
        usesOfficialHazeMaterial,
    ) {
        if (usesOfficialHazeMaterial) {
            baseStyle
        } else {
            baseStyle.copy(
                backgroundColor = if (dialogSurface) Color.Transparent else glassColors.containerColor,
                blurRadius = glassColors.blurRadius,
                noiseFactor = glassColors.noiseFactor,
                fallbackColorEffect = HazeColorEffect.tint(
                    if (dialogSurface) Color.Transparent else glassColors.baseTintColor,
                ),
            )
        }
    }
}

private fun defaultGlassComponentRole(
    dialogSurface: Boolean,
    visualTreatment: GlassVisualTreatment,
): GlassComponentRole {
    return when {
        dialogSurface -> GlassComponentRole.Dialog
        visualTreatment == GlassVisualTreatment.TopBarPrototype -> GlassComponentRole.TopBar
        else -> GlassComponentRole.Surface
    }
}

@Composable
private fun resolveOfficialHazeMaterialStyle(
    preset: AppSettings.GlassMaterialPreset,
    componentRole: GlassComponentRole,
    containerColor: Color,
): HazeBlurStyle {
    return when (componentRole) {
        GlassComponentRole.TopBar,
        GlassComponentRole.BottomBar -> HazeMaterials.thin(
            containerColor = containerColor,
        )
        GlassComponentRole.Menu -> HazeMaterials.regular(
            containerColor = containerColor,
        )
        GlassComponentRole.Sheet -> HazeMaterials.thick(
            containerColor = containerColor,
        )
        GlassComponentRole.Dialog -> HazeMaterials.regular(
            containerColor = containerColor,
        )
        GlassComponentRole.Surface -> when (preset) {
            AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN -> HazeMaterials.ultraThin(
                containerColor = containerColor,
            )
            AppSettings.GlassMaterialPreset.HAZE_THIN -> HazeMaterials.thin(
                containerColor = containerColor,
            )
            AppSettings.GlassMaterialPreset.HAZE_REGULAR -> HazeMaterials.regular(
                containerColor = containerColor,
            )
            AppSettings.GlassMaterialPreset.HAZE_THICK -> HazeMaterials.thick(
                containerColor = containerColor,
            )
            AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK -> HazeMaterials.ultraThick(
                containerColor = containerColor,
            )
            else -> HazeMaterials.regular(
                containerColor = containerColor,
            )
        }
    }
}

@Composable
private fun rememberOfficialMaterialContainerColor(
    preset: AppSettings.GlassMaterialPreset,
    fallbackColor: Color,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    return remember(preset, fallbackColor, colorScheme, isDarkTheme) {
        val resolvedAlpha = fallbackColor.alpha.coerceIn(0f, 1f)
        when {
            preset.isCupertinoMaterial() -> {
                if (isDarkTheme) {
                    colorScheme.surfaceBright.copy(alpha = resolvedAlpha)
                } else {
                    colorScheme.surface.copy(alpha = resolvedAlpha)
                }
            }
            preset.isFluentMaterial() -> {
                if (isDarkTheme) {
                    lerp(colorScheme.surfaceContainerHigh, colorScheme.surfaceBright, 0.22f).copy(alpha = resolvedAlpha)
                } else {
                    lerp(colorScheme.surfaceContainerLow, colorScheme.surface, 0.35f).copy(alpha = resolvedAlpha)
                }
            }
            preset.isHazeMaterial() -> {
                if (isDarkTheme) {
                    colorScheme.surfaceContainer.copy(alpha = resolvedAlpha)
                } else {
                    colorScheme.surface.copy(alpha = resolvedAlpha)
                }
            }
            else -> fallbackColor
        }
    }
}

@Composable
fun rememberGlassHazeBackgroundColor(
    glassPrefs: GlassPrefs,
    glassColors: GlassSurfaceColors,
    hazeStyle: HazeBlurStyle,
    componentRole: GlassComponentRole = GlassComponentRole.Surface,
    dialogSurface: Boolean = false,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    val effectivePreset = remember(glassPrefs.materialPreset, componentRole) {
        glassPrefs.materialPreset.toFamily().resolvePreset(componentRole)
    }
    return remember(effectivePreset, glassColors, hazeStyle, dialogSurface, isDarkTheme, colorScheme) {
        if (dialogSurface) {
            return@remember Color.Transparent
        }
        when (effectivePreset) {
            AppSettings.GlassMaterialPreset.KOTOTORO,
            AppSettings.GlassMaterialPreset.CUSTOM -> glassColors.containerColor
            AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN,
            AppSettings.GlassMaterialPreset.HAZE_THIN,
            AppSettings.GlassMaterialPreset.HAZE_REGULAR,
            AppSettings.GlassMaterialPreset.HAZE_THICK,
            AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK,
            AppSettings.GlassMaterialPreset.CUPERTINO_ULTRA_THIN,
            AppSettings.GlassMaterialPreset.CUPERTINO_THIN,
            AppSettings.GlassMaterialPreset.CUPERTINO_REGULAR,
            AppSettings.GlassMaterialPreset.CUPERTINO_THICK,
            AppSettings.GlassMaterialPreset.FLUENT_THIN_ACRYLIC,
            AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_BASE,
            AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_DEFAULT,
            AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_BASE,
            AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_DEFAULT,
            AppSettings.GlassMaterialPreset.FLUENT_MICA,
            AppSettings.GlassMaterialPreset.FLUENT_MICA_ALT -> Color.Unspecified
        }
    }
}

private fun AppSettings.GlassMaterialPreset.usesOfficialHazeMaterial(): Boolean {
    return when (this) {
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN,
        AppSettings.GlassMaterialPreset.HAZE_THIN,
        AppSettings.GlassMaterialPreset.HAZE_REGULAR,
        AppSettings.GlassMaterialPreset.HAZE_THICK,
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK,
        AppSettings.GlassMaterialPreset.CUPERTINO_ULTRA_THIN,
        AppSettings.GlassMaterialPreset.CUPERTINO_THIN,
        AppSettings.GlassMaterialPreset.CUPERTINO_REGULAR,
        AppSettings.GlassMaterialPreset.CUPERTINO_THICK,
        AppSettings.GlassMaterialPreset.FLUENT_THIN_ACRYLIC,
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_BASE,
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_DEFAULT,
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_BASE,
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_DEFAULT,
        AppSettings.GlassMaterialPreset.FLUENT_MICA,
        AppSettings.GlassMaterialPreset.FLUENT_MICA_ALT -> true
        AppSettings.GlassMaterialPreset.KOTOTORO,
        AppSettings.GlassMaterialPreset.CUSTOM -> false
    }
}

private fun AppSettings.GlassMaterialPreset.isHazeMaterial(): Boolean {
    return when (this) {
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN,
        AppSettings.GlassMaterialPreset.HAZE_THIN,
        AppSettings.GlassMaterialPreset.HAZE_REGULAR,
        AppSettings.GlassMaterialPreset.HAZE_THICK,
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK -> true
        else -> false
    }
}

private fun AppSettings.GlassMaterialPreset.isCupertinoMaterial(): Boolean {
    return when (this) {
        AppSettings.GlassMaterialPreset.CUPERTINO_ULTRA_THIN,
        AppSettings.GlassMaterialPreset.CUPERTINO_THIN,
        AppSettings.GlassMaterialPreset.CUPERTINO_REGULAR,
        AppSettings.GlassMaterialPreset.CUPERTINO_THICK -> true
        else -> false
    }
}

private fun AppSettings.GlassMaterialPreset.isFluentMaterial(): Boolean {
    return when (this) {
        AppSettings.GlassMaterialPreset.FLUENT_THIN_ACRYLIC,
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_BASE,
        AppSettings.GlassMaterialPreset.FLUENT_ACCENT_ACRYLIC_DEFAULT,
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_BASE,
        AppSettings.GlassMaterialPreset.FLUENT_ACRYLIC_DEFAULT,
        AppSettings.GlassMaterialPreset.FLUENT_MICA,
        AppSettings.GlassMaterialPreset.FLUENT_MICA_ALT -> true
        else -> false
    }
}
