package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestProgressHeader
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestResultRow
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestStepLine
import org.skepsun.kototoro.core.ui.sourcetest.SourceTestVerdict

/**
 * The shared source-test presentation: Android's dialog and the Windows page draw the same progress block and the same
 * expandable result rows. These tests pin what the Windows probe looks up (the row, verdict and step tags) and the
 * expansion behaviour both hosts rely on.
 */
@OptIn(ExperimentalTestApi::class)
class SourceTestContentTest {
    private val passing = SourceTestResultLine(
        key = "source-a",
        title = "Local CMS",
        verdict = SourceTestVerdict.PASS,
        verdictLabel = "Passed",
        trailingLabel = "6 steps",
        steps = listOf(
            SourceTestStepLine("LIST", "Main list", SourceTestVerdict.PASS, "Passed", 12),
            SourceTestStepLine("PAGES", "Pages and image", SourceTestVerdict.PASS, "Passed", 40),
        ),
    )
    private val failing = SourceTestResultLine(
        key = "source-b",
        title = "Broken site",
        verdict = SourceTestVerdict.FAIL,
        verdictLabel = "Failed",
        steps = listOf(
            SourceTestStepLine("PAGES", "Pages and image", SourceTestVerdict.FAIL, "Failed", 30, "Stream failed: HTTP 500"),
        ),
    )

    @Test
    fun `the collapsed row shows the title, verdict and trailing count`() {
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    SourceTestResultRow(
                        result = passing,
                        expanded = false,
                        onToggle = {},
                        rowTag = "source-test-result:${passing.key}",
                        verdictTag = "source-test-verdict:${passing.key}",
                    )
                }
            }
            onNodeWithTag("source-test-result:source-a").assertIsDisplayed()
            onNodeWithTag("source-test-verdict:source-a", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Passed").assertIsDisplayed()
            onNodeWithText("6 steps").assertIsDisplayed()
            onNodeWithText("Main list").assertDoesNotExist()
        }
    }

    @Test
    fun `expanding shows every step with its verdict, duration and message`() {
        var expanded = true
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    SourceTestResultRow(
                        result = failing,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        verdictTag = "source-test-verdict:${failing.key}",
                        stepTag = { step -> "source-test-step:${failing.key}:$step" },
                    )
                }
            }
            onNodeWithTag("source-test-step:source-b:PAGES", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Pages and image").assertIsDisplayed()
            onNodeWithText("30 ms").assertIsDisplayed()
            onNodeWithText("Stream failed: HTTP 500").assertIsDisplayed()
            onNodeWithText("Failed").assertIsDisplayed()
        }
    }

    @Test
    fun `a disabled verdict keeps the row but reports the host label`() {
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    SourceTestResultRow(
                        result = passing.copy(disabled = true, verdictLabel = "Disabled"),
                        expanded = false,
                        onToggle = {},
                        verdictTag = "source-test-verdict:source-a",
                    )
                }
            }
            onNodeWithText("Disabled").assertIsDisplayed()
        }
    }

    @Test
    fun `the progress header carries the tags the probe and the dialog look for`() {
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    SourceTestProgressHeader(
                        done = 1,
                        total = 2,
                        progressLabel = "1 / 2 checked",
                        runningLabel = "Broken site",
                        summaryLabel = "1 available, 1 unavailable, 0 Cloudflare",
                        cancelledNote = "The run was cancelled",
                        barTag = "source-test-bar",
                        progressTag = "source-test-progress",
                        summaryTag = "source-test-summary",
                        cancelledTag = "source-test-cancelled",
                    )
                }
            }
            onNodeWithTag("source-test-bar").assertIsDisplayed()
            onNodeWithTag("source-test-progress").assertIsDisplayed()
            onNodeWithTag("source-test-summary").assertIsDisplayed()
            onNodeWithTag("source-test-cancelled").assertIsDisplayed()
            onNodeWithText("1 / 2 checked").assertIsDisplayed()
            onNodeWithText("The run was cancelled").assertIsDisplayed()
        }
    }

    @Test
    fun `clicking the row toggles it through the host callback`() {
        var toggles = 0
        runDesktopComposeUiTest(width = 700, height = 400) {
            setContent {
                DesktopTheme {
                    SourceTestResultRow(
                        result = passing,
                        expanded = false,
                        onToggle = { toggles++ },
                        rowTag = "source-test-result:source-a",
                    )
                }
            }
            onNodeWithTag("source-test-result:source-a").performClick()
            assertEquals(1, toggles)
        }
    }
}
