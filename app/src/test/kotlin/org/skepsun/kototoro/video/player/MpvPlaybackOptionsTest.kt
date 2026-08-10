package org.skepsun.kototoro.video.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.VideoRendererMode

class MpvPlaybackOptionsTest {

	@Test
	fun `software decoding overrides every renderer`() {
		VideoRendererMode.entries.forEach { renderer ->
			assertEquals(
				"no",
				MpvPlaybackOptions.hardwareDecoder(renderer, VideoDecoderMode.SOFTWARE),
			)
		}
	}

	@Test
	fun `embedded renderer uses direct MediaCodec`() {
		assertEquals(
			"mediacodec",
			MpvPlaybackOptions.hardwareDecoder(
				VideoRendererMode.MEDIACODEC_EMBED,
				VideoDecoderMode.HARDWARE,
			),
		)
	}

	@Test
	fun `GPU renderers use stable copy decoding`() {
		listOf(VideoRendererMode.AUTO, VideoRendererMode.GPU, VideoRendererMode.GPU_NEXT).forEach { renderer ->
			assertEquals(
				"mediacodec-copy",
				MpvPlaybackOptions.hardwareDecoder(renderer, VideoDecoderMode.HARDWARE),
			)
		}
	}
}
