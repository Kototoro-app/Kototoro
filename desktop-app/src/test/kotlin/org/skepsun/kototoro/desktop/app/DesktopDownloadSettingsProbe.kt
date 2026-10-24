package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.download.DownloadPolicy
import org.skepsun.kototoro.desktop.runtime.DesktopDownloadSettings
import java.nio.file.Path

/**
 * Android's download settings page on Windows: 更多 → 下载设置 opens it, the five rows start on Android's defaults, a
 * committed slider writes the preference the queue reads (same keys Android uses), the series row can reach Android's
 * "uncapped" value, and back returns to 更多. The queue's own use of these numbers is covered by the unit tests; this
 * probe checks the page edits the same stored value the queue reloads.
 */
internal object DesktopDownloadSettingsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val directory = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(directory) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                fun stored() = runBlocking { session.downloadPreferences.current() }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("download-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DOWNLOAD_SETTINGS }
                idle()

                check(stored() == DesktopDownloadSettings()) { "a fresh data root should hold the defaults: ${stored()}" }
                listOf(
                    "download-setting-threads",
                    "download-setting-series",
                    "download-setting-request-delay",
                    "download-setting-retry-count",
                    "download-setting-retry-delay",
                ).forEach { tag -> onNodeWithTag(tag).performScrollTo().assertExists() }
                onNodeWithTag("downloads-settings-parity").performScrollTo().assertExists()
                println("DESKTOP_DOWNLOAD_SETTINGS=defaults:${stored()}")

                // A committed slider writes through to the store under Android's key names.
                onNodeWithTag("download-setting-threads").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
                waitUntil(timeoutMillis = 15_000) { stored().threads == 7 }
                idle()
                check(runBlocking {
                    session.storage.preferences.open("desktop_download_settings").snapshot()["downloads_threads"] != null
                }) { "the thread count should be persisted, not only in memory" }

                // Retries: the slider's range is the shared policy's, so 10 is reachable and 0 is not.
                onNodeWithTag("download-setting-retry-count").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
                waitUntil(timeoutMillis = 15_000) { stored().retryCount == DownloadPolicy.RETRY_COUNT_MAX }
                idle()

                // Android's "uncapped" is the top of the series range and yields no series limit at all.
                onNodeWithTag("download-setting-series").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) {
                        it(DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED.toFloat())
                    }
                waitUntil(timeoutMillis = 15_000) { stored().maxActiveSeries == DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED }
                idle()
                onNodeWithText(AndroidStrings["download_max_active_series_uncapped"]).assertExists()
                check(stored().activeSeriesLimit == null) { "the uncapped value must not limit the queue" }

                // The spacing: 0 turns the throttle off, and every value is stored in milliseconds as Android does.
                onNodeWithTag("download-setting-request-delay").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(800f) }
                waitUntil(timeoutMillis = 15_000) { stored().requestDelayMs == 800 }
                idle()
                onNodeWithTag("download-setting-retry-delay").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }
                waitUntil(timeoutMillis = 15_000) { stored().retryDelayMs == 1500 }
                idle()
                println("DESKTOP_DOWNLOAD_SETTINGS=edited:${stored()}")

                // The queue reloads the stored value, so a change made on the page is what the next request sees.
                check(controller.downloadSettings.value == stored()) { "the page and the queue disagree" }

                onNodeWithTag("downloads-settings-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
