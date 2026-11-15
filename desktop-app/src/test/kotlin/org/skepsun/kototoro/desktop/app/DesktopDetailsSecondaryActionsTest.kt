package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.DetailsActionGroup
import org.skepsun.kototoro.core.ui.compose.DetailsActionGroupKey
import org.skepsun.kototoro.core.ui.compose.DetailsActionSelectedKey
import org.skepsun.kototoro.core.ui.compose.DetailsSecondaryAction
import org.skepsun.kototoro.core.ui.compose.DetailsSecondaryActionRow

/**
 * The details page's secondary actions are grouped by the task they belong to and report their state, so the host can
 * tell "how is this work read" apart from "is this work kept" without guessing from the button order.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopDetailsSecondaryActionsTest {

    private val actions = listOf(
        DetailsSecondaryAction(
            key = "favourite",
            label = "已收藏",
            group = DetailsActionGroup.LIBRARY,
            selected = true,
        ),
        DetailsSecondaryAction(
            key = "incognito",
            label = "无痕模式",
            group = DetailsActionGroup.READING,
        ),
        DetailsSecondaryAction(
            key = "share",
            label = "复制链接",
            group = DetailsActionGroup.REFERENCE,
            enabled = false,
        ),
    )

    @Test
    fun `each action keeps its task group, its state and its own tag`() = runDesktopComposeUiTest(width = 720, height = 200) {
        var clicked: String? = null
        setContent {
            DesktopTheme {
                DetailsSecondaryActionRow(actions, onAction = { clicked = it }, tagPrefix = "preview")
            }
        }
        onNodeWithTag("preview-favourite")
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(DetailsActionGroupKey, DetailsActionGroup.LIBRARY))
            .assert(SemanticsMatcher.expectValue(DetailsActionSelectedKey, true))
            .performClick()
        onNodeWithTag("preview-incognito")
            .assert(SemanticsMatcher.expectValue(DetailsActionGroupKey, DetailsActionGroup.READING))
            .assert(SemanticsMatcher.expectValue(DetailsActionSelectedKey, false))
        // A disabled action is drawn but cannot be pressed, which keeps "not available" visible instead of hidden.
        onNodeWithTag("preview-share").assertIsNotEnabled()
        assertEquals("favourite", clicked)
    }

    @Test
    fun `the reading task is ordered before the library task`() = runDesktopComposeUiTest(width = 720, height = 200) {
        setContent {
            DesktopTheme {
                DetailsSecondaryActionRow(actions, onAction = {}, tagPrefix = "preview")
            }
        }
        val reading = onNodeWithTag("preview-incognito").fetchSemanticsNode().boundsInRoot.left
        val library = onNodeWithTag("preview-favourite").fetchSemanticsNode().boundsInRoot.left
        assertEquals(true, reading < library, "reading actions should come first: $reading vs $library")
    }
}
