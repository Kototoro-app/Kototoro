package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTab
import org.skepsun.kototoro.core.ui.extensions.ExtensionManagementTabs

/**
 * Layout guards for the counted source/repository/package tab strip, shared by the Android settings top bar
 * and the desktop extensions page. The strip has to clamp inside whatever slot its host gives it, because the
 * labels grow with the font scale and with the counts; if it ever reported its full content width instead, it
 * would push the host's trailing actions (search/filter/more capsule on Android, add/删除 on desktop) out of
 * the row. The device check at 411dp and 1.5x font showed the labels clipping inside the capsule with a 23px
 * gap to the action capsule, which is the behaviour these cases pin down.
 */
class DesktopExtensionTabsWidthTest {

    private val tabs = listOf(
        ExtensionManagementTab("Sources (0)", 0),
        ExtensionManagementTab("Repositories (12)", 12),
        ExtensionManagementTab("Packages (3)", 3, 2),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tab strip stays inside a narrow slot instead of overflowing it`() = runDesktopComposeUiTest(900, 200) {
        val slot = 200.dp
        setContent {
            MaterialTheme {
                Box(Modifier.width(slot)) {
                    ExtensionManagementTabs(tabs = tabs, selectedTab = 0, onTabClick = {})
                }
            }
        }
        waitForIdle()
        val right = onNodeWithTag("extensions-tabs").getUnclippedBoundsInRoot().right
        assertTrue(right <= slot + 1.dp, "tab strip ends at ${right.value}dp inside a ${slot.value}dp slot")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tab strip still wraps its content inside a wide slot`() = runDesktopComposeUiTest(900, 200) {
        val slot = 860.dp
        setContent {
            MaterialTheme {
                Box(Modifier.width(slot)) {
                    ExtensionManagementTabs(tabs = tabs, selectedTab = 0, onTabClick = {})
                }
            }
        }
        waitForIdle()
        val right = onNodeWithTag("extensions-tabs").getUnclippedBoundsInRoot().right
        assertTrue(right < slot, "tab strip filled the ${slot.value}dp slot (${right.value}dp) instead of wrapping")
        assertTrue(right > 120.dp, "tab strip collapsed to ${right.value}dp")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `trailing buttons keep their slot when the tab strip is wider than the page row`() = runDesktopComposeUiTest(900, 200) {
        val page = 300.dp
        setContent {
            MaterialTheme {
                Row(Modifier.width(page), verticalAlignment = Alignment.CenterVertically) {
                    ExtensionManagementTabs(tabs = tabs, selectedTab = 0, onTabClick = {})
                    Spacer(Modifier.weight(1f))
                    Button({}, modifier = Modifier.testTag("extension-add")) { Text("Add") }
                }
            }
        }
        waitForIdle()
        val addRight = onNodeWithTag("extension-add").getUnclippedBoundsInRoot().right
        val addLeft = onNodeWithTag("extension-add").getUnclippedBoundsInRoot().left
        val tabsRight = onNodeWithTag("extensions-tabs").getUnclippedBoundsInRoot().right
        assertTrue(tabsRight <= addLeft, "tab strip ends at ${tabsRight.value}dp, add button starts at ${addLeft.value}dp")
        assertTrue(addRight <= page + 1.dp, "add button ends at ${addRight.value}dp in a ${page.value}dp page row")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every tab stays clickable when the strip is clipped`() = runDesktopComposeUiTest(900, 200) {
        var clicked = -1
        setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp)) {
                    ExtensionManagementTabs(tabs = tabs, selectedTab = 0, onTabClick = { clicked = it })
                }
            }
        }
        waitForIdle()
        onNodeWithTag("extensions-tab:0").performClick()
        assertEquals(0, clicked)
    }

}
