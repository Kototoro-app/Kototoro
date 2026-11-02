package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopTriState

/**
 * Android's `IncognitoModeDialog` asks an adult-work reader whether to record anything. Windows asks the same question
 * before opening the reader, so the answers and the "don't ask again" checkbox have to reach the controller intact.
 */
class DesktopIncognitoTest {

    private fun prompt(dontAskAgain: Boolean = false) = DesktopIncognitoPrompt(
        DesktopReadingRequest.Chapter(SourceChapter(id = 1, title = "Chapter 1", number = 1f, volume = 0, url = "/c1",
            scanlator = null, uploadDate = 0, branch = null, source = SourceRef("MIHON_1", "en", "MANGA"))),
        dontAskAgain,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the question offers Android's three answers and reports the picked one`() = runDesktopComposeUiTest {
        var answered: Boolean? = null
        var dismissed = false
        setContent {
            DesktopTheme {
                DesktopIncognitoDialog(prompt(), enabled = true, onAnswer = { answered = it },
                    onDontAskAgain = {}, onDismiss = { dismissed = true })
            }
        }
        onNodeWithTag("incognito-dialog").assertIsDisplayed()
        onNodeWithText(AndroidStrings["incognito_mode_hint_nsfw"]).assertIsDisplayed()
        onNodeWithTag("incognito-cancel").performClick()
        assertTrue(dismissed)
        assertEquals(null, answered)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `incognito and disable answer the question instead of dismissing it`() = runDesktopComposeUiTest {
        val answers = mutableListOf<Boolean>()
        var dismissed = false
        setContent {
            DesktopTheme {
                DesktopIncognitoDialog(prompt(), enabled = true, onAnswer = { answers += it },
                    onDontAskAgain = {}, onDismiss = { dismissed = true })
            }
        }
        onNodeWithTag("incognito-accept").performClick()
        onNodeWithTag("incognito-disable").performClick()
        assertEquals(listOf(true, false), answers)
        assertFalse(dismissed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the don't ask again row reflects and reports its checkbox`() = runDesktopComposeUiTest {
        var state by mutableStateOf(prompt())
        setContent {
            DesktopTheme {
                DesktopIncognitoDialog(state, enabled = true, onAnswer = {},
                    onDontAskAgain = { state = state.copy(dontAskAgain = it) }, onDismiss = {})
            }
        }
        onNodeWithTag("incognito-dont-ask").assertIsOff()
        onNodeWithTag("incognito-dont-ask").performClick()
        assertTrue(state.dontAskAgain)
        // The checkbox follows the stored flag, so the dialog cannot show a stale tick.
        onNodeWithTag("incognito-dont-ask").assertIsOn()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the details button reads as Android's dock item`() = runDesktopComposeUiTest {
        var clicked = 0
        setContent { DesktopTheme { DesktopIncognitoReadingButton(true, { clicked++ }) } }
        onNodeWithText(AndroidStrings["incognito"]).assertIsDisplayed()
        onNodeWithTag("preview-incognito").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `only an incognito session stops the automatic saves`() {
        assertTrue(DesktopAppState().savesProgress)
        assertFalse(DesktopAppState(readerIncognito = true).savesProgress)
        // Leaving the reader keeps the flag until the next open sets it, and the readers re-check the screen first.
        assertFalse(DesktopAppState(screen = DesktopScreen.HISTORY, readerIncognito = true).savesProgress)
    }

    @Test
    fun `the stored tri-state names match Android's option names`() {
        assertEquals(listOf("ENABLED", "ASK", "DISABLED"), DesktopTriState.entries.map { it.name })
    }
}
