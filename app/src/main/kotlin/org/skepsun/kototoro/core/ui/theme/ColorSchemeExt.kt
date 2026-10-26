package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.skepsun.kototoro.core.prefs.BackgroundStyle

/** Popup menus have no backdrop blur, so only allow a restrained amount of artwork to show through. */
@Composable
internal fun ColorScheme.popupMenuContainerColor(): Color =
    if (LocalArtworkBackdrop.current != ArtworkBackdrop.NONE) {
        surfaceContainer.copy(alpha = 0.96f)
    } else {
        surfaceContainer
    }

internal fun ColorScheme.artworkOverlayColor(strength: Float = 1f): Color {
    val baseColor = if (isDarkTheme()) {
        Color.Black.copy(alpha = 0.60f)
    } else {
        Color.White.copy(alpha = 0.68f)
    }
    return baseColor.copy(alpha = baseColor.alpha * strength.coerceIn(0f, 1f))
}

/** The shared backdrop kind a background style paints; the artwork presets live with the shared theme. */
val BackgroundStyle.artworkBackdrop: ArtworkBackdrop
    get() = when (this) {
        BackgroundStyle.DYNAMIC_ARTWORK_GALLERY -> ArtworkBackdrop.GALLERY
        BackgroundStyle.DYNAMIC_ARTWORK_BLUR -> ArtworkBackdrop.ATMOSPHERE
        else -> ArtworkBackdrop.NONE
    }

/** Presets for an app background style; the table itself is shared with the desktop host. */
fun ArtworkBackdropPresets.forStyle(style: BackgroundStyle): ArtworkBackdropPreset = forBackdrop(style.artworkBackdrop)
