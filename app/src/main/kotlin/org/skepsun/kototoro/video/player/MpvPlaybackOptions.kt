package org.skepsun.kototoro.video.player

import org.skepsun.kototoro.core.prefs.VideoDecoderMode
import org.skepsun.kototoro.core.prefs.VideoRendererMode

internal object MpvPlaybackOptions {

	const val AUDIO_OUTPUT = "aaudio,audiotrack,opensles"
	private const val FORCED_HLS_OPTIONS =
		"demuxer-lavf-format=hls,demuxer-lavf-o=extension_picky=0"

	fun hardwareDecoder(
		rendererMode: VideoRendererMode,
		decoderMode: VideoDecoderMode,
	): String = when {
		decoderMode == VideoDecoderMode.SOFTWARE -> "no"
		rendererMode == VideoRendererMode.MEDIACODEC_EMBED -> "mediacodec"
		else -> "mediacodec-copy"
	}

	fun hlsLoadFileOptions(url: String, isHls: Boolean): String? {
		if (!isHls) return null
		val path = url.substringBefore('?').substringBefore('#')
		return FORCED_HLS_OPTIONS.takeUnless { path.endsWith(".m3u8", ignoreCase = true) }
	}
}
