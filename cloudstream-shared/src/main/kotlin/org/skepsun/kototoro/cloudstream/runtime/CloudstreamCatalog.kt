package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentExternalTrack
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * One Cloudstream provider (`MainAPI`) seen through Kototoro's content model: home sections and search as lists,
 * `load` as details with episodes, `loadLinks` as playable pages with subtitles. Shared by Android
 * (`CloudstreamContentRepository`) and the Windows source runtime; [platform] supplies logging and challenge handling.
 */
@OptIn(Prerelease::class)
class CloudstreamCatalog(
    val source: CloudstreamSource,
    private val platform: CloudstreamPlatform,
) {
    private val gateway = CloudstreamApiGateway(source, platform)
    private val terminalSearchPages = ConcurrentHashMap<String, Int>()
    private val terminalMainPagePages = ConcurrentHashMap<String, Int>()

    val sortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(isSearchSupported = true)

    /** PAGE_INDEX paging: offset 0 is page 1. A blank query lists the home sections (one, if a section tag is set). */
    suspend fun getList(offset: Int, filter: ContentListFilter?): List<Content> {
        val query = filter?.query?.trim().orEmpty()
        platform.debug(
            "getList source=${source.displayName} offset=$offset query=${query.takeIf { it.isNotBlank() }} " +
                "hasMainPage=${source.api.hasMainPage} mainPageCount=${source.api.mainPage.size} filter=$filter",
        )
        if (query.isBlank()) {
            if (source.api.hasMainPage) return loadMainPage(offset, filter)
            platform.debug("getList returning empty because query is blank for source=${source.displayName}")
            return emptyList()
        }
        val page = (offset + 1).coerceAtLeast(1)
        val searchKey = query.lowercase()
        if (page > (terminalSearchPages[searchKey] ?: Int.MAX_VALUE)) return emptyList()
        val result = executeWithChallenge(source.api.mainUrl, "search") {
            gateway.search(query, page)
        } ?: error("Cloudstream search returned null: source=${source.displayName} query=$query page=$page")
        if (!result.hasNext) terminalSearchPages[searchKey] = page
        platform.debug(
            "search result source=${source.displayName} query=$query page=$page items=${result.items.size} " +
                "hasNext=${result.hasNext}",
        )
        return result.items.map { it.toKotoContent() }
    }

    suspend fun getDetails(manga: Content): Content {
        val response = executeWithChallenge(manga.url, "load") {
            gateway.load(manga.url)
        } ?: error("Cloudstream load returned null: source=${source.displayName} url=${manga.url}")
        val chapters = mapCloudstreamChapters(response, source)
        platform.debug(
            "load result source=${source.displayName} url=${manga.url} name=${response.name} " +
                "type=${response::class.simpleName} chapters=${chapters.size} respUrl=${response.url}",
        )
        return manga.copy(
            title = response.name.ifBlank { manga.title },
            altTitles = response.toAlternativeTitles().ifEmpty { manga.altTitles },
            publicUrl = response.url.ifBlank { manga.publicUrl },
            rating = response.score.toKotoRating() ?: manga.rating,
            contentRating = response.contentRating.toKotoContentRating() ?: manga.contentRating,
            coverUrl = response.posterUrl ?: manga.coverUrl,
            largeCoverUrl = response.backgroundPosterUrl ?: response.posterUrl ?: manga.largeCoverUrl,
            description = response.plot ?: manga.description,
            tags = response.tags.orEmpty().map { ContentTag(it, it, source) }.toSet().ifEmpty { manga.tags },
            state = response.toKotoState() ?: manga.state,
            authors = manga.authors,
            chapters = chapters,
            sourceData = response.toContentMetadata(manga.sourceData, source.name),
        )
    }

    /** Playable links of an episode; a direct media URL stands in when the plugin resolves none. */
    suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val links = resolveVideoPages(chapter)
        if (links.isNotEmpty()) return links
        if (chapter.url.isDirectPlayableUrl()) {
            platform.debug("loadLinks empty, falling back to direct url source=${source.displayName} url=${chapter.url}")
            return listOf(ContentPage(id = chapter.id, url = chapter.url, preview = null, source = source))
        }
        platform.warn("loadLinks resolved no playable links source=${source.displayName} url=${chapter.url}")
        return emptyList()
    }

    /** Links and subtitles as the plugin finds them, for players that start before `loadLinks` returns. */
    fun playbackEvents(chapter: ContentChapter, clearCache: Boolean = false): Flow<CloudstreamPlaybackEvent> =
        channelFlow { resolveVideoPages(chapter, clearCache) { event -> trySend(event) } }

    /** The provider's home sections, offered as one exclusive tag group. */
    fun getFilterOptions(): ContentListFilterOptions {
        val sectionTags = source.api.mainPage.mapIndexedNotNull { index, page ->
            page.name.takeIf { it.isNotBlank() }?.let { name -> ContentTag(title = name, key = sectionTagKey(index), source = source) }
        }.toSet()
        if (sectionTags.isEmpty()) return ContentListFilterOptions()
        return ContentListFilterOptions(
            availableTags = sectionTags,
            tagGroups = listOf(ContentTagGroup(title = "分区", tags = sectionTags, isExclusive = true)),
        )
    }

    /** The provider's own recommendations kept from `load`; empty when it gave none (hosts may search instead). */
    fun getRecommendations(seed: Content): List<Content> =
        CloudstreamMetadataCodec.decodeContent(seed.sourceData)?.recommendations.orEmpty().map { it.toKotoContent() }

    private fun SearchResponse.toKotoContent(
        mainPageRequest: MainPageRequest? = null,
        homeRowName: String? = null,
        horizontalImages: Boolean? = null,
    ): Content {
        val type = type ?: TvType.Movie
        CloudstreamArtworkHeaders.remember(source.name, posterUrl, posterHeaders)
        return Content(
            id = cloudstreamStableId("${source.name}|content|$url"),
            title = name,
            altTitles = buildSet {
                if (this@toKotoContent is AnimeSearchResponse) otherName?.takeIf { it.isNotBlank() }?.let(::add)
            },
            url = url,
            publicUrl = url,
            rating = score.toKotoRating() ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = posterUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = posterUrl,
            description = null,
            chapters = null,
            source = source,
            sourceData = CloudstreamMetadataCodec.encodeContent(
                CloudstreamContentMetadata(
                    type = type.name,
                    posterHeaders = CloudstreamArtworkHeaders.persistable(posterHeaders),
                    quality = quality?.name,
                    providerId = id,
                    mainPageRequestName = mainPageRequest?.name,
                    mainPageRequestData = mainPageRequest?.data,
                    homeRowName = homeRowName,
                    horizontalImages = horizontalImages,
                ),
            ),
        )
    }

    private fun CloudstreamRecommendationMetadata.toKotoContent(): Content {
        CloudstreamArtworkHeaders.remember(source.name, posterUrl, posterHeaders)
        return Content(
            id = cloudstreamStableId("${source.name}|content|$url"),
            title = name,
            altTitles = emptySet(),
            url = url,
            publicUrl = url,
            rating = score ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = posterUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = posterUrl,
            description = null,
            chapters = null,
            source = source,
            sourceData = CloudstreamMetadataCodec.encodeContent(CloudstreamContentMetadata(type = type, posterHeaders = posterHeaders)),
        )
    }

    private fun LoadResponse.toKotoState(): ContentState? {
        if (comingSoon) return ContentState.UPCOMING
        return when ((this as? EpisodeResponse)?.showStatus) {
            ShowStatus.Ongoing -> ContentState.ONGOING
            ShowStatus.Completed -> ContentState.FINISHED
            null -> null
        }
    }

    private fun String?.toKotoContentRating(): ContentRating? =
        this?.takeIf { it.contains("18", true) || it.contains("adult", true) }?.let { ContentRating.ADULT }

    private fun sectionTagKey(index: Int): String = "$SECTION_TAG_PREFIX$index"

    private fun parseSectionTagIndex(key: String): Int? =
        if (!key.startsWith(SECTION_TAG_PREFIX)) null else key.removePrefix(SECTION_TAG_PREFIX).toIntOrNull()

    private suspend fun resolveVideoPages(
        chapter: ContentChapter,
        clearCache: Boolean = false,
        onEvent: ((CloudstreamPlaybackEvent) -> Unit)? = null,
    ): List<ContentPage> {
        platform.debug(
            "loadLinks start source=${source.displayName} chapterId=${chapter.id} chapterTitle=${chapter.title} " +
                "locator=${chapter.url} branch=${chapter.branch}",
        )
        if (platform.isDebug) {
            platform.debug(
                "loadLinks extractors source=${source.displayName} total=${synchronized(extractorApis) { extractorApis.size }}",
            )
        }
        val directLinkType = CloudstreamMetadataCodec.decodeEpisode(chapter.sourceData)
            ?.linkType
            ?.let { runCatching { ExtractorLinkType.valueOf(it) }.getOrNull() }
            ?: when {
                chapter.url.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                chapter.url.isCloudstreamTorrentLocator() -> ExtractorLinkType.TORRENT
                else -> null
            }
        if (directLinkType == ExtractorLinkType.MAGNET || directLinkType == ExtractorLinkType.TORRENT) {
            val page = ContentPage(
                id = cloudstreamStableId("${chapter.id}|${chapter.url}"),
                url = chapter.url,
                preview = null,
                playbackLabel = chapter.title,
                source = source,
            )
            onEvent?.invoke(CloudstreamPlaybackEvent.Link(page, directLinkType))
            return listOf(page)
        }
        val cacheKey = source.name to chapter.id
        val snapshot = playbackCache.prepare(cacheKey, clearCache)
        val subtitles = LinkedHashMap<String, SubtitleFile>().apply { snapshot.subtitles.forEach { put(it.url, it) } }
        val links = LinkedHashMap<String, ExtractorLink>().apply {
            snapshot.links.filterNot { it.url.isMissingCloudstreamUrl() }.forEach { put(it.url, it) }
        }
        fun SubtitleFile.toTrack() = ContentExternalTrack(url = url, lang = lang, headers = headers)
        fun ExtractorLink.toPage(): ContentPage = ContentPage(
            id = cloudstreamStableId("${chapter.id}|$name|$url"),
            url = url,
            preview = null,
            headers = getAllHeaders().toMutableMap().apply {
                if (keys.none { it.equals("User-Agent", ignoreCase = true) }) put("User-Agent", USER_AGENT)
            },
            externalSubtitleTracks = subtitles.values.map { it.toTrack() },
            playbackLabel = name.takeIf { it.isNotBlank() },
            playbackQuality = quality.takeIf { it > 0 },
            // ExtractorLink has its own `source` (a provider name).
            source = this@CloudstreamCatalog.source,
        )
        snapshot.links.filter { it.type in PLAYABLE_LINK_TYPES }
            .forEach { onEvent?.invoke(CloudstreamPlaybackEvent.Link(it.toPage(), it.type)) }
        snapshot.subtitles.forEach { onEvent?.invoke(CloudstreamPlaybackEvent.Subtitle(it.toTrack())) }
        if (snapshot.saturated) {
            platform.debug("loadLinks using saturated cache source=${source.displayName} chapterId=${chapter.id}")
            return links.values.filter { it.type in PLAYABLE_LINK_TYPES }.map { it.toPage() }
        }
        var detectedChallenge: Throwable? = null
        suspend fun loadLinksOnce(): Boolean {
            val result = gateway.loadLinks(
                data = chapter.url,
                isCasting = false,
                subtitleCallback = { subtitle ->
                    if (subtitle.url.isBlank() || !playbackCache.addSubtitle(cacheKey, subtitle.url, subtitle)) {
                        return@loadLinks
                    }
                    subtitles[subtitle.url] = subtitle
                    onEvent?.invoke(CloudstreamPlaybackEvent.Subtitle(subtitle.toTrack()))
                },
                linkCallback = { link ->
                    if (link.url.isMissingCloudstreamUrl()) {
                        platform.debug("loadLinks rejected invalid url source=${source.displayName} name=${link.name}")
                        return@loadLinks
                    }
                    if (!playbackCache.addLink(cacheKey, link.url, link)) return@loadLinks
                    links[link.url] = link
                    if (link.type in PLAYABLE_LINK_TYPES) {
                        onEvent?.invoke(CloudstreamPlaybackEvent.Link(link.toPage(), link.type))
                    }
                    platform.debug(
                        "loadLinks link source=${source.displayName} name=${link.name} type=${link.type} " +
                            "quality=${link.quality} url=${link.url}",
                    )
                },
            )
            detectedChallenge = result.challenge ?: detectedChallenge
            return result.success
        }
        var success = false
        val firstError = runCatchingCancellable { success = loadLinksOnce() }.exceptionOrNull()
        var completed = firstError == null
        var challengeRetried = false
        if (firstError != null) {
            platform.error("loadLinks failed source=${source.displayName} url=${chapter.url}", firstError)
            val challenge = platform.findChallenge(firstError, source)
            if (challenge != null) {
                platform.warn(
                    "loadLinks challenge detected source=${source.displayName} url=${chapter.url} " +
                        "cookies=${platform.cookieSummary(chapter.url)}",
                )
                challengeRetried = true
                if (platform.resolveChallenge(challenge, chapter.url, "loadLinks")) {
                    val retry = runCatchingCancellable { loadLinksOnce() }
                        .onFailure { platform.error("loadLinks retry failed source=${source.displayName}", it) }
                    completed = retry.isSuccess
                    success = retry.getOrDefault(false)
                }
            }
        }
        if (!challengeRetried && links.values.none { it.type in PLAYABLE_LINK_TYPES }) {
            detectedChallenge?.let { challenge ->
                platform.warn("loadLinks plugin fallbacks exhausted after a challenge source=${source.displayName}")
                if (platform.resolveChallenge(challenge, chapter.url, "loadLinks fallback")) {
                    val retry = runCatchingCancellable { loadLinksOnce() }
                        .onFailure { platform.error("loadLinks fallback retry failed source=${source.displayName}", it) }
                    completed = retry.isSuccess
                    success = retry.getOrDefault(false)
                }
            }
        }
        if (completed) playbackCache.finish(cacheKey)
        val pages = links.values.filter { it.type in PLAYABLE_LINK_TYPES }.map { it.toPage() }
        if (platform.isDebug) {
            platform.debug(
                "loadLinks done source=${source.displayName} success=$success links=${pages.size} " +
                    "subtitles=${subtitles.size} rawLinks=${links.size} types=${links.values.groupingBy { it.type }.eachCount()}",
            )
        }
        return pages
    }

    private fun String.isDirectPlayableUrl(): Boolean {
        if (!startsWith("http://") && !startsWith("https://")) return false
        val lower = lowercase()
        return listOf(".m3u8", ".mp4", ".mkv", ".webm", ".mpd").any(lower::contains)
    }

    private suspend fun loadMainPage(offset: Int, filter: ContentListFilter?): List<Content> {
        val mainPages = source.api.mainPage
        if (mainPages.isEmpty()) {
            platform.warn("main page load skipped source=${source.displayName} because mainPage is empty")
            return emptyList()
        }
        val page = (offset + 1).coerceAtLeast(1)
        val selectedSectionIndex = filter?.tags
            ?.firstNotNullOfOrNull { tag -> parseSectionTagIndex(tag.key) }
            ?.takeIf { it in mainPages.indices }
        val requests = selectedSectionIndex?.let { listOf(mainPages[it]) } ?: mainPages
        gateway.prepareMainPageRequest()
        val responses = if (source.api.sequentialMainPage || requests.size == 1) {
            requests.mapIndexedNotNull { index, mainPage ->
                if (index > 0) delay(source.api.sequentialMainPageDelay)
                loadMainPageEntry(mainPage, index, page)
            }
        } else {
            coroutineScope {
                requests.mapIndexed { index, mainPage -> async { loadMainPageEntry(mainPage, index, page) } }
                    .awaitAll().filterNotNull()
            }
        }
        val aggregated = ArrayList<CloudstreamMainPageItem>()
        responses.forEach { entry ->
            platform.debug(
                "main page load source=${source.displayName} requestName=${entry.request.name} slot=${entry.slot} " +
                    "page=$page rows=${entry.response.items.size} hasNext=${entry.response.hasNext}",
            )
            entry.response.items.forEach { row ->
                row.list.forEach { item ->
                    aggregated += CloudstreamMainPageItem(item, entry.request, row.name, row.isHorizontalImages)
                }
            }
        }
        val deduped = aggregated.distinctBy { it.response.url }
        return deduped.map { item ->
            item.response.toKotoContent(
                mainPageRequest = item.request,
                homeRowName = item.rowName,
                horizontalImages = item.horizontalImages,
            )
        }.also { items ->
            if (items.isEmpty() && aggregated.isEmpty() && platform.isDebug) {
                requests.forEachIndexed { index, mainPage ->
                    platform.diagnoseEmptyMainPage(
                        source, MainPageRequest(mainPage.name, mainPage.data, mainPage.horizontalImages), index, page,
                    )
                }
            }
        }
    }

    private suspend fun loadMainPageEntry(
        page: com.lagradost.cloudstream3.MainPageData,
        slot: Int,
        requestPage: Int,
    ): CloudstreamMainPageResponse? {
        val request = MainPageRequest(page.name, page.data, page.horizontalImages)
        val terminalKey = "${request.name}\n${request.data}"
        if (requestPage > (terminalMainPagePages[terminalKey] ?: Int.MAX_VALUE)) return null
        val response = try {
            gateway.getMainPage(requestPage, request)
        } catch (error: Throwable) {
            val challenge = platform.findChallenge(error, source)
            platform.error(
                "main page load failed source=${source.displayName} requestName=${request.name} slot=$slot page=$requestPage",
                error,
            )
            throw challenge ?: error
        } ?: return null
        if (!response.hasNext) terminalMainPagePages[terminalKey] = requestPage
        return CloudstreamMainPageResponse(request, response, slot)
    }

    private suspend fun <T> executeWithChallenge(url: String, stage: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            val challenge = platform.findChallenge(error, source) ?: throw error
            platform.warn("$stage challenge detected source=${source.displayName} url=$url", error)
            if (!platform.resolveChallenge(challenge, url, stage)) throw challenge
            block()
        }
    }

    companion object {
        private const val SECTION_TAG_PREFIX = "cloudstream-section:"
        private const val PLAYBACK_CACHE_TTL_MILLIS = 20 * 60 * 1000L
        private val PLAYABLE_LINK_TYPES = setOf(
            ExtractorLinkType.VIDEO,
            ExtractorLinkType.DASH,
            ExtractorLinkType.M3U8,
            ExtractorLinkType.TORRENT,
            ExtractorLinkType.MAGNET,
        )
        private val playbackCache =
            CloudstreamLinkSessionCache<Pair<String, Long>, ExtractorLink, SubtitleFile>(PLAYBACK_CACHE_TTL_MILLIS)
    }
}

