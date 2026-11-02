package org.skepsun.kototoro.video.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * Shared look for the player menus that float above the live picture.
 *
 * Phone presentation keeps the translucent "glass" panel: it is close to the eye and the video
 * behind it reads as context. TV presentation cannot afford that — the picture bleeds through the
 * labels, and a viewer sitting three meters away loses track of the text, especially over a bright
 * scene. TV menus therefore use an opaque panel, wider rows and a type size that survives distance.
 */
@Composable
internal fun isTvPlayerMenu(): Boolean = LocalUiPresentationConfig.current.isTv

/** Opaque TV menu background; deliberately not pure black so the panel edge stays visible. */
private val TvMenuContainer = Color(0xFF16171A)

@Composable
internal fun playerMenuContainerColor(phoneAlpha: Float): Color = if (isTvPlayerMenu()) {
    TvMenuContainer
} else {
    Color.Black.copy(alpha = phoneAlpha)
}

/**
 * Width range for a menu panel. TV menus hold sentence-length labels at a larger type size, so the
 * phone range is scaled up instead of being capped at a phone-sized card.
 */
@Composable
internal fun playerMenuWidth(phoneMin: Dp, phoneMax: Dp): Pair<Dp, Dp> = if (isTvPlayerMenu()) {
    (phoneMin * 1.6f).coerceAtLeast(phoneMin) to (phoneMax * 1.6f).coerceAtLeast(phoneMax)
} else {
    phoneMin to phoneMax
}

/** Minimum row height; a TV focus ring needs more room than a touch target. */
@Composable
internal fun playerMenuRowMinHeight(phoneMin: Dp): Dp = if (isTvPlayerMenu()) {
    maxOf(phoneMin, 60.dp)
} else {
    phoneMin
}

/** Row label style: the phone uses the very small label roles, which are unreadable on a TV. */
@Composable
internal fun playerMenuRowTextStyle(compact: Boolean): TextStyle = when {
    isTvPlayerMenu() && compact -> MaterialTheme.typography.bodyMedium
    isTvPlayerMenu() -> MaterialTheme.typography.bodyLarge
    compact -> MaterialTheme.typography.labelMedium
    else -> MaterialTheme.typography.labelLarge
}
