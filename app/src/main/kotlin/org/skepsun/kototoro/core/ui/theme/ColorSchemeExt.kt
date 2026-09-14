package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.skepsun.kototoro.core.prefs.BackgroundStyle

internal fun ColorScheme.isDarkTheme(): Boolean = onBackground.luminance() > 0.5f

internal fun ColorScheme.artworkOverlayColor(strength: Float = 1f): Color {
    val baseColor = if (isDarkTheme()) {
        Color.Black.copy(alpha = 0.60f)
    } else {
        Color.White.copy(alpha = 0.68f)
    }
    return baseColor.copy(alpha = baseColor.alpha * strength.coerceIn(0f, 1f))
}

/**
 * Keeps nested containers visibly translucent when artwork is used as the app background.
 *
 * [Color.copy] sets an absolute alpha, which avoids multiplying the alpha already present in the
 * artwork color scheme and keeps similar cards visually consistent across light and dark themes.
 */
@Composable
internal fun Color.artworkAwareContainerColor(): Color {
    return if (LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR) {
        copy(alpha = 0.50f)
    } else {
        this
    }
}
