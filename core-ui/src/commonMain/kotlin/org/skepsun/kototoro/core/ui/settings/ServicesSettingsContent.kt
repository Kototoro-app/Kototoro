package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString

/**
 * The services page Android's `ServicesSettingsScreen` and desktop's `DesktopServicesPage` now share.
 *
 * Every row is a preference the host already stores; the shared page only projects the current values and forwards the
 * edits. Android's browse-tracking pair is nullable because Windows has no browse-tracking carousel yet — a switch that
 * gates nothing would be worse than a missing row — and its Discord door arrives as an optional action for the same
 * reason. The suggestions and statistics rows keep Android's "enabled"/"disabled" wording: when a switch is the only
 * value on the row, the row's summary says which way it is set.
 */
data class ServicesSettingsUiState(
    val suggestionsEnabled: Boolean,
    val relatedContent: Boolean,
    val statsEnabled: Boolean,
    val readingTimeEstimation: Boolean,
    val browseTracking: Boolean? = null,
    val browseMoreTracking: Boolean = false,
)

/** Android's icons for the same rows; desktop passes none because its settings pages have no leading artwork. */
data class ServicesSettingsRowIcons(
    val suggestions: Painter? = null,
    val browseTracking: Painter? = null,
    val browseMoreTracking: Painter? = null,
    val relatedContent: Painter? = null,
    val stats: Painter? = null,
    val readingTime: Painter? = null,
    val discord: Painter? = null,
)

/** Android's extra door to the Discord RPC page; hosts without that screen leave it out. */
data class ServicesSettingsAction(
    val title: String,
    val summary: String?,
    val onClick: () -> Unit,
)

@Composable
fun ServicesSettingsContent(
    state: ServicesSettingsUiState,
    onSuggestionsClick: () -> Unit,
    onRelatedContentChange: (Boolean) -> Unit,
    onStatsClick: () -> Unit,
    onStatsEnabledChange: (Boolean) -> Unit,
    onReadingTimeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: ServicesSettingsRowIcons = ServicesSettingsRowIcons(),
    onBrowseTrackingChange: (Boolean) -> Unit = {},
    onBrowseMoreTrackingChange: (Boolean) -> Unit = {},
    discordSettings: ServicesSettingsAction? = null,
) {
    @Composable
    fun onOff(value: Boolean): String = if (value) uiString("enabled") else uiString("disabled")
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("services-settings-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader(uiString("services"))
        SettingsGroup {
            SettingsValueRow(uiString("suggestions"), onOff(state.suggestionsEnabled), null, enabled,
                onSuggestionsClick, testTag = "services-suggestions",
                leadingIcon = icons.suggestions)
            if (state.browseTracking != null) {
                SettingsRowDivider()
                SettingsSwitchRow(uiString("browse_tracking_recommendations"),
                    uiString("browse_tracking_recommendations_summary"),
                    state.browseTracking, enabled, onBrowseTrackingChange,
                    testTag = "services-browse-tracking", leadingIcon = icons.browseTracking)
                if (state.browseTracking) {
                    SettingsRowDivider()
                    SettingsSwitchRow(uiString("browse_more_tracking_recommendations"),
                        uiString("browse_more_tracking_recommendations_summary"),
                        state.browseMoreTracking, enabled, onBrowseMoreTrackingChange,
                        testTag = "services-browse-more-tracking",
                        leadingIcon = icons.browseMoreTracking)
                }
            }
            SettingsRowDivider()
            SettingsSwitchRow(uiString("related_manga"), uiString("related_manga_summary"),
                state.relatedContent, enabled, onRelatedContentChange,
                testTag = "services-related-manga", leadingIcon = icons.relatedContent)
            SettingsRowDivider()
            // Android's split preference: the label opens the statistics screen, the switch turns recording on or off.
            SettingsSplitSwitchRow(uiString("reading_stats"), onOff(state.statsEnabled),
                state.statsEnabled, enabled, onStatsClick, onStatsEnabledChange,
                testTag = "services-stats-open", switchTag = "services-stats-enabled",
                leadingIcon = icons.stats)
            SettingsRowDivider()
            SettingsSwitchRow(uiString("reading_time_estimation"),
                uiString("reading_time_estimation_summary"), state.readingTimeEstimation, enabled,
                onReadingTimeChange, testTag = "services-reading-time",
                leadingIcon = icons.readingTime)
            if (discordSettings != null) {
                SettingsRowDivider()
                SettingsValueRow(discordSettings.title, discordSettings.summary, null, enabled,
                    discordSettings.onClick, testTag = "services-discord",
                    leadingIcon = icons.discord)
            }
        }
    }
}
