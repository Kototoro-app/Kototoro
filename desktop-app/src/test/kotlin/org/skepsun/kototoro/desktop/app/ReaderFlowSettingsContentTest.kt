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
import org.skepsun.kototoro.core.ui.settings.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ReaderFlowSettingsContentTest {
    @Test
    fun webtoonGapsAndPreloadReductionKeepIndependentState() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 800) {
            var gaps by mutableStateOf(false)
            var reduced by mutableStateOf(true)
            setContent {
                DesktopTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ReaderWebtoonSettingsContent(gaps, { gaps = it },
                                platformControls = { Text("Device webtoon controls") })
                            SettingsGroup { ReaderPreloadSettingsRow(reduced, { reduced = it }) }
                        }
                    }
                }
            }
            onNodeWithText("Device webtoon controls").assertExists()
            onNodeWithTag("reader-setting-webtoon-gaps").assertIsOff().performClick().assertIsOn()
            runOnIdle { assertEquals(true, gaps); assertEquals(true, reduced) }
            onNodeWithTag("reader-setting-reduce-preloading").assertIsOn().performClick().assertIsOff()
            runOnIdle { assertEquals(true, gaps); assertEquals(false, reduced) }
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-reader-flow-$width.png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun disabledFlowSettingsIgnoreClicks() = runDesktopComposeUiTest(width = 380, height = 800) {
        var writes = 0
        setContent {
            DesktopTheme {
                Column {
                    ReaderWebtoonSettingsContent(true, { writes++ }, enabled = false)
                    ReaderPreloadSettingsRow(false, { writes++ }, enabled = false)
                }
            }
        }
        onNodeWithText("Device webtoon controls").assertDoesNotExist()
        onNodeWithTag("reader-setting-webtoon-gaps").assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag("reader-setting-reduce-preloading").assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(0, writes) }
    }
}
