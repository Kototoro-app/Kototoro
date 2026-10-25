package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourceFilterNode
import org.skepsun.kototoro.core.source.SourceFilterValue

/**
 * Android's filter sheet draws a short option set as chips and a long one as a menu. Windows keeps both, split at five
 * options: these tests pin which control a node gets and what one press reports.
 */
class DesktopFilterChipsTest {

    private fun node(kind: SourceFilterKind, values: List<String>, name: String = "标签") =
        SourceFilterNode(id = "label", name = name, kind = kind, values = values)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a short option set is chips that select in one press`() = runDesktopComposeUiTest(width = 580, height = 420) {
        var reported: SourceFilterValue? = null
        setContent {
            var value by remember { mutableStateOf<SourceFilterValue?>(null) }
            DesktopTheme {
                FilterControl(node(SourceFilterKind.SELECT, listOf("冒险", "恋爱", "科幻")), value, true) {
                    reported = it
                    value = it
                }
            }
        }
        onNodeWithTag("filter:label:option:0").assertIsDisplayed().assertIsNotSelected()
        onNodeWithTag("filter:label:option:1").performClick()
        assertEquals(SourceFilterValue.Choice(1), reported)
        waitForIdle()
        onNodeWithTag("filter:label:option:1").assertIsSelected()
        onNodeWithTag("filter:label:option:2").assertIsNotSelected()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a long option set keeps the menu and its two steps`() = runDesktopComposeUiTest(width = 580, height = 420) {
        var reported: SourceFilterValue? = null
        val values = listOf("冒险", "恋爱", "科幻", "悬疑", "恐怖", "历史", "运动")
        setContent {
            var value by remember { mutableStateOf<SourceFilterValue?>(null) }
            DesktopTheme {
                FilterControl(node(SourceFilterKind.SELECT, values), value, true) {
                    reported = it
                    value = it
                }
            }
        }
        // Only the button is drawn, so no option exists until the menu opens.
        onNodeWithTag("filter:label:option:0").assertDoesNotExist()
        onNodeWithTag("filter:label").assertIsDisplayed().performClick()
        onNodeWithTag("filter:label:option:6").assertIsDisplayed().performClick()
        assertEquals(SourceFilterValue.Choice(6), reported)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a chip sort keeps its direction toggle and its reset`() = runDesktopComposeUiTest(width = 580, height = 420) {
        var reported: SourceFilterValue? = null
        setContent {
            var value by remember { mutableStateOf<SourceFilterValue?>(null) }
            DesktopTheme {
                FilterControl(node(SourceFilterKind.SORT, listOf("名称", "更新"), name = "排序"), value, true) {
                    reported = it
                    value = it
                }
            }
        }
        onNodeWithTag("filter:label:direction").assertIsDisplayed()
        onNodeWithTag("filter:label:option:1").performClick()
        assertEquals(SourceFilterValue.Sort(1, false), reported)
        waitForIdle()
        // The direction toggle only works once an option is picked, as on Android.
        onNodeWithTag("filter:label:direction").performClick()
        assertEquals(SourceFilterValue.Sort(1, true), reported)
        waitForIdle()
        onNodeWithTag("filter:label:clear").performClick()
        assertNull(reported)
    }
}
