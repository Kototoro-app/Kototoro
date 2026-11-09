package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerAction
import org.skepsun.kototoro.video.ui.compose.VideoPlayerBottomControls
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerTopControls
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop player draws Android's shared player chrome: icon buttons (no text play/±10s buttons), without the
 * Android-only note, marker, lock and rotation buttons, and a fullscreen toggle that reports its action. Rendered
 * offscreen, so the screenshot never captures other windows.
 */
class DesktopVideoControlsTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the shared player controls drop Android-only buttons and toggle fullscreen`() = runDesktopComposeUiTest(1100, 620) {
        val actions = mutableListOf<VideoPlayerAction>()
        val state = VideoPlayerControlState(title = "作品", subtitle = "第 1 话", positionMs = 65_000, durationMs = 1_440_000,
            isPlaying = true, canSeek = true, hasPreviousChapter = true, hasNextChapter = true, qualityLabel = "1080P",
            supportsNotes = false, supportsIntroOutroMarkers = false, supportsScreenLock = false, supportsMore = false,
            supportsAspectRatio = false, showsRotation = false)
        setContent {
            DesktopTheme(DesktopAppearance.DARK) {
                Column(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    VideoPlayerTopControls(state, { actions += it })
                    Box(Modifier.weight(1f))
                    VideoPlayerBottomControls(state, { actions += it })
                }
            }
        }
        waitForIdle()
        onAllNodesWithText("播放").assertCountEquals(0)
        onAllNodesWithText("+10s").assertCountEquals(0)
        onAllNodesWithContentDescription("Lock controls").assertCountEquals(0)
        onNodeWithText("1080P").assertExists()
        onNodeWithTag("video-fullscreen").performClick()
        assertEquals(VideoPlayerAction.ToggleFullscreen, actions.last())
        val reports = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData()?.use { Files.write(reports.resolve("video-controls-shared.png"), it.bytes) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `aspect ratio shortcut stays to the left of subtitles at narrow and wide widths`() =
        runDesktopComposeUiTest(840, 240) {
            val actions = mutableListOf<VideoPlayerAction>()
            val labels = mutableStateListOf("16:9", "拉伸铺满")
            setContent {
                MaterialTheme {
                    Column {
                        listOf(320, 840).forEachIndexed { index, width ->
                            VideoPlayerTopControls(
                                state = VideoPlayerControlState(title = "A long video title", aspectRatioLabel = labels[index]),
                                onAction = { actions += it; labels[index] = "4:3" },
                                modifier = Modifier.width(width.dp),
                            )
                        }
                    }
                }
            }
            val aspectButtons = onAllNodesWithContentDescription("video_aspect_ratio", substring = true)
            val subtitleButtons = onAllNodesWithContentDescription("Subtitles")
            aspectButtons.assertCountEquals(2)
            subtitleButtons.assertCountEquals(2)
            onNodeWithText("16:9").assertIsDisplayed()
            onNodeWithText("拉伸铺满").assertIsDisplayed()
            val reports = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(reports.resolve("video-aspect-labels.png"), it.bytes) }
            }
            repeat(2) { index ->
                val aspect = aspectButtons[index].fetchSemanticsNode().boundsInRoot
                val subtitles = subtitleButtons[index].fetchSemanticsNode().boundsInRoot
                assertTrue(aspect.width > 0f && aspect.center.x < subtitles.center.x)
                aspectButtons[index].assertIsDisplayed()
                aspectButtons[index].performClick()
            }
            assertEquals(listOf(VideoPlayerAction.CycleAspectRatio, VideoPlayerAction.CycleAspectRatio), actions)
            onAllNodesWithText("4:3").assertCountEquals(2)
        }
}
