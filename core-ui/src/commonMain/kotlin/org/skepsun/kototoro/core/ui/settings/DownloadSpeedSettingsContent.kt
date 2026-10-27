package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString

/**
 * The download "speed and retries" section Android's `DownloadsSettingsScreen` and desktop's
 * `DesktopDownloadSettingsPage` now share.
 *
 * Only the five numbers both hosts actually honour are here: pages fetched at once, series running at once, request
 * spacing and the retry count/delay. Android's other download rows (content directories, format, video quality, metered
 * network, battery exemption) are platform features Windows cannot execute, so they stay in Android's page instead of
 * becoming switches that write a preference nothing reads. The ranges and steps are Android's; they live here in
 * [DownloadSpeedSettingsRanges] instead of `core-domain`'s `DownloadPolicy` because `core-ui` does not depend on the
 * domain module, and the queue still clamps every stored value through `DownloadPolicy` on its own side.
 */
data class DownloadSpeedSettingsUiState(
    val threads: Int,
    val maxActiveSeries: Int,
    val requestDelayMs: Int,
    val retryCount: Int,
    val retryDelayMs: Int,
)

/** Android's icons for the same rows; desktop passes none because its settings pages have no leading artwork. */
data class DownloadSpeedSettingsRowIcons(
    val threads: Painter? = null,
    val maxActiveSeries: Painter? = null,
    val requestDelay: Painter? = null,
    val retryCount: Painter? = null,
    val retryDelay: Painter? = null,
)

/** Android's `SettingsSliderPreference` ranges and steps for the five numbers, shared so both pages offer the same. */
object DownloadSpeedSettingsRanges {
    val threads: IntRange = 1..10
    val maxActiveSeries: IntRange = 1..11
    /** 0..5000 ms in 100 ms notches. */
    val requestDelayMs: IntRange = 0..5000
    const val requestDelayStepMs = 100
    val retryCount: IntRange = 1..10
    /** 500..10000 ms in 500 ms notches. */
    val retryDelayMs: IntRange = 500..10_000
    const val retryDelayStepMs = 500
    /** The series cap's last value means "no cap", matching `DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED`. */
    const val maxActiveSeriesUnlimited = 11
}

@Composable
fun DownloadSpeedSettingsContent(
    state: DownloadSpeedSettingsUiState,
    onThreadsChange: (Int) -> Unit,
    onMaxActiveSeriesChange: (Int) -> Unit,
    onRequestDelayChange: (Int) -> Unit,
    onRetryCountChange: (Int) -> Unit,
    onRetryDelayChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: DownloadSpeedSettingsRowIcons = DownloadSpeedSettingsRowIcons(),
) {
    val uncappedText = uiString("download_max_active_series_uncapped")
    Column(
        modifier.fillMaxWidth().testTag("download-speed-settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader(uiString("download_speed_and_retries"))
        SettingsGroup {
            SettingsSliderRow(
                uiString("download_threads"), uiString("download_threads_summary"), state.threads,
                DownloadSpeedSettingsRanges.threads, { it.toString() }, enabled,
                onThreadsChange, testTag = "download-setting-threads", leadingIcon = icons.threads,
            )
            SettingsRowDivider()
            SettingsSliderRow(
                uiString("download_max_active_series"), uiString("download_max_active_series_summary"),
                state.maxActiveSeries,
                DownloadSpeedSettingsRanges.maxActiveSeries,
                { if (it >= DownloadSpeedSettingsRanges.maxActiveSeriesUnlimited) uncappedText else it.toString() },
                enabled, onMaxActiveSeriesChange,
                testTag = "download-setting-series", leadingIcon = icons.maxActiveSeries,
            )
            SettingsRowDivider()
            SettingsSliderRow(
                uiString("download_request_delay"), uiString("download_request_delay_summary"),
                state.requestDelayMs, DownloadSpeedSettingsRanges.requestDelayMs, { "$it ms" }, enabled,
                onRequestDelayChange, step = DownloadSpeedSettingsRanges.requestDelayStepMs,
                testTag = "download-setting-request-delay", leadingIcon = icons.requestDelay,
            )
            SettingsRowDivider()
            SettingsSliderRow(
                uiString("download_retry_count"), uiString("download_retry_count_summary"),
                state.retryCount, DownloadSpeedSettingsRanges.retryCount,
                { it.toString() }, enabled, onRetryCountChange,
                testTag = "download-setting-retry-count", leadingIcon = icons.retryCount,
            )
            SettingsRowDivider()
            SettingsSliderRow(
                uiString("download_retry_delay"), uiString("download_retry_delay_summary"),
                state.retryDelayMs,
                DownloadSpeedSettingsRanges.retryDelayMs, { "$it ms" }, enabled,
                onRetryDelayChange, step = DownloadSpeedSettingsRanges.retryDelayStepMs,
                testTag = "download-setting-retry-delay", leadingIcon = icons.retryDelay,
            )
        }
    }
}