fun mapCloudstreamChapters(response: LoadResponse, source: CloudstreamSource): List<ContentChapter> {
    val (singleLocator, singleLinkType) = when (response) {
        is MovieLoadResponse -> response.dataUrl to null
        is LiveStreamLoadResponse -> response.dataUrl to null
        is TorrentLoadResponse -> response.torrent?.takeIf { it.isNotBlank() }
            ?.let { it to ExtractorLinkType.TORRENT }
            ?: response.magnet?.let { it to ExtractorLinkType.MAGNET }
            ?: (null to null)
        else -> null to null
    }
    if (singleLocator != null) {
        if (singleLocator.isBlank()) return emptyList()
        return listOf(
            ContentChapter(
                id = cloudstreamStableId("${source.name}|${response::class.simpleName}|$singleLocator"),
                title = response.name,
                number = 1f,
                volume = 1,
                url = singleLocator,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
                sourceData = singleLinkType?.let { linkType ->
                    CloudstreamMetadataCodec.encodeEpisode(CloudstreamEpisodeMetadata(linkType = linkType.name))
                },
            ),
        )
    }

    val groupedEpisodes = when (response) {
        is TvSeriesLoadResponse -> listOf(
            DubStatus.None to response.episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
        )
        is AnimeLoadResponse -> response.episodes.entries.map { it.key to it.value }
        else -> emptyList()
    }
    return groupedEpisodes.flatMap { (dubStatus, episodes) ->
        episodes.filter { it.data.isNotBlank() }.mapIndexed { index, episode ->
            val episodeNumber = episode.episode ?: (index + 1)
            val displaySeason = (response as? EpisodeResponse)?.seasonNames
                ?.firstOrNull { it.season == episode.season }
                ?.displaySeason
            val identity = episode.episode?.let { "${episode.season}|$it" } ?: episode.data
            ContentChapter(
                id = cloudstreamStableId("${source.name}|episode|${dubStatus.name}|$identity"),
                title = resolveCloudstreamEpisodeTitle(episode.name, episodeNumber),
                number = episodeNumber.toFloat(),
                volume = displaySeason ?: episode.season ?: 0,
                url = episode.data,
                scanlator = null,
                uploadDate = episode.date ?: 0L,
                branch = dubStatus.takeUnless { it == DubStatus.None }?.name,
                source = source,
                sourceData = CloudstreamMetadataCodec.encodeEpisode(
                    CloudstreamEpisodeMetadata(
                        dubStatus = dubStatus.takeUnless { it == DubStatus.None }?.name,
                        season = episode.season,
                        displaySeason = displaySeason,
                        episode = episode.episode,
                        posterUrl = episode.posterUrl,
                        score = episode.score.toKotoRating(),
                        description = episode.description,
                        runtimeSeconds = episode.runTime,
                    ),
                ),
            )
        }
    }.distinctBy { it.id }
}

