package org.skepsun.kototoro.video.domain

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.flow.collect
import okhttp3.Headers
import org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamContentRepository
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamPlaybackEvent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.video.data.isTorrentLocator
import org.skepsun.kototoro.video.player.PlaybackMediaKind

data class VideoCandidate(
    val url: String,
    val title: String,
    val resolution: Int?,
    val headers: Map<String, String>?,
    val subtitleTracks: List<PlaybackSubtitle> = emptyList(),
    val audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
    val isTorrent: Boolean = false,
    val mediaKind: PlaybackMediaKind = PlaybackMediaKind.AUTO,
)

suspend fun ContentRepository.resolveVideoCandidates(chapter: ContentChapter): List<VideoCandidate> {
    val aniyomiRepo = this as? AniyomiAnimeRepository
    if (aniyomiRepo != null) {
        return aniyomiRepo.getVideoListForChapter(chapter)
            .filter { it.videoUrl.isNotBlank() }
            .map { video ->
                val headers = video.headers
                    ?.toMultimap()
                    ?.mapValues { entry -> entry.value.firstOrNull().orEmpty() }
                    ?.filterValues { it.isNotBlank() }
                VideoCandidate(
                    url = video.videoUrl,
                    title = video.videoTitle,
                    resolution = video.resolution,
                    headers = headers,
                    subtitleTracks = video.toPlaybackSubtitles(
                        origin = SubtitleOrigin.ANIYOMI_EXTERNAL,
                        inheritedHeaders = headers.orEmpty(),
                    ),
                    audioTracks = video.audioTracks,
                    isTorrent = video.videoUrl.isTorrentLocator(),
                    mediaKind = inferPlaybackMediaKind(video.videoUrl),
                )
            }
    }
    val cloudstreamRepo = this as? CloudstreamContentRepository
    if (cloudstreamRepo != null) {
        val candidates = LinkedHashMap<String, VideoCandidate>()
        val subtitles = LinkedHashMap<String, PlaybackSubtitle>()
        cloudstreamRepo.getPlaybackEvents(chapter).collect { event ->
            when (event) {
                is CloudstreamPlaybackEvent.Link -> {
                    val page = event.page
                    candidates.putIfAbsent(
                        page.url,
                        VideoCandidate(
                            url = page.url,
                            title = buildFallbackTitle(page),
                            resolution = page.playbackQuality,
                            headers = page.headers?.takeIf { it.isNotEmpty() },
                            subtitleTracks = page.externalSubtitleTracks.map { it.toCloudstreamPlaybackSubtitle() },
                            isTorrent = event.type == ExtractorLinkType.MAGNET ||
                                event.type == ExtractorLinkType.TORRENT ||
                                page.url.isTorrentLocator(),
                            mediaKind = when (event.type) {
                                ExtractorLinkType.M3U8 -> PlaybackMediaKind.HLS
                                ExtractorLinkType.DASH -> PlaybackMediaKind.DASH
                                ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT -> PlaybackMediaKind.TORRENT
                                ExtractorLinkType.VIDEO -> PlaybackMediaKind.PROGRESSIVE
                                else -> inferPlaybackMediaKind(page.url)
                            },
                        ),
                    )
                }
                is CloudstreamPlaybackEvent.Subtitle -> {
                    subtitles.putIfAbsent(
                        event.track.url,
                        event.track.toCloudstreamPlaybackSubtitle(),
                    )
                }
            }
        }
        return candidates.values.map { candidate ->
            candidate.copy(subtitleTracks = (candidate.subtitleTracks + subtitles.values).distinctBy(PlaybackSubtitle::id))
        }
    }
    val pages = getPages(chapter, nextChapterUrl = null)
    return pages.toFallbackVideoCandidates(this)
}

private suspend fun List<ContentPage>.toFallbackVideoCandidates(repo: ContentRepository): List<VideoCandidate> {
    return mapNotNull { page ->
        val streamUrl = runCatchingCancellable { repo.getPageUrl(page) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        VideoCandidate(
            url = streamUrl,
            title = buildFallbackTitle(page),
            resolution = page.playbackQuality,
            headers = page.headers?.takeIf { it.isNotEmpty() },
            subtitleTracks = page.externalSubtitleTracks.map {
                PlaybackSubtitle.external(
                    url = it.url,
                    label = it.lang,
                    languageTag = it.lang.takeIf(String::isNotBlank),
                    origin = SubtitleOrigin.ANIYOMI_EXTERNAL,
                    headers = it.headers.orEmpty(),
                )
            },
            isTorrent = streamUrl.isTorrentLocator(),
            mediaKind = inferPlaybackMediaKind(streamUrl),
        )
    }
}

private fun inferPlaybackMediaKind(url: String): PlaybackMediaKind {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        url.isTorrentLocator() -> PlaybackMediaKind.TORRENT
        path.endsWith(".m3u8") -> PlaybackMediaKind.HLS
        path.endsWith(".mpd") -> PlaybackMediaKind.DASH
        path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") -> PlaybackMediaKind.PROGRESSIVE
        else -> PlaybackMediaKind.AUTO
    }
}

private fun buildFallbackTitle(page: ContentPage): String {
    val qualityLabel = page.playbackQuality?.takeIf { it > 0 }?.let { "${it}p" }
    val label = page.playbackLabel?.trim().orEmpty()
    return when {
        !qualityLabel.isNullOrBlank() && label.isNotBlank() -> "$qualityLabel · $label"
        !qualityLabel.isNullOrBlank() -> qualityLabel
        label.isNotBlank() -> label
        else -> ""
    }
}

fun VideoCandidate.toOkHttpHeaders(): Headers? {
    val headerMap = headers?.takeIf { it.isNotEmpty() } ?: return null
    return Headers.headersOf(*headerMap.flatMap { listOf(it.key, it.value) }.toTypedArray())
}
