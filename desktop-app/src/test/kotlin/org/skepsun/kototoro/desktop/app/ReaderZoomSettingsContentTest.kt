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
class ReaderZoomSettingsContentTest {
    private val fits = listOf(PreferenceChoice("CENTER", "Fit center"), PreferenceChoice("WIDTH", "Fit width"))
    private val animations = listOf(PreferenceChoice("SLIDE", "Slide"), PreferenceChoice("NONE", "None"))
    private val cropTypes = listOf(PreferenceChoice("1", "Paged reader"), PreferenceChoice("2", "Continuous reader"))

    @Test
    fun `per-type crop confirms or discards a private draft in narrow and wide windows`() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var state by mutableStateOf(ReaderZoomSettingsUiState("", "UNKNOWN"))
            var selected by mutableStateOf(setOf("future"))
            var commits = 0
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ReaderZoomSettingsContent(state, fits, animations,
                                ReaderCropSetting.ByReaderType(selected, cropTypes) { selected = it; commits++ },
                                { state = state.copy(fitMode = it) }, { state = state.copy(animation = it) },
                                platformZoomControls = { Text("Zoom buttons") },
                                platformScrollControls = { Text("Pull gesture") })
                        }
                    }
                }
            }
            onNodeWithText("Zoom buttons").assertExists()
            onNodeWithText("Pull gesture").assertExists()
            onNodeWithTag("reader-setting-fit").performClick()
            onNodeWithTag("reader-fit-option:CENTER").assertIsNotSelected()
            onNodeWithTag("reader-fit-close").performClick()
            runOnIdle { assertEquals("", state.fitMode) }
            onNodeWithTag("reader-setting-fit").performClick()
            onNodeWithTag("reader-fit-option:WIDTH").performClick()
            onNodeWithTag("reader-setting-animation").performClick()
            onNodeWithTag("reader-animation-option:NONE").performClick()
            runOnIdle { assertEquals(ReaderZoomSettingsUiState("WIDTH", "NONE"), state) }

            onNodeWithTag("reader-setting-crop").performClick()
            onNodeWithTag("reader-crop-option:1").performClick()
            runOnIdle { assertEquals(setOf("future"), selected); assertEquals(0, commits) }
            onNodeWithTag("reader-crop-cancel").performClick()
            onNodeWithTag("reader-setting-crop").performClick()
            onNodeWithTag("reader-crop-option:1").assertIsOff()
            onNodeWithTag("reader-crop-option:1").performClick()
            onNodeWithTag("reader-crop-option:2").performClick()
            onNodeWithTag("reader-crop-save").performClick()
            runOnIdle { assertEquals(setOf("future", "1", "2"), selected); assertEquals(1, commits) }
            onNodeWithTag("reader-setting-crop").performClick()
            onNodeWithTag("reader-crop-option:1").assertIsOn().performClick()
            onNodeWithTag("reader-crop-cancel").performClick()
            runOnIdle { assertEquals(setOf("future", "1", "2"), selected); assertEquals(1, commits) }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-reader-zoom-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun `current-mode crop writes immediately while animation follows its capability`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var crop by mutableStateOf(false)
            var animationEnabled by mutableStateOf(true)
            var enabled by mutableStateOf(true)
            var animationWrites = 0
            setContent {
                DesktopTheme {
                    ReaderZoomSettingsContent(ReaderZoomSettingsUiState("CENTER", "SLIDE", animationEnabled),
                        fits, animations, ReaderCropSetting.CurrentMode(crop) { crop = it }, {}, { animationWrites++ },
                        enabled = enabled)
                }
            }
            onNodeWithTag("reader-setting-crop").assertIsOff().performClick().assertIsOn()
            runOnIdle { assertTrue(crop) }
            onNodeWithTag("reader-setting-animation").performClick()
            runOnIdle { animationEnabled = false }
            onNodeWithTag("reader-animation-option:NONE").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("reader-animation-close").performClick()
            onNodeWithTag("reader-setting-animation").assertIsNotEnabled()
            onNodeWithTag("reader-setting-crop").performClick().assertIsOff()
            runOnIdle { enabled = false }
            onNodeWithTag("reader-setting-crop").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("reader-setting-fit").assertIsNotEnabled()
            runOnIdle { assertFalse(crop); assertEquals(0, animationWrites) }
        }

    @Test
    fun `busy state prevents changes and confirmation in an already open crop picker`() =
        runDesktopComposeUiTest(width = 380, height = 800) {
            var enabled by mutableStateOf(true)
            var writes = 0
            setContent {
                DesktopTheme {
                    ReaderZoomSettingsContent(ReaderZoomSettingsUiState("CENTER", "SLIDE"), fits, animations,
                        ReaderCropSetting.ByReaderType(emptySet(), cropTypes) { writes++ }, {}, {}, enabled = enabled)
                }
            }
            onNodeWithTag("reader-setting-crop").performClick()
            onNodeWithTag("reader-crop-option:1").performClick()
            runOnIdle { enabled = false }
            onNodeWithTag("reader-crop-option:2").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("reader-crop-save").assertIsNotEnabled().performTouchInput { click() }
            onNodeWithTag("reader-crop-dialog").assertExists()
            onNodeWithTag("reader-crop-cancel").performClick()
            runOnIdle { assertEquals(0, writes) }
        }
}
