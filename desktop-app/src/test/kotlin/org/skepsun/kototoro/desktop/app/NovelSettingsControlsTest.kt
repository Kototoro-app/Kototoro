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
import org.skepsun.kototoro.reader.novel.compose.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class NovelSettingsControlsTest {
    @Test
    fun typographyRetainsEachHostsFractionalStepsAndRange() {
        for (android in listOf(true, false)) for (width in listOf(380, 760)) {
            runDesktopComposeUiTest(width = width, height = 800) {
                var font by mutableFloatStateOf(if (android) 17f else 19f)
                var spacing by mutableFloatStateOf(if (android) 1.6f else 1.75f)
                val fontRange = if (android) 14f..24f else 14f..36f
                val spacingRange = if (android) 1.2f..2f else 1.2f..2.6f
                val fontStep = if (android) .5f else 1f
                val spacingStep = if (android) .1f else .05f
                setContent {
                    DesktopTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NovelTypographyControls(
                                    NovelReaderValueOption("Font size", "%.1fsp".format(font), font, fontRange,
                                        fontStep, { font = it }, "typography-font"),
                                    NovelReaderValueOption("Line spacing", "%.2f".format(spacing), spacing, spacingRange,
                                        spacingStep, { spacing = it }, "typography-spacing"),
                                    fontOption = if (android) null else ({ Text("Host font selector") }),
                                    additionalOptions = { Text("Host layout options") },
                                )
                            }
                        }
                    }
                }
                if (android) onNodeWithText("Host font selector").assertDoesNotExist()
                else onNodeWithText("Host font selector").assertExists()
                onNodeWithText("Host layout options").assertExists()
                onNode(hasText("+") and hasAnyAncestor(hasTestTag("typography-font"))).performClick()
                onNode(hasText("+") and hasAnyAncestor(hasTestTag("typography-spacing"))).performClick()
                runOnIdle {
                    assertEquals(if (android) 17.5f else 20f, font, .001f)
                    assertEquals(if (android) 1.7f else 1.8f, spacing, .001f)
                }
                val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                    .resolve("shared-novel-typography-$android-$width.png")
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(report, it.bytes) }
                }
                runOnIdle { font = fontRange.endInclusive; spacing = spacingRange.start }
                onNode(hasText("+") and hasAnyAncestor(hasTestTag("typography-font"))).assertIsNotEnabled()
                onNode(hasText("−") and hasAnyAncestor(hasTestTag("typography-spacing"))).assertIsNotEnabled()
                onNode(hasText("−") and hasAnyAncestor(hasTestTag("typography-font"))).performClick()
                runOnIdle { assertEquals(fontRange.endInclusive - fontStep, font, .001f) }
            }
        }
    }

    @Test
    fun readingModeChoicesMapBothWaysAndFollowExternalChanges() =
        runDesktopComposeUiTest(width = 380, height = 300) {
            var paged by mutableStateOf(false)
            var writes = 0
            setContent {
                DesktopTheme { NovelReadingModeOptions(paged, { paged = it; writes++ }) }
            }
            val pageLabel = AndroidStrings["novel_mode_paged"]
            val scrollLabel = AndroidStrings["novel_mode_scroll"]
            onNodeWithText(scrollLabel).assertIsSelected()
            onNodeWithText(pageLabel).performClick().assertIsSelected()
            runOnIdle { assertTrue(paged); assertEquals(1, writes) }
            onNodeWithText(scrollLabel).performClick().assertIsSelected()
            runOnIdle { assertFalse(paged); assertEquals(2, writes); paged = true }
            onNodeWithText(pageLabel).assertIsSelected()
            runOnIdle { assertEquals(2, writes) }
        }
}
