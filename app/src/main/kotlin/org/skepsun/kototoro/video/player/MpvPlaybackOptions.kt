package org.skepsun.kototoro.video.player

import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.VideoRendererMode

internal object MpvPlaybackOptions {

	const val AUDIO_OUTPUT = "aaudio,audiotrack,opensles"

	fun hardwareDecoder(
		rendererMode: VideoRendererMode,
		decoderMode: VideoDecoderMode,
	): String = when {
		decoderMode == VideoDecoderMode.SOFTWARE -> "no"
		rendererMode == VideoRendererMode.MEDIACODEC_EMBED -> "mediacodec"
		else -> "mediacodec-copy"
	}
}
