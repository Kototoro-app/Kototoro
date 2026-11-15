package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerActionSlot
import org.skepsun.kototoro.video.ui.compose.VideoPlayerActionsTierKey
import org.skepsun.kototoro.video.ui.compose.VideoPlayerBottomControls
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlState
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlsTier

/**
 * The shared control row must keep the viewer's controls on screen without a sideways drag, whatever the window width.
 * These tests render the real row at three widths and read the tier it decided.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPlayerControlsTierTest {

    private fun state(supportsMore: Boolean) = VideoPlayerControlState(
        title = "测试作品",
        subtitle = "第 1 话",
        positionMs = 30_000,
        durationMs = 1_440_000,
        isPlaying = true,
        canSeek = true,
        hasPreviousChapter = true,
        hasNextChapter = true,
        playbackSpeedLabel = "1x",
        supportsNotes = false,
        supportsIntroOutroMarkers = false,
        supportsScreenLock = false,
        supportsAspectRatio = false,
        supportsMore = supportsMore,
        showsRotation = false,
    )

    private fun assertTier(width: Int, height: Int, tier: VideoPlayerControlsTier) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                DesktopTheme {
                    VideoPlayerBottomControls(
                        state = state(supportsMore = true),
                        onAction = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            onNodeWithTag("video-actions").assert(SemanticsMatcher.expectValue(VideoPlayerActionsTierKey, tier))
        }

    @Test
    fun `a wide window keeps every control inline`() = assertTier(900, 200, VideoPlayerControlsTier.EXPANDED)

    @Test
    fun `a narrow window falls back to the core controls plus the overflow`() =
        assertTier(420, 200, VideoPlayerControlsTier.COMPACT)

    @Test
    fun `a very narrow window still shows previous, next and fullscreen`() = runDesktopComposeUiTest(width = 300, height = 200) {
        setContent {
            DesktopTheme {
                VideoPlayerBottomControls(
                    state = state(supportsMore = true),
                    onAction = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithTag("video-actions").assert(
            SemanticsMatcher.expectValue(VideoPlayerActionsTierKey, VideoPlayerControlsTier.NARROW),
        )
        // Fullscreen is the last button of the inline cluster; it is on screen without any scrolling.
        onNodeWithTag("video-fullscreen").assertIsDisplayed()
        // The speed moved to the row's tail, and the overflow button reaches the rest.
        onNodeWithTag("video-actions-overflow").assertIsDisplayed()
        val tier = org.skepsun.kototoro.video.ui.compose.videoPlayerActionLayout(
            availableWidth = 300f,
            fontScale = 1f,
            supports = VideoPlayerActionSlot.entries.toSet(),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            tier.showsInline(VideoPlayerActionSlot.PLAYBACK_SPEED),
            "the speed label should not squeeze the core controls",
        )
    }

    @Test
    fun `a host without an overflow menu gets no dead button`() = runDesktopComposeUiTest(width = 300, height = 200) {
        setContent {
            DesktopTheme {
                VideoPlayerBottomControls(
                    state = state(supportsMore = false),
                    onAction = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithTag("video-actions-overflow").assertDoesNotExist()
    }
}
