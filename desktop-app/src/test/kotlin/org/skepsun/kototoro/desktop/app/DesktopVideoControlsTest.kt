package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerAction
import org.skepsun.kototoro.video.ui.compose.VideoPlayerActionsTierKey
import org.skepsun.kototoro.video.ui.compose.VideoPlayerBottomControls
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlsTier
import org.skepsun.kototoro.video.ui.compose.VideoPlayerTopControls
import org.skepsun.kototoro.core.prefs.UiPresentationMode
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.UiPresentationConfig
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

    /**
     * At 320dp the row is the NARROW tier, and the tier is enforced: only the slots it keeps inline are drawn, so the
     * row fits its window and no control has to be dragged out from the side. The speed label and the quality label move
     * to the overflow menu, which sits at the row's end and is therefore always on screen.
     *
     * See [org.skepsun.kototoro.video.ui.compose.videoPlayerActionLayout].
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow player draws only the inline slots and offers the rest behind the overflow`() =
        runDesktopComposeUiTest(320, 240) {
            val actions = mutableListOf<VideoPlayerAction>()
            var interactionStarts = 0
            var interactionEnds = 0
            setContent {
                MaterialTheme {
                    VideoPlayerBottomControls(
                        state = VideoPlayerControlState(
                            hasPreviousChapter = true,
                            hasNextChapter = true,
                            canSeek = true,
                            durationMs = 120_000,
                            qualityLabel = "1080p",
                            supportsNotes = true,
                        ),
                        onAction = { actions += it },
                        onInteractionStart = { interactionStarts++ },
                        onInteractionEnd = { interactionEnds++ },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            onNodeWithTag("video-actions")
                .assert(SemanticsMatcher.expectValue(VideoPlayerActionsTierKey, VideoPlayerControlsTier.NARROW))
            // Everything the narrow tier keeps inline is on screen without touching the row.
            onNodeWithContentDescription("Play").assertIsDisplayed()
            onNodeWithContentDescription("Previous chapter").assertIsDisplayed()
            onNodeWithContentDescription("Next chapter").assertIsDisplayed()
            onNodeWithContentDescription("Chapters").assertIsDisplayed()
            onNodeWithContentDescription("Lock controls").assertIsDisplayed()
            onNodeWithContentDescription("rotate_screen").assertIsDisplayed()
            onNodeWithTag("video-actions-overflow").assertIsDisplayed()
            // What the tier moved to the overflow is not drawn in the row at all — that is what keeps the row inside
            // the window instead of ending past the right edge.
            onNodeWithText("1.00x").assertDoesNotExist()
            onNodeWithText("1080p").assertDoesNotExist()
            onNodeWithContentDescription("video_add_note").assertDoesNotExist()
            onNodeWithTag("video-actions-scroll-forward").assertDoesNotExist()
            val reports = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(reports.resolve("video-actions-narrow.png"), it.bytes) }
            }
            // The controls that stayed in the row still work by touch, without any drag first.
            onNodeWithContentDescription("Previous chapter").performClick()
            assertEquals(VideoPlayerAction.PreviousChapter, actions.last())
            onNodeWithTag("video-actions-overflow").performClick()
            assertTrue(actions.last() is VideoPlayerAction.OpenMore)
            assertTrue(actions.none { it is VideoPlayerAction.SeekTo })
            assertTrue(interactionStarts > 0 && interactionEnds > 0)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide player shows all actions without scroll hints when they fit`() = runDesktopComposeUiTest(1280, 240) {
        setContent {
            MaterialTheme {
                VideoPlayerBottomControls(
                    state = VideoPlayerControlState(qualityLabel = "1080p"),
                    onAction = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithText("1080p").assertIsDisplayed()
        onNodeWithContentDescription("video_add_note").assertIsDisplayed()
        onNodeWithText("video_mark_intro").assertIsDisplayed()
        onNodeWithText("video_mark_outro").assertIsDisplayed()
        onNodeWithTag("video-actions-scroll-forward").assertDoesNotExist()
        onNodeWithTag("video-actions-scroll-back").assertDoesNotExist()
    }

    /**
     * A remote has no pointer, so an action it can reach must be focusable where it is drawn: in the wide tier the note
     * button is drawn in the row, and focusing it must not need a scroll first.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tv focus reaches the note button in the row without clipping`() = runDesktopComposeUiTest(1280, 240) {
        setContent {
            CompositionLocalProvider(
                LocalUiPresentationConfig provides UiPresentationConfig(UiPresentationMode.TV, UiPresentationMode.TV),
            ) {
                MaterialTheme {
                    VideoPlayerBottomControls(
                        state = VideoPlayerControlState(hasPreviousChapter = true, hasNextChapter = true),
                        onAction = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        onNodeWithContentDescription("video_add_note").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onNodeWithContentDescription("video_add_note").assertIsDisplayed().assertIsFocused()
        onNodeWithTag("video-actions-scroll-back").assertDoesNotExist()
    }

    /**
     * At the narrowest width the tail of the row is off screen; focusing the overflow button has to bring it into view,
     * otherwise the low-frequency actions would be unreachable from a remote.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tv focus pulls the overflow button into view at the narrowest width`() = runDesktopComposeUiTest(320, 240) {
        setContent {
            CompositionLocalProvider(
                LocalUiPresentationConfig provides UiPresentationConfig(UiPresentationMode.TV, UiPresentationMode.TV),
            ) {
                MaterialTheme {
                    VideoPlayerBottomControls(
                        state = VideoPlayerControlState(hasPreviousChapter = true, hasNextChapter = true),
                        onAction = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        onNodeWithTag("video-actions-overflow").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onNodeWithTag("video-actions-overflow").assertIsDisplayed().assertIsFocused()
    }
}
