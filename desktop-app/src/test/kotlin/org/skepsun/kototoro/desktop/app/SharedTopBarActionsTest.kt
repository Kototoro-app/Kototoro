package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.topbar.SourceTypeMenuEntry
import org.skepsun.kototoro.core.ui.topbar.SourceTypeMenuItems
import org.skepsun.kototoro.core.ui.topbar.TopBarIconAction
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

@OptIn(ExperimentalTestApi::class)
class SharedTopBarActionsTest {
    @Test
    fun `source type rows report picks and the all row and skip disabled types`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var selected by mutableStateOf(emptySet<String>())
            val picks = mutableListOf<String?>()
            setContent {
                DesktopTheme {
                    Surface {
                        Column {
                            SourceTypeMenuItems(
                                allLabel = "All",
                                entries = listOf("mihon", "tvbox").map {
                                    SourceTypeMenuEntry(it, it, rememberVectorPainter(ReaderPanelIcons.Search),
                                        selected = it in selected, enabled = it != "tvbox")
                                },
                                onSelect = { picks += it; selected = if (it == null) emptySet() else selected + it },
                                allModifier = Modifier.testTag("all"),
                                entryModifier = { Modifier.testTag("type:$it") },
                            )
                        }
                    }
                }
            }
            onNodeWithTag("type:tvbox").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("type:mihon").performClick()
            runOnIdle { assertEquals(listOf<String?>("mihon"), picks) }
            onNodeWithTag("all").performClick()
            runOnIdle { assertEquals(listOf("mihon", null), picks) }
            onNodeWithTag("type:mihon").assertIsEnabled()
        }

    @Test
    fun `icon action reports its label and click and honours disabled`() =
        runDesktopComposeUiTest(width = 380, height = 200) {
            var enabled by mutableStateOf(true)
            var clicks = 0
            setContent {
                DesktopTheme {
                    Surface {
                        TopBarIconAction(rememberVectorPainter(ReaderPanelIcons.MoreVert), "More", { clicks++ },
                            active = true, badge = true, enabled = enabled, modifier = Modifier.testTag("action"))
                    }
                }
            }
            onNodeWithContentDescription("More").assertExists()
            onNodeWithTag("action").performClick()
            runOnIdle { assertEquals(1, clicks); enabled = false }
            onNodeWithTag("action").assertIsNotEnabled().performTouchInput { click() }
            runOnIdle { assertEquals(1, clicks) }
        }
}
