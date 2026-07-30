package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal fun ColorScheme.isDarkTheme(): Boolean = onBackground.luminance() > 0.5f

internal fun ColorScheme.artworkOverlayColor(): Color = if (isDarkTheme()) {
	Color.Black.copy(alpha = 0.60f)
} else {
	Color.White.copy(alpha = 0.68f)
}
