package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsContent
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsItem
import org.skepsun.kototoro.core.ui.theme.ArtworkSurfaceRole
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.artworkAwareContainerColor
import org.skepsun.kototoro.settings.nav.model.NavItemConfigModel

/** Android supplies resource labels, artwork styling, TV focus and persistence to the shared editor. */
@Composable
fun NavConfigScreen(
    configuredItems: List<NavItemConfigModel>,
    availableItems: List<NavItem>,
    canShowAddAction: Boolean,
    canAddAction: Boolean,
    onAddItem: (NavItem) -> Unit,
    onRemoveItem: (NavItem) -> Unit,
    onReorder: (fromPos: Int, toPos: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val rowColor = if (LocalBackgroundStyle.current.usesArtworkBackdrop) {
        MaterialTheme.colorScheme.surfaceContainerLow.artworkAwareContainerColor(ArtworkSurfaceRole.Card)
    } else MaterialTheme.colorScheme.surfaceContainer
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavigationSettingsContent(
            configuredItems = configuredItems.map { config ->
                NavigationSettingsItem(
                    id = config.item.name,
                    title = stringResource(config.item.title),
                    summary = if (config.disabledHintResId != 0) stringResource(config.disabledHintResId) else null,
                    icon = rememberSafePainter(config.item.icon),
                )
            },
            availableItems = availableItems.map { item ->
                NavigationSettingsItem(item.name, stringResource(item.title), icon = rememberSafePainter(item.icon))
            },
            canShowAddAction = canShowAddAction,
            canAddAction = canAddAction,
            onAddItem = { onAddItem(NavItem.valueOf(it)) },
            onRemoveItem = { onRemoveItem(NavItem.valueOf(it)) },
            onReorder = onReorder,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(20.dp),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            rowShape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
            rowColor = rowColor,
            dragHandle = painterResource(R.drawable.ic_reorder_handle),
            addIcon = painterResource(R.drawable.ic_add),
            removeButtonModifier = Modifier.tvIconButtonRing(),
            renderDialog = { AndroidPreferenceDialog(it) },
        )
    }
}
