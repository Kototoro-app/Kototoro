package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.*

@OptIn(ExperimentalTestApi::class)
class SharedFilterPanelTest {
    @Test
    fun `facet dropdown stays open while options toggle and resets only what is checked`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var checked by mutableStateOf(emptySet<String>())
            var enabled by mutableStateOf(true)
            var resets = 0
            setContent {
                DesktopTheme {
                    Surface {
                        FilterPanelGroup("Sources") {
                            FilterFacetDropdown(
                                label = if (checked.isEmpty()) "Sources" else checked.sorted().joinToString(),
                                options = listOf("a", "b").map { FilterFacetOption(it, "Source $it", it in checked) },
                                onOptionClick = { checked = if (it in checked) checked - it else checked + it },
                                resetLabel = "Reset",
                                onReset = { resets++; checked = emptySet() },
                                optionIcon = { Text(if (it.checked) "x" else " ") },
                                enabled = enabled,
                                modifier = Modifier.testTag("facet"),
                                optionModifier = { Modifier.testTag("facet:$it") },
                                resetModifier = Modifier.testTag("facet-reset"),
                            )
                        }
                    }
                }
            }
            onNodeWithTag("facet").assertIsNotSelected().performClick()
            onNodeWithTag("facet-reset").assertIsNotEnabled()
            onNodeWithTag("facet:a").performClick()
            onNodeWithTag("facet:b").performClick()
            runOnIdle { assertEquals(setOf("a", "b"), checked) }
            onNodeWithTag("facet").assertIsSelected().assertTextEquals("a, b")
            onNodeWithTag("facet-reset").assertIsEnabled().performClick()
            runOnIdle { assertEquals(emptySet<String>(), checked); assertEquals(1, resets) }
            onNodeWithTag("facet:a").assertExists()
            onNodeWithTag("facet").performTouchInput { click() }
            waitForIdle()
            onNodeWithTag("facet:a").assertDoesNotExist()
            runOnIdle { enabled = false }
            onNodeWithTag("facet").assertIsNotEnabled()
        }

    @Test
    fun `panel header, chips, switch and reset report their actions`() =
        runDesktopComposeUiTest(width = 380, height = 640) {
            var picked by mutableStateOf("grid")
            var switch by mutableStateOf(false)
            var done = 0
            var reset = 0
            setContent {
                DesktopTheme {
                    Surface {
                        Column {
                            FilterPanelHeader("Filter", "Done", { done++ }, doneModifier = Modifier.testTag("done"))
                            FilterPanelGroup("Layout") {
                                CompactFilterChipFlow {
                                    listOf("grid", "list").forEach { mode ->
                                        CompactFilterChip(picked == mode, { picked = mode }, mode,
                                            modifier = Modifier.testTag("chip:$mode"))
                                    }
                                }
                                FilterPanelSwitchRow("Quick filters", switch, { switch = it },
                                    modifier = Modifier.testTag("switch"))
                            }
                            FilterPanelReset("Reset", { reset++ }, modifier = Modifier.testTag("reset"))
                        }
                    }
                }
            }
            onNodeWithTag("chip:grid").assertIsSelected()
            onNodeWithTag("chip:list").assertIsNotSelected().performClick()
            onNodeWithTag("chip:list").assertIsSelected()
            onNodeWithTag("switch").performClick()
            onNodeWithTag("done").performClick()
            onNodeWithTag("reset").performClick()
            runOnIdle {
                assertEquals("list", picked); assertTrue(switch); assertEquals(1, done); assertEquals(1, reset)
            }
        }
}
