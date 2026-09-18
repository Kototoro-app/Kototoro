package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.skepsun.kototoro.core.prefs.BackgroundStyle

internal fun ColorScheme.isDarkTheme(): Boolean = onBackground.luminance() > 0.5f

/** Popup menus have no backdrop blur, so only allow a restrained amount of artwork to show through. */
@Composable
internal fun ColorScheme.popupMenuContainerColor(): Color =
    if (LocalBackgroundStyle.current.usesArtworkBackdrop) {
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

/**
 * Role-aware translucency for surfaces over the artwork background.
 *
 * The previous implementation collapsed every artwork-mode container to a single alpha
 * (0.50), which put chrome, cards and content on the same plane and read as flat
 * stickers on the image. Alpha is now resolved per [ArtworkSurfaceRole] and per preset
 * (ATMOSPHERE vs GALLERY) so surfaces stack into a legible hierarchy. [Color.copy]
 * sets an absolute alpha, avoiding multiplication of the artwork color scheme's own
 * alpha so similar cards stay consistent across light and dark themes.
 */
@Composable
internal fun Color.artworkAwareContainerColor(
    role: ArtworkSurfaceRole = ArtworkSurfaceRole.Surface,
): Color {
    val style = LocalBackgroundStyle.current
    if (!style.usesArtworkBackdrop) return this
    val preset = ArtworkBackdropPresets.forStyle(style)
    val isDark = MaterialTheme.colorScheme.isDarkTheme()
    val alpha = when (role) {
        ArtworkSurfaceRole.Chrome -> if (isDark) preset.chromeAlphaDark else preset.chromeAlphaLight
        ArtworkSurfaceRole.PillControl -> if (isDark) preset.pillAlphaDark else preset.pillAlphaLight
        ArtworkSurfaceRole.Card -> if (isDark) preset.cardAlphaDark else preset.cardAlphaLight
        ArtworkSurfaceRole.Surface -> if (isDark) preset.surfaceAlphaDark else preset.surfaceAlphaLight
    }
    return copy(alpha = alpha)
}

/** Surface roles layered over the artwork background, from floating chrome down to content. */
internal enum class ArtworkSurfaceRole {
    Chrome,
    PillControl,
    Card,
    Surface,
}

/** Per-preset alpha values for the layered roles. */
internal data class ArtworkBackdropPreset(
    val chromeAlphaDark: Float,
    val chromeAlphaLight: Float,
    val pillAlphaDark: Float,
    val pillAlphaLight: Float,
    val cardAlphaDark: Float,
    val cardAlphaLight: Float,
    val surfaceAlphaDark: Float,
    val surfaceAlphaLight: Float,
    val overlayTopDark: Float,
    val overlayMidDark: Float,
    val overlayBottomDark: Float,
    val overlayTopLight: Float,
    val overlayMidLight: Float,
    val overlayBottomLight: Float,
    val backdropSaturation: Float,
)

internal object ArtworkBackdropPresets {
    private val Atmosphere = ArtworkBackdropPreset(
        chromeAlphaDark = 0.85f, chromeAlphaLight = 0.92f,
        pillAlphaDark = 0.75f, pillAlphaLight = 0.82f,
        cardAlphaDark = 0.66f, cardAlphaLight = 0.74f,
        surfaceAlphaDark = 0.62f, surfaceAlphaLight = 0.70f,
        overlayTopDark = 0.72f, overlayMidDark = 0.60f, overlayBottomDark = 0.80f,
        overlayTopLight = 0.80f, overlayMidLight = 0.68f, overlayBottomLight = 0.86f,
        backdropSaturation = 0.65f,
    )
    private val Gallery = ArtworkBackdropPreset(
        chromeAlphaDark = 0.68f, chromeAlphaLight = 0.78f,
        pillAlphaDark = 0.55f, pillAlphaLight = 0.65f,
        cardAlphaDark = 0.45f, cardAlphaLight = 0.55f,
        surfaceAlphaDark = 0.40f, surfaceAlphaLight = 0.50f,
        overlayTopDark = 0.55f, overlayMidDark = 0.40f, overlayBottomDark = 0.70f,
        overlayTopLight = 0.62f, overlayMidLight = 0.48f, overlayBottomLight = 0.76f,
        backdropSaturation = 0.85f,
    )

    fun forStyle(style: BackgroundStyle): ArtworkBackdropPreset = when (style) {
        BackgroundStyle.DYNAMIC_ARTWORK_GALLERY -> Gallery
        else -> Atmosphere
    }
}
