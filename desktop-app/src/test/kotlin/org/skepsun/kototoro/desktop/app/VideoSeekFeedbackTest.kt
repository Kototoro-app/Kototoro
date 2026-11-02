package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoSeekFeedback

/**
 * The seeking bubble Windows shows for a fixed jump. Android draws it over the picture; mpv renders into a native
 * window here, so the Windows player floats it over the controls while the same shared card and the same jump
 * sentence are used. Android's own shape is unit tested in `:core-ui`; these tests pin this host's wiring.
 */
@OptIn(ExperimentalTestApi::class)
class VideoSeekFeedbackTest {

    // The sentence is Android's own key, so the test reads the same catalog instead of pinning a language.
    private val forward = AndroidStrings.format("video_fast_forward_time", "10")
    private val rewind = AndroidStrings.format("video_rewind_time", "10")

    @Test
    fun `a forward jump states Android's sentence and the new position`() {
        val feedback = desktopSeekFeedback(position = 55.0, duration = 600.0, offset = 10.0)
        assertEquals("$forward\n01:05 / 10:00", feedback?.text)
        assertEquals(65_000f / 600_000f, feedback?.progress)
    }

    @Test
    fun `a rewind keeps its own wording`() {
        assertEquals("$rewind\n00:45 / 10:00", desktopSeekFeedback(55.0, 600.0, -10.0)?.text)
    }

    @Test
    fun `a jump stops at both ends of the episode`() {
        assertEquals("$forward\n00:30 / 00:30", desktopSeekFeedback(25.0, 30.0, 10.0)?.text)
        assertEquals("$rewind\n00:00 / 00:30", desktopSeekFeedback(3.0, 30.0, -10.0)?.text)
    }

    @Test
    fun `nothing to jump in before the length is known`() {
        assertNull(desktopSeekFeedback(0.0, 0.0, 10.0))
        assertNull(desktopSeekFeedback(0.0, -1.0, 10.0))
        assertNull(desktopSeekFeedback(12.0, 600.0, 0.0))
    }

    @Test
    fun `the bubble renders the jump and its progress bar`() = runDesktopComposeUiTest(width = 640, height = 240) {
        val feedback = requireNotNull(desktopSeekFeedback(65.0, 600.0, 10.0))
        assertEquals("$forward\n01:15 / 10:00", feedback.text)
        setContent {
            DesktopTheme {
                // The anchor the Windows player uses: the bottom band of the player, above its control bar.
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.BottomCenter) {
                    VideoSeekFeedback(feedback)
                }
            }
        }
        val node = onNodeWithText(feedback.text)
        node.assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
    }
}
