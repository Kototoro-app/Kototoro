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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsContent
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsUiState
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow

/**
 * Android's download settings screen, restricted to the section Windows can honour: how many pages one chapter fetches
 * at a time, how many series run at once, the spacing between requests and how a failed page is retried. The five
 * sliders and their ranges come from the shared [DownloadSpeedSettingsContent], so Android's page and this one cannot
 * drift apart.
 *
 * Every row writes the preference the queue reads (`DesktopDownloadSettings`), and the queue reads it per chapter and
 * per page, so a change applies to the next request while the page is still open. Android's other rows are platform
 * features that do not exist here — a cellular-network question, battery-optimisation exemption, the video quality of
 * video downloads, novel illustrations, and the content-directory picker — so the page ends with a note saying so
 * instead of showing switches that would write a preference nothing reads. The status line above them reports what the
 * queue is doing right now, which is the number these sliders actually move.
 */
@Composable
internal fun DesktopDownloadSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings by controller.downloadSettings.collectAsState()
    val active = !state.busy
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ controller.more() }, enabled = active,
                modifier = Modifier.testTag("downloads-settings-back")) {
                Text(desktopText("common.back"))
            }
            Text(AndroidStrings["downloads"], style = MaterialTheme.typography.headlineSmall)
        }
        DownloadSpeedSettingsContent(
            state = DownloadSpeedSettingsUiState(
                threads = settings.threads,
                maxActiveSeries = settings.maxActiveSeries,
                requestDelayMs = settings.requestDelayMs,
                retryCount = settings.retryCount,
                retryDelayMs = settings.retryDelayMs,
            ),
            onThreadsChange = { controller.saveDownloadSettings(settings.copy(threads = it)) },
            onMaxActiveSeriesChange = { controller.saveDownloadSettings(settings.copy(maxActiveSeries = it)) },
            onRequestDelayChange = { controller.saveDownloadSettings(settings.copy(requestDelayMs = it)) },
            onRetryCountChange = { controller.saveDownloadSettings(settings.copy(retryCount = it)) },
            onRetryDelayChange = { controller.saveDownloadSettings(settings.copy(retryDelayMs = it)) },
            enabled = active,
        )
        SettingsGroup {
            SettingsInfoRow(desktopText("downloadsSettings.parity"),
                desktopText("downloadsSettings.parityDetail"),
                Modifier.testTag("downloads-settings-parity"))
        }
    }
}
