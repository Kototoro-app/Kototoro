package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.theme.*

/** The label is resolved per access, so a language switch relabels the buttons without a restart. */
enum class DesktopAppearance {
    SYSTEM, LIGHT, DARK;

    val title: String get() = when (this) {
        SYSTEM -> desktopText("appearance.system")
        LIGHT -> desktopText("appearance.light")
        DARK -> desktopText("appearance.dark")
    }
}

enum class DesktopInterfaceStyle {
    MATERIAL, IOS;

    val title: String get() = when (this) {
        MATERIAL -> desktopText("interfaceStyle.material")
        IOS -> desktopText("interfaceStyle.glass")
    }
}

internal val LocalDesktopInterfaceStyle = staticCompositionLocalOf { DesktopInterfaceStyle.MATERIAL }

internal val LocalDesktopWindowWidth = staticCompositionLocalOf { 0.dp }

/** Material 3 has no `isLight`, and every dark/light branch here wants the chosen appearance, not a luminance guess. */
internal val LocalDesktopDark = staticCompositionLocalOf { false }

internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Canvas: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)

/** Shared Android colour scheme, typography and shapes, so desktop screens and core-ui components agree. */
@Composable
internal fun DesktopTheme(appearance: DesktopAppearance = DesktopAppearance.SYSTEM,
    interfaceStyle: DesktopInterfaceStyle = DesktopInterfaceStyle.MATERIAL, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        DesktopAppearance.SYSTEM -> isSystemInDarkTheme()
        DesktopAppearance.LIGHT -> false
        DesktopAppearance.DARK -> true
    }
    val colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF003062),
        secondary = Color(0xFF82D3EB), onSecondary = Color(0xFF003441),
        background = Color(0xFF10131B), onBackground = Color(0xFFE8E6EF),
        surface = Color(0xFF242733), onSurface = Color(0xFFE8E6EF),
    ) else lightColorScheme(
        // Android's iOS primary is #007AFF. The darker blue keeps small desktop labels legible on light surfaces.
        primary = Color(0xFF0066D6), onPrimary = Color.White,
        secondary = Color(0xFF007C9E), onSecondary = Color.Black,
        background = Color(0xFFFFF8FF), onBackground = Color(0xFF1C1B20),
        surface = Color(0xFFFCF9FF), onSurface = Color(0xFF1C1B20),
    )
    val typography = kototoroTypography(isExpressiveStyle = true, defaultFontFamily = null)
    val tokens = if (interfaceStyle == DesktopInterfaceStyle.IOS) InterfaceStyleTokens.Ios
        else InterfaceStyleTokens.Material3Expressive
    val shapes = kototoroShapes(tokens)
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface,
            LocalDesktopDark provides dark,
            LocalDesktopInterfaceStyle provides interfaceStyle, LocalInterfaceStyleTokens provides tokens,
            // Android's own catalog for the screens both hosts share (see DesktopUiStrings).
            LocalUiStrings provides DesktopUiStrings, content = content)
    }
}

/** A quiet, theme-derived wash like the Android shelf; all content and controls retain stable surface colours. */
@Composable
internal fun desktopCanvasBrush(): Brush = Brush.verticalGradient(listOf(
    MaterialTheme.colorScheme.secondary.copy(alpha = if (LocalDesktopDark.current) .04f else .08f),
    Canvas,
), endY = 420f)

@Composable
internal fun DesktopSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, tag: String? = null) {
    DesktopControlSurface(modifier, RoundedCornerShape(28.dp)) {
        TextField(value, onValueChange, enabled = enabled, singleLine = true,
            placeholder = { Text(placeholder) }, leadingIcon = {
                Icon(painterResource("icons/ic_search.svg"), null, tint = Muted, modifier = Modifier.size(22.dp))
            }, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent), modifier = Modifier.fillMaxWidth()
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier))
    }
}
