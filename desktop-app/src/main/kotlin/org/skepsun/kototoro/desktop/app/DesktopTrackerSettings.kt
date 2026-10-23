package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * Android's update preferences: a switch for checking new chapters and a choice of how often to check. The frequency
 * used to be a row of chips; Android edits it through a choice dialog like every other single-value preference.
 */
@Composable
internal fun DesktopTrackerSettings(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val tracker = state.trackerSettings
    val frequencies = desktopTrackerFrequencies()
    val active = enabled && tracker.enabled
    var choosingFrequency by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(desktopText("settings.updates"))
        SettingsGroup {
            SettingsSwitchRow(desktopText("settings.checkNewChapters"), desktopText("settings.checkNewChaptersDetail"),
                tracker.enabled, enabled, { controller.trackerSettings(tracker.copy(enabled = it)) },
                testTag = "settings-tracker-enabled")
            SettingsRowDivider()
            SettingsValueRow(desktopText("settings.checkFrequency"), null,
                frequencies.firstOrNull { it.first == tracker.frequency }?.second, active,
                { choosingFrequency = true }, testTag = "settings-tracker-frequency")
        }
    }
    if (choosingFrequency) {
        DesktopChoiceSettingsDialog(
            title = desktopText("settings.checkFrequency"), summary = null, bodyTag = "settings-tracker-dialog",
            optionTagPrefix = "settings-tracker-frequency",
            options = frequencies.map { (value, label) -> label to value.toString() },
            selected = tracker.frequency.toString(), enabled = active,
            onPick = { controller.trackerSettings(tracker.copy(frequency = it.toFloat())) },
            onDismiss = { choosingFrequency = false },
        )
    }
}
