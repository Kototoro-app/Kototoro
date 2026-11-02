package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.*
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsMultiChoicePreference
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class PreferenceMultiChoiceDialogTest {
    private val options = listOf(PreferenceChoice("A", "Manga sources"), PreferenceChoice("B", "Novel sources"))

    @Test
    fun draftPreservesUnknownKeysAndLimitsSelections() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var selected by mutableStateOf(setOf("future"))
            var visible by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    if (visible) PreferenceMultiChoiceDialog("Adult content filtering", selected, options,
                        { selected = it; writes++ }, { visible = false }, maxSelections = 2, tagPrefix = "adult")
                }
            }
            onNodeWithTag("adult-option:A").performClick().assertIsOn()
            onNodeWithTag("adult-option:B").assertIsNotEnabled()
            runOnIdle { assertEquals(setOf("future"), selected); assertEquals(0, writes) }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-multi-choice-" + width + ".png")
            Image.makeFromBitmap(onNode(isRoot() and hasAnyDescendant(hasTestTag("adult-dialog"))).captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("adult-cancel").performClick()
            runOnIdle { visible = true }
            onNodeWithTag("adult-option:A").assertIsOff().performClick()
            onNodeWithTag("adult-save").performClick()
            runOnIdle { assertEquals(setOf("future", "A"), selected); assertEquals(1, writes); assertFalse(visible) }
        }
    }

    @Test
    fun liveEditorUsesAcknowledgedHostStateAndAllowsClosingWhenBusy() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var selected by mutableStateOf(setOf("future"))
            var enabled by mutableStateOf(true)
            var closed = false
            val writes = mutableListOf<Set<String>>()
            setContent {
                DesktopTheme {
                    PreferenceMultiChoiceDialog("Source preferences", selected, options, { writes += it },
                        { closed = true }, enabled = enabled, commitOnToggle = true, tagPrefix = "live")
                }
            }
            onNodeWithTag("live-save").assertDoesNotExist()
            onNodeWithTag("live-option:A").performClick().assertIsOff()
            runOnIdle { assertEquals(listOf(setOf("future", "A")), writes); selected = writes.single() }
            onNodeWithTag("live-option:A").assertIsOn().performClick()
            runOnIdle { assertEquals(setOf("future"), writes.last()); enabled = false }
            onNodeWithTag("live-option:B").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("live-dialog-close").performClick()
            runOnIdle { assertEquals(2, writes.size); assertTrue(closed) }
        }

    @Test
    fun androidPreferenceKeepsPrivateDraftAndDisablesAnOpenEditor() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var selected by mutableStateOf(emptySet<String>())
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    SettingsMultiChoicePreference("Choose sources", selected,
                        options.map { SettingsChoiceOption(it.value, it.label) }, "None", enabled = enabled,
                        onValueChange = { selected = it; writes++ })
                }
            }
            onNodeWithText("Choose sources").performClick()
            onNodeWithTag("preference-multi-choice-option:A").performClick()
            runOnIdle { assertEquals(0, writes); enabled = false }
            onNodeWithTag("preference-multi-choice-option:B").assertIsNotEnabled()
            onNodeWithTag("preference-multi-choice-save").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("preference-multi-choice-cancel").performClick()
            runOnIdle { enabled = true }
            onNodeWithText("Choose sources").performClick()
            onNodeWithTag("preference-multi-choice-option:A").assertIsOff().performClick()
            onNodeWithTag("preference-multi-choice-save").performClick()
            runOnIdle { assertEquals(setOf("A"), selected); assertEquals(1, writes) }
        }
}
