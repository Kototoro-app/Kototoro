package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.crashlog.CrashLogEntry
import org.skepsun.kototoro.core.ui.crashlog.CrashLogReportContent
import org.skepsun.kototoro.core.ui.crashlog.CrashLogsContent

/**
 * The shared crash-log presentation: Android's list body and report body, which the phone's two screens and the
 * desktop's page-and-dialog both draw. The desktop probe drives the same rows inside the real screen; these tests pin
 * the row content, the three states and the report's selectable text without the file directory behind them.
 */
@OptIn(ExperimentalTestApi::class)
class CrashLogsContentTest {
    private val entries = listOf(
        CrashLogEntry(
            id = "crash_20261027_120000.log",
            title = "2026-10-27 12:00:00",
            summary = "java.lang.IllegalStateException: second report",
            sizeLabel = "2.0 KB",
        ),
        CrashLogEntry(
            id = "crash_20261027_110000.log",
            title = "2026-10-27 11:00:00",
            summary = "java.lang.NullPointerException: first report",
        ),
    )

    @Test
    fun `rows carry the timestamp, the exception line and the host's size, and hand the entry back`() {
        var opened: CrashLogEntry? = null
        runDesktopComposeUiTest(width = 820, height = 700) {
            setContent {
                DesktopTheme {
                    CrashLogsContent(
                        entries = entries,
                        loading = false,
                        emptyText = desktopText("crashLogs.empty"),
                        enabled = true,
                        onOpen = { opened = it },
                    )
                }
            }
            onNodeWithText("2026-10-27 12:00:00").assertExists()
            onNodeWithText("java.lang.IllegalStateException: second report").assertExists()
            onNodeWithText("2.0 KB").assertExists()
            // The second report has no size label, so Android's layout stays exactly two lines for it.
            onNodeWithText("java.lang.NullPointerException: first report").assertExists()
            onNodeWithTag("crash-log:crash_20261027_120000.log").performClick()
            assertEquals(entries.first(), opened)
        }
    }

    @Test
    fun `an empty directory and an unread one say different things`() {
        runDesktopComposeUiTest(width = 820, height = 400) {
            setContent {
                DesktopTheme {
                    CrashLogsContent(
                        entries = emptyList(),
                        loading = false,
                        emptyText = desktopText("crashLogs.empty"),
                        enabled = true,
                        onOpen = {},
                        emptyTag = "crash-logs-empty",
                    )
                }
            }
            onNodeWithTag("crash-logs-empty").assertExists()
            onNodeWithText(desktopText("crashLogs.empty")).assertExists()
        }
        runDesktopComposeUiTest(width = 820, height = 400) {
            setContent {
                DesktopTheme {
                    CrashLogsContent(
                        entries = emptyList(),
                        loading = true,
                        emptyText = desktopText("crashLogs.empty"),
                        loadingText = desktopText("crashLogs.loading"),
                        enabled = true,
                        onOpen = {},
                        loadingTag = "crash-logs-loading",
                    )
                }
            }
            onNodeWithTag("crash-logs-loading").assertExists()
            onNodeWithText(desktopText("crashLogs.loading")).assertExists()
            onNodeWithText(desktopText("crashLogs.empty")).assertDoesNotExist()
        }
    }

    @Test
    fun `a busy host disables the rows instead of dropping them`() {
        var opened: CrashLogEntry? = null
        runDesktopComposeUiTest(width = 820, height = 700) {
            setContent {
                DesktopTheme {
                    CrashLogsContent(
                        entries = entries,
                        loading = false,
                        emptyText = desktopText("crashLogs.empty"),
                        enabled = false,
                        onOpen = { opened = it },
                    )
                }
            }
            onNodeWithTag("crash-log:crash_20261027_110000.log").performClick()
            assertNull(opened)
        }
    }

    @Test
    fun `the report body shows the whole text under the probe's tag`() {
        val text = "=== Crash Report ===\njava.lang.IllegalStateException: second report\n\tat org.example.Main.run(Main.kt:15)"
        runDesktopComposeUiTest(width = 700, height = 500) {
            setContent {
                DesktopTheme {
                    Column(Modifier.fillMaxSize().width(680.dp)) {
                        CrashLogReportContent(text)
                    }
                }
            }
            onNodeWithTag("crash-log-text").assertExists()
            onNodeWithText(text).assertExists()
        }
    }
}
