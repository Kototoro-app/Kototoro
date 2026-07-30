package org.skepsun.kototoro.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.AppFontPreset
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.normalized

val LocalMaterialExpressiveComponentsEnabled = staticCompositionLocalOf { false }
val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.MATERIAL_3_EXPRESSIVE }
val LocalBackgroundStyle = staticCompositionLocalOf { BackgroundStyle.DEFAULT }

@Composable
fun KototoroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    cornerRadius: Int = -1,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember(appContext) { AppSettings(appContext) }
    val interfaceStyle by settings.observeAsState(AppSettings.KEY_INTERFACE_STYLE) {
        interfaceStyle
    }
    val effectiveInterfaceStyle = interfaceStyle.normalized()
    val expressiveComponents = effectiveInterfaceStyle == InterfaceStyle.MATERIAL_3_EXPRESSIVE
    val styleTokens = effectiveInterfaceStyle.tokens()
	val stylePolicy = remember(effectiveInterfaceStyle) { InterfaceStylePolicy.from(effectiveInterfaceStyle) }
    val appFontPreset by settings.observeAsState(AppSettings.KEY_APP_FONT_PRESET) {
        appFontPreset
    }
    val expressiveAppFontPreset by settings.observeAsState(AppSettings.KEY_EXPRESSIVE_APP_FONT_PRESET) {
        expressiveAppFontPreset
    }
    val selectedBackgroundStyle by settings.observeAsState(AppSettings.KEY_BACKGROUND_STYLE) {
        backgroundStyle
    }
    val backgroundStyle = selectedBackgroundStyle.takeIf {
        effectiveInterfaceStyle == InterfaceStyle.IOS ||
            it != BackgroundStyle.DYNAMIC_TONAL_GLASS
    } ?: BackgroundStyle.DEFAULT
    val selectedColorScheme by settings.observeAsState(AppSettings.KEY_COLOR_THEME) {
        colorScheme
    }
    val colorScheme = remember(context, darkTheme, dynamicColor, backgroundStyle, effectiveInterfaceStyle, selectedColorScheme) {
        context.resolveComposeColorScheme(darkTheme, backgroundStyle)
    }
    
    val radius = when {
        cornerRadius != -1 -> cornerRadius.dp
        else -> styleTokens.groupCornerRadius
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(styleTokens.controlCornerRadius.coerceAtMost(14.dp)),
        small = RoundedCornerShape(styleTokens.controlCornerRadius),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(styleTokens.groupCornerRadius),
        extraLarge = RoundedCornerShape(styleTokens.groupCornerRadius),
    )
    val activeFontPreset = if (effectiveInterfaceStyle == InterfaceStyle.IOS || expressiveComponents) {
        expressiveAppFontPreset
    } else {
        appFontPreset
    }
    val googleFontProvider = remember {
        GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )
    }
    val onlineFontLoader = remember(appContext) {
        EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(appContext).onlineFontLoader
    }
    val fontFamily by produceState<FontFamily?>(initialValue = null, activeFontPreset) {
        value = activeFontPreset.toFontFamily(
            provider = googleFontProvider,
            onlineFontLoader = onlineFontLoader,
        )
    }
    val typography = remember(effectiveInterfaceStyle, fontFamily) {
        kototoroTypography(
            isExpressiveStyle = expressiveComponents,
            defaultFontFamily = fontFamily,
        )
    }

    CompositionLocalProvider(
        LocalMaterialExpressiveComponentsEnabled provides expressiveComponents,
        LocalInterfaceStyle provides effectiveInterfaceStyle,
        LocalInterfaceStyleTokens provides styleTokens,
		LocalInterfaceStylePolicy provides stylePolicy,
        LocalBackgroundStyle provides backgroundStyle,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}

private suspend fun AppFontPreset.toFontFamily(
    provider: GoogleFont.Provider,
    onlineFontLoader: OnlineFontLoader,
): FontFamily? {
    val fontName = when (this) {
        AppFontPreset.SARASA_GOTHIC -> return onlineFontLoader.load(OnlineFontPreset.SARASA_GOTHIC)
        AppFontPreset.LXGW_WENKAI -> return onlineFontLoader.load(OnlineFontPreset.LXGW_WENKAI)
        AppFontPreset.NOTO_SANS_CJK_SC -> return onlineFontLoader.load(OnlineFontPreset.NOTO_SANS_CJK_SC)
        AppFontPreset.SOURCE_HAN_SERIF_SC -> return onlineFontLoader.load(OnlineFontPreset.SOURCE_HAN_SERIF_SC)
        AppFontPreset.SYSTEM -> return null
        AppFontPreset.ROBOTO -> "Roboto"
        AppFontPreset.ROBOTO_FLEX -> "Roboto Flex"
        AppFontPreset.GOOGLE_SANS -> "Google Sans"
        AppFontPreset.NOTO_SANS -> "Noto Sans"
        AppFontPreset.INTER -> "Inter"
    }
    return FontFamily(Font(googleFont = GoogleFont(fontName), fontProvider = provider))
}

