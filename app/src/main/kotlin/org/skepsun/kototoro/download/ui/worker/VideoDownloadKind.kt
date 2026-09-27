package org.skepsun.kototoro.download.ui.worker

import org.skepsun.kototoro.video.player.PlaybackMediaKind
import org.skepsun.kototoro.video.player.looksLikeHlsManifest

/**
 * Decides whether a video is fetched as HLS segments rather than as a single file, with the same
 * evidence the player uses: the kind the source declared, the URL, and for an undeclared kind the
 * start of the response. A stream URL without `.m3u8` otherwise downloads its playlist as the video.
 *
 * [probeManifestPrefix] returns the beginning of the response body, or null when it cannot be read;
 * it is only called when neither the declared kind nor the URL settles the question.
 */
internal suspend fun shouldDownloadVideoAsHls(
    mediaKind: PlaybackMediaKind,
    url: String,
    probeManifestPrefix: suspend () -> String?,
): Boolean {
    if (mediaKind == PlaybackMediaKind.HLS || url.contains(".m3u8", ignoreCase = true)) return true
    if (mediaKind != PlaybackMediaKind.AUTO) return false
    return probeManifestPrefix()?.let(::looksLikeHlsManifest) == true
}
