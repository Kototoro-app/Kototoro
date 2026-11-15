package org.skepsun.kototoro.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.video.ui.compose.VideoPlayerActionLayout
import org.skepsun.kototoro.video.ui.compose.VideoPlayerActionSlot
import org.skepsun.kototoro.video.ui.compose.VideoPlayerControlsTier
import org.skepsun.kototoro.video.ui.compose.videoPlayerActionLayout

/**
 * The player's bottom bar must never make a viewer drag sideways to reach play, the episode list, the speed or
 * fullscreen. These tests pin which tier a width/font-scale combination lands in and that the core controls keep their
 * place while the low-frequency ones move to the overflow.
 */
class VideoPlayerActionLayoutTest {

    private val androidSlots = setOf(
        VideoPlayerActionSlot.PREVIOUS_CHAPTER,
        VideoPlayerActionSlot.NEXT_CHAPTER,
        VideoPlayerActionSlot.CHAPTERS,
        VideoPlayerActionSlot.PLAYBACK_SPEED,
        VideoPlayerActionSlot.FULLSCREEN,
        VideoPlayerActionSlot.QUALITY,
        VideoPlayerActionSlot.NOTES,
        VideoPlayerActionSlot.INTRO_MARKER,
        VideoPlayerActionSlot.OUTRO_MARKER,
        VideoPlayerActionSlot.SCREEN_LOCK,
    )

    private fun layout(width: Float, fontScale: Float = 1f, supports: Set<VideoPlayerActionSlot> = androidSlots) =
        videoPlayerActionLayout(width, fontScale, supports)

    @Test
    fun `a wide player shows everything inline`() {
        val result = layout(900f)
        assertEquals(VideoPlayerControlsTier.EXPANDED, result.tier)
        assertEquals(androidSlots.size, result.inline.size)
        assertTrue(result.overflow.isEmpty())
    }

    @Test
    fun `the core controls stay inline once the player narrows`() {
        val result = layout(430f)
        assertEquals(VideoPlayerControlsTier.COMPACT, result.tier)
        assertEquals(
            listOf(
                VideoPlayerActionSlot.PREVIOUS_CHAPTER,
                VideoPlayerActionSlot.NEXT_CHAPTER,
                VideoPlayerActionSlot.CHAPTERS,
                VideoPlayerActionSlot.PLAYBACK_SPEED,
                VideoPlayerActionSlot.FULLSCREEN,
                VideoPlayerActionSlot.SCREEN_LOCK,
            ),
            result.inline,
        )
        // Nothing is dropped: the low-frequency actions are reachable through the overflow instead. The screen lock is
        // core, because on a phone it is the only way to stop accidental touches.
        assertTrue(result.showsInOverflow(VideoPlayerActionSlot.QUALITY))
        assertTrue(result.showsInOverflow(VideoPlayerActionSlot.NOTES))
        assertEquals(androidSlots.size, result.inline.size + result.overflow.size)
    }

    @Test
    fun `a narrow player keeps the episode controls and moves the speed to the overflow`() {
        val result = layout(320f)
        assertEquals(VideoPlayerControlsTier.NARROW, result.tier)
        assertTrue(result.showsInline(VideoPlayerActionSlot.PREVIOUS_CHAPTER))
        assertTrue(result.showsInline(VideoPlayerActionSlot.NEXT_CHAPTER))
        assertTrue(result.showsInline(VideoPlayerActionSlot.CHAPTERS))
        assertTrue(result.showsInline(VideoPlayerActionSlot.FULLSCREEN))
        assertFalse(result.showsInline(VideoPlayerActionSlot.PLAYBACK_SPEED))
        assertTrue(result.showsInOverflow(VideoPlayerActionSlot.PLAYBACK_SPEED))
    }

    @Test
    fun `a large font shrinks the tier like a narrow window does`() {
        // 900dp at 2.0 font scale needs the room of 450dp, so it lands in COMPACT rather than EXPANDED.
        assertEquals(VideoPlayerControlsTier.COMPACT, layout(900f, fontScale = 2f).tier)
        // 800dp at font scale 2.0 behaves like 400dp: still COMPACT, not NARROW.
        assertEquals(VideoPlayerControlsTier.COMPACT, layout(800f, fontScale = 2f).tier)
        // 700dp at 2.0 is 350dp effective: NARROW.
        assertEquals(VideoPlayerControlsTier.NARROW, layout(700f, fontScale = 2f).tier)
    }

    @Test
    fun `only the capabilities the host declares are offered`() {
        val desktop = setOf(
            VideoPlayerActionSlot.PREVIOUS_CHAPTER,
            VideoPlayerActionSlot.NEXT_CHAPTER,
            VideoPlayerActionSlot.CHAPTERS,
            VideoPlayerActionSlot.PLAYBACK_SPEED,
            VideoPlayerActionSlot.FULLSCREEN,
            VideoPlayerActionSlot.QUALITY,
        )
        val result = layout(400f, supports = desktop)
        assertFalse(result.showsInline(VideoPlayerActionSlot.NOTES))
        assertFalse(result.showsInOverflow(VideoPlayerActionSlot.SCREEN_LOCK))
        assertEquals(desktop.size, result.inline.size + result.overflow.size)
    }

    @Test
    fun `an unmeasured row asks for nothing`() {
        val result = layout(0f)
        assertEquals(VideoPlayerControlsTier.NARROW, result.tier)
        assertTrue(result.inline.isEmpty())
        assertTrue(result.overflow.isEmpty())
        assertEquals(VideoPlayerActionLayout(VideoPlayerControlsTier.NARROW, emptyList(), emptyList()), result)
    }
}