sealed interface CloudstreamPlaybackEvent {
    data class Link(val page: ContentPage, val type: ExtractorLinkType) : CloudstreamPlaybackEvent
    data class Subtitle(val track: ContentExternalTrack) : CloudstreamPlaybackEvent
}

fun resolveCloudstreamEpisodeTitle(name: String?, episodeNumber: Int): String {
    val title = name?.trim().orEmpty()
    return title.takeIf { it.isNotEmpty() && !isCloudstreamStructuredLocator(it) } ?: "Episode $episodeNumber"
}

fun isCloudstreamStructuredLocator(value: String): Boolean =
    value.trimStart().let { it.startsWith('[') || it.startsWith('{') }

fun shouldResolveCloudstreamPlaybackLocator(value: String, isKnownChapterLocator: Boolean): Boolean =
    isKnownChapterLocator || isCloudstreamStructuredLocator(value)

fun String.isMissingCloudstreamUrl(): Boolean {
    val normalized = trim()
    return normalized.isEmpty() || normalized.equals("null", ignoreCase = true) ||
        normalized.equals("undefined", ignoreCase = true)
}

/** A magnet link or a `.torrent` file URL. */
fun String.isCloudstreamTorrentLocator(): Boolean {
    val locator = trim()
    if (locator.startsWith("magnet:", ignoreCase = true)) return true
    val path = runCatching { URI(locator).path }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: locator.substringBefore('?').substringBefore('#')
    return path.endsWith(".torrent", ignoreCase = true)
}

fun cloudstreamStableId(value: String): Long = value.longHashCode() and Long.MAX_VALUE

private data class CloudstreamMainPageItem(
    val response: SearchResponse,
    val request: MainPageRequest,
    val rowName: String,
    val horizontalImages: Boolean,
)

private data class CloudstreamMainPageResponse(
    val request: MainPageRequest,
    val response: com.lagradost.cloudstream3.HomePageResponse,
    val slot: Int,
)
