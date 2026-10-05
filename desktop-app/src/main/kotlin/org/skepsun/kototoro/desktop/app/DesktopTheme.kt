package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.theme.*

enum class DesktopAppearance(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class DesktopInterfaceStyle(val title: String) { MATERIAL("Material 3"), IOS("iOS 玻璃") }

internal val LocalDesktopInterfaceStyle = staticCompositionLocalOf { DesktopInterfaceStyle.MATERIAL }

internal val LocalDesktopWindowWidth = staticCompositionLocalOf { 0.dp }

internal val Accent: Color @Composable get() = MaterialTheme.colors.primary
internal val Canvas: Color @Composable get() = MaterialTheme.colors.background
internal val Ink: Color @Composable get() = MaterialTheme.colors.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colors.onSurface.copy(alpha = .7f)

/** Shared Android typography and Material 3 host, with a bridge for desktop screens awaiting migration. */
@Composable
internal fun DesktopTheme(appearance: DesktopAppearance = DesktopAppearance.SYSTEM,
    interfaceStyle: DesktopInterfaceStyle = DesktopInterfaceStyle.MATERIAL, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        DesktopAppearance.SYSTEM -> isSystemInDarkTheme()
        DesktopAppearance.LIGHT -> false
        DesktopAppearance.DARK -> true
    }
    val colors = if (dark) darkColors(
        primary = Color(0xFFA9C7FF), secondary = Color(0xFF82D3EB),
        onPrimary = Color(0xFF003062), onSecondary = Color(0xFF003441),
        background = Color(0xFF10131B), surface = Color(0xFF242733),
        onBackground = Color(0xFFE8E6EF), onSurface = Color(0xFFE8E6EF),
    ) else lightColors(
        // Android's iOS primary is #007AFF. The darker blue keeps small desktop labels legible on light surfaces.
        primary = Color(0xFF0066D6), secondary = Color(0xFF007C9E),
        background = Color(0xFFFFF8FF), surface = Color(0xFFFCF9FF),
        onBackground = Color(0xFF1C1B20), onSurface = Color(0xFF1C1B20),
    )
    val typography = kototoroTypography(isExpressiveStyle = true, defaultFontFamily = null)
    val sharedColors = (if (dark) androidx.compose.material3.darkColorScheme()
        else androidx.compose.material3.lightColorScheme()).copy(
        primary = colors.primary, onPrimary = colors.onPrimary,
        secondary = colors.secondary, onSecondary = colors.onSecondary,
        background = colors.background, onBackground = colors.onBackground,
        surface = colors.surface, onSurface = colors.onSurface,
    )
    val tokens = if (interfaceStyle == DesktopInterfaceStyle.IOS) InterfaceStyleTokens.Ios
        else InterfaceStyleTokens.Material3Expressive
    val shapes = kototoroShapes(tokens)
    androidx.compose.material3.MaterialTheme(colorScheme = sharedColors, typography = typography, shapes = shapes) {
        // Existing desktop screens are migrated incrementally; bridge the same semantic roles to Material 2.
        MaterialTheme(colors = colors,
            shapes = Shapes(small = shapes.small, medium = shapes.medium, large = shapes.large),
            typography = Typography(h4 = typography.headlineLarge, h5 = typography.headlineMedium,
                h6 = typography.titleLarge, subtitle1 = typography.titleMedium, body1 = typography.bodyLarge,
                body2 = typography.bodyMedium, button = typography.labelLarge, caption = typography.bodySmall)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface,
                LocalDesktopInterfaceStyle provides interfaceStyle, LocalInterfaceStyleTokens provides tokens,
                content = content)
        }
    }
}

/** A quiet, theme-derived wash like the Android shelf; all content and controls retain stable surface colours. */
@Composable
internal fun desktopCanvasBrush(): Brush = Brush.verticalGradient(listOf(
    MaterialTheme.colors.secondary.copy(alpha = if (MaterialTheme.colors.isLight) .08f else .04f),
    Canvas,
), endY = 420f)

@Composable
internal fun DesktopSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, tag: String? = null) {
    DesktopControlSurface(modifier, RoundedCornerShape(28.dp)) {
        TextField(value, onValueChange, enabled = enabled, singleLine = true,
            placeholder = { Text(placeholder) }, leadingIcon = {
                Icon(painterResource("icons/ic_search.svg"), null, tint = Muted, modifier = Modifier.size(22.dp))
            }, colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent), modifier = Modifier.fillMaxWidth()
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier))
    }
}
