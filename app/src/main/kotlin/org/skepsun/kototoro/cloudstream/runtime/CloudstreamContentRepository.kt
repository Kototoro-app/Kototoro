package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.parser.CachingContentRepository
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
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers

@OptIn(Prerelease::class)
class CloudstreamContentRepository(
	override val source: CloudstreamSource,
	cache: MemoryContentCache,
	private val webViewExecutor: WebViewExecutor,
	private val cookieJar: MutableCookieJar,
) : CachingContentRepository(cache) {

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
			Log.w(
				TAG,
				"getList returning empty because query is blank for source=${source.displayName} hasMainPage=${source.api.hasMainPage}",
			)
			return emptyList()
		}
		val page = (offset + 1).coerceAtLeast(1)
		val result = runCatchingCancellable {
			CloudstreamRequestContext.withSource(source) {
				withContext(Dispatchers.IO) {
					source.api.search(query, page)
				}
			}
		}.onFailure { error ->
			Log.e(TAG, "search exception source=${source.displayName} query=$query page=$page", error)
		}.getOrNull()
		if (result == null) {
			Log.w(TAG, "search returned null source=${source.displayName} query=$query page=$page")
			return emptyList()
		}
		Log.d(
			TAG,
			"search result source=${source.displayName} query=$query page=$page items=${result.items.size} hasNext=${result.hasNext}",
		)
		return result.items.mapIndexed { index, item ->
			item.toKotoContent(source, page, index)
		}
	}

	override suspend fun getDetailsImpl(manga: Content): Content {
		val response = runCatchingCancellable {
			CloudstreamRequestContext.withSource(source) {
				withContext(Dispatchers.IO) { source.api.load(manga.url) }
			}
		}.onFailure { error ->
			Log.e(TAG, "load exception source=${source.displayName} url=${manga.url}", error)
		}.getOrNull() ?: run {
			Log.w(TAG, "load returned null source=${source.displayName} url=${manga.url}")
			return manga
		}
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
		)
	}

	override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
		val links = resolveVideoPages(chapter)
		if (links.isNotEmpty()) {
			return links
		}
		if (chapter.url.isDirectPlayableUrl()) {
			Log.w(
				TAG,
				"loadLinks empty, falling back to direct url source=${source.displayName} chapterId=${chapter.id} url=${chapter.url}",
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
		page: Int,
		index: Int,
	): Content {
		val type = type ?: TvType.Movie
		return Content(
			id = stableId("${source.name}|$url|$page|$index"),
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
			chapters = buildPreviewChapters(type, source, this),
			source = source,
		)
	}

	private fun buildPreviewChapters(
		type: TvType,
		source: CloudstreamSource,
		response: SearchResponse,
	): List<ContentChapter>? {
		if (!type.isMovieType()) return null
		return listOf(
			ContentChapter(
				id = stableId("${source.name}|movie|${response.url}"),
				title = response.name,
				number = 1f,
				volume = 1,
				url = response.url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			),
		)
	}

	private fun LoadResponse.toChapters(source: CloudstreamSource): List<ContentChapter> {
		if (this is MovieLoadResponse) {
			Log.d(TAG, "toChapters MovieLoadResponse name=$name url=$url dataUrl=$dataUrl")
			return listOf(
				ContentChapter(
					id = stableId("${source.name}|movie|$dataUrl"),
					title = name,
					number = 1f,
					volume = 1,
					url = dataUrl,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}

		val episodes = when (this) {
			is TvSeriesLoadResponse -> episodes
			is AnimeLoadResponse -> episodes.values.flatten()
			else -> emptyList()
		}
		if (episodes.isEmpty()) {
			return listOf(
				ContentChapter(
					id = stableId("${source.name}|fallback|$url"),
					title = name,
					number = 1f,
					volume = 1,
					url = url,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}
		return episodes.mapIndexed { index, episode ->
			ContentChapter(
				id = stableId("${source.name}|${episode.data}|$index"),
				title = episode.name,
				number = (episode.episode ?: (index + 1)).toFloat(),
				volume = episode.season ?: 1,
				url = episode.data,
				scanlator = null,
				uploadDate = episode.date ?: 0L,
				branch = resolveBranch(this, episode),
				source = source,
			)
		}
	}

	private fun resolveBranch(response: LoadResponse, episode: com.lagradost.cloudstream3.Episode): String? {
		if (response !is AnimeLoadResponse) return null
		return response.episodes.entries.firstOrNull { (_, value) -> episode in value }?.key
			?.takeUnless { it == DubStatus.None }
			?.name
	}

	private fun LoadResponse.toKotoState(): ContentState? {
		return null
	}

	private fun String?.toKotoContentRating(): ContentRating? {
		return this?.takeIf { it.contains("18", true) || it.contains("adult", true) }?.let {
			ContentRating.ADULT
		}
	}

	private fun Score?.toKotoRating(): Float? {
		return this?.toInt(100)?.div(100f)
	}

	private fun stableId(value: String): Long {
		return value.hashCode().toLong() and Long.MAX_VALUE
	}

	private fun sectionTagKey(index: Int): String = "$SECTION_TAG_PREFIX$index"

	private fun parseSectionTagIndex(key: String): Int? {
		if (!key.startsWith(SECTION_TAG_PREFIX)) return null
		return key.removePrefix(SECTION_TAG_PREFIX).toIntOrNull()
	}

	private suspend fun resolveVideoPages(chapter: ContentChapter): List<ContentPage> {
		Log.d(
			TAG,
			"loadLinks start source=${source.displayName} chapterId=${chapter.id} chapterTitle=${chapter.title} " +
				"locator=${chapter.url} branch=${chapter.branch}",
		)
		Log.d(
			TAG,
			"loadLinks extractors source=${source.displayName} total=${synchronized(extractorApis) { extractorApis.size }} " +
				"sample=${cloudstreamExtractorSummary()}",
		)
		val subtitles = ArrayList<SubtitleFile>()
		val links = ArrayList<ExtractorLink>()
		suspend fun loadLinksOnce(): Boolean {
			return withContext(Dispatchers.IO) {
				CloudstreamRequestContext.withSource(source) {
					CloudstreamRequestContext.withLoadLinksCompatibility {
						source.api.loadLinks(
							data = chapter.url,
							isCasting = false,
							subtitleCallback = { subtitle ->
								subtitles += subtitle
								Log.d(
									TAG,
									"loadLinks subtitle source=${source.displayName} chapterId=${chapter.id} " +
										"lang=${subtitle.lang} url=${subtitle.url}",
								)
							},
							callback = { link ->
								links += link
								Log.d(
									TAG,
									"loadLinks link source=${source.displayName} chapterId=${chapter.id} name=${link.name} " +
										"type=${link.type} quality=${link.quality} url=${link.url} headers=${link.getAllHeaders().keys}",
								)
							},
						)
					}
				}
			}
		}
		var success = false
		val firstError = runCatchingCancellable {
			success = loadLinksOnce()
		}.exceptionOrNull()
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
				if (resolveCloudflare(cfError, chapter.url, "loadLinks")) {
					links.clear()
					subtitles.clear()
					success = runCatchingCancellable {
						loadLinksOnce()
					}.onFailure { retryError ->
						Log.e(
							TAG,
							"loadLinks retry failed source=${source.displayName} chapterId=${chapter.id} url=${chapter.url}",
							retryError,
						)
					}.getOrDefault(false)
				}
			}
		}
		val pages = links
			.distinctBy { it.url to it.getAllHeaders() }
			.sortedWith(compareByDescending<ExtractorLink> { it.url.contains("/config-", ignoreCase = true) }
				.thenByDescending { it.url.contains("master.m3u8", ignoreCase = true) }
				.thenByDescending { it.url.contains("/playlist.m3u8", ignoreCase = true) }
				.thenByDescending { it.quality })
			.mapIndexed { index, link ->
				ContentPage(
					id = stableId("${chapter.id}|${link.name}|${link.url}|$index"),
					url = link.url,
					preview = null,
					headers = link.getAllHeaders()
						.toMutableMap()
						.apply {
							(CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent)?.takeIf { it.isNotBlank() }?.let {
								putIfAbsent("User-Agent", it)
							}
						}
						.takeIf { it.isNotEmpty() },
						externalSubtitleTracks = subtitles.map { subtitle ->
							ContentExternalTrack(
								url = subtitle.url,
								lang = subtitle.lang,
								headers = subtitle.headers,
							)
						},
						playbackLabel = link.name.takeIf { it.isNotBlank() },
						playbackQuality = link.quality.takeIf { it > 0 },
						source = source,
					)
				}
		val linkTypes = links.groupingBy { it.url.substringAfterLast('.', "<none>") }.eachCount()
		Log.d(
			TAG,
			"loadLinks done source=${source.displayName} chapterId=${chapter.id} success=$success links=${pages.size} " +
				"subtitles=${subtitles.size} rawLinks=${links.size} types=$linkTypes selected=${pages.firstOrNull()?.url}",
		)
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
		val requestIndex = selectedSectionIndex ?: (offset % mainPages.size)
		val requestPage = if (selectedSectionIndex != null) {
			val sectionPageSize = probeMainPageSize(mainPages[requestIndex]).coerceAtLeast(1)
			(offset / sectionPageSize) + 1
		} else {
			(offset / mainPages.size) + 1
		}
		val requests = listOf(mainPages[requestIndex])
		val aggregated = ArrayList<SearchResponse>()
		requests.forEachIndexed { requestIndex, page ->
			val request = MainPageRequest(page.name, page.data, page.horizontalImages)
			val response = try {
				loadMainPageResponse(request, requestIndex, requestPage)
			} catch (error: Throwable) {
				Log.e(
					TAG,
					"main page load failed source=${source.displayName} requestName=${request.name} requestData=${request.data} " +
						"slot=$requestIndex page=$requestPage",
					error,
				)
				if (error.findCloudFlareException() != null) {
					throw error
				}
				return@forEachIndexed
			} ?: return@forEachIndexed
			Log.d(
				TAG,
				"main page load source=${source.displayName} requestName=${request.name} requestData=${request.data} " +
					"slot=$requestIndex page=$requestPage rows=${response.items.size} hasNext=${response.hasNext}",
			)
			if (response.items.isEmpty()) {
				logMainPageEmptyResponse(request, requestIndex, requestPage, response.hasNext)
			} else {
				logMainPageRows(request, requestIndex, requestPage, response.items)
			}
			response.items.forEach { row ->
				aggregated += row.list
			}
		}
		val deduped = aggregated.distinctBy { it.url }
		Log.d(
			TAG,
			"main page aggregated source=${source.displayName} page=$page slot=$requestIndex slotPage=$requestPage " +
				"requestCount=${requests.size} items=${deduped.size} selectedSectionIndex=$selectedSectionIndex",
		)
		return deduped.mapIndexed { index, item ->
			item.toKotoContent(source, page, index)
		}.also { items ->
			if (items.isEmpty() && aggregated.isEmpty()) {
				Log.w(
					TAG,
					"main page produced 0 items source=${source.displayName} page=$page slot=$requestIndex " +
						"slotPage=$requestPage requestCount=${requests.size} selectedSectionIndex=$selectedSectionIndex " +
						"aggregatedRaw=${aggregated.size}",
				)
				requests.forEachIndexed { index, page ->
					val request = MainPageRequest(page.name, page.data, page.horizontalImages)
					logMainPageBrowserContext(request, index, requestPage)
				}
			}
		}
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
			val resolved = resolveCloudflare(cfError, request, slot, requestPage)
			if (!resolved) {
				throw cfError
			}
			Log.i(
				TAG,
				"main page retry after cloudflare source=${source.displayName} requestName=${request.name} " +
					"requestData=${request.data} slot=$slot page=$requestPage cookies=${cookieSummary(request.data)}",
			)
			getMainPageResponse(request, requestPage)
		}
	}

	private suspend fun getMainPageResponse(
		request: MainPageRequest,
		requestPage: Int,
	): com.lagradost.cloudstream3.HomePageResponse? = CloudstreamRequestContext.withSource(source) {
		withContext(Dispatchers.IO) {
			source.api.getMainPage(page = requestPage, request = request)
		}
	}

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

	private suspend fun resolveCloudflare(
		error: CloudFlareException,
		request: MainPageRequest,
		slot: Int,
		requestPage: Int,
	): Boolean {
		val resolved = webViewExecutor.tryResolveCaptcha(error, timeout = 30_000)
		Log.w(
			TAG,
			"main page cloudflare resolve result source=${source.displayName} requestName=${request.name} " +
				"requestData=${request.data} slot=$slot page=$requestPage resolved=$resolved cookies=${cookieSummary(request.data)}",
		)
		return resolved
	}

	private suspend fun resolveCloudflare(
		error: CloudFlareException,
		url: String,
		stage: String,
	): Boolean {
		val resolved = webViewExecutor.tryResolveCaptcha(error, timeout = 30_000)
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
		Log.w(
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
		val diagnosticUrl = request.data.takeIf { it.isNotBlank() } ?: source.api.mainUrl
		val result = runCatchingCancellable {
			webViewExecutor.fetchWithBrowserContext(
				url = diagnosticUrl,
				userAgent = CloudstreamRequestContext.userAgent ?: webViewExecutor.defaultUserAgent,
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
				"usesWebView=${source.api.usesWebView} requestName=${request.name} requestData=${request.data} diagnosticUrl=$diagnosticUrl " +
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

	private suspend fun probeMainPageSize(page: com.lagradost.cloudstream3.MainPageData): Int {
		val request = MainPageRequest(page.name, page.data, page.horizontalImages)
		val response = getMainPageResponse(request, 1)
		return response?.items?.sumOf { it.list.size } ?: 0
	}

	companion object {
		private const val TAG = "CloudstreamRepo"
		private const val SECTION_TAG_PREFIX = "cloudstream-section:"
	}
}
