package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.DataCleanupContent
import org.skepsun.kototoro.core.ui.settings.DataCleanupRow
import org.skepsun.kototoro.core.ui.settings.DataCleanupSection

/**
 * The shared "data removal" page: Android's four groups with their action rows and the read-chapter switch, drawn from
 * whatever set each host can really perform. The desktop probe drives the real page through the same tags.
 */
@OptIn(ExperimentalTestApi::class)
class DataCleanupContentTest {
    private fun sections(
        onClearCache: () -> Unit,
        onFeed: () -> Unit,
        chaptersEnabled: Boolean,
        autoOn: Boolean,
        onAuto: (Boolean) -> Unit,
    ) = listOf(
        DataCleanupSection(
            key = "cache",
            title = "Cache",
            rows = listOf(
                DataCleanupRow("image-cache", "Clear image cache", summary = "12 MB", onClick = onClearCache),
                DataCleanupRow("page-index", "Clear page cache", summary = null, enabled = false, onClick = {}),
            ),
        ),
        // A section a host cannot perform at all is skipped instead of leaving an empty group behind.
        DataCleanupSection(key = "local_storage", title = "Local storage", rows = emptyList()),
        DataCleanupSection(
            key = "privacy",
            title = "Privacy",
            rows = listOf(DataCleanupRow("feed", "Clear updates", summary = "3", onClick = onFeed)),
        ),
        DataCleanupSection(
            key = "chapters",
            title = "Chapters",
            rows = listOf(
                DataCleanupRow(
                    key = "read-chapters",
                    title = "Delete read chapters",
                    summary = "Removes what the history passed",
                    enabled = chaptersEnabled,
                    onClick = {},
                ),
                DataCleanupRow(
                    key = "read-chapters-auto",
                    title = "Delete read chapters automatically",
                    summary = "Runs on app start",
                    checked = autoOn,
                    onCheckedChange = onAuto,
                ),
            ),
        ),
    )

    @Test
    fun groupsKeepAndroidOrderAndRowsReportTheirOwnState() {
        for (width in listOf(420, 900)) runDesktopComposeUiTest(width = width, height = 900) {
            var cleared = 0
            var feed = 0
            var auto by mutableStateOf(false)
            setContent {
                DesktopTheme {
                    DataCleanupContent(
                        sections = sections({ cleared++ }, { feed++ }, false, auto) { auto = it },
                        header = { Text("Host header") },
                    )
                }
            }
            onNodeWithText("Host header").assertExists()
            onNodeWithText("Cache").assertExists()
            onNodeWithText("Privacy").assertExists()
            onNodeWithText("Chapters").assertExists()
            onNodeWithText("Local storage").assertDoesNotExist()
            onNodeWithTag("cleanup-image-cache").performClick()
            waitForIdle()
            assertEquals(1, cleared)
            // The disabled row reports itself as disabled and never reports a click.
            onNodeWithTag("cleanup-page-index").assertIsNotEnabled()
            onNodeWithTag("cleanup-page-index").performClick()
            waitForIdle()
            assertEquals(1, cleared)
            onNodeWithTag("cleanup-feed").performClick()
            waitForIdle()
            assertEquals(1, feed)
            onNodeWithTag("cleanup-read-chapters").assertIsNotEnabled()
            onNodeWithTag("cleanup-read-chapters-auto").performClick()
            waitForIdle()
            assertTrue(auto)
        }
    }

    @Test
    fun theHostPrefixKeepsProbeTagsOfAnotherPageApart() {
        runDesktopComposeUiTest(width = 600, height = 800) {
            setContent {
                DesktopTheme {
                    DataCleanupContent(
                        sections = sections({}, {}, true, true) {},
                        tagPrefix = "data-cleanup",
                    )
                }
            }
            onNodeWithTag("data-cleanup-image-cache").assertExists()
            onNodeWithTag("data-cleanup-read-chapters").performClick()
            onNodeWithTag("cleanup-image-cache").assertDoesNotExist()
        }
    }
}
