package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.MainNavigationRules
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.desktop.runtime.DesktopNavItem

/**
 * Android's nav configuration screen (`NavConfigSettings`, titled "main screen sections") for the Windows rail.
 *
 * The rows are the same ones Android edits: the configured destinations in display order, the same five-item cap, the
 * same "adding never duplicates" and "removing the last one brings Home back" rules — all of them from
 * [MainNavigationRules], so the two platforms cannot drift apart. Reordering is done with explicit up/down buttons
 * instead of Android's drag handle, and the edits are written on the press: the rail below reads the stored list, so a
 * move is visible as soon as the page is left.
 *
 * 更多 is deliberately absent from the list. It is the only way into every other page, so the rail always appends it and
 * the note under the rows says so.
 */
@Composable
internal fun DesktopNavigationConfigPage(controller: DesktopController, state: DesktopAppState) {
    val settings by controller.navSettings.collectAsState()
    val active = !state.busy
    var addDialogVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ controller.more() }, enabled = active,
                modifier = Modifier.testTag("nav-config-back")) {
                Text(desktopText("common.back"))
            }
            Text(AndroidStrings["main_screen_sections"], style = MaterialTheme.typography.headlineSmall)
        }
        SettingsSectionHeader(AndroidStrings["main_screen_sections"])
        SettingsGroup {
            settings.items.forEachIndexed { index, item ->
                if (index != 0) SettingsRowDivider()
                SettingsActionRow(
                    title = desktopNavItemLabel(item),
                    summary = desktopText("navSettings.position", index + 1, settings.items.size),
                    modifier = Modifier.testTag("nav-config-item:${item.name}"),
                ) {
                    TextButton(
                        onClick = { controller.saveNavigationSettings(settings.step(item, direction = -1)) },
                        enabled = active && index > 0,
                        modifier = Modifier.testTag("nav-config-up:${item.name}"),
                    ) { Text("↑") }
                    TextButton(
                        onClick = { controller.saveNavigationSettings(settings.step(item, direction = 1)) },
                        enabled = active && index < settings.items.lastIndex,
                        modifier = Modifier.testTag("nav-config-down:${item.name}"),
                    ) { Text("↓") }
                    TextButton(
                        onClick = { controller.saveNavigationSettings(settings.remove(item)) },
                        enabled = active,
                        modifier = Modifier.testTag("nav-config-remove:${item.name}"),
                    ) { Text("✕") }
                }
            }
            if (settings.available.isNotEmpty()) {
                SettingsRowDivider()
                SettingsActionRow(
                    title = AndroidStrings["add"],
                    summary = if (settings.canAdd) null else desktopText("navSettings.atCap"),
                ) {
                    TextButton(
                        onClick = { addDialogVisible = true },
                        enabled = active && settings.canAdd,
                        modifier = Modifier.testTag("nav-config-add"),
                    ) { Text(AndroidStrings["add"]) }
                }
            }
        }
        SettingsGroup {
            SettingsInfoRow(
                desktopText("navSettings.morePinned"),
                desktopText("navSettings.morePinnedDetail"),
                Modifier.testTag("nav-config-pinned"),
            )
        }
        Text(
            text = desktopText("navSettings.cap", MainNavigationRules.MAX_ITEMS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("nav-config-cap"),
        )
    }
    if (addDialogVisible) {
        DesktopChoiceSettingsDialog(
            title = AndroidStrings["add"],
            summary = desktopText("navSettings.addSummary"),
            bodyTag = "nav-config-add-dialog",
            optionTagPrefix = "nav-config-add-option",
            options = settings.available.map { item -> desktopNavItemLabel(item) to item.name },
            selected = null,
            enabled = active,
            onPick = { name ->
                DesktopNavItem.entries.find { it.name == name }?.let { item ->
                    controller.saveNavigationSettings(settings.add(item))
                }
            },
            onDismiss = { addDialogVisible = false },
        )
    }
}
