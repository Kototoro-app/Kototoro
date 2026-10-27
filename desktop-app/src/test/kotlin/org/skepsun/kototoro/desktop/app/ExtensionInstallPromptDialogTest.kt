package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallPromptDialog
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ExtensionInstallPromptDialogTest {
    @Test
    fun installationChoicesKeepRememberedAnswerAndCancelNeverInstalls() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var visible by mutableStateOf(true)
            var remember by mutableStateOf(false)
            var count by mutableIntStateOf(3)
            var dismissals = 0
            val choices = mutableListOf<Pair<Boolean, Boolean>>()
            setContent {
                DesktopTheme {
                    if (visible) ExtensionInstallPromptDialog("Fixture", count, "JAR", remember,
                        { remember = it }, { choices += it to remember; visible = false },
                        { dismissals++; visible = false })
                }
            }
            onNodeWithText(AndroidStrings.format("extension_install_choice_message", 3)).assertExists()
            onNodeWithTag("install-prompt-remember").assertIsOff().performClick().assertIsOn()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-install-prompt-$width.png")
            Image.makeFromBitmap(onNodeWithTag("install-prompt-dialog").captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("install-prompt-only").performClick()
            runOnIdle {
                assertEquals(listOf(false to true), choices)
                remember = false; count = 0; visible = true
            }
            onNodeWithText(AndroidStrings["extension_install_choice_message_unknown"]).assertExists()
            onNodeWithTag("install-prompt-enable").performClick()
            runOnIdle {
                assertEquals(listOf(false to true, true to false), choices)
                visible = true
            }
            onNodeWithTag("install-prompt-cancel").performClick()
            runOnIdle { assertEquals(1, dismissals); assertEquals(2, choices.size) }
        }
    }

    @Test
    fun disabledPromptBlocksEditsAndChoicesButStillAllowsCancel() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var remember by mutableStateOf(false)
            var writes = 0
            var cancelled = 0
            setContent {
                DesktopTheme {
                    ExtensionInstallPromptDialog("Fixture", -1, "JAR", remember, { writes++ },
                        { writes++ }, { cancelled++ }, enabled = false)
                }
            }
            onNodeWithTag("install-prompt-remember").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("install-prompt-enable").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("install-prompt-only").assertIsNotEnabled().performTouchInput { click() }
            runOnIdle { remember = true }
            onNodeWithTag("install-prompt-remember").assertIsOn()
            onNodeWithTag("install-prompt-cancel").performClick()
            runOnIdle { assertEquals(0, writes); assertEquals(1, cancelled) }
        }
}
