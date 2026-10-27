package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.page.PageSummaryAction
import org.skepsun.kototoro.core.ui.page.PageSummaryHeader

/**
 * The shared page-summary card: Android's updates/suggestions/history/notes header, which the desktop suggestions page
 * now draws too instead of a bare title row. These tests pin what the card shows (title, optional subtitle, the action
 * pills' own slugs), that a pill really reports its press, that an icon-only pill still names itself, and that a busy
 * host disables pills instead of dropping them.
 */
@OptIn(ExperimentalTestApi::class)
class PageSummaryHeaderTest {
    /** The cards' icon-only pills need a glyph to name; a path-less vector is enough to hold the description. */
    private val emptyGlyph = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f,
        viewportHeight = 24f).build()
    @Test
    fun `the card shows the title, the subtitle and every action under the host's tag`() {
        runDesktopComposeUiTest(width = 900, height = 400) {
            setContent {
                DesktopTheme {
                    PageSummaryHeader(
                        title = "Discover & For You",
                        subtitle = "Curated recommendations based on reading taste",
                        modifier = Modifier.testTag("summary-card"),
                    ) {
                        PageSummaryAction("Refresh", {}, modifier = Modifier.testTag("action-refresh"))
                        PageSummaryAction(null, {}, imageVector = emptyGlyph,
                            contentDescription = "Check for updates",
                            modifier = Modifier.testTag("action-check"))
                    }
                }
            }
            onNodeWithTag("summary-card").assertExists()
            onNodeWithText("Discover & For You").assertExists()
            onNodeWithText("Curated recommendations based on reading taste").assertExists()
            onNodeWithTag("action-refresh").assertExists()
            // An icon-only pill carries no text, so its description is the only handle a screen reader gets.
            onNodeWithTag("action-check").assertExists()
            onNodeWithContentDescription("Check for updates").assertExists()
        }
    }

    @Test
    fun `a pill reports its press and a disabled pill refuses one`() {
        var presses = 0
        runDesktopComposeUiTest(width = 900, height = 400) {
            setContent {
                DesktopTheme {
                    PageSummaryHeader(title = "Updates", subtitle = null) {
                        PageSummaryAction("Mark all read", { presses++ },
                            emphasized = true, modifier = Modifier.testTag("action-read"))
                        PageSummaryAction("Refresh", { presses += 100 },
                            enabled = false, modifier = Modifier.testTag("action-busy"))
                    }
                }
            }
            onNodeWithTag("action-read").assertIsEnabled().performClick()
            assertEquals(1, presses)
            onNodeWithTag("action-busy").assertIsNotEnabled().performClick()
            assertEquals(1, presses)
        }
    }

    @Test
    fun `a header without a subtitle or icon still renders the title`() {
        runDesktopComposeUiTest(width = 900, height = 400) {
            setContent {
                DesktopTheme { PageSummaryHeader(title = "History", subtitle = null) }
            }
            onNodeWithText("History").assertExists()
        }
    }
}
