package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.topbar.SourceResultsTopBar
import org.skepsun.kototoro.core.ui.topbar.TopBarActionItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalTestApi::class)
class SharedSourceResultsTopBarTest {
    private val clicks = mutableListOf<String>()

    private fun ComposeUiTest.bar(width: Int, resetShown: Boolean = false) = setContent {
        DesktopTheme {
            Surface {
                SourceResultsTopBar(
                    title = "Source",
                    onBack = { clicks += "back" },
                    backIcon = { Text("<") },
                    backLabel = "Back",
                    actions = listOfNotNull(
                        TopBarActionItem("sort", rememberVectorPainter(ReaderPanelIcons.SortDesc), "Sort",
                            modifier = Modifier.testTag("sort"), menu = { dismiss ->
                                CompactDropdownMenuItem({ CompactDropdownMenuText("Popular") },
                                    { dismiss(); clicks += "popular" }, modifier = Modifier.testTag("popular"))
                            }),
                        TopBarActionItem("search", rememberVectorPainter(ReaderPanelIcons.Search), "Search",
                            { clicks += "search" }, modifier = Modifier.testTag("search")),
                        TopBarActionItem("settings", rememberVectorPainter(ReaderPanelIcons.Settings), "Settings",
                            { clicks += "settings" }, minWidth = 532.dp, modifier = Modifier.testTag("settings")),
                        TopBarActionItem("reset", rememberVectorPainter(ReaderPanelIcons.Delete), "Reset",
                            { clicks += "reset" }, overflowOnly = true, dividerBefore = true,
                            modifier = Modifier.testTag("reset")).takeIf { resetShown },
                    ),
                    moreIcon = rememberVectorPainter(ReaderPanelIcons.MoreVert),
                    moreLabel = "More",
                    surface = { modifier, content -> Surface(modifier) { androidx.compose.foundation.layout.Box { content() } } },
                    menuHost = { expanded, onDismiss, _, content -> DropdownMenu(expanded, onDismiss, content = content) },
                    backModifier = Modifier.testTag("back"),
                    moreModifier = Modifier.testTag("more"),
                    modifier = Modifier.testTag("bar:$width"),
                )
            }
        }
    }

    @Test
    fun `wide bars keep every action in the capsule and the sort button opens its own menu`() =
        runDesktopComposeUiTest(width = 760, height = 400) {
            bar(760)
            onNodeWithTag("settings").assertExists()
            onNodeWithTag("more").assertDoesNotExist()
            onNodeWithContentDescription("Search").performClick()
            onNodeWithTag("sort").performClick()
            onNodeWithTag("popular").performClick()
            onNodeWithTag("popular").assertDoesNotExist()
            onNodeWithTag("back").performClick()
            runOnIdle { assertEquals(listOf("search", "popular", "back"), clicks) }
        }

    @Test
    fun `narrow bars fold low priority actions into more with the overflow-only reset`() =
        runDesktopComposeUiTest(width = 380, height = 400) {
            bar(380, resetShown = true)
            onNodeWithTag("settings").assertDoesNotExist()
            onNodeWithTag("reset").assertDoesNotExist()
            onNodeWithTag("sort").assertExists()
            onNodeWithTag("more").performClick()
            onNodeWithTag("settings").assertExists()
            onNodeWithTag("reset").performClick()
            onNodeWithTag("reset").assertDoesNotExist()
            runOnIdle { assertEquals(listOf("reset"), clicks) }
        }
}
