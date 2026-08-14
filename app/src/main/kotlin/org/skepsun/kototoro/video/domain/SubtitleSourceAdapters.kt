package org.skepsun.kototoro.video.domain

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import org.skepsun.kototoro.parsers.model.ContentExternalTrack

internal fun Track.toPlaybackSubtitle(
    origin: SubtitleOrigin,
    headers: Map<String, String> = emptyMap(),
): PlaybackSubtitle = PlaybackSubtitle.external(
    url = url,
    label = lang.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "Subtitle" } },
    languageTag = lang.takeIf(String::isNotBlank),
    origin = origin,
    headers = headers,
)

internal fun Video.toPlaybackSubtitles(
    origin: SubtitleOrigin,
    inheritedHeaders: Map<String, String> = emptyMap(),
): List<PlaybackSubtitle> = subtitleTracks.map { track ->
    track.toPlaybackSubtitle(origin = origin, headers = inheritedHeaders)
}

internal fun ContentExternalTrack.toCloudstreamPlaybackSubtitle(): PlaybackSubtitle = PlaybackSubtitle.external(
    url = url,
    label = lang,
    languageTag = lang.takeIf(String::isNotBlank),
    origin = SubtitleOrigin.CLOUDSTREAM_EXTERNAL,
    headers = headers.orEmpty(),
)
