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
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.AppFontPreset
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.getThemeColor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

import org.skepsun.kototoro.core.prefs.BackgroundStyle

val LocalMaterialExpressiveComponentsEnabled = staticCompositionLocalOf { false }
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
    val expressiveComponents by settings.observeAsState(AppSettings.KEY_MATERIAL_EXPRESSIVE_COMPONENTS) {
        isMaterialExpressiveComponentsEnabled
    }
    val appFontPreset by settings.observeAsState(AppSettings.KEY_APP_FONT_PRESET) {
        appFontPreset
    }
    val expressiveAppFontPreset by settings.observeAsState(AppSettings.KEY_EXPRESSIVE_APP_FONT_PRESET) {
        expressiveAppFontPreset
    }
    val backgroundStyle by settings.observeAsState(AppSettings.KEY_BACKGROUND_STYLE) {
        backgroundStyle
    }
    val colorScheme = remember(context, darkTheme, dynamicColor, backgroundStyle) {
        context.resolveComposeColorScheme(darkTheme, backgroundStyle)
    }
    
    val radius = when {
        cornerRadius != -1 -> cornerRadius.dp
        expressiveComponents -> 18.dp
        else -> 12.dp
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(if (expressiveComponents) 12.dp else radius.coerceAtMost(14.dp)),
        small = RoundedCornerShape(if (expressiveComponents) 18.dp else radius.coerceAtMost(18.dp)),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(radius * 1.5f),
        extraLarge = RoundedCornerShape(radius * 2f),
    )
    val activeFontPreset = if (expressiveComponents) expressiveAppFontPreset else appFontPreset
    val googleFontProvider = remember {
        GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )
    }
    val fontFamily = remember(activeFontPreset, googleFontProvider) {
        activeFontPreset.toFontFamily(provider = googleFontProvider)
    }
    val typography = remember(expressiveComponents, fontFamily) {
        kototoroTypography(
            expressive = expressiveComponents,
            defaultFontFamily = fontFamily,
        )
    }

    CompositionLocalProvider(
        LocalMaterialExpressiveComponentsEnabled provides expressiveComponents,
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

private fun AppFontPreset.toFontFamily(provider: GoogleFont.Provider): FontFamily? {
    val fontName = when (this) {
        AppFontPreset.SYSTEM -> return null
        AppFontPreset.ROBOTO -> "Roboto"
        AppFontPreset.ROBOTO_FLEX -> "Roboto Flex"
        AppFontPreset.GOOGLE_SANS -> "Google Sans"
    }
    return FontFamily(Font(googleFont = GoogleFont(fontName), fontProvider = provider))
}

private fun kototoroTypography(
    expressive: Boolean,
    defaultFontFamily: FontFamily?,
): Typography {
    val base = Typography()
    fun androidx.compose.ui.text.TextStyle.withDefaultFont(): androidx.compose.ui.text.TextStyle {
        return if (defaultFontFamily == null) this else copy(fontFamily = defaultFontFamily)
    }
    if (!expressive) {
        return base.copy(
            displayLarge = base.displayLarge.copy(letterSpacing = 0.sp).withDefaultFont(),
            displayMedium = base.displayMedium.copy(letterSpacing = 0.sp).withDefaultFont(),
            displaySmall = base.displaySmall.copy(letterSpacing = 0.sp).withDefaultFont(),
            headlineLarge = base.headlineLarge.copy(letterSpacing = 0.sp).withDefaultFont(),
            headlineMedium = base.headlineMedium.copy(letterSpacing = 0.sp).withDefaultFont(),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            bodyLarge = base.bodyLarge.copy(letterSpacing = 0.sp).withDefaultFont(),
            bodyMedium = base.bodyMedium.copy(letterSpacing = 0.sp).withDefaultFont(),
            bodySmall = base.bodySmall.copy(letterSpacing = 0.sp).withDefaultFont(),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
            labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
        )
    }
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp).withDefaultFont(),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).withDefaultFont(),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).withDefaultFont(),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).withDefaultFont(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = 0.sp).withDefaultFont(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp).withDefaultFont(),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp).withDefaultFont(),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.sp).withDefaultFont(),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp).withDefaultFont(),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp).withDefaultFont(),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp).withDefaultFont(),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp).withDefaultFont(),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp).withDefaultFont(),
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

    val common = ThemeColorSnapshot(
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

    return if (darkTheme) {
        val baseBg = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(Color(0xFF0C0D0F), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color.Black
            BackgroundStyle.DEFAULT -> Color.Black
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color.Black
        }
        val baseSurface = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(Color(0xFF111316), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF141414)
            BackgroundStyle.DEFAULT -> Color.Black
            BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> Color(0xFF0C0C0C)
        }
        val liftedSurfaceContainerLowest = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerLowest.liftForDarkContrast(0.10f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF121212)
            else -> common.surfaceContainerLowest.liftForDarkContrast(0.10f)
        }
        val liftedSurfaceContainerLow = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerLow.liftForDarkContrast(0.14f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF161616)
            else -> common.surfaceContainerLow.liftForDarkContrast(0.14f)
        }
        val liftedSurfaceContainer = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainer.liftForDarkContrast(0.16f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF1E1E1E)
            else -> common.surfaceContainer.liftForDarkContrast(0.16f)
        }
        val liftedSurfaceContainerHigh = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerHigh.liftForDarkContrast(0.18f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF242424)
            else -> common.surfaceContainerHigh.liftForDarkContrast(0.18f)
        }
        val liftedSurfaceContainerHighest = when (backgroundStyle) {
            BackgroundStyle.SYSTEM_DYNAMIC_TINT -> lerp(common.surfaceContainerHighest.liftForDarkContrast(0.20f), common.primary, 0.08f)
            BackgroundStyle.ELEVATED_CONTAINERS -> Color(0xFF2C2C2C)
            else -> common.surfaceContainerHighest.liftForDarkContrast(0.20f)
        }

        darkColorScheme(
            primary = common.primary,
            onPrimary = common.onPrimary,
            primaryContainer = common.primaryContainer,
            onPrimaryContainer = common.onPrimaryContainer,
            inversePrimary = common.inversePrimary,
            secondary = common.secondary,
            onSecondary = common.onSecondary,
            secondaryContainer = common.secondaryContainer,
            onSecondaryContainer = common.onSecondaryContainer,
            tertiary = common.tertiary,
            onTertiary = common.onTertiary,
            tertiaryContainer = common.tertiaryContainer,
            onTertiaryContainer = common.onTertiaryContainer,
            background = baseBg,
            onBackground = common.onBackground,
            surface = baseSurface,
            onSurface = common.onSurface,
            surfaceVariant = common.surfaceVariant,
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
            scrim = Color.Black,
            surfaceBright = common.surfaceBright,
            surfaceDim = common.surfaceDim,
            surfaceContainerLowest = liftedSurfaceContainerLowest,
            surfaceContainerLow = liftedSurfaceContainerLow,
            surfaceContainer = liftedSurfaceContainer,
            surfaceContainerHigh = liftedSurfaceContainerHigh,
            surfaceContainerHighest = liftedSurfaceContainerHighest,
        )
    } else {
        lightColorScheme(
            primary = common.primary,
            onPrimary = common.onPrimary,
            primaryContainer = common.primaryContainer,
            onPrimaryContainer = common.onPrimaryContainer,
            inversePrimary = common.inversePrimary,
            secondary = common.secondary,
            onSecondary = common.onSecondary,
            secondaryContainer = common.secondaryContainer,
            onSecondaryContainer = common.onSecondaryContainer,
            tertiary = common.tertiary,
            onTertiary = common.onTertiary,
            tertiaryContainer = common.tertiaryContainer,
            onTertiaryContainer = common.onTertiaryContainer,
            background = common.background,
            onBackground = common.onBackground,
            surface = common.surface,
            onSurface = common.onSurface,
            surfaceVariant = common.surfaceVariant,
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
            scrim = Color.Black,
            surfaceBright = common.surfaceBright,
            surfaceDim = common.surfaceDim,
            surfaceContainerLowest = common.surfaceContainerLowest,
            surfaceContainerLow = common.surfaceContainerLow,
            surfaceContainer = common.surfaceContainer,
            surfaceContainerHigh = common.surfaceContainerHigh,
            surfaceContainerHighest = common.surfaceContainerHighest,
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
