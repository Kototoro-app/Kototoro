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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.ext.getThemeColor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val LocalMaterialExpressiveComponentsEnabled = staticCompositionLocalOf { false }

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
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        context.resolveComposeColorScheme(darkTheme)
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
    val typography = remember(expressiveComponents) {
        if (expressiveComponents) expressiveTypography() else Typography()
    }

    CompositionLocalProvider(LocalMaterialExpressiveComponentsEnabled provides expressiveComponents) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}

private fun expressiveTypography(): Typography {
    val base = Typography()
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(letterSpacing = 0.sp),
    )
}

private fun android.content.Context.resolveComposeColorScheme(
    darkTheme: Boolean,
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
        val liftedSurfaceContainerLowest = common.surfaceContainerLowest.liftForDarkContrast(0.10f)
        val liftedSurfaceContainerLow = common.surfaceContainerLow.liftForDarkContrast(0.14f)
        val liftedSurfaceContainer = common.surfaceContainer.liftForDarkContrast(0.16f)
        val liftedSurfaceContainerHigh = common.surfaceContainerHigh.liftForDarkContrast(0.18f)
        val liftedSurfaceContainerHighest = common.surfaceContainerHighest.liftForDarkContrast(0.20f)

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
