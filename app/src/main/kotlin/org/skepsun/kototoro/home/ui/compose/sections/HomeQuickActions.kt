package org.skepsun.kototoro.home.ui.compose.sections

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.home.HomeQuickActionsGrid
import org.skepsun.kototoro.core.ui.theme.ArtworkSurfaceRole
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.core.ui.home.HomeQuickAction as SharedHomeQuickAction

/** Target container opacity for the glass (iOS) tiles while the blurred artwork image background is active. */
private const val QUICK_ACTION_ARTWORK_GLASS_ALPHA = 0.45f

/**
 * Android's quick access section: the shared grid ([HomeQuickActionsGrid]) with glass tiles for the iOS style,
 * artwork-aware tile colours and TV focus handling.
 */
@Composable
internal fun QuickActionsSection(
    actions: List<HomeQuickAction>,
    modifier: Modifier = Modifier,
    /** Wider tiles for the tablet side column, where fewer, larger tiles read better. */
    preferredTileWidth: Dp? = null,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val isArtworkBackground = LocalBackgroundStyle.current.usesArtworkBackdrop
    HomeQuickActionsGrid(
        title = stringResource(R.string.quick_access),
        actions = actions.map { SharedHomeQuickAction(it.label, painterResource(it.iconRes), it.onClick, it.enabled) },
        modifier = modifier.then(if (isTvPresentation) Modifier.focusRestorer().focusGroup() else Modifier),
        preferredTileWidth = preferredTileWidth ?: if (isTvPresentation) 112.dp else 68.dp,
        tileHeight = if (isTvPresentation) 88.dp else 64.dp,
        spacing = if (isTvPresentation) 12.dp else 6.dp,
        expressive = expressive,
        isIosStyle = isIosStyle,
        large = isTvPresentation,
        container = { tileModifier, shape, paletteIndex, content ->
            if (isIosStyle) {
                GlassSurface(
                    modifier = tileModifier,
                    shape = shape,
                    style = GlassDefaults.subtleStyle().copy(
                        containerAlpha = if (isArtworkBackground) {
                            QUICK_ACTION_ARTWORK_GLASS_ALPHA
                        } else {
                            GlassDefaults.subtleStyle().containerAlpha
                        },
                    ),
                ) { content() }
            } else {
                val color = when {
                    !expressive -> MaterialTheme.colorScheme.surfaceContainerLow
                    paletteIndex % 3 == 0 -> MaterialTheme.colorScheme.secondaryContainer
                    paletteIndex % 3 == 1 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                Surface(
                    modifier = tileModifier,
                    shape = shape,
                    color = if (isArtworkBackground) color.artworkAwareContainerColor(ArtworkSurfaceRole.Card) else color,
                    tonalElevation = if (expressive) 0.dp else 1.dp,
                ) { content() }
            }
        },
        tileModifier = { _, shape ->
            if (isTvPresentation) {
                var isFocused by remember { mutableStateOf(false) }
                Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(if (isFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            } else {
                Modifier
            }
        },
    )
}

internal data class HomeQuickAction(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)
