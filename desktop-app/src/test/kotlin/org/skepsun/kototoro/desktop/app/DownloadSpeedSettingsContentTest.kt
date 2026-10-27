package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsContent
import org.skepsun.kototoro.core.ui.settings.DownloadSpeedSettingsUiState

/** The download speed/retry section Android and desktop now share: five writes, the shared ranges and the cap label. */
@OptIn(ExperimentalTestApi::class)
class DownloadSpeedSettingsContentTest {
    private val defaults = DownloadSpeedSettingsUiState(
        threads = 4,
        maxActiveSeries = 4,
        requestDelayMs = 1600,
        retryCount = 2,
        retryDelayMs = 2000,
    )

    @Test
    fun `each slider writes its host value through the shared ranges`() =
        runDesktopComposeUiTest(width = 760, height = 1400) {
            var state by mutableStateOf(defaults)
            setContent {
                DesktopTheme {
                    DownloadSpeedSettingsContent(
                        state = state,
                        onThreadsChange = { state = state.copy(threads = it) },
                        onMaxActiveSeriesChange = { state = state.copy(maxActiveSeries = it) },
                        onRequestDelayChange = { state = state.copy(requestDelayMs = it) },
                        onRetryCountChange = { state = state.copy(retryCount = it) },
                        onRetryDelayChange = { state = state.copy(retryDelayMs = it) },
                    )
                }
            }
            onNodeWithTag("download-speed-settings").assertExists()
            onNodeWithTag("download-setting-threads")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
            runOnIdle { assertEquals(7, state.threads) }

            // The last notch is Android's "no cap"; the row renders its own label for it.
            onNodeWithTag("download-setting-series")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(11f) }
            runOnIdle { assertEquals(11, state.maxActiveSeries) }

            // The delay rows snap to Android's 100 ms / 500 ms notches.
            onNodeWithTag("download-setting-request-delay")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(800f) }
            runOnIdle { assertEquals(800, state.requestDelayMs) }
            onNodeWithTag("download-setting-retry-count")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
            runOnIdle { assertEquals(10, state.retryCount) }
            onNodeWithTag("download-setting-retry-delay")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }
            runOnIdle { assertEquals(1500, state.retryDelayMs) }
        }

    @Test
    fun `values clamp to the shared ranges and a disabled section disables its rows`() =
        runDesktopComposeUiTest(width = 760, height = 1400) {
            var state by mutableStateOf(defaults)
            var enabled by mutableStateOf(true)
            setContent {
                DesktopTheme {
                    DownloadSpeedSettingsContent(
                        state = state,
                        onThreadsChange = { state = state.copy(threads = it) },
                        onMaxActiveSeriesChange = { state = state.copy(maxActiveSeries = it) },
                        onRequestDelayChange = { state = state.copy(requestDelayMs = it) },
                        onRetryCountChange = { state = state.copy(retryCount = it) },
                        onRetryDelayChange = { state = state.copy(retryDelayMs = it) },
                        enabled = enabled,
                    )
                }
            }
            onNodeWithTag("download-setting-request-delay")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(99_999f) }
            runOnIdle { assertEquals(5000, state.requestDelayMs) }
            onNodeWithTag("download-setting-retry-delay")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            runOnIdle { assertEquals(500, state.retryDelayMs) }

            runOnIdle { enabled = false }
            onNodeWithTag("download-setting-threads").assertIsNotEnabled()
            onNodeWithTag("download-setting-series").assertIsNotEnabled()
            onNodeWithTag("download-setting-retry-count").assertIsNotEnabled()
        }
}
