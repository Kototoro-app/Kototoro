package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSplitSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * Android's `ServicesSettingsScreen`. Windows used to hang its recommendations and statistics entries straight off 更多;
 * Android keeps them under one 服务 page, together with the switches that decide what the reader records, so 更多 goes
 * back to being a list of pages and this page owns the choices.
 *
 * The rows follow Android's order: the recommendations entry, 阅读统计 as a split row — the title opens the statistics
 * screen while the switch alone starts or stops recording — then 阅读时间估算, whose estimate the details page shows.
 * Android's remaining rows (browse-tracking carousels, related works, Discord RPC) have no Windows behaviour yet, so they
 * are not drawn: a switch that gates nothing would be worse than a missing row.
 */
@Composable
internal fun DesktopServicesPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.services
    val active = !state.busy
    // Android writes the on/off word as the summary of both the recommendations row and the statistics switch.
    val recommendationsSummary =
        if (state.suggestionSettings.enabled) AndroidStrings["enabled"] else AndroidStrings["disabled"]
    val statsSummary = if (settings.statsEnabled) AndroidStrings["enabled"] else AndroidStrings["disabled"]
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active) { Text(desktopText("common.back")) }
        SettingsSectionHeader(AndroidStrings["services"])
        SettingsGroup {
            SettingsValueRow(AndroidStrings["suggestions"], recommendationsSummary, null, active,
                { controller.showRecommendations() }, testTag = "settings-recommendations-open",
                leadingIcon = painterResource("icons/ic_suggestion.svg"))
            SettingsRowDivider()
            // Android's split preference: the label opens the statistics screen, the switch turns recording on or off.
            SettingsSplitSwitchRow(AndroidStrings["reading_stats"], statsSummary,
                settings.statsEnabled, active, { controller.showReadingStats() },
                { controller.servicesSettings(settings.copy(statsEnabled = it)) },
                testTag = "services-stats-open", switchTag = "services-stats-enabled")
            SettingsRowDivider()
            SettingsSwitchRow(AndroidStrings["reading_time_estimation"],
                AndroidStrings["reading_time_estimation_summary"], settings.readingTimeEstimation, active,
                { controller.servicesSettings(settings.copy(readingTimeEstimation = it)) },
                testTag = "services-reading-time")
        }
    }
}
