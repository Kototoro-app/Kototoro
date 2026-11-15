package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Test

/**
 * The add-source dialog classifies a pasted address with the shared rules, so the type chips only remain as an
 * override instead of being the first thing the user has to answer.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSourceAddressDetectionTest {

    @Test
    fun `a source configuration address selects the configuration chip by itself`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            setContent {
                DesktopTheme {
                    DesktopExtensionImportDialog(
                        enabled = true,
                        error = null,
                        onDismiss = {},
                        onRepository = {},
                        onSource = { _, _ -> },
                        onFile = {},
                    )
                }
            }
            onNodeWithTag("extension-add-tvbox").assertIsNotSelected()
            onNodeWithTag("repository-url").performTextInput("https://example.org/sites.json")
            onNodeWithTag("extension-add-tvbox").assertIsSelected()
            onNodeWithTag("extension-add-detected").assertIsDisplayed()
            onNodeWithText(desktopText("extensions.detectedSourceConfig")).assertIsDisplayed()
        }

    @Test
    fun `a repository index address keeps the repository chip and says why`() =
        runDesktopComposeUiTest(width = 900, height = 900) {
            setContent {
                DesktopTheme {
                    DesktopExtensionImportDialog(
                        enabled = true,
                        error = null,
                        onDismiss = {},
                        onRepository = {},
                        onSource = { _, _ -> },
                        onFile = {},
                    )
                }
            }
            onNodeWithTag("repository-url").performTextInput("https://example.org/index.min.json")
            onNodeWithTag("extension-add-tvbox").assertIsNotSelected()
            onNodeWithText(desktopText("extensions.detectedRepository")).assertIsDisplayed()
            // A manual choice still wins for the addresses the rules cannot read.
            onNodeWithTag("repository-url").performTextReplacement("https://example.org/download")
            onNodeWithTag("extension-add-tvbox").performClick()
            onNodeWithTag("extension-add-tvbox").assertIsSelected()
            onNodeWithText(desktopText("extensions.detectedJsonAddress")).assertDoesNotExist()
        }
}
