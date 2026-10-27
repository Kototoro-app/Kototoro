package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.MainNavigationRules
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsContent
import org.skepsun.kototoro.core.ui.settings.NavigationSettingsItem
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.desktop.runtime.DesktopNavItem

/** Desktop supplies destinations and persistence; Android and desktop consume the same editor and add picker. */
@Composable
internal fun DesktopNavigationConfigPage(controller: DesktopController, state: DesktopAppState) {
    val settings by controller.navSettings.collectAsState()
    val active = !state.busy
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        OutlinedButton({ controller.more() }, enabled = active, modifier = Modifier.testTag("nav-config-back")) {
            Text(desktopText("common.back"))
        }
        NavigationSettingsContent(
            configuredItems = settings.items.mapIndexed { index, item ->
                NavigationSettingsItem(
                    id = item.name,
                    title = desktopNavItemLabel(item),
                    summary = if (item.isAvailable(state.suggestionSettings.enabled)) {
                        desktopText("navSettings.position", index + 1, settings.items.size)
                    } else AndroidStrings["suggestions_unavailable_text"],
                )
            },
            availableItems = settings.available.map { NavigationSettingsItem(it.name, desktopNavItemLabel(it)) },
            canShowAddAction = settings.available.isNotEmpty(),
            canAddAction = settings.canAdd,
            onAddItem = { controller.saveNavigationSettings(settings.add(DesktopNavItem.valueOf(it))) },
            onRemoveItem = { controller.saveNavigationSettings(settings.remove(DesktopNavItem.valueOf(it))) },
            onReorder = { from, to ->
                controller.saveNavigationSettings(settings.move(from, to))
            },
            enabled = active,
            showMoveButtons = true,
            footer = {
                SettingsGroup {
                    SettingsInfoRow(desktopText("navSettings.morePinned"), desktopText("navSettings.morePinnedDetail"),
                        Modifier.testTag("nav-config-pinned"))
                }
                Text(desktopText("navSettings.cap", MainNavigationRules.MAX_ITEMS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("nav-config-cap"))
            },
        )
    }
}
