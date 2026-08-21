package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
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
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
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
import org.skepsun.kototoro.video.data.isTorrentLocator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

@OptIn(Prerelease::class)
class CloudstreamContentRepository(
    override val source: CloudstreamSource,
    cache: MemoryContentCache,
    private val webViewExecutor: WebViewExecutor,
    private val cookieJar: MutableCookieJar,
    private val settings: AppSettings,
) : CachingContentRepository(cache) {
    private val gateway = CloudstreamApiGateway(source)
    private val terminalSearchPages = ConcurrentHashMap<String, Int>()
    private val terminalMainPagePages = ConcurrentHashMap<String, Int>()

    override val listPagingMode: ContentRepository.ListPagingMode = ContentRepository.ListPagingMode.PAGE_INDEX

    override val sortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    override var defaultSortOrder: SortOrder = SortOrder.RELEVANCE

    override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
        val query = filter?.query?.trim().orEmpty()
        Log.d(
            TAG,
            "getList source=${source.displayName} offset=$offset order=$order query=${query.takeIf { it.isNotBlank() }} " +
                "hasMainPage=${source.api.hasMainPage} mainPageCount=${source.api.mainPage.size} filter=$filter",
        )
        if (query.isBlank()) {
            if (source.api.hasMainPage) {
                return loadMainPage(offset, filter)
            }
            Log.d(
                TAG,
                "getList returning empty because query is blank for source=${source.displayName} " +
                    "hasMainPage=${source.api.hasMainPage}",
            )
            return emptyList()
        }
        val page = (offset + 1).coerceAtLeast(1)
        val searchKey = query.lowercase()
        if (page > (terminalSearchPages[searchKey] ?: Int.MAX_VALUE)) return emptyList()
        val result = executeWithCloudflare(source.api.mainUrl, "search") {
            gateway.search(query, page)
        } ?: error("Cloudstream search returned null: source=${source.displayName} query=$query page=$page")
        if (!result.hasNext) terminalSearchPages[searchKey] = page
        Log.d(
            TAG,
            "search result source=${source.displayName} query=$query page=$page items=${result.items.size} " +
                "hasNext=${result.hasNext}",
        )
        return result.items.map { item ->
            item.toKotoContent(source)
        }
    }

    override suspend fun getDetailsImpl(manga: Content): Content {
        val response = executeWithCloudflare(manga.url, "load") {
            gateway.load(manga.url)
        } ?: error("Cloudstream load returned null: source=${source.displayName} url=${manga.url}")
        val chapters = response.toChapters(source)
        Log.d(
            TAG,
            "load result source=${source.displayName} url=${manga.url} name=${response.name} " +
                "type=${response::class.simpleName} chapters=${chapters.size} " +
                "respUrl=${response.url} " +
                when (response) {
                    is MovieLoadResponse -> "movieDataUrl=${response.dataUrl}"
                    is TvSeriesLoadResponse -> "episodesSize=${response.episodes.size}"
                    is AnimeLoadResponse -> "episodesSize=${response.episodes.entries.sumOf { it.value.size }}"
                    else -> "unk"
                },
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
            tags = response.tags.orEmpty()
                .map { ContentTag(it, it, source) }
                .toSet()
                .ifEmpty { manga.tags },
            state = response.toKotoState() ?: manga.state,
            authors = manga.authors,
            chapters = chapters,
            sourceData = response.toContentMetadata(manga.sourceData, source.name),
        )
    }

    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
        val links = resolveVideoPages(chapter)
        if (links.isNotEmpty()) {
            return links
        }
        if (chapter.url.isDirectPlayableUrl()) {
            Log.d(
                TAG,
                "loadLinks empty, falling back to direct url source=${source.displayName} " +
                    "chapterId=${chapter.id} url=${chapter.url}",
            )
            return listOf(
                ContentPage(
                    id = chapter.id,
                    url = chapter.url,
                    preview = null,
                    source = source,
                ),
            )
        }
        Log.w(
            TAG,
            "loadLinks resolved no playable links source=${source.displayName} chapterId=${chapter.id} url=${chapter.url}",
        )
        return emptyList()
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    internal fun getPlaybackEvents(
        chapter: ContentChapter,
        clearCache: Boolean = false,
    ): Flow<CloudstreamPlaybackEvent> = channelFlow {
        resolveVideoPages(chapter, clearCache) { event ->
            trySend(event)
        }
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val sectionTags = source.api.mainPage
            .mapIndexedNotNull { index, page ->
                page.name.takeIf { it.isNotBlank() }?.let { name ->
                    ContentTag(
                        title = name,
                        key = sectionTagKey(index),
                        source = source,
                    )
                }
            }
            .toSet()
        if (sectionTags.isEmpty()) {
            return ContentListFilterOptions()
        }
        return ContentListFilterOptions(
            availableTags = sectionTags,
            tagGroups = listOf(
                ContentTagGroup(
                    title = "分区",
                    tags = sectionTags,
                    isExclusive = true,
                ),
            ),
        )
    }

    override suspend fun getRelatedContentImpl(seed: Content): List<Content> {
        val recommendations = CloudstreamMetadataCodec.decodeContent(seed.sourceData)
            ?.recommendations
            .orEmpty()
        if (recommendations.isNotEmpty()) {
            return recommendations.map { it.toKotoContent(source) }
        }
        return RelatedContentSearchFallback.find(seed) { query ->
            getList(
                offset = 0,
                order = defaultSortOrder,
                filter = ContentListFilter(query = query),
            )
        }
    }

    private fun SearchResponse.toKotoContent(
        source: CloudstreamSource,
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
                if (this@toKotoContent is AnimeSearchResponse) {
                    otherName?.takeIf { it.isNotBlank() }?.let(::add)
                }
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

    private fun CloudstreamRecommendationMetadata.toKotoContent(source: CloudstreamSource): Content {
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
            sourceData = CloudstreamMetadataCodec.encodeContent(
                CloudstreamContentMetadata(
                    type = type,
                    posterHeaders = posterHeaders,
                ),
            ),
        )
    }

    private fun LoadResponse.toChapters(source: CloudstreamSource): List<ContentChapter> {
        return mapCloudstreamChapters(this, source)
    }

    private fun LoadResponse.toKotoState(): ContentState? {
        if (comingSoon) return ContentState.UPCOMING
        return when ((this as? EpisodeResponse)?.showStatus) {
            ShowStatus.Ongoing -> ContentState.ONGOING
            ShowStatus.Completed -> ContentState.FINISHED
            null -> null
        }
    }

    private fun String?.toKotoContentRating(): ContentRating? {
        return this?.takeIf { it.contains("18", true) || it.contains("adult", true) }?.let {
            ContentRating.ADULT
        }
    }

    private fun sectionTagKey(index: Int): String = "$SECTION_TAG_PREFIX$index"

    private fun parseSectionTagIndex(key: String): Int? {
        if (!key.startsWith(SECTION_TAG_PREFIX)) return null
        return key.removePrefix(SECTION_TAG_PREFIX).toIntOrNull()
    }

    private suspend fun resolveVideoPages(
        chapter: ContentChapter,
        clearCache: Boolean = false,
        onEvent: ((CloudstreamPlaybackEvent) -> Unit)? = null,
    ): List<ContentPage> {
        Log.d(
            TAG,
            "loadLinks start source=${source.displayName} chapterId=${chapter.id} chapterTitle=${chapter.title} " +
                "locator=${chapter.url} branch=${chapter.branch}",
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "loadLinks extractors source=${source.displayName} total=${synchronized(extractorApis) { extractorApis.size }} " +
                    "sample=${cloudstreamExtractorSummary()}",
            )
        }
        val directLinkType = CloudstreamMetadataCodec.decodeEpisode(chapter.sourceData)
            ?.linkType
            ?.let { runCatching { ExtractorLinkType.valueOf(it) }.getOrNull() }
            ?: when {
                chapter.url.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                chapter.url.isTorrentLocator() -> ExtractorLinkType.TORRENT
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
        val subtitles = LinkedHashMap<String, SubtitleFile>().apply {
            snapshot.subtitles.forEach { put(it.url, it) }
        }
        val links = LinkedHashMap<String, ExtractorLink>().apply {
            snapshot.links.filterNot { it.url.isMissingCloudstreamUrl() }.forEach { put(it.url, it) }
        }
        fun SubtitleFile.toTrack() = ContentExternalTrack(
            url = url,
            lang = lang,
            headers = headers,
        )
        fun ExtractorLink.toPage(): ContentPage {
            return ContentPage(
                id = cloudstreamStableId("${chapter.id}|$name|$url"),
                url = url,
                preview = null,
                headers = getAllHeaders().toMutableMap().apply {
                    if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        put("User-Agent", USER_AGENT)
                    }
                },
                externalSubtitleTracks = subtitles.values.map { it.toTrack() },
                playbackLabel = name.takeIf { it.isNotBlank() },
                playbackQuality = quality.takeIf { it > 0 },
                source = this@CloudstreamContentRepository.source,
            )
        }
        snapshot.links
            .filter { it.type in PLAYABLE_LINK_TYPES }
            .forEach { onEvent?.invoke(CloudstreamPlaybackEvent.Link(it.toPage(), it.type)) }
        snapshot.subtitles.forEach { subtitle ->
            onEvent?.invoke(CloudstreamPlaybackEvent.Subtitle(subtitle.toTrack()))
        }
        if (snapshot.saturated) {
            Log.d(TAG, "loadLinks using saturated cache source=${source.displayName} chapterId=${chapter.id}")
            return links.values.filter { it.type in PLAYABLE_LINK_TYPES }.map { it.toPage() }
        }
        var detectedChallenge: CloudFlareProtectedException? = null
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
                    Log.d(
                        TAG,
                        "loadLinks subtitle source=${source.displayName} chapterId=${chapter.id} " +
                            "lang=${subtitle.lang} url=${subtitle.url}",
                    )
                },
                linkCallback = { link ->
                    if (link.url.isMissingCloudstreamUrl()) {
                        Log.d(
                            TAG,
                            "loadLinks rejected invalid url source=${source.displayName} chapterId=${chapter.id} " +
                                "name=${link.name} url=${link.url}",
                        )
                        return@loadLinks
                    }
                    if (!playbackCache.addLink(cacheKey, link.url, link)) {
                        return@loadLinks
                    }
                    links[link.url] = link
                    if (link.type in PLAYABLE_LINK_TYPES) {
                        onEvent?.invoke(CloudstreamPlaybackEvent.Link(link.toPage(), link.type))
                    }
                    Log.d(
                        TAG,
                        "loadLinks link source=${source.displayName} chapterId=${chapter.id} name=${link.name} " +
                            "type=${link.type} quality=${link.quality} url=${link.url} headers=${link.getAllHeaders().keys}",
                    )
                },
            )
            detectedChallenge = result.challenge ?: detectedChallenge
            return result.success
        }
        var success = false
        val firstError = runCatchingCancellable {
            success = loadLinksOnce()
        }.exceptionOrNull()
        var completed = firstError == null
        var captchaFallbackAttempted = false
        if (firstError != null) {
            Log.e(
                TAG,
                "loadLinks failed source=${source.displayName} chapterId=${chapter.id} url=${chapter.url}",
                firstError,
            )
            val cfError = firstError.findCloudFlareException()?.withCloudstreamSource(firstError)
            if (cfError != null) {
                Log.w(
                    TAG,
                    "loadLinks cloudflare detected source=${source.displayName} chapterId=${chapter.id} " +
                        "url=${chapter.url} cfUrl=${cfError.url} cookies=${cookieSummary(chapter.url)}",
                )
                captchaFallbackAttempted = true
                if (resolveCloudflare(cfError, chapter.url, "loadLinks")) {
                    val retryResult = runCatchingCancellable {
                        loadLinksOnce()
                    }.onFailure { retryError ->
                        Log.e(
                            TAG,
                            "loadLinks retry failed source=${source.displayName} chapterId=${chapter.id} url=${chapter.url}",
                            retryError,
                        )
                    }
                    completed = retryResult.isSuccess
                    success = retryResult.getOrDefault(false)
                }
            }
        }
        if (!captchaFallbackAttempted && links.values.none { it.type in PLAYABLE_LINK_TYPES }) {
            detectedChallenge?.let { challenge ->
                Log.w(
                    TAG,
                    "loadLinks plugin fallback exhausted after cloudflare response " +
                        "source=${source.displayName} chapterId=${chapter.id} cfUrl=${challenge.url}",
                )
                if (resolveCloudflare(challenge, chapter.url, "loadLinks fallback")) {
                    val retryResult = runCatchingCancellable {
                        loadLinksOnce()
                    }.onFailure { retryError ->
                        Log.e(
                            TAG,
                            "loadLinks fallback retry failed source=${source.displayName} " +
                                "chapterId=${chapter.id} url=${chapter.url}",
                            retryError,
                        )
                    }
                    completed = retryResult.isSuccess
                    success = retryResult.getOrDefault(false)
                }
            }
        }
        if (completed) playbackCache.finish(cacheKey)
        val pages = links.values.filter { it.type in PLAYABLE_LINK_TYPES }.map { it.toPage() }
        if (BuildConfig.DEBUG) {
            val linkTypes = links.values.groupingBy { it.type }.eachCount()
            Log.d(
                TAG,
                "loadLinks done source=${source.displayName} chapterId=${chapter.id} success=$success links=${pages.size} " +
                    "subtitles=${subtitles.size} rawLinks=${links.size} types=$linkTypes selected=${pages.firstOrNull()?.url}",
            )
        }
        return pages
    }

    private fun String.isDirectPlayableUrl(): Boolean {
        if (!startsWith("http://") && !startsWith("https://")) {
            return false
        }
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".webm") ||
            lower.contains(".mpd")
    }

    private fun cloudstreamExtractorSummary(): String {
        val names = setOf(
            "Sbface",
            "StreamSB",
            "Rpmvip",
            "Nontonanimeid",
            "EmbedKotakAnimeid",
            "KotakAnimeid",
            "Kotaksb",
            "Gdplayer",
            "Vidhidepre",
        )
        return synchronized(extractorApis) {
            extractorApis
                .filter { extractor ->
                    extractor.name in names || names.any { name ->
                        extractor.mainUrl.contains(name, ignoreCase = true)
                    }
                }
                .joinToString(limit = 20) { "${it.name}=${it.mainUrl}" }
                .ifBlank { "<none>" }
        }
    }

    private suspend fun loadMainPage(offset: Int, filter: ContentListFilter?): List<Content> {
        val mainPages = source.api.mainPage
        if (mainPages.isEmpty()) {
            Log.w(TAG, "main page load skipped source=${source.displayName} because mainPage is empty")
            return emptyList()
        }
        val page = (offset + 1).coerceAtLeast(1)
        val selectedSectionIndex = filter?.tags
            ?.firstNotNullOfOrNull { tag -> parseSectionTagIndex(tag.key) }
            ?.takeIf { it in mainPages.indices }
        val requests = selectedSectionIndex?.let { listOf(mainPages[it]) } ?: mainPages
        val requestPage = page
        gateway.prepareMainPageRequest()
        val responses = if (source.api.sequentialMainPage || requests.size == 1) {
            requests.mapIndexedNotNull { index, mainPage ->
                if (index > 0) delay(source.api.sequentialMainPageDelay)
                loadMainPageEntry(mainPage, index, requestPage)
            }
        } else {
            coroutineScope {
                requests.mapIndexed { index, mainPage ->
                    async { loadMainPageEntry(mainPage, index, requestPage) }
                }.awaitAll().filterNotNull()
            }
        }
        val aggregated = ArrayList<CloudstreamMainPageItem>()
        responses.forEach { entry ->
            val request = entry.request
            val response = entry.response
            Log.d(
                TAG,
                "main page load source=${source.displayName} requestName=${request.name} requestData=${request.data} " +
                    "slot=${entry.slot} page=$requestPage rows=${response.items.size} hasNext=${response.hasNext}",
            )
            if (response.items.isEmpty()) {
                logMainPageEmptyResponse(request, entry.slot, requestPage, response.hasNext)
            } else {
                logMainPageRows(request, entry.slot, requestPage, response.items)
            }
            response.items.forEach { row ->
                row.list.forEach { item ->
                    aggregated += CloudstreamMainPageItem(
                        response = item,
                        request = request,
                        rowName = row.name,
                        horizontalImages = row.isHorizontalImages,
                    )
                }
            }
        }
        val deduped = aggregated.distinctBy { it.response.url }
        Log.d(
            TAG,
            "main page aggregated source=${source.displayName} page=$page slotPage=$requestPage " +
                "requestCount=${requests.size} items=${deduped.size} selectedSectionIndex=$selectedSectionIndex",
        )
        return deduped.map { item ->
            item.response.toKotoContent(
                source = source,
                mainPageRequest = item.request,
                homeRowName = item.rowName,
                horizontalImages = item.horizontalImages,
            )
        }.also { items ->
            if (items.isEmpty() && aggregated.isEmpty()) {
                Log.d(
                    TAG,
                    "main page produced 0 items source=${source.displayName} page=$page " +
                        "slotPage=$requestPage requestCount=${requests.size} selectedSectionIndex=$selectedSectionIndex " +
                        "aggregatedRaw=${aggregated.size}",
                )
                if (BuildConfig.DEBUG) {
                    requests.forEachIndexed { index, page ->
                        val request = MainPageRequest(page.name, page.data, page.horizontalImages)
                        logMainPageBrowserContext(request, index, requestPage)
                    }
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
            loadMainPageResponse(request, slot, requestPage)
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "main page load failed source=${source.displayName} requestName=${request.name} requestData=${request.data} " +
                    "slot=$slot page=$requestPage",
                error,
            )
            throw error
        } ?: return null
        if (!response.hasNext) terminalMainPagePages[terminalKey] = requestPage
        return CloudstreamMainPageResponse(request, response, slot)
    }

    private suspend fun loadMainPageResponse(
        request: MainPageRequest,
        slot: Int,
        requestPage: Int,
    ): com.lagradost.cloudstream3.HomePageResponse? {
        return try {
            getMainPageResponse(request, requestPage)
        } catch (error: Throwable) {
            val cfError = error.findCloudFlareException()?.withCloudstreamSource(error)
            if (cfError == null) {
                throw error
            }
            Log.w(
                TAG,
                "main page cloudflare detected source=${source.displayName} requestName=${request.name} " +
                    "requestData=${request.data} slot=$slot page=$requestPage url=${cfError.url}",
                error,
            )
            throw cfError
        }
    }

    private suspend fun getMainPageResponse(
        request: MainPageRequest,
        requestPage: Int,
    ): com.lagradost.cloudstream3.HomePageResponse? = gateway.getMainPage(requestPage, request)

    private fun CloudFlareException.withCloudstreamSource(cause: Throwable): CloudFlareException {
        val headers = (this as? CloudFlareProtectedException)?.headers
            ?: Headers.Builder().build()
        val enriched = CloudFlareProtectedException(
            url = url,
            source = this@CloudstreamContentRepository.source,
            headers = headers.newBuilder()
                .apply {
                    (CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent)?.takeIf { it.isNotBlank() }?.let {
                        set(CommonHeaders.USER_AGENT, it)
                    }
                }
                .set(CommonHeaders.MANGA_SOURCE, this@CloudstreamContentRepository.source.name)
                .build(),
        )
        if (cause !== this) {
            enriched.addSuppressed(cause)
        }
        return enriched
    }

    private suspend fun <T> executeWithCloudflare(
        url: String,
        stage: String,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (error: Throwable) {
            val cloudflare = error.findCloudFlareException()?.withCloudstreamSource(error) ?: throw error
            Log.w(TAG, "$stage cloudflare detected source=${source.displayName} url=$url", error)
            if (!resolveCloudflare(cloudflare, url, stage)) throw cloudflare
            block()
        }
    }

    private suspend fun resolveCloudflare(
        error: CloudFlareException,
        url: String,
        stage: String,
    ): Boolean {
        if (settings.cloudflareStrategy != CloudflareStrategy.TRANSPORT) {
            Log.w(
                TAG,
                "$stage webview transport not selected (${settings.cloudflareStrategy}); skipping cloudstream auto resolve url=$url",
            )
            return false
        }
        val resolved = webViewExecutor.tryResolveCaptcha(
            error,
            timeout = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS,
        )
        Log.w(
            TAG,
            "$stage cloudflare resolve result source=${source.displayName} url=$url " +
                "resolved=$resolved cookies=${cookieSummary(url)}",
        )
        return resolved
    }

    private fun logMainPageRows(
        request: MainPageRequest,
        slot: Int,
        requestPage: Int,
        rows: List<com.lagradost.cloudstream3.HomePageList>,
    ) {
        if (!BuildConfig.DEBUG) return
        val summary = rows.mapIndexed { index, row ->
            "#$index name=${row.name} list=${row.list.size}"
        }
        Log.d(
            TAG,
            "main page rows source=${source.displayName} requestName=${request.name} requestData=${request.data} " +
                "slot=$slot page=$requestPage rows=${rows.size} rowSummary=$summary",
        )
    }

    private fun logMainPageEmptyResponse(
        request: MainPageRequest,
        slot: Int,
        requestPage: Int,
        hasNext: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "main page empty response source=${source.displayName} api=${source.api.name} mainUrl=${source.api.mainUrl} " +
                "usesWebView=${source.api.usesWebView} requestName=${request.name} requestData=${request.data} " +
                "slot=$slot page=$requestPage hasNext=$hasNext cookies=${cookieSummary(request.data)}",
        )
    }

    private suspend fun logMainPageBrowserContext(
        request: MainPageRequest,
        slot: Int,
        requestPage: Int,
    ) {
        if (settings.cloudflareStrategy != CloudflareStrategy.TRANSPORT) return
        val diagnosticUrl = request.data.takeIf { it.isNotBlank() } ?: source.api.mainUrl
        val result = runCatchingCancellable {
            webViewExecutor.fetchWithBrowserContext(
                url = diagnosticUrl,
                userAgent = CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent,
                allowInteractiveChallenge = false,
                settleDelayMs = 2_000,
                timeoutMs = 15_000,
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "main page browserContext failed source=${source.displayName} requestName=${request.name} " +
                    "requestData=${request.data} slot=$slot page=$requestPage",
                error,
            )
        }.getOrNull()
        if (result == null) {
            Log.w(
                TAG,
                "main page browserContext returned null source=${source.displayName} requestName=${request.name} " +
                    "requestData=${request.data} slot=$slot page=$requestPage",
            )
            return
        }
        val headers = result.headers
        val markers = cloudflareMarkers(result.body)
        val server = headers.firstHeaderValue("server")
        val contentType = headers.firstHeaderValue("content-type")
        val cookieSummary = cookieSummary(diagnosticUrl)
        val bodyPreview = sanitizePreview(result.body)
        Log.w(
            TAG,
            "main page browserContext source=${source.displayName} api=${source.api.name} mainUrl=${source.api.mainUrl} " +
                "usesWebView=${source.api.usesWebView} requestName=${request.name} requestData=${request.data} " +
                "diagnosticUrl=$diagnosticUrl " +
                "slot=$slot page=$requestPage status=${result.status} finalUrl=${result.url} " +
                "server=$server contentType=$contentType " +
                "bodyLength=${result.body.length} cfMarkers=$markers siteMarkers=${siteMarkers(result.body)} " +
                "cookies=$cookieSummary bodyPreview=$bodyPreview",
        )
    }

    private fun cookieSummary(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return "invalid-url"
        val cookies = runCatching { cookieJar.loadForRequest(httpUrl) }.getOrElse { return "error=${it::class.simpleName}" }
        if (cookies.isEmpty()) return "count=0 names=[] hasCfClearance=false"
        val hasCfClearance = cookies.any { it.name == "cf_clearance" }
        return "count=${cookies.size} names=${cookies.map { it.name }} hasCfClearance=$hasCfClearance"
    }

    private fun Map<String, String>.firstHeaderValue(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun cloudflareMarkers(body: String): List<String> {
        return listOf(
            "cf-browser-verification",
            "__cf_chl_opt",
            "cf_chl",
            "turnstile",
            "Cloudflare",
            "Ray ID",
        ).filter { body.contains(it, ignoreCase = true) }
    }

    private fun siteMarkers(body: String): List<String> {
        return listOf(
            "anime",
            "series",
            "NontonAnimeID",
        ).filter { body.contains(it, ignoreCase = true) }
    }

    private fun sanitizePreview(body: String): String {
        return body
            .replace(Regex("\\s+"), " ")
            .take(1_000)
    }

    companion object {
        private const val TAG = "CloudstreamRepo"
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

internal fun mapCloudstreamChapters(
    response: LoadResponse,
    source: CloudstreamSource,
): List<ContentChapter> {
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
                    CloudstreamMetadataCodec.encodeEpisode(
                        CloudstreamEpisodeMetadata(
                            linkType = linkType.name,
                        ),
                    )
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

internal sealed interface CloudstreamPlaybackEvent {
    data class Link(
        val page: ContentPage,
        val type: ExtractorLinkType,
    ) : CloudstreamPlaybackEvent
    data class Subtitle(val track: ContentExternalTrack) : CloudstreamPlaybackEvent
}

internal fun resolveCloudstreamEpisodeTitle(name: String?, episodeNumber: Int): String {
    val title = name?.trim().orEmpty()
    return title.takeIf { it.isNotEmpty() && !isCloudstreamStructuredLocator(it) } ?: "Episode $episodeNumber"
}

internal fun isCloudstreamStructuredLocator(value: String): Boolean {
    return value.trimStart().let { it.startsWith('[') || it.startsWith('{') }
}

internal fun String.isMissingCloudstreamUrl(): Boolean {
    val normalized = trim()
    return normalized.isEmpty() ||
        normalized.equals("null", ignoreCase = true) ||
        normalized.equals("undefined", ignoreCase = true)
}

internal fun cloudstreamStableId(value: String): Long = value.longHashCode() and Long.MAX_VALUE

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
