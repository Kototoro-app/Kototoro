package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.list.ui.compose.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class SharedContentCardsTest {
    @Test
    fun `poster and compact row keep click long press selection and disabled semantics`() {
        for (poster in listOf(true, false)) runDesktopComposeUiTest(width = 380, height = 500) {
            var selected by mutableStateOf(false)
            var enabled by mutableStateOf(true)
            var opens = 0
            var longPresses = 0
            setContent {
                DesktopTheme {
                    val model = ContentCardPresentation(42, "作品标题", "作者", "来源")
                    val open = { if (selected) selected = false else opens++; Unit }
                    val select = { selected = true; longPresses++; Unit }
                    if (poster) ContentPosterCard(model, CompactPosterCardStyle(112.dp, 168.dp, 12.dp),
                        rememberCoverRimBorderBrush(false), open, select,
                        cover = { Box(Modifier.matchParentSize().background(Color.Blue)) },
                        modifier = Modifier.width(140.dp).testTag("card"), enabled = enabled, isSelected = selected)
                    else ContentCompactCard(model, open, select,
                        cover = { Box(Modifier.size(48.dp, 72.dp).background(Color.Blue)) },
                        modifier = Modifier.testTag("card"), enabled = enabled, isSelected = selected)
                }
            }
            val card = onNodeWithTag("card")
            card.assertIsNotSelected().performClick()
            runOnIdle { assertEquals(1, opens) }
            card.performTouchInput { longClick() }
            card.assertIsSelected()
            onNodeWithTag("content-selected:42", useUnmergedTree = true).assertExists()
            card.performClick()
            card.assertIsNotSelected()
            runOnIdle { assertEquals(1, opens); assertEquals(1, longPresses); enabled = false }
            card.assertIsNotEnabled().performTouchInput { click(); longClick() }
            runOnIdle { assertEquals(1, opens); assertEquals(1, longPresses) }
        }
    }

    @Test
    fun `poster preserves cover bounds for host transitions in portrait landscape and overlay modes`() {
        for (landscape in listOf(false, true)) for (overlay in listOf(false, true)) {
            runDesktopComposeUiTest(width = 380, height = 500) {
                var coverBounds = Rect.Zero
                val width = if (landscape) 176.dp else 112.dp
                val height = if (landscape) 99.dp else 168.dp
                setContent {
                    DesktopTheme(DesktopAppearance.LIGHT) {
                        ContentPosterCard(ContentCardPresentation(7, "标题", supportingText = "额外信息"),
                            CompactPosterCardStyle(width, height, 12.dp), rememberCoverRimBorderBrush(false), {}, {},
                            cover = { Box(Modifier.matchParentSize().background(Color.Blue)) },
                            modifier = Modifier.width(width), compactOverlay = overlay,
                            cellContentPadding = PaddingValues(0.dp),
                            coverModifier = Modifier.onGloballyPositioned { coverBounds = it.boundsInRoot() })
                    }
                }
                waitForIdle()
                runOnIdle { assertEquals(width.value / height.value, coverBounds.width / coverBounds.height, .02f) }
                if (overlay) onNodeWithText("额外信息").assertDoesNotExist()
                else onNodeWithText("额外信息").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `compact rows keep title metadata and status reachable in narrow and wide light and dark layouts`() {
        for (width in listOf(380, 760)) for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = width, height = 180) {
                setContent {
                    DesktopTheme(appearance) {
                        androidx.compose.material3.Surface {
                            ContentCompactCard(ContentCardPresentation(9, "很长的标题".repeat(20), "作者", "来源"), {}, {},
                                cover = { Box(Modifier.size(48.dp, 72.dp).background(Color.Blue)) },
                                isSelected = true,
                                status = { ContentCardBottomProgressBar(.4f, false, Modifier.testTag("progress")) })
                        }
                    }
                }
                onNodeWithText("作者").assertIsDisplayed()
                onNodeWithText("来源").assertIsDisplayed()
                val progress = onNodeWithTag("progress", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(progress.right <= width && progress.left > 48)
                val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                    .resolve("shared-content-row-$width-${appearance.name}.png")
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(report, it.bytes) }
                }
            }
        }
    }
}
