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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsContent
import org.skepsun.kototoro.core.ui.settings.ReaderDisplaySettingsUiState
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ReaderDisplaySettingsContentTest {
    @Test
    fun `display switches independently edit host state in narrow and wide windows`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var state by mutableStateOf(ReaderDisplaySettingsUiState(false, true))
            var titleWrites = 0
            var numberWrites = 0
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ReaderDisplaySettingsContent(state,
                                { state = state.copy(chapterTitleAtBottom = it); titleWrites++ },
                                { state = state.copy(pageNumbers = it); numberWrites++ },
                                platformControlsBefore = { Text("Device display controls") },
                                platformControlsAfter = { Text("Color depth controls") })
                        }
                    }
                }
            }
            onNodeWithText("Device display controls").assertExists()
            onNodeWithText("Color depth controls").assertExists()
            onNodeWithTag("reader-setting-chapter-title-bottom").assertIsOff().performClick().assertIsOn()
            runOnIdle {
                assertEquals(ReaderDisplaySettingsUiState(true, true), state)
                assertEquals(1, titleWrites)
                assertEquals(0, numberWrites)
            }
            onNodeWithTag("reader-setting-page-numbers").assertIsOn().performClick().assertIsOff()
            runOnIdle {
                assertEquals(ReaderDisplaySettingsUiState(true, false), state)
                assertEquals(1, numberWrites)
            }
            // External preference changes update the same switches without writing back to the host.
            runOnIdle { state = ReaderDisplaySettingsUiState(false, true) }
            onNodeWithTag("reader-setting-chapter-title-bottom").assertIsOff()
            onNodeWithTag("reader-setting-page-numbers").assertIsOn()
            runOnIdle { assertEquals(1, titleWrites); assertEquals(1, numberWrites) }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-reader-display-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun `busy state blocks both display writes and absent platform controls add no rows`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    ReaderDisplaySettingsContent(ReaderDisplaySettingsUiState(false, true),
                        { writes++ }, { writes++ }, enabled = enabled, pageNumbersSummary = null)
                }
            }
            runOnIdle { enabled = false }
            onNodeWithText("Device display controls").assertDoesNotExist()
            onNodeWithText("Color depth controls").assertDoesNotExist()
            onNodeWithText(AndroidStrings["show_pages_numbers_summary"]).assertDoesNotExist()
            onNodeWithTag("reader-setting-chapter-title-bottom").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("reader-setting-page-numbers").assertIsNotEnabled().performTouchInput { click() }
            runOnIdle { assertEquals(0, writes) }
            runOnIdle { enabled = true }
            onNodeWithTag("reader-setting-page-numbers").performClick()
            runOnIdle { assertEquals(1, writes) }
        }
}
