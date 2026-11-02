package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.compose.ContentCollectionHeader

/**
 * The shared section header: Android's bookmarks page puts a clickable title with a chevron over each work, the Windows
 * shelf puts the bookmark count and a "more" button there. These tests pin that the row is only clickable when the host
 * asks for it, so the Windows shelf cannot start navigating from the heading by accident.
 */
@OptIn(ExperimentalTestApi::class)
class ContentCollectionHeaderTest {
    @Test
    fun `the title is shown alone when the host passes no meta or trailing`() = runDesktopComposeUiTest(
        width = 600,
        height = 200,
    ) {
        setContent { DesktopTheme { ContentCollectionHeader(title = "Witch Hat Atelier") } }
        onNodeWithText("Witch Hat Atelier").assertIsDisplayed()
    }

    @Test
    fun `the meta label and the trailing slot both render`() = runDesktopComposeUiTest(width = 600, height = 200) {
        var clicked = 0
        setContent {
            DesktopTheme {
                ContentCollectionHeader(
                    title = "Witch Hat Atelier",
                    metaLabel = "2 个书签",
                    trailing = {
                        TextButton({ clicked++ }, modifier = Modifier.testTag("header-more")) { Text("更多") }
                    },
                )
            }
        }
        onNodeWithText("2 个书签").assertIsDisplayed()
        onNodeWithTag("header-more").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `the heading is clickable only when the host passes a click handler`() = runDesktopComposeUiTest(
        width = 600,
        height = 200,
    ) {
        var clicked = 0
        setContent {
            DesktopTheme {
                ContentCollectionHeader(
                    title = "Witch Hat Atelier",
                    onClick = { clicked++ },
                    modifier = Modifier.testTag("header-clickable"),
                )
            }
        }
        onNodeWithTag("header-clickable").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `a header without a click handler does not take the click`() = runDesktopComposeUiTest(
        width = 600,
        height = 200,
    ) {
        setContent {
            DesktopTheme {
                ContentCollectionHeader(
                    title = "Witch Hat Atelier",
                    modifier = Modifier.testTag("header-passive"),
                )
            }
        }
        // No onClick: the row stays plain text, so the platform never offers it as a target.
        onNodeWithTag("header-passive").assertHasNoClickAction()
        onNodeWithText("Witch Hat Atelier").assertIsDisplayed()
    }
}