internal fun kototoroTypography(
	isExpressiveStyle: Boolean,
	defaultFontFamily: FontFamily?,
): Typography {
	val base = Typography()
	val destinationTitleWeight = if (isExpressiveStyle) FontWeight.SemiBold else FontWeight.Bold
	fun androidx.compose.ui.text.TextStyle.withDefaultFont(): androidx.compose.ui.text.TextStyle {
		return if (defaultFontFamily == null) this else copy(fontFamily = defaultFontFamily)
	}
	return base.copy(
		displayLarge = base.displayLarge.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
		displayMedium = base.displayMedium.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
		displaySmall = base.displaySmall.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
		headlineLarge = base.headlineLarge.copy(
			fontWeight = destinationTitleWeight,
			fontSize = 32.sp,
			lineHeight = 40.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		headlineMedium = base.headlineMedium.copy(
			fontWeight = destinationTitleWeight,
			fontSize = 28.sp,
			lineHeight = 36.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		headlineSmall = base.headlineSmall.copy(
			fontWeight = FontWeight.SemiBold,
			fontSize = 24.sp,
			lineHeight = 32.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		titleLarge = base.titleLarge.copy(
			fontWeight = FontWeight.SemiBold,
			fontSize = 22.sp,
			lineHeight = 28.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		titleMedium = base.titleMedium.copy(
			fontWeight = FontWeight.SemiBold,
			fontSize = 16.sp,
			lineHeight = 24.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		titleSmall = base.titleSmall.copy(
			fontWeight = FontWeight.Medium,
			fontSize = 14.sp,
			lineHeight = 20.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		bodyLarge = base.bodyLarge.copy(
			fontWeight = FontWeight.Normal,
			fontSize = 16.sp,
			lineHeight = 24.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		bodyMedium = base.bodyMedium.copy(
			fontWeight = FontWeight.Normal,
			fontSize = 14.sp,
			lineHeight = 20.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		bodySmall = base.bodySmall.copy(
			fontWeight = FontWeight.Normal,
			fontSize = 12.sp,
			lineHeight = 16.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		labelLarge = base.labelLarge.copy(
			fontWeight = FontWeight.Medium,
			fontSize = 14.sp,
			lineHeight = 20.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		labelMedium = base.labelMedium.copy(
			fontWeight = FontWeight.Medium,
			fontSize = 12.sp,
			lineHeight = 16.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
		labelSmall = base.labelSmall.copy(
			fontWeight = FontWeight.Medium,
			fontSize = 11.sp,
			lineHeight = 16.sp,
			letterSpacing = 0.sp,
		).withDefaultFont(),
	)
}

private fun android.content.Context.resolveComposeColorScheme(
    darkTheme: Boolean,
    backgroundStyle: BackgroundStyle,
): ColorScheme {
    val background = themeColor(android.R.attr.colorBackground)
    val surface = themeColorByName("colorSurface", background)
    val primary = themeColorByName("colorPrimary")
    val surfaceVariant = themeColorByName("colorSurfaceVariant", surface)
    val surfaceContainer = themeColorByName("colorSurfaceContainer", surface)
    val surfaceContainerHigh = themeColorByName("colorSurfaceContainerHigh", surfaceContainer)

    val baseCommon = ThemeColorSnapshot(
        primary = primary,
        onPrimary = themeColorByName("colorOnPrimary"),
        primaryContainer = themeColorByName("colorPrimaryContainer", primary),
        onPrimaryContainer = themeColorByName("colorOnPrimaryContainer"),
        inversePrimary = themeColorByName("colorPrimaryInverse", primary),
        secondary = themeColorByName("colorSecondary"),
        onSecondary = themeColorByName("colorOnSecondary"),
        secondaryContainer = themeColorByName("colorSecondaryContainer"),
        onSecondaryContainer = themeColorByName("colorOnSecondaryContainer"),
        tertiary = themeColorByName("colorTertiary"),
        onTertiary = themeColorByName("colorOnTertiary"),
        tertiaryContainer = themeColorByName("colorTertiaryContainer"),
        onTertiaryContainer = themeColorByName("colorOnTertiaryContainer"),
        background = background,
        onBackground = themeColorByName("colorOnBackground"),
        surface = surface,
        onSurface = themeColorByName("colorOnSurface"),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = themeColorByName("colorOnSurfaceVariant"),
        inverseSurface = themeColorByName("colorSurfaceInverse", background),
        inverseOnSurface = themeColorByName("colorOnSurfaceInverse"),
        error = themeColorByName("colorError"),
        onError = themeColorByName("colorOnError"),
        errorContainer = themeColorByName("colorErrorContainer"),
        onErrorContainer = themeColorByName("colorOnErrorContainer"),
        outline = themeColorByName("colorOutline"),
        outlineVariant = themeColorByName("colorOutlineVariant"),
        surfaceBright = themeColorByName("colorSurfaceBright", surface),
        surfaceDim = themeColorByName("colorSurfaceDim", surface),
        surfaceContainerLowest = themeColorByName("colorSurfaceContainerLowest", surface),
        surfaceContainerLow = themeColorByName("colorSurfaceContainerLow", surface),
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = themeColorByName("colorSurfaceContainerHighest", surfaceContainerHigh),
    )
    val common = baseCommon

    val isArtworkBlur = backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
    return if (darkTheme) {
        val onBackground = if (isArtworkBlur) Color.White else common.onBackground
        val onSurface = if (isArtworkBlur) Color.White else common.onSurface
        val onSurfaceVariant = if (isArtworkBlur) Color(0xFFE4E1E9) else common.onSurfaceVariant
        val baseBg = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(Color(0xFF0C0D0F), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color.Black
            BackgroundStyle.DEFAULT -> Color.Black
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color.Transparent
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF101114)
        }
        val baseSurface = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(Color(0xFF111316), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF141414)
            BackgroundStyle.DEFAULT -> Color.Black
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF0A0A0E).copy(alpha = 0.45f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF17181D)
        }
        val liftedSurfaceContainerLowest = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerLowest.liftForDarkContrast(0.10f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF121212)
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF121216).copy(alpha = 0.40f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF141519)
            else -> common.surfaceContainerLowest.liftForDarkContrast(0.10f)
        }
        val liftedSurfaceContainerLow = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerLow.liftForDarkContrast(0.14f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF161616)
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF16161A)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF1B1C21)
            else -> common.surfaceContainerLow.liftForDarkContrast(0.14f)
        }
        val liftedSurfaceContainer = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainer.liftForDarkContrast(0.16f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF1E1E1E)
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF1A1A20).copy(alpha = 0.52f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF222329)
            else -> common.surfaceContainer.liftForDarkContrast(0.16f)
        }
        val liftedSurfaceContainerHigh = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerHigh.liftForDarkContrast(0.18f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF242424)
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF202026).copy(alpha = 0.86f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF2B2C33)
            else -> common.surfaceContainerHigh.liftForDarkContrast(0.18f)
        }
        val liftedSurfaceContainerHighest = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerHighest.liftForDarkContrast(0.20f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF2C2C2C)
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF26262E).copy(alpha = 0.90f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFF34353D)
            else -> common.surfaceContainerHighest.liftForDarkContrast(0.20f)
        }
        val liftedSurfaceVariant = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            common.surfaceVariant.copy(alpha = 0.52f)
        } else if (backgroundStyle == BackgroundStyle.DYNAMIC_TONAL_GLASS) {
            common.surfaceVariant.copy(alpha = 0.72f)
        } else {
            common.surfaceVariant
        }
        val liftedSurfaceBright = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            common.surfaceBright.copy(alpha = 0.60f)
        } else {
            common.surfaceBright
        }
        val liftedSurfaceDim = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            common.surfaceDim.copy(alpha = 0.40f)
        } else {
            common.surfaceDim
        }
        val liftedSecondaryContainer = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            common.secondaryContainer.copy(alpha = 0.55f)
        } else if (backgroundStyle == BackgroundStyle.DYNAMIC_TONAL_GLASS) {
            lerp(common.secondaryContainer, common.primaryContainer, 0.35f)
        } else {
            common.secondaryContainer
        }
        val resolvedScrim = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            Color(0xCC000000)
        } else {
            Color.Black
        }

        darkColorScheme(
            primary = common.primary,
            onPrimary = common.onPrimary,
            primaryContainer = common.primaryContainer,
            onPrimaryContainer = common.onPrimaryContainer,
            inversePrimary = common.inversePrimary,
            secondary = common.secondary,
            onSecondary = common.onSecondary,
            secondaryContainer = liftedSecondaryContainer,
            onSecondaryContainer = common.onSecondaryContainer,
            tertiary = common.tertiary,
            onTertiary = common.onTertiary,
            tertiaryContainer = common.tertiaryContainer,
            onTertiaryContainer = common.onTertiaryContainer,
            background = baseBg,
            onBackground = onBackground,
            surface = baseSurface,
            onSurface = onSurface,
            surfaceVariant = liftedSurfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = common.primary,
            inverseSurface = common.inverseSurface,
            inverseOnSurface = common.inverseOnSurface,
            error = common.error,
            onError = common.onError,
            errorContainer = common.errorContainer,
            onErrorContainer = common.onErrorContainer,
            outline = common.outline,
            outlineVariant = common.outlineVariant,
            scrim = resolvedScrim,
            surfaceBright = liftedSurfaceBright,
            surfaceDim = liftedSurfaceDim,
            surfaceContainerLowest = liftedSurfaceContainerLowest,
            surfaceContainerLow = liftedSurfaceContainerLow,
            surfaceContainer = liftedSurfaceContainer,
            surfaceContainerHigh = liftedSurfaceContainerHigh,
            surfaceContainerHighest = liftedSurfaceContainerHighest,
        )
    } else {
        val lightBg = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color.Transparent
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFF6F6F8)
            else -> common.background
        }
        val lightSurface = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surface.copy(alpha = 0.55f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFFFFFFF)
            else -> common.surface
        }
        val lightSurfaceContainerLowest = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceContainerLowest.copy(alpha = 0.45f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFF9F9FB)
            else -> common.surfaceContainerLowest
        }
        val lightSurfaceContainerLow = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceContainerLow.copy(alpha = 1f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFEFEFF3)
            else -> common.surfaceContainerLow
        }
        val lightSurfaceContainer = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceContainer.copy(alpha = 0.56f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFE9E9EE)
            else -> common.surfaceContainer
        }
        val lightSurfaceContainerHigh = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceContainerHigh.copy(alpha = 0.86f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFE2E2E8)
            else -> common.surfaceContainerHigh
        }
        val lightSurfaceContainerHighest = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceContainerHighest.copy(alpha = 0.90f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> Color(0xFFDADAE1)
            else -> common.surfaceContainerHighest
        }
        val lightSurfaceVariant = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.surfaceVariant.copy(alpha = 0.55f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> common.surfaceVariant.copy(alpha = 0.72f)
            else -> common.surfaceVariant
        }
        val lightSecondaryContainer = when (backgroundStyle) {
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> common.secondaryContainer.copy(alpha = 0.60f)
            BackgroundStyle.DYNAMIC_TONAL_GLASS -> lerp(common.secondaryContainer, common.primaryContainer, 0.35f)
            else -> common.secondaryContainer
        }
        val resolvedScrim = if (backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
            Color(0xCC000000)
        } else {
            Color.Black
        }

        lightColorScheme(
            primary = common.primary,
            onPrimary = common.onPrimary,
            primaryContainer = common.primaryContainer,
            onPrimaryContainer = common.onPrimaryContainer,
            inversePrimary = common.inversePrimary,
            secondary = common.secondary,
            onSecondary = common.onSecondary,
            secondaryContainer = lightSecondaryContainer,
            onSecondaryContainer = common.onSecondaryContainer,
            tertiary = common.tertiary,
            onTertiary = common.onTertiary,
            tertiaryContainer = common.tertiaryContainer,
            onTertiaryContainer = common.onTertiaryContainer,
            background = lightBg,
            onBackground = common.onBackground,
            surface = lightSurface,
            onSurface = common.onSurface,
            surfaceVariant = lightSurfaceVariant,
            onSurfaceVariant = common.onSurfaceVariant,
            surfaceTint = common.primary,
            inverseSurface = common.inverseSurface,
            inverseOnSurface = common.inverseOnSurface,
            error = common.error,
            onError = common.onError,
            errorContainer = common.errorContainer,
            onErrorContainer = common.onErrorContainer,
            outline = common.outline,
            outlineVariant = common.outlineVariant,
            scrim = resolvedScrim,
            surfaceBright = common.surfaceBright,
            surfaceDim = common.surfaceDim,
            surfaceContainerLowest = lightSurfaceContainerLowest,
            surfaceContainerLow = lightSurfaceContainerLow,
            surfaceContainer = lightSurfaceContainer,
            surfaceContainerHigh = lightSurfaceContainerHigh,
            surfaceContainerHighest = lightSurfaceContainerHighest,
        )
    }
}

private fun android.content.Context.themeColorByName(
    attrName: String,
    fallback: Color = Color.Unspecified,
): Color {
    val attrId = resources.getIdentifier(attrName, "attr", packageName)
        .takeIf { it != 0 }
        ?: resources.getIdentifier(attrName, "attr", "com.google.android.material")

    return if (attrId != 0) {
        themeColor(attrId, fallback)
    } else if (fallback.isSpecified) {
        fallback
    } else {
        Color.Transparent
    }
}

private fun android.content.Context.themeColor(
    attr: Int,
    fallback: Color = Color.Unspecified,
): Color {
    val fallbackArgb = if (fallback.isSpecified) fallback.toArgb() else android.graphics.Color.TRANSPARENT
    return Color(getThemeColor(attr, fallbackArgb))
}

private fun Color.liftForDarkContrast(amount: Float): Color {
    return lerp(this, Color.White, amount.coerceIn(0f, 1f))
}

private data class ThemeColorSnapshot(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)
