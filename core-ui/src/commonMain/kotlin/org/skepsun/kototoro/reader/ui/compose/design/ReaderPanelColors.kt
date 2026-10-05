package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.skepsun.kototoro.core.prefs.ReaderBackground

/**
 * Colours of the reader options panel, derived from what is being read (the novel theme or the
 * manga reader background) rather than from the app theme, so the panel belongs to the page.
 */
@Immutable
data class ReaderPanelColors(
    val container: Color,
    val card: Color,
    val content: Color,
    val contentSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val selectedContainer: Color,
    val divider: Color,
    val isDark: Boolean,
)

val LocalReaderPanelColors = staticCompositionLocalOf<ReaderPanelColors?> { null }

/** The panel colours, or colours taken from the Material theme outside a panel. */
@Composable
fun currentReaderPanelColors(): ReaderPanelColors {
    LocalReaderPanelColors.current?.let { return it }
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { materialReaderPanelColors(scheme) }
}

fun materialReaderPanelColors(scheme: ColorScheme) = ReaderPanelColors(
    container = scheme.surfaceContainerHigh,
    card = scheme.surfaceContainerLow,
    content = scheme.onSurface,
    contentSecondary = scheme.onSurfaceVariant,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    selectedContainer = scheme.primaryContainer,
    divider = scheme.outlineVariant.copy(alpha = 0.55f),
    isDark = scheme.surface.luminance() < 0.5f,
)

fun readerPanelColors(
    base: Color,
    content: Color,
    contentSecondary: Color,
    accent: Color,
    isDark: Boolean,
): ReaderPanelColors {
    val card = if (isDark) lerp(base, Color.White, 0.07f) else lerp(base, Color.Black, 0.045f)
    return ReaderPanelColors(
        container = base,
        card = card,
        content = content,
        contentSecondary = contentSecondary,
        accent = accent,
        onAccent = if (accent.luminance() > 0.4f) Color.Black else Color.White,
        selectedContainer = lerp(card, accent, if (isDark) 0.30f else 0.18f),
        divider = content.copy(alpha = 0.12f),
        isDark = isDark,
    )
}

private val MangaPanelLight = Color(0xFFF4F3F7)
private val MangaPanelLightContent = Color(0xFF1C1B1F)
private val MangaPanelLightSecondary = Color(0xFF5E5C66)
private val MangaPanelDark = Color(0xFF1C1B20)
private val MangaPanelDarkContent = Color(0xFFE6E1E6)
private val MangaPanelDarkSecondary = Color(0xFFB3AFB8)

fun ReaderBackground.isDarkPanel(isSystemDark: Boolean): Boolean = when (this) {
    ReaderBackground.LIGHT, ReaderBackground.WHITE -> false
    ReaderBackground.DARK, ReaderBackground.BLACK -> true
    ReaderBackground.DEFAULT, ReaderBackground.AUTO -> isSystemDark
}

fun mangaReaderPanelColors(
    background: ReaderBackground,
    isSystemDark: Boolean,
    scheme: ColorScheme,
): ReaderPanelColors {
    val dark = background.isDarkPanel(isSystemDark)
    // The app scheme follows the system; a panel of the opposite brightness needs the inverse accent.
    val accent = if (dark == isSystemDark) scheme.primary else scheme.inversePrimary
    return if (dark) {
        readerPanelColors(MangaPanelDark, MangaPanelDarkContent, MangaPanelDarkSecondary, accent, isDark = true)
    } else {
        readerPanelColors(MangaPanelLight, MangaPanelLightContent, MangaPanelLightSecondary, accent, isDark = false)
    }
}

/** Paper and ink only: no tints, no translucency, for e-ink screens. */
fun ReaderPanelColors.forEInk(): ReaderPanelColors {
    val paper = if (isDark) Color.Black else Color.White
    val ink = if (isDark) Color.White else Color.Black
    return ReaderPanelColors(
        container = paper,
        card = paper,
        content = ink,
        contentSecondary = ink,
        accent = ink,
        onAccent = paper,
        selectedContainer = ink.copy(alpha = 0.12f).compositeOver(paper),
        divider = ink.copy(alpha = 0.5f),
        isDark = isDark,
    )
}

/**
 * A complete scheme for Material components inside the panel. Every surface role is set, so no
 * component can fall back to an app-theme surface that clashes with the page (the grey cards on
 * a sepia novel theme came from an unset `surfaceContainerLow`).
 */
fun ReaderPanelColors.toColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = selectedContainer,
    onPrimaryContainer = content,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = selectedContainer,
    onSecondaryContainer = content,
    background = container,
    onBackground = content,
    surface = container,
    onSurface = content,
    // Chips inside cards (e.g. the novel font row) use surfaceVariant; the panel colour keeps them
    // visible against the card.
    surfaceVariant = container,
    onSurfaceVariant = contentSecondary,
    surfaceTint = accent,
    surfaceBright = card,
    surfaceDim = container,
    surfaceContainerLowest = card,
    surfaceContainerLow = card,
    surfaceContainer = card,
    surfaceContainerHigh = card,
    surfaceContainerHighest = card,
    outline = contentSecondary,
    outlineVariant = divider,
    inverseSurface = content,
    inverseOnSurface = container,
)

@Composable
fun ProvideReaderPanelColors(colors: ReaderPanelColors, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(colors, base) { colors.toColorScheme(base) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalReaderPanelColors provides colors,
            LocalContentColor provides colors.content,
            content = content,
        )
    }
}
