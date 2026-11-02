package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.stats.ContentStatsBarChart

/**
 * The shared per-work statistics chart (Android's `ContentStatsBarChart`), now drawn by one canvas for both hosts.
 * These tests pin the contract the two pages depend on: the host's own tag lands on the chart (the desktop dialog and
 * its probe look it up), an all-zero or empty series shows the host's empty sentence instead of an empty axis, and a
 * real series draws without throwing at both heights the hosts ask for.
 */
@OptIn(ExperimentalTestApi::class)
class ContentStatsBarChartTest {
    @Test
    fun `an all-zero series shows the host's empty sentence under the host's tag`() {
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    ContentStatsBarChart(
                        values = List(30) { 0 },
                        barColor = MaterialTheme.colorScheme.primary,
                        emptyText = "Nothing read yet",
                        modifier = Modifier.fillMaxWidth().height(200.dp).testTag("chart"),
                    )
                }
            }
            onNodeWithTag("chart").assertExists()
            onNodeWithText("Nothing read yet").assertExists()
        }
    }

    @Test
    fun `an empty series is the same empty state, not a crash`() {
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    ContentStatsBarChart(
                        values = emptyList(),
                        barColor = Color.Red,
                        emptyText = "Nothing read yet",
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        testTag = "chart-tagged",
                    )
                }
            }
            onNodeWithTag("chart-tagged").assertExists()
            onNodeWithText("Nothing read yet").assertExists()
        }
    }

    @Test
    fun `the ninety-day series draws at both host heights`() {
        runDesktopComposeUiTest(width = 700, height = 700) {
            setContent {
                DesktopTheme {
                    androidx.compose.foundation.layout.Column {
                        ContentStatsBarChart(
                            values = List(90) { index -> if (index % 7 == 0) 0 else index },
                            barColor = MaterialTheme.colorScheme.tertiary,
                            emptyText = "Nothing read yet",
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            testTag = "chart-android-height",
                        )
                        ContentStatsBarChart(
                            values = List(90) { index -> if (index % 7 == 0) 0 else index },
                            barColor = MaterialTheme.colorScheme.tertiary,
                            emptyText = "Nothing read yet",
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            testTag = "chart-desktop-height",
                        )
                    }
                }
            }
            onNodeWithTag("chart-android-height").assertExists()
            onNodeWithTag("chart-desktop-height").assertExists()
            // The empty sentence belongs to the zero series only.
            onNodeWithText("Nothing read yet").assertDoesNotExist()
        }
    }
}
