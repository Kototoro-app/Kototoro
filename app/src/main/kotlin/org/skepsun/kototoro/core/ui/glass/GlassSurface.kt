package org.skepsun.kototoro.core.ui.glass

import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.FluentMaterials
import dev.chrisbanes.haze.materials.HazeMaterials
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import dev.chrisbanes.haze.HazeDefaults as HazeBlurDefaults
import dev.chrisbanes.haze.hazeChild

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
        AppSettings.KEY_GLASS_MATERIAL_PRESET,
        AppSettings.KEY_HAZE_OPACITY,
        AppSettings.KEY_GLASS_BLUR_STRENGTH,
        AppSettings.KEY_GLASS_NOISE_STRENGTH,
        AppSettings.KEY_GLASS_IMMERSIVE_STRENGTH,
    ) {
        GlassPrefs(
            isGlassEffectEnabled = isGlassEffectEnabled,
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
)

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
    debugLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glassPrefs = rememberGlassPrefsOrFallback()
    val hazeState = LocalHazeState.current
    val colorScheme = MaterialTheme.colorScheme
    val usesOfficialHazeMaterial = glassPrefs.materialPreset.usesOfficialHazeMaterial()
    val effectiveStyle = if (dialogSurface) {
        style.copy(
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        )
    } else {
        style
    }
    val glassColors = rememberGlassSurfaceColors(style = effectiveStyle, glassPrefs = glassPrefs)

    val useRuntimeHaze = glassPrefs.isGlassEffectEnabled && allowRuntimeHaze && supportsRuntimeHaze()
    val hazeStyle = rememberGlassHazeStyle(
        glassPrefs = glassPrefs,
        glassColors = glassColors,
        dialogSurface = dialogSurface,
    )
    val hazeBackgroundColor = rememberGlassHazeBackgroundColor(
        glassPrefs = glassPrefs,
        glassColors = glassColors,
        hazeStyle = hazeStyle,
        dialogSurface = dialogSurface,
    )
    val surfaceColor = when {
        dialogSurface -> glassColors.containerColor
        useRuntimeHaze && !dialogSurface -> Color.Transparent
        !useRuntimeHaze && usesOfficialHazeMaterial -> hazeStyle.backgroundColor.takeOrElse { glassColors.containerColor }
        else -> glassColors.containerColor
    }
    var lastDebugBounds by remember(debugLabel) { mutableStateOf<String?>(null) }
    var lastDebugConfig by remember(debugLabel) { mutableStateOf<String?>(null) }
    if (BuildConfig.DEBUG && debugLabel != null) {
        val debugConfig =
            "$debugLabel config useRuntimeHaze=$useRuntimeHaze allowRuntimeHaze=$allowRuntimeHaze " +
                "dialogSurface=$dialogSurface material=${glassPrefs.materialPreset} " +
                "opacity=${glassPrefs.hazeOpacityPercent} blurPref=${glassPrefs.blurStrengthPercent} " +
                "noisePref=${glassPrefs.noiseStrengthPercent} " +
                "tonalElevation=${effectiveStyle.tonalElevation} shadowElevation=${effectiveStyle.shadowElevation} " +
                "surfaceAlpha=${surfaceColor.alpha} hazeBgAlpha=${hazeBackgroundColor.alpha} " +
                "blurRadius=${glassColors.blurRadius} noise=${glassColors.noiseFactor} " +
                "styleBgAlpha=${hazeStyle.backgroundColor.alpha} fallbackTint=${hazeStyle.fallbackTint}"
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
                    .hazeChild(hazeState, hazeStyle) {
                        backgroundColor = hazeBackgroundColor
                        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
                        clipToAreasBounds = true
                        expandLayerBounds = !dialogSurface
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
            Box(content = content)
        }
    }
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
    style: GlassStyle = GlassDefaults.prominentStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = style,
        shape = RoundedCornerShape(30.dp),
        content = content,
    )
}

@Composable
fun GlassBottomBarContainer(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.prominentStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = style,
        shape = RoundedCornerShape(32.dp),
        content = content,
    )
}

private fun computeGlassColors(
    hazeOpacityPercent: Int,
    blurStrengthPercent: Int,
    noiseStrengthPercent: Int,
    isDarkTheme: Boolean,
    style: GlassStyle,
    colorScheme: androidx.compose.material3.ColorScheme,
): GlassSurfaceColors {
    val preferenceAlpha = (hazeOpacityPercent.coerceIn(0, 100)) / 100f
    val effectiveContainerAlpha = (preferenceAlpha * style.containerAlpha).coerceIn(0f, 1f)
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

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun rememberGlassHazeStyle(
    glassPrefs: GlassPrefs,
    glassColors: GlassSurfaceColors,
    dialogSurface: Boolean = false,
): HazeStyle {
    val usesOfficialHazeMaterial = glassPrefs.materialPreset.usesOfficialHazeMaterial()
    val officialContainerColor = rememberOfficialMaterialContainerColor(
        preset = glassPrefs.materialPreset,
        fallbackColor = glassColors.containerColor,
    )
    val baseStyle = when (glassPrefs.materialPreset) {
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
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THIN -> HazeMaterials.ultraThin(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.HAZE_THIN -> HazeMaterials.thin(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.HAZE_REGULAR -> HazeMaterials.regular(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.HAZE_THICK -> HazeMaterials.thick(
            containerColor = officialContainerColor,
        )
        AppSettings.GlassMaterialPreset.HAZE_ULTRA_THICK -> HazeMaterials.ultraThick(
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
    return remember(baseStyle, glassColors, glassPrefs.materialPreset, dialogSurface, usesOfficialHazeMaterial) {
        if (usesOfficialHazeMaterial) {
            baseStyle
        } else {
            baseStyle.copy(
                backgroundColor = if (dialogSurface) Color.Transparent else glassColors.containerColor,
                blurRadius = glassColors.blurRadius,
                noiseFactor = glassColors.noiseFactor,
                fallbackTint = HazeTint(if (dialogSurface) Color.Transparent else glassColors.baseTintColor),
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
        when {
            preset.isCupertinoMaterial() -> {
                if (isDarkTheme) {
                    colorScheme.surfaceBright.copy(alpha = 0.92f)
                } else {
                    colorScheme.surface.copy(alpha = 0.84f)
                }
            }
            preset.isFluentMaterial() -> {
                if (isDarkTheme) {
                    lerp(colorScheme.surfaceContainerHigh, colorScheme.surfaceBright, 0.22f).copy(alpha = 0.90f)
                } else {
                    lerp(colorScheme.surfaceContainerLow, colorScheme.surface, 0.35f).copy(alpha = 0.86f)
                }
            }
            preset.isHazeMaterial() -> {
                if (isDarkTheme) {
                    colorScheme.surfaceContainer.copy(alpha = 0.88f)
                } else {
                    colorScheme.surface.copy(alpha = 0.82f)
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
    hazeStyle: HazeStyle,
    dialogSurface: Boolean = false,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f
    return remember(glassPrefs.materialPreset, glassColors, hazeStyle, dialogSurface, isDarkTheme, colorScheme) {
        if (dialogSurface) {
            return@remember Color.Transparent
        }
        when (glassPrefs.materialPreset) {
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
