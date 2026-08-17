package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle

private val IosLightSecondaryLabel = Color(0xFF8E8E93)
private val IosDarkSecondaryLabel = Color(0xFF98989D)
private val IosLightSeparator = Color(0xFFD1D1D6)
private val IosDarkSeparator = Color(0xFF38383A)
private val IosLightChevron = Color(0xFFC7C7CC)
private val IosDarkChevron = Color(0xFF48484A)

@Composable
internal fun settingsScreenBackgroundColor(): Color = MaterialTheme.colorScheme.background

@Composable
internal fun settingsSectionLabelColor(): Color = if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) IosDarkSecondaryLabel else IosLightSecondaryLabel
} else {
    MaterialTheme.colorScheme.primary
}

@Composable
internal fun settingsSeparatorColor(): Color = if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) IosDarkSeparator else IosLightSeparator
} else {
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
}

@Composable
internal fun settingsChevronColor(): Color = if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) IosDarkChevron else IosLightChevron
} else {
    MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun Modifier.settingsIconBackground(
    baseColor: Color,
    shape: Shape,
    isIosStyle: Boolean,
): Modifier = if (isIosStyle) {
    background(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(baseColor, Color.White, 0.08f),
                baseColor,
                lerp(baseColor, Color.Black, 0.05f),
            ),
        ),
        shape = shape,
    )
} else {
    background(color = baseColor, shape = shape)
}

internal fun settingsGroupItemContainerColor(
    interfaceStyle: InterfaceStyle,
    backgroundStyle: BackgroundStyle,
    surfaceContainerLow: Color,
    surfaceContainer: Color,
): Color = if (
    interfaceStyle == InterfaceStyle.IOS &&
    backgroundStyle == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
) {
    surfaceContainerLow.copy(alpha = SETTINGS_IOS_CONTAINER_ALPHA)
} else {
    surfaceContainer
}

internal const val SETTINGS_IOS_CONTAINER_ALPHA = 0.74f
