package org.skepsun.kototoro.video.player

import java.net.URI

enum class PlaybackRoute {
	DIRECT,
	TRANSFORMING_HLS_PROXY,
	TORRENT_LOCAL_HTTP,
}

data class NormalizedPlaybackRequest(
	val mediaKind: PlaybackMediaKind,
	val headers: Map<String, String>,
	val route: PlaybackRoute,
)

object PlaybackRequestNormalizer {
	private val unsafeHeaders = setOf("host", "connection", "content-length")

	fun normalize(
		url: String,
		declaredKind: PlaybackMediaKind = PlaybackMediaKind.AUTO,
		originalHeaders: Map<String, String> = emptyMap(),
		isCloudstream: Boolean = false,
	): NormalizedPlaybackRequest {
		val path = runCatching { URI(url).path.orEmpty() }
			.getOrElse { url.substringBefore('?').substringBefore('#') }
			.lowercase()
		val kind = when {
			declaredKind != PlaybackMediaKind.AUTO -> declaredKind
			url.startsWith("magnet:", true) || path.endsWith(".torrent") -> PlaybackMediaKind.TORRENT
			path.endsWith(".m3u8") -> PlaybackMediaKind.HLS
			path.endsWith(".mpd") -> PlaybackMediaKind.DASH
			path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ->
				PlaybackMediaKind.PROGRESSIVE
			else -> PlaybackMediaKind.AUTO
		}
		val route = when {
			kind == PlaybackMediaKind.TORRENT -> PlaybackRoute.TORRENT_LOCAL_HTTP
			isCloudstream && kind == PlaybackMediaKind.HLS && (
				!path.endsWith(".m3u8") || path.contains("/config-") || path.endsWith(".json")
			) -> PlaybackRoute.TRANSFORMING_HLS_PROXY
			else -> PlaybackRoute.DIRECT
		}
		return NormalizedPlaybackRequest(
			mediaKind = kind,
			headers = originalHeaders.filterKeys { it.lowercase() !in unsafeHeaders }.toMap(),
			route = route,
		)
	}
}
