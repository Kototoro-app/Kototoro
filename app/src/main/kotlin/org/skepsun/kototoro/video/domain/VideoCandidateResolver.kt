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

data class VideoCandidate(
    val url: String,
    val title: String,
    val resolution: Int?,
    val headers: Map<String, String>?,
    val subtitleTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
    val audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
    val isTorrent: Boolean = false,
)

suspend fun ContentRepository.resolveVideoCandidates(chapter: ContentChapter): List<VideoCandidate> {
    val aniyomiRepo = this as? AniyomiAnimeRepository
    if (aniyomiRepo != null) {
        return aniyomiRepo.getVideoListForChapter(chapter)
            .filter { it.videoUrl.isNotBlank() }
            .map { video ->
                VideoCandidate(
                    url = video.videoUrl,
                    title = video.videoTitle,
                    resolution = video.resolution,
                    headers = video.headers
                        ?.toMultimap()
                        ?.mapValues { entry -> entry.value.firstOrNull().orEmpty() }
                        ?.filterValues { it.isNotBlank() },
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                    isTorrent = video.videoUrl.isTorrentLocator(),
                )
            }
    }
    val cloudstreamRepo = this as? CloudstreamContentRepository
    if (cloudstreamRepo != null) {
        val candidates = LinkedHashMap<String, VideoCandidate>()
        val subtitles = LinkedHashMap<String, eu.kanade.tachiyomi.animesource.model.Track>()
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
                            subtitleTracks = page.externalSubtitleTracks.map {
                                eu.kanade.tachiyomi.animesource.model.Track(it.url, it.lang)
                            },
                            isTorrent = event.type == ExtractorLinkType.MAGNET ||
                                event.type == ExtractorLinkType.TORRENT ||
                                page.url.isTorrentLocator(),
                        ),
                    )
                }
                is CloudstreamPlaybackEvent.Subtitle -> {
                    subtitles.putIfAbsent(
                        event.track.url,
                        eu.kanade.tachiyomi.animesource.model.Track(event.track.url, event.track.lang),
                    )
                }
            }
        }
        return candidates.values.map { candidate ->
            candidate.copy(subtitleTracks = (candidate.subtitleTracks + subtitles.values).distinctBy { it.url })
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
                eu.kanade.tachiyomi.animesource.model.Track(it.url, it.lang)
            },
            isTorrent = streamUrl.isTorrentLocator(),
        )
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
