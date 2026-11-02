package org.skepsun.kototoro.desktop.app

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.compose.design.ReaderInfoBarAlignment
import org.skepsun.kototoro.reader.ui.compose.design.ReaderInfoBarInsets
import org.skepsun.kototoro.reader.ui.compose.design.ReaderInfoBarRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderInfoBarText
import kotlin.math.abs

/**
 * The shared reader info bar as Windows draws it while the chrome is hidden: the chapter on the left and the visible
 * pages next to it, both using Android's outlined text. The placement rules themselves are unit tested in `:core-ui`;
 * these tests pin that the Windows host really renders the shared row, fills the reader's width like Android's bar and
 * keeps the two items in the order Android shows them.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderInfoBarTest {

    private val style = TextStyle(color = Color.Black, fontSize = 12.sp)

    @Test
    fun `the centered bar shows the chapter left of the visible pages`() = runDesktopComposeUiTest(
        width = 800,
        height = 200,
    ) {
        setContent {
            DesktopTheme {
                ReaderInfoBarRow(
                    alignment = ReaderInfoBarAlignment.CENTERED,
                    readingStatus = {
                        ReaderInfoBarText(
                            text = "第 1 章",
                            style = style,
                            outlineColor = Color.White,
                            drawOutline = true,
                            modifier = Modifier.testTag("reader-chapter"),
                        )
                    },
                    progressStatus = {
                        Text("4–5 / 5", style = style, modifier = Modifier.testTag("reader-progress"))
                    },
                    insets = { ReaderInfoBarInsets(left = 0, right = 0, vertical = 0) },
                    modifier = Modifier.testTag("reader-info-bar"),
                )
            }
        }

        val bar = onNodeWithTag("reader-info-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(800f, bar.width, 1f)
        val chapter = onNodeWithTag("reader-chapter").fetchSemanticsNode().boundsInRoot
        val progress = onNodeWithTag("reader-progress").fetchSemanticsNode().boundsInRoot
        assertTrue(chapter.right <= progress.left) { "the chapter sits left of the page range" }
        // Centred as one group, like Android's centered info bar.
        val leftGap = chapter.left - bar.left
        val rightGap = bar.right - progress.right
        assertTrue(abs(leftGap - rightGap) <= 2f) { "the centered group is not centred: $leftGap vs $rightGap" }
    }

    @Test
    fun `a split bar anchors the chapter at the left inset`() = runDesktopComposeUiTest(width = 800, height = 200) {
        setContent {
            DesktopTheme {
                ReaderInfoBarRow(
                    alignment = ReaderInfoBarAlignment.SPLIT,
                    readingStatus = {
                        ReaderInfoBarText(
                            text = "第 1 章",
                            style = style,
                            outlineColor = Color.White,
                            drawOutline = false,
                            modifier = Modifier.testTag("reader-chapter"),
                        )
                    },
                    insets = { ReaderInfoBarInsets(left = 40, right = 40, vertical = 0) },
                    modifier = Modifier.testTag("reader-info-bar"),
                )
            }
        }

        onNodeWithTag("reader-chapter").assertIsDisplayed()
        val bar = onNodeWithTag("reader-info-bar").fetchSemanticsNode().boundsInRoot
        val chapter = onNodeWithTag("reader-chapter").fetchSemanticsNode().boundsInRoot
        assertEquals(800f, bar.width, 1f)
        assertEquals(40f, chapter.left - bar.left, 1f)
    }

    @Test
    fun `the info bar keeps Android's vertical padding`() = runDesktopComposeUiTest(width = 800, height = 200) {
        setContent {
            DesktopTheme {
                ReaderInfoBarRow(
                    alignment = ReaderInfoBarAlignment.CENTERED,
                    readingStatus = {
                        ReaderInfoBarText(
                            text = "第 1 章",
                            style = style,
                            outlineColor = Color.White,
                            drawOutline = true,
                            modifier = Modifier.testTag("reader-chapter"),
                        )
                    },
                    insets = { ReaderInfoBarInsets(left = 0, right = 0, vertical = 6) },
                    modifier = Modifier.testTag("reader-info-bar"),
                )
            }
        }

        val bar = onNodeWithTag("reader-info-bar").fetchSemanticsNode().boundsInRoot
        val chapter = onNodeWithTag("reader-chapter").fetchSemanticsNode().boundsInRoot
        assertEquals((6.dp * 2).value, bar.height - chapter.height, 1f)
    }
}
