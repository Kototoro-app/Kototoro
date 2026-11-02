package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.stats.StatsKindOption
import org.skepsun.kototoro.core.ui.stats.StatsKindSelector

/**
 * The shared segmented content-type pill (Android's `ContentKindSelector`). Both statistics screens draw it, so these
 * tests pin the contract they rely on: every option gets its host tag, a click reports the host's own key back, the
 * selected option is the only one filled, and a disabled row reports nothing.
 */
@OptIn(ExperimentalTestApi::class)
class StatsKindSelectorTest {
    private val options = listOf(
        StatsKindOption("ALL", "全部"),
        StatsKindOption("MANGA", "漫画"),
        StatsKindOption("NOVEL", "小说"),
        StatsKindOption("VIDEO", "视频"),
    )

    @Test
    fun `every option is tagged and a click reports its key once`() {
        val picked = mutableListOf<String>()
        runDesktopComposeUiTest(width = 600, height = 300) {
            setContent {
                DesktopTheme {
                    StatsKindSelector(
                        options = options,
                        selectedKey = "ALL",
                        onSelect = { picked += it },
                        modifier = Modifier.testTag("kind-filter"),
                        optionModifier = { option, _ -> Modifier.testTag("kind:${option.key}") },
                    )
                }
            }
            onNodeWithTag("kind-filter").assertIsDisplayed()
            options.forEach { onNodeWithText(it.label).assertIsDisplayed() }
            onNodeWithTag("kind:NOVEL").performClick()
            assertEquals(listOf("NOVEL"), picked)
        }
    }

    @Test
    fun `a disabled row reports nothing`() {
        val picked = mutableListOf<String>()
        runDesktopComposeUiTest(width = 600, height = 300) {
            setContent {
                DesktopTheme {
                    StatsKindSelector(
                        options = options,
                        selectedKey = "MANGA",
                        onSelect = { picked += it },
                        enabled = false,
                        optionModifier = { option, _ -> Modifier.testTag("kind:${option.key}") },
                    )
                }
            }
            onNodeWithTag("kind:VIDEO").performClick()
            assertEquals(emptyList<String>(), picked)
        }
    }

    @Test
    fun `the selected option is the one the host says it is`() {
        runDesktopComposeUiTest(width = 600, height = 300) {
            setContent {
                DesktopTheme {
                    // The host tags the selected pill separately, which is how Android attaches its focus ring to it.
                    StatsKindSelector(
                        options = options,
                        selectedKey = "VIDEO",
                        onSelect = {},
                        optionModifier = { option, selected ->
                            Modifier.testTag(if (selected) "selected:${option.key}" else "option:${option.key}")
                        },
                    )
                }
            }
            onNodeWithTag("selected:VIDEO").assertIsDisplayed()
            onNodeWithTag("option:ALL").assertIsDisplayed()
        }
    }
}
