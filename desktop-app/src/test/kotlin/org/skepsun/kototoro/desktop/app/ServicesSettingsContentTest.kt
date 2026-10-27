package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsAction
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsContent
import org.skepsun.kototoro.core.ui.settings.ServicesSettingsUiState

/** The services page Android and desktop now share: the switch writes, Android's optional rows and the split row. */
@OptIn(ExperimentalTestApi::class)
class ServicesSettingsContentTest {
    private val windowsState = ServicesSettingsUiState(
        suggestionsEnabled = false,
        relatedContent = true,
        statsEnabled = true,
        readingTimeEstimation = true,
    )

    @Test
    fun `rows write host state and Android-only rows follow their slots`() =
        runDesktopComposeUiTest(width = 760, height = 900) {
            var state by mutableStateOf(windowsState)
            var suggestionsClicks = 0
            var statsClicks = 0
            var discordClicks = 0
            setContent {
                DesktopTheme {
                    ServicesSettingsContent(
                        state = state,
                        onSuggestionsClick = { suggestionsClicks++ },
                        onRelatedContentChange = { state = state.copy(relatedContent = it) },
                        onStatsClick = { statsClicks++ },
                        onStatsEnabledChange = { state = state.copy(statsEnabled = it) },
                        onReadingTimeChange = { state = state.copy(readingTimeEstimation = it) },
                        discordSettings = ServicesSettingsAction("Discord RPC", "show presence") { discordClicks++ },
                    )
                }
            }
            onNodeWithTag("services-settings-content").assertExists()
            // Windows has no browse-tracking carousel, so its two rows (and the nested one) are not drawn.
            onNodeWithTag("services-browse-tracking").assertDoesNotExist()
            onNodeWithTag("services-browse-more-tracking").assertDoesNotExist()

            onNodeWithTag("services-suggestions").performClick()
            runOnIdle { assertEquals(1, suggestionsClicks) }

            onNodeWithTag("services-related-manga").performClick()
            runOnIdle { assertEquals(false, state.relatedContent) }

            // Android's split row: the switch alone flips recording, the label opens the statistics screen.
            onNodeWithTag("services-stats-enabled").performClick()
            runOnIdle { assertEquals(false, state.statsEnabled) }
            onNodeWithTag("services-stats-open").performClick()
            runOnIdle { assertEquals(1, statsClicks) }

            onNodeWithTag("services-reading-time").performScrollTo().performClick()
            runOnIdle { assertEquals(false, state.readingTimeEstimation) }

            onNodeWithTag("services-discord").performScrollTo().performClick()
            runOnIdle { assertEquals(1, discordClicks) }
        }

    @Test
    fun `Android's browse-tracking pair appears when the host supplies it`() =
        runDesktopComposeUiTest(width = 760, height = 900) {
            var state by mutableStateOf(windowsState.copy(browseTracking = true, browseMoreTracking = true))
            setContent {
                DesktopTheme {
                    ServicesSettingsContent(
                        state = state,
                        onSuggestionsClick = {},
                        onRelatedContentChange = { state = state.copy(relatedContent = it) },
                        onStatsClick = {},
                        onStatsEnabledChange = { state = state.copy(statsEnabled = it) },
                        onReadingTimeChange = { state = state.copy(readingTimeEstimation = it) },
                        onBrowseTrackingChange = { state = state.copy(browseTracking = it) },
                        onBrowseMoreTrackingChange = { state = state.copy(browseMoreTracking = it) },
                    )
                }
            }
            onNodeWithTag("services-browse-tracking").assertExists()
            onNodeWithTag("services-browse-more-tracking").assertExists()
            // Discord is a host action; without it there is no row.
            onNodeWithTag("services-discord").assertDoesNotExist()

            onNodeWithTag("services-browse-more-tracking").performClick()
            runOnIdle { assertEquals(false, state.browseMoreTracking) }
            onNodeWithTag("services-browse-tracking").performClick()
            runOnIdle { assertEquals(false, state.browseTracking) }
            // The nested row only exists while its parent switch is on.
            onNodeWithTag("services-browse-more-tracking").assertDoesNotExist()
        }

    @Test
    fun `a disabled page disables its rows`() =
        runDesktopComposeUiTest(width = 760, height = 900) {
            setContent {
                DesktopTheme {
                    ServicesSettingsContent(
                        state = windowsState,
                        onSuggestionsClick = {},
                        onRelatedContentChange = {},
                        onStatsClick = {},
                        onStatsEnabledChange = {},
                        onReadingTimeChange = {},
                        enabled = false,
                    )
                }
            }
            onNodeWithTag("services-suggestions").assertIsNotEnabled()
            onNodeWithTag("services-related-manga").assertIsNotEnabled()
            onNodeWithTag("services-stats-enabled").assertIsNotEnabled()
            onNodeWithTag("services-reading-time").performScrollTo().assertIsNotEnabled()
        }
}
