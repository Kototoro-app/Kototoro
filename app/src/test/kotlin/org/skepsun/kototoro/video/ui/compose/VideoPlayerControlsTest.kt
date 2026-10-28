package org.skepsun.kototoro.video.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoPlayerControlsTest {

    @Test
    fun `formats short durations as minutes and seconds`() {
        assertEquals("2:05", formatPlaybackTime(125_000L))
    }

    @Test
    fun `formats long durations with an hour field`() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }

    @Test
    fun `clamps negative duration to zero`() {
        assertEquals("0:00", formatPlaybackTime(-1L))
    }
}
