package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.wizard.WizardDonePage
import org.skepsun.kototoro.core.ui.wizard.WizardIntroPage
import org.skepsun.kototoro.core.ui.wizard.WizardStartPage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import java.io.File

/**
 * The shared setup-wizard pages: Android's intro and done bodies, which the phone and the Windows host both draw. The
 * desktop probe drives the same pages inside the real wizard screen; these tests pin the page content and the two
 * jumps without the rest of the wizard around them.
 */
@OptIn(ExperimentalTestApi::class)
class SetupWizardContentTest {
    @Test
    fun `start page provides immediate actions at phone width without configuration`() {
        var opened: String? = null
        runDesktopComposeUiTest(width = 390, height = 850) {
            setContent {
                DesktopTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WizardStartPage(1, 4,
                            onAddSources = { opened = "sources" },
                            onRestoreBackup = { opened = "backup" },
                            onStart = { opened = "start" },
                            onImportLocal = { opened = "local" },
                        )
                    }
                }
            }
            listOf("wizard-open-extensions" to "sources", "wizard-import-local" to "local",
                "wizard-restore-backup" to "backup", "wizard-start" to "start").forEach { (tag, expected) ->
                onNodeWithTag(tag).performScrollTo().performClick()
                assertEquals(expected, opened)
            }
            onNodeWithTag("wizard-open-extensions").performScrollTo()
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                val file = File("build/reports/wizard-start-phone.png")
                file.parentFile.mkdirs()
                file.writeBytes(checkNotNull(image.encodeToData()).bytes)
            }
        }
    }

    @Test
    fun `unsupported import is omitted and busy actions are disabled`() {
        runDesktopComposeUiTest(width = 760, height = 1000) {
            setContent {
                DesktopTheme {
                    WizardStartPage(1, 3, {}, {}, {}, enabled = false)
                }
            }
            onNodeWithTag("wizard-import-local").assertDoesNotExist()
            listOf("wizard-open-extensions", "wizard-restore-backup", "wizard-start").forEach {
                onNodeWithTag(it).assertIsNotEnabled()
            }
        }
    }

    @Test
    fun `the intro page repeats Android's welcome text and the disclaimer card`() {
        runDesktopComposeUiTest(width = 760, height = 1000) {
            setContent {
                DesktopTheme {
                    // The wizard page sits in the host's scroller, exactly like the real screen's page body.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WizardIntroPage(step = 1, totalSteps = 3)
                    }
                }
            }
            onNodeWithTag("wizard-step-counter")
                .assertTextEquals(AndroidStrings.format("welcome_step_counter", 1, 3))
            onNodeWithText(AndroidStrings["welcome_intro_app_title"]).assertExists()
            onNodeWithText(AndroidStrings["welcome_intro_body"]).assertExists()
            onNodeWithText(AndroidStrings["welcome_intro_concepts_title"]).assertExists()
            // The three concepts Android explains before its permission page: repository, extension, source.
            listOf(
                "welcome_intro_repo_label" to "welcome_intro_repo_body",
                "welcome_intro_extension_label" to "welcome_intro_extension_body",
                "welcome_intro_source_label" to "welcome_intro_source_body",
            ).forEach { (label, body) ->
                onNodeWithText(AndroidStrings[label]).assertExists()
                onNodeWithText(AndroidStrings[body]).assertExists()
            }
            onNodeWithText(AndroidStrings["welcome_intro_disclaimer"]).assertExists()
        }
    }

    @Test
    fun `the done page offers Android's two ways on and hands them to the host`() {
        var opened: String? = null
        runDesktopComposeUiTest(width = 760, height = 1200) {
            setContent {
                DesktopTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        WizardDonePage(
                            step = 3,
                            totalSteps = 3,
                            onOpenExtensionManagement = { opened = "extensions" },
                            onRestoreBackup = { opened = "backups" },
                        )
                    }
                }
            }
            onNodeWithTag("wizard-step-counter")
                .assertTextEquals(AndroidStrings.format("welcome_step_counter", 3, 3))
            onNodeWithText(AndroidStrings["welcome_done_title_finished"]).assertExists()
            onNodeWithText(AndroidStrings["welcome_done_summary_finished"]).assertExists()
            onNodeWithText(AndroidStrings["welcome_done_body_finished"]).assertExists()
            // The advice card is the last thing Android's wizard says.
            onNodeWithText(AndroidStrings["welcome_done_advice_title"]).assertExists()
            onNodeWithText(AndroidStrings["welcome_done_advice_body"]).assertExists()
            onNodeWithTag("wizard-open-extensions").performScrollTo().performClick()
            assertEquals("extensions", opened)
            onNodeWithTag("wizard-restore-backup").performScrollTo().performClick()
            assertEquals("backups", opened)
        }
    }
}
