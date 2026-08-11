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

	@Test
	fun `standard HLS relies on normal mime detection`() {
		assertEquals(
			null,
			MpvPlaybackOptions.hlsLoadFileOptions("https://cdn.example/video/master.m3u8?token=abc", true),
		)
	}

	@Test
	fun `nonstandard HLS keeps the compatibility demuxer options`() {
		assertEquals(
			"demuxer-lavf-format=hls,demuxer-lavf-o=extension_picky=0",
			MpvPlaybackOptions.hlsLoadFileOptions("https://cdn.example/video/index.json", true),
		)
	}

	@Test
	fun `regular video has no HLS load options`() {
		assertEquals(
			null,
			MpvPlaybackOptions.hlsLoadFileOptions("https://cdn.example/video/file.mp4", false),
		)
	}
}
