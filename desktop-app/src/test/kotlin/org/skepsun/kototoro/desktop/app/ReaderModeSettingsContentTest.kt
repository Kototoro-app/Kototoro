package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ReaderModeSettingsContentTest {
    private val modes = listOf(PreferenceChoice("PAGED", "Pages"), PreferenceChoice("STRIP", "Strip"))
    private val backgrounds = listOf(PreferenceChoice("AUTO", "Auto"), PreferenceChoice("BLACK", "Black"))

    @Test
    fun `shared reading mode and novel rule actions work in narrow and wide windows`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var state by mutableStateOf(ReaderModeSettingsUiState("PAGED", true, "AUTO"))
            var replacementClicks = 0
            var dictionaryClicks = 0
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ReaderModeSettingsContent(state, modes, backgrounds,
                                { state = state.copy(mode = it) }, { state = state.copy(detectMode = it) },
                                { state = state.copy(background = it) },
                                platformSettings = { Text("Device display controls") })
                            SettingsGroup {
                                NovelRuleSettingsRows({ replacementClicks++ }, { dictionaryClicks++ })
                            }
                        }
                    }
                }
            }
            onNodeWithText("Device display controls").assertExists()
            onNodeWithTag("reader-setting-mode").performClick()
            onNodeWithTag("reader-mode-option:STRIP").performClick()
            onNodeWithTag("reader-setting-background").performClick()
            onNodeWithTag("reader-background-option:BLACK").performClick()
            onNodeWithTag("reader-setting-detect-mode").performClick()
            runOnIdle { assertEquals(ReaderModeSettingsUiState("STRIP", false, "BLACK"), state) }
            onNodeWithTag("reader-setting-replace-rules").performScrollTo().performClick()
            onNodeWithTag("reader-setting-dictionary-rules").performScrollTo().performClick()
            runOnIdle { assertEquals(1, replacementClicks); assertEquals(1, dictionaryClicks) }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-reader-mode-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun `unconfigured Android keys stay unselected and dismissing a choice never writes`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var writes = 0
            setContent {
                DesktopTheme {
                    ReaderModeSettingsContent(ReaderModeSettingsUiState("", true, "UNKNOWN"), modes, backgrounds,
                        { writes++ }, { writes++ }, { writes++ })
                }
            }
            onNodeWithTag("reader-setting-mode").performClick()
            onNodeWithTag("reader-mode-option:PAGED").assertIsNotSelected()
            onNodeWithTag("reader-mode-option:STRIP").assertIsNotSelected()
            onNodeWithTag("reader-mode-close").performClick()
            onNodeWithTag("reader-setting-background").performClick()
            onNodeWithTag("reader-background-option:AUTO").assertIsNotSelected()
            onNodeWithTag("reader-background-close").performClick()
            runOnIdle { assertEquals(0, writes) }
        }

    @Test
    fun `busy state disables preference rows open choices and rule actions`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    Column {
                        ReaderModeSettingsContent(ReaderModeSettingsUiState("PAGED", true, "AUTO"), modes, backgrounds,
                            { writes++ }, { writes++ }, { writes++ }, enabled = enabled)
                        NovelRuleSettingsRows({ writes++ }, { writes++ }, enabled = enabled)
                    }
                }
            }
            onNodeWithTag("reader-setting-mode").performClick()
            runOnIdle { enabled = false }
            onNodeWithTag("reader-mode-option:STRIP").assertIsNotEnabled()
            onNodeWithTag("reader-mode-close").performClick()
            for (tag in listOf("mode", "detect-mode", "background", "replace-rules", "dictionary-rules")) {
                onNodeWithTag("reader-setting-$tag").assertIsNotEnabled()
            }
            runOnIdle { assertEquals(0, writes) }
        }
}
