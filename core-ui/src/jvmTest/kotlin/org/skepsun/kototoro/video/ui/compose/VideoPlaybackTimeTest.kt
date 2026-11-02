package org.skepsun.kototoro.video.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The player clock and the seeking bubble moved out of Android's `VideoPlayerControls`/`VideoPlayerActivity`, so their
 * shapes are pinned here: Android's two private formatters and the desktop player now all read this one.
 */
class VideoPlaybackTimeTest {

    @Test
    fun `short values show minutes without padding, as the control bar does`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:05", formatPlaybackTime(5_000))
        assertEquals("0:59", formatPlaybackTime(59_999))
        assertEquals("5:03", formatPlaybackTime(303_000))
        assertEquals("59:59", formatPlaybackTime(3_599_999))
    }

    @Test
    fun `the hour digit appears by itself once the value reaches an hour`() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
        assertEquals("12:00:00", formatPlaybackTime(43_200_000))
    }

    @Test
    fun `padded minutes keep the clock from jittering next to a slider`() {
        assertEquals("00:05", formatPlaybackTime(5_000, padMinutes = true))
        assertEquals("05:03", formatPlaybackTime(303_000, padMinutes = true))
        assertEquals("01:00:00", formatPlaybackTime(3_600_000, padMinutes = true))
    }

    @Test
    fun `an hour-shaped bubble keeps three digits even at the start of a long episode`() {
        assertEquals("00:00:00", formatPlaybackTime(0, padMinutes = true, forceHours = true))
        assertEquals("00:12:03", formatPlaybackTime(723_000, padMinutes = true, forceHours = true))
        assertEquals("0:12:03", formatPlaybackTime(723_000, forceHours = true))
    }

    @Test
    fun `a negative or unknown position reads as the start`() {
        assertEquals("0:00", formatPlaybackTime(-1))
        assertEquals("0:00", formatPlaybackTime(-59_000))
        assertEquals("00:00", formatPlaybackTime(-900, padMinutes = true))
    }

    @Test
    fun `the bubble states the jump, the new position and the progress`() {
        val feedback = videoSeekFeedbackState(positionMs = 65_000, durationMs = 600_000, delta = "Fast forward 10 s")
        assertEquals("Fast forward 10 s\n01:05 / 10:00", feedback.text)
        assertEquals(65_000f / 600_000f, feedback.progress)
    }

    @Test
    fun `a drag has no jump sentence and an hour-long episode keeps the hour digits`() {
        assertEquals("00:12:03 / 01:40:00", videoSeekFeedbackState(723_000, 6_000_000).text)
        assertEquals("00:00:00 / 01:40:00", videoSeekFeedbackState(0, 6_000_000, "").text)
    }

    @Test
    fun `an unknown length has no progress to show`() {
        val feedback = videoSeekFeedbackState(positionMs = 5_000, durationMs = 0)
        assertEquals(0f, feedback.progress)
        assertEquals("00:05 / 00:00", feedback.text)
    }

    @Test
    fun `progress never leaves the bar`() {
        assertEquals(0f, videoSeekFeedbackState(-5_000, 600_000).progress)
        assertEquals(1f, videoSeekFeedbackState(900_000, 600_000).progress)
    }
}
