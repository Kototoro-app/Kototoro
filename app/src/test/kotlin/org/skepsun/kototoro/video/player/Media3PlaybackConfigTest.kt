package org.skepsun.kototoro.video.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Media3PlaybackConfigTest {
	@Test
	fun `interactive seeks allow cloudstream compatible tolerance`() {
		val parameters = Media3PlaybackConfig.seekParameters()

		assertEquals(300_000L, parameters.toleranceBeforeUs)
		assertEquals(300_000L, parameters.toleranceAfterUs)
	}

	@Test
	fun `live playback bounds match cloudstream defaults`() {
		assertEquals(5_000L, Media3PlaybackConfig.PREFERRED_LIVE_OFFSET_MS)
		assertEquals(0.97f, Media3PlaybackConfig.MIN_LIVE_PLAYBACK_SPEED)
		assertEquals(1.03f, Media3PlaybackConfig.MAX_LIVE_PLAYBACK_SPEED)
	}

	@Test
	fun `back buffer retains thirty seconds from a keyframe`() {
		assertEquals(30_000, Media3PlaybackConfig.BACK_BUFFER_MS)
	}
}
