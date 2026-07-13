package org.skepsun.kototoro.core.parser.tvbox

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.skepsun.kototoro.core.exceptions.UnsupportedSourceException
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import java.io.File
import java.io.StringReader
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.Element
import org.w3c.dom.Node

class TVBoxRepository(
	override val source: JsonContentSource,
	private val context: Context,
	private val httpClient: LegadoHttpClient,
	private val videoLocalCacheProxy: VideoLocalCacheProxy,
) : ContentRepository {

	companion object {
		private const val TAG = "TVBoxRepository"
	}

	private val config by lazy(LazyThreadSafetyMode.NONE) {
		TVBoxStoredConfig.parse(source.entity.config)
	}
	private val spiderRuntime by lazy(LazyThreadSafetyMode.NONE) {
		TVBoxSpiderRuntimeFactory.create(
			source = source,
			config = config,
			context = context,
			httpClient = httpClient,
			videoLocalCacheProxy = videoLocalCacheProxy,
		)
	}

	private val catalogMutex = Mutex()
	private val cmsProviderMutex = Mutex()
	private val cmsItems = ConcurrentHashMap<String, TVBoxMediaItem>()

	@Volatile
	private var catalogCache: TVBoxCatalog? = null

	@Volatile
	private var cmsProviderCache: CmsProvider? = null

	@Volatile
	private var cmsProviderResolved = false

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
		SortOrder.NEWEST,
	)

	override var defaultSortOrder: SortOrder = SortOrder.ALPHABETICAL

	override val listPagingMode: ContentRepository.ListPagingMode
		get() = if (mightBeCmsSource()) {
			ContentRepository.ListPagingMode.PAGE_INDEX
		} else {
			ContentRepository.ListPagingMode.OFFSET
		}

	override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: ContentListFilter?,
	): List<Content> {
		Log.i(
			TAG,
			"getList start for ${source.name}: offset=$offset order=${order ?: defaultSortOrder} query=${filter?.query.orEmpty()} runtime=${spiderRuntime?.id ?: "none"}",
		)
		val shouldPreferCmsSearch = mightBeCmsSource() && !filter?.query.isNullOrBlank()
		if (!shouldPreferCmsSearch) {
			spiderRuntime?.getList(offset, order, filter)?.let {
				Log.i(TAG, "getList resolved by spider runtime for ${source.name}: count=${it.size}")
				return it
			}
		} else {
			Log.d(TAG, "Skipping spider runtime search for CMS source ${source.name} to preserve CMS cover metadata")
		}
		Log.d(TAG, "getList spider runtime returned null or was skipped for ${source.name}, trying CMS/catalog fallback")
		resolveCmsProvider()?.let { provider ->
			val result = getCmsList(provider, offset, order, filter)
			Log.i(TAG, "getList resolved by CMS provider for ${source.name}: count=${result.size}")
			return result
		}
		if (shouldPreferCmsSearch) {
			spiderRuntime?.getList(offset, order, filter)?.let {
				Log.i(TAG, "getList fallback to spider runtime for ${source.name}: count=${it.size}")
				return it
			}
		}
		if (offset > 0) {
			Log.d(TAG, "getList offset > 0 and no runtime/CMS data for ${source.name}, returning empty list")
			return emptyList()
		}
		val catalog = loadCatalog()
		val query = filter?.query?.trim().orEmpty()
		val includeTags = filter?.tags?.map { it.key }?.toSet().orEmpty()
		val excludeTags = filter?.tagsExclude?.map { it.key }?.toSet().orEmpty()
		val filtered = catalog.items.asSequence()
			.filter { item ->
				query.isBlank() || item.title.contains(query, ignoreCase = true) || item.description.orEmpty().contains(query, ignoreCase = true)
			}
			.filter { item ->
				includeTags.isEmpty() || item.tags.any { it.key in includeTags }
			}
			.filter { item ->
				excludeTags.isEmpty() || item.tags.none { it.key in excludeTags }
			}
			.toList()
		return sortItems(filtered, order ?: defaultSortOrder, query).map { item -> item.toContent(source) }.also {
			Log.i(TAG, "getList resolved by catalog parsing for ${source.name}: count=${it.size}")
		}
	}

	override suspend fun getDetails(manga: Content): Content {
		spiderRuntime?.getDetails(manga)?.let { return it }
		resolveCmsProvider()?.let { provider ->
			val details = getCmsDetails(provider, manga)
			if (details != null) {
				return details
			}
		}
		val item = findItem(manga) ?: return manga
		return manga.copy(
			title = item.title.ifBlank { manga.title },
			coverUrl = item.coverUrl ?: manga.coverUrl,
			largeCoverUrl = item.coverUrl ?: manga.largeCoverUrl,
			publicUrl = item.publicUrl,
			description = item.description ?: manga.description,
			tags = if (item.tags.isNotEmpty()) item.tags else manga.tags,
			authors = manga.authors,
			chapters = item.streams.mapIndexed { index, stream ->
				ContentChapter(
					id = positiveHash("${item.token}|chapter|${stream.token}|$index"),
					title = stream.title,
					number = (index + 1).toFloat(),
					volume = 0,
					url = buildChapterUrl(item.token, stream.token),
					scanlator = item.group,
					uploadDate = 0L,
					branch = item.group,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
		spiderRuntime?.getPages(chapter, nextChapterUrl)?.let { return it }
		val locator = parseChapterUrl(chapter.url)
			?: throw UnsupportedSourceException("Invalid TVBox chapter URL", null)
		val cachedItem = cmsItems[locator.itemToken]
			?: loadCatalog().items.firstOrNull { it.token == locator.itemToken }
		val stream = cachedItem?.streams?.firstOrNull { it.token == locator.streamToken }
			?: resolveCmsStream(locator)
			?: locator.streamUrl?.let { directUrl ->
				TVBoxStream(
					token = locator.streamToken,
					title = chapter.title.orEmpty().ifBlank { source.displayName },
					url = directUrl,
					headers = buildHeadersForUrl(directUrl, emptyMap()),
				)
			}
			?: throw UnsupportedSourceException("TVBox stream not found for chapter", null)
		return listOf(
			ContentPage(
				id = positiveHash("${chapter.url}|page"),
				url = stream.url,
				preview = null,
				headers = stream.headers,
				source = source,
			),
		)
	}

	override suspend fun getPageUrl(page: ContentPage): String {
		return page.url
	}

	override suspend fun getFilterOptions(): ContentListFilterOptions {
		spiderRuntime?.getFilterOptions()?.let { return it }
		resolveCmsProvider()?.let { provider ->
			if (provider.categories.isNotEmpty()) {
				val tags = provider.categories.mapTo(linkedSetOf()) { category ->
					buildCmsTag(category.name, category.id)
				}
				return ContentListFilterOptions(
					availableTags = tags,
					tagGroups = listOf(ContentTagGroup("分类", tags)),
				)
			}
		}
		val availableTags = runCatching { loadCatalog().availableTags }.getOrNull()
			?.takeIf { it.isNotEmpty() }
			?: config.site.categories
				.mapTo(linkedSetOf()) { category ->
					ContentTag(
						title = category,
						key = "group:${category.lowercase()}",
						source = source,
					)
				}
		return if (availableTags.isEmpty()) {
			ContentListFilterOptions()
		} else {
			ContentListFilterOptions(
				availableTags = availableTags,
				tagGroups = listOf(ContentTagGroup("分类", availableTags)),
			)
		}
	}

	override fun getRequestHeaders(): Map<String, String> {
		return spiderRuntime?.getRequestHeaders()
			?: buildHeadersForUrl(config.site.api.takeIf(::looksLikeUrl), emptyMap())
	}

	override suspend fun getRelated(seed: Content): List<Content> {
		return RelatedContentSearchFallback.find(seed) { query ->
			getList(
				offset = 0,
				order = SortOrder.RELEVANCE,
				filter = ContentListFilter(query = query),
			)
		}
	}

	fun getRuntimeCapabilitySummary(): String? = spiderRuntime?.describeCapability(config)

	fun getRuntimeUnavailabilitySummary(): String? = spiderRuntime?.describeUnavailability(config)

	private suspend fun loadCatalog(): TVBoxCatalog {
		catalogCache?.let { return it }
		return catalogMutex.withLock {
			catalogCache?.let { return it }
			buildCatalog().also { catalogCache = it }
		}
	}

	private suspend fun resolveCmsProvider(): CmsProvider? {
		if (cmsProviderResolved) {
			return cmsProviderCache
		}
		return cmsProviderMutex.withLock {
			if (cmsProviderResolved) {
				return cmsProviderCache
			}
			val provider = runCatching { detectCmsProvider() }.getOrNull()
			cmsProviderCache = provider
			cmsProviderResolved = true
			provider
		}
	}

	private suspend fun findItem(manga: Content): TVBoxMediaItem? {
		val locator = parseItemUrl(manga.url) ?: parseItemUrl(manga.publicUrl)
		if (locator != null) {
			cmsItems[locator.token]?.let { return it }
		}
		return loadCatalog().items.firstOrNull { it.publicUrl == manga.publicUrl || it.url == manga.url || it.id == manga.id }
	}

	private suspend fun detectCmsProvider(): CmsProvider? {
		if (!mightBeCmsSource()) {
			return null
		}
		val candidates = buildResourceCandidates()
		Log.d(TAG, "Trying CMS detection for ${source.name} with ${candidates.size} candidates")
		for (candidate in candidates) {
			if (!candidate.url.startsWith("http", ignoreCase = true)) {
				continue
			}
			Log.d(TAG, "Checking CMS candidate: ${candidate.label} -> ${candidate.url}")
			val content = runCatching { readTextResource(candidate.url, candidate.headers) }
				.onFailure {
					logRepositoryFailure("detectCms", it, "candidate=${candidate.url}")
				}
				.getOrNull()
				?: run {
					continue
				}
			val parsed = parseCmsResponse(candidate, content)
			if (parsed.categories.isNotEmpty() || parsed.items.isNotEmpty()) {
				Log.d(
					TAG,
					"Detected CMS candidate ${candidate.url} with ${parsed.categories.size} categories and ${parsed.items.size} items",
				)
				return CmsProvider(
					candidate = candidate,
					categories = parsed.categories,
				)
			}
		}
		logRepositoryFailure("detectCms", null, "no_candidate_detected")
		return null
	}

	private suspend fun getCmsList(
		provider: CmsProvider,
		offset: Int,
		order: SortOrder?,
		filter: ContentListFilter?,
	): List<Content> {
		val page = offset + 1
		val query = filter?.query?.trim().orEmpty()
		val selectedCategoryId = filter?.tags
			?.firstNotNullOfOrNull { tag -> parseCmsTagId(tag.key) }
		val requestUrls = buildCmsListUrls(provider.candidate.url, page, query, selectedCategoryId)
		val items = fetchCmsItems(provider.candidate, requestUrls)
		val filtered = items.asSequence()
			.filter { item ->
				query.isBlank() || item.title.contains(query, ignoreCase = true) || item.description.orEmpty().contains(query, ignoreCase = true)
			}
			.filter { item ->
				filter?.tags.isNullOrEmpty() || item.tags.any { it in filter?.tags.orEmpty() }
			}
			.filter { item ->
				filter?.tagsExclude.isNullOrEmpty() || item.tags.none { it in filter?.tagsExclude.orEmpty() }
			}
			.toList()
		filtered.forEach { cmsItems[it.token] = it }
		Log.d(
			TAG,
			"CMS list result for ${source.name}: page=$page query=$query category=$selectedCategoryId total=${filtered.size} covers=${
				filtered.joinToString(limit = 5) { "${it.title}=>${it.coverUrl ?: "<null>"}" }
			}",
		)
		return sortItems(filtered, order ?: defaultSortOrder, query).map { it.toContent(source) }
	}

	private suspend fun getCmsDetails(provider: CmsProvider, manga: Content): Content? {
		val locator = parseItemUrl(manga.url) ?: parseItemUrl(manga.publicUrl) ?: return null
		cmsItems[locator.token]?.takeIf { it.streams.isNotEmpty() }?.let { cached ->
			return mergeItemIntoContent(manga, cached)
		}
		val externalId = locator.externalId ?: return null
		val requestUrls = buildCmsDetailUrls(provider.candidate.url, externalId)
		val items = fetchCmsItems(provider.candidate, requestUrls)
		val item = items.firstOrNull { it.externalId == externalId } ?: items.firstOrNull() ?: return null
		cmsItems[item.token] = item
		return mergeItemIntoContent(manga, item)
	}

	private fun mergeItemIntoContent(manga: Content, item: TVBoxMediaItem): Content {
		return manga.copy(
			title = item.title.ifBlank { manga.title },
			coverUrl = item.coverUrl ?: manga.coverUrl,
			largeCoverUrl = item.coverUrl ?: manga.largeCoverUrl,
			publicUrl = item.publicUrl,
			description = item.description ?: manga.description,
			tags = if (item.tags.isNotEmpty()) item.tags else manga.tags,
			authors = manga.authors,
			chapters = item.streams.mapIndexed { index, stream ->
				ContentChapter(
					id = positiveHash("${item.token}|chapter|${stream.token}|$index"),
					title = stream.title,
					number = (index + 1).toFloat(),
					volume = 0,
					url = buildChapterUrl(
						itemToken = item.token,
						streamToken = stream.token,
						externalId = item.externalId,
						streamUrl = stream.url,
					),
					scanlator = item.group,
					uploadDate = 0L,
					branch = item.group,
					source = source,
				)
			},
		)
	}

	private suspend fun fetchCmsItems(
		candidate: ResourceCandidate,
		requestUrls: List<String>,
	): List<TVBoxMediaItem> {
		for (requestUrl in requestUrls) {
			Log.d(TAG, "Fetching CMS items from $requestUrl")
			val content = runCatching { readTextResource(requestUrl, candidate.headers) }.getOrNull()
				?: run {
					Log.d(TAG, "Failed to fetch CMS items from $requestUrl")
					continue
				}
			val parsed = parseCmsResponse(
				candidate = candidate.copy(url = requestUrl),
				content = content,
			)
			if (parsed.items.isNotEmpty()) {
				Log.d(TAG, "Parsed ${parsed.items.size} CMS items from $requestUrl")
				return parsed.items
			}
		}
		Log.d(TAG, "No CMS items found for ${candidate.url}")
		return emptyList()
	}

	private fun parseCmsResponse(candidate: ResourceCandidate, content: String): CmsParsedResponse {
		val normalized = content.removePrefix("\uFEFF").trim()
		if (looksLikeXml(normalized)) {
			return parseCmsXmlResponse(candidate, normalized)
		}
		val root = runCatching { JSONTokener(normalized).nextValue() }.getOrNull() ?: return CmsParsedResponse.empty()
		val categories = linkedMapOf<String, CmsCategory>()
		val items = linkedMapOf<String, TVBoxMediaItem>()
		val preferredNodes = buildPreferredCmsPayloads(root)
		preferredNodes.forEach { node ->
			extractCmsPayload(
				node = node,
				candidate = candidate,
				categories = categories,
				items = items,
			)
		}
		if (categories.isEmpty() && items.isEmpty()) {
			extractCmsPayload(
				node = root,
				candidate = candidate,
				categories = categories,
				items = items,
			)
		}
		return CmsParsedResponse(
			categories = categories.values.toList(),
			items = items.values.toList(),
		)
	}

	private fun buildPreferredCmsPayloads(root: Any?): List<Any?> {
		if (root !is JSONObject) {
			return listOf(root)
		}
		val dataNode = root.opt("data")
		val dataObject = dataNode as? JSONObject
		return listOfNotNull(
			root.opt("class"),
			root.opt("classes"),
			root.opt("types"),
			root.opt("list"),
			root.opt("data_list"),
			dataObject?.opt("class"),
			dataObject?.opt("classes"),
			dataObject?.opt("types"),
			dataObject?.opt("list"),
			dataObject?.opt("data"),
			dataNode,
		)
			.filterNot { it == JSONObject.NULL }
			.ifEmpty { listOf(root) }
	}

	private fun parseCmsXmlResponse(candidate: ResourceCandidate, content: String): CmsParsedResponse {
		val document = runCatching {
			val factory = DocumentBuilderFactory.newInstance().apply {
				isNamespaceAware = false
				isExpandEntityReferences = false
			}
			factory.trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			factory.trySetFeature("http://xml.org/sax/features/external-general-entities", false)
			factory.trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
			factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(content)))
		}.getOrNull() ?: return CmsParsedResponse.empty()
		val categories = linkedMapOf<String, CmsCategory>()
		document.getElementsByTagName("ty").forEachElement { element ->
			val id = element.attrOrText("id").ifBlank { element.attrOrText("type_id") }
			val name = element.textContent?.trim().orEmpty()
			if (id.isNotBlank() && name.isNotBlank()) {
				categories.putIfAbsent(id, CmsCategory(id, name))
			}
		}
		val items = linkedMapOf<String, TVBoxMediaItem>()
		document.getElementsByTagName("video").forEachElement { element ->
			val node = JSONObject().apply {
				putIfNotBlank("vod_id", element.childText("id"))
				putIfNotBlank("vod_name", element.childText("name"))
				putIfNotBlank("type_id", element.childText("tid"))
				putIfNotBlank("type_name", element.childText("type"))
				putIfNotBlank("vod_pic", element.childText("pic"))
				putIfNotBlank("vod_remarks", element.childText("note"))
				putIfNotBlank("vod_content", element.childText("des"))
				val playInfo = element.extractMacCmsXmlPlayInfo()
				putIfNotBlank("vod_play_from", playInfo.playFrom ?: element.childText("dt"))
				putIfNotBlank("vod_play_url", playInfo.playUrl ?: element.childText("dl"))
			}
			parseCmsVodItem(node, candidate)?.let { item ->
				items.putIfAbsent(item.token, item)
			}
		}
		return CmsParsedResponse(
			categories = categories.values.toList(),
			items = items.values.toList(),
		)
	}

	private fun extractCmsPayload(
		node: Any?,
		candidate: ResourceCandidate,
		categories: MutableMap<String, CmsCategory>,
		items: MutableMap<String, TVBoxMediaItem>,
	) {
		when (node) {
			is JSONArray -> {
				for (index in 0 until node.length()) {
					extractCmsPayload(node.opt(index), candidate, categories, items)
				}
			}

			is JSONObject -> {
				val categoryId = node.firstNonBlank("type_id", "class_id")
				val categoryName = node.firstNonBlank("type_name", "class_name", "group")
				if (!categoryId.isNullOrBlank() && !categoryName.isNullOrBlank()) {
					categories.putIfAbsent(categoryId, CmsCategory(categoryId, categoryName))
				}

				parseCmsVodItem(node, candidate)?.let { item ->
					items.putIfAbsent(item.token, item)
				}

				val iterator = node.keys()
				while (iterator.hasNext()) {
					extractCmsPayload(node.opt(iterator.next()), candidate, categories, items)
				}
			}
		}
	}

	private fun parseCmsVodItem(node: JSONObject, candidate: ResourceCandidate): TVBoxMediaItem? {
		if (!looksLikeCmsVodNode(node)) {
			return null
		}
		val externalId = node.firstNonBlank("vod_id", "id", "ids")
		val title = node.firstNonBlank("vod_name", "name", "title")
		val group = node.firstNonBlank("type_name", "group")
		val typeId = node.firstNonBlank("type_id")
		val description = node.firstNonBlank("vod_remarks", "remark", "vod_content", "description", "desc")
		val coverUrl = resolveUrl(
			candidate.url,
			node.firstNonBlank(
				"vod_pic",
				"vod_pic_thumb",
				"vod_pic_slide",
				"pic",
				"pic_url",
				"img",
				"image",
				"thumb",
				"thumbnail",
				"thumbnail_url",
				"cover",
				"cover_url",
				"poster",
			),
		)
			?: firstNonBlankUrl(config.root.logo, config.root.wallpaper)
		if (!title.isNullOrBlank()) {
			Log.d(
				TAG,
				"parseCmsVodItem source=${source.name} title=$title externalId=${externalId ?: "<null>"} cover=$coverUrl rawCoverFields=${
					listOf(
						"vod_pic" to node.optStringOrNull("vod_pic"),
						"vod_pic_thumb" to node.optStringOrNull("vod_pic_thumb"),
						"vod_pic_slide" to node.optStringOrNull("vod_pic_slide"),
						"pic" to node.optStringOrNull("pic"),
						"pic_url" to node.optStringOrNull("pic_url"),
						"img" to node.optStringOrNull("img"),
						"image" to node.optStringOrNull("image"),
						"thumb" to node.optStringOrNull("thumb"),
						"thumbnail" to node.optStringOrNull("thumbnail"),
						"thumbnail_url" to node.optStringOrNull("thumbnail_url"),
						"cover" to node.optStringOrNull("cover"),
						"cover_url" to node.optStringOrNull("cover_url"),
						"poster" to node.optStringOrNull("poster"),
					).filter { !it.second.isNullOrBlank() }
						.joinToString { "${it.first}=${it.second}" }
				}",
			)
		}
		val streams = buildList {
			val directUrl = node.firstNonBlank("playUrl", "play_url", "url", "link", "src", "video", "videoUrl", "m3u8")
				?.let { resolveUrl(candidate.url, it) }
				?.takeIf(::isPlayableStreamUrl)
			if (!directUrl.isNullOrBlank() && !title.isNullOrBlank()) {
				add(
					TVBoxStream(
						token = positiveHash("${title}|$directUrl").toString(),
						title = title,
						url = directUrl,
						headers = buildHeadersForUrl(directUrl, candidate.headers),
					),
				)
			}
			node.firstNonBlank("vod_play_url")?.let { payload ->
				addAll(
					parseVodPlayUrl(
						title = title.orEmpty().ifBlank { externalId ?: source.displayName },
						payload = payload,
						playFrom = node.firstNonBlank("vod_play_from"),
						baseUrl = candidate.url,
						baseHeaders = candidate.headers,
					),
				)
			}
		}
		if (title.isNullOrBlank() || (externalId.isNullOrBlank() && streams.isEmpty())) {
			return null
		}
		val token = buildCmsToken(externalId, title)
		return TVBoxMediaItem(
			id = positiveHash("item|$token"),
			token = token,
			title = title,
			url = buildItemUrl(token, externalId),
			publicUrl = buildItemUrl(token, externalId),
			description = description,
			coverUrl = coverUrl,
			group = group,
			tags = buildCmsTags(group, typeId),
			streams = streams,
			externalId = externalId,
		)
	}

	private fun looksLikeCmsVodNode(node: JSONObject): Boolean {
		if (node.has("vod_id") || node.has("vod_name") || node.has("vod_play_url") || node.has("vod_pic")) {
			return true
		}
		val title = node.firstNonBlank("name", "title")
		val directUrl = node.firstNonBlank("playUrl", "play_url", "url", "link", "src", "video", "videoUrl", "m3u8")
		return !title.isNullOrBlank() && !directUrl.isNullOrBlank() && isPlayableStreamUrl(directUrl)
	}

	private fun buildCmsListUrls(baseUrl: String, page: Int, query: String, categoryId: String?): List<String> {
		val urls = linkedSetOf<String>()
		fun add(
			ac: String?,
			queryPairs: List<Pair<String, String>>,
			categoryPairs: List<Pair<String, String>>,
		) {
			val params = buildList {
				if (!ac.isNullOrBlank()) {
					add("ac" to ac)
				}
				add("pg" to page.toString())
				addAll(categoryPairs)
				addAll(queryPairs)
			}
			buildUrl(baseUrl, params)?.let { urls += it }
		}
		val categoryPairsVariants = if (categoryId.isNullOrBlank()) {
			listOf(emptyList())
		} else {
			listOf(
				listOf("t" to categoryId),
				listOf("type" to categoryId),
				listOf("class" to categoryId),
				listOf("type_id" to categoryId),
				listOf("class_id" to categoryId),
			)
		}
		if (query.isNotBlank()) {
			val queryPairsVariants = listOf(
				listOf("wd" to query),
				listOf("key" to query),
				listOf("keyword" to query),
			)
			for (queryPairs in queryPairsVariants) {
				for (categoryPairs in categoryPairsVariants) {
					add(null, queryPairs, categoryPairs)
					add("videolist", queryPairs, categoryPairs)
					add("detail", queryPairs, categoryPairs)
					add("list", queryPairs, categoryPairs)
				}
			}
		} else {
			for (categoryPairs in categoryPairsVariants) {
				add("videolist", emptyList(), categoryPairs)
				add("detail", emptyList(), categoryPairs)
				add("list", emptyList(), categoryPairs)
				add(null, emptyList(), categoryPairs)
			}
		}
		return urls.toList()
	}

	private fun buildCmsDetailUrls(baseUrl: String, externalId: String): List<String> {
		val urls = linkedSetOf<String>()
		fun add(ac: String?, idParamName: String) {
			val params = buildList {
				if (!ac.isNullOrBlank()) {
					add("ac" to ac)
				}
				add(idParamName to externalId)
			}
			buildUrl(baseUrl, params)?.let { urls += it }
		}
		for (idParamName in listOf("ids", "id")) {
			add("detail", idParamName)
			add("videolist", idParamName)
			add("list", idParamName)
			add(null, idParamName)
		}
		return urls.toList()
	}

	private suspend fun buildCatalog(): TVBoxCatalog {
		val candidates = buildResourceCandidates()
		Log.d(TAG, "Building TVBox catalog for ${source.name} with candidates: ${candidates.joinToString { "${it.label}=${it.url}" }}")
		if (candidates.isEmpty()) {
			logRepositoryFailure("buildCatalog", null, "no_resource_candidates")
			throw if (requiresSpiderRuntime()) {
				unsupportedSpiderSource()
			} else {
				UnsupportedSourceException(
					"TVBox site is imported but no direct media or playlist resource could be resolved from api/ext",
					null,
				)
			}
		}

		var lastLoadError: Throwable? = null
		var attemptedRemoteResource = false
		for (candidate in candidates) {
			if (isDirectCandidateUrl(candidate.url)) {
				Log.d(TAG, "Using direct TVBox candidate: ${candidate.url}")
				return buildDirectCatalog(candidate)
			}
			if (candidate.url.startsWith("http", ignoreCase = true)) {
				attemptedRemoteResource = true
			}
			val content = runCatching { readTextResource(candidate.url, candidate.headers) }
				.onFailure {
					lastLoadError = it
					logRepositoryFailure("buildCatalog", it, "candidate=${candidate.url}")
				}
				.getOrNull()
				?: run {
					continue
				}
			val catalog = parseCandidateDocument(candidate, content)
			if (catalog.items.isNotEmpty()) {
				Log.d(TAG, "Candidate ${candidate.url} produced ${catalog.items.size} items")
				return catalog
			}
		}

		if (lastLoadError != null && attemptedRemoteResource && mightBeCmsSource()) {
			logRepositoryFailure("buildCatalog", lastLoadError, "cms_resource_load_failed")
			throw IllegalStateException(
				"TVBox/CMS resource could not be loaded: ${lastLoadError.message.orEmpty()}",
				lastLoadError,
			)
		}
		logRepositoryFailure("buildCatalog", null, "no_supported_candidate")
		throw UnsupportedSourceException(
			"TVBox site is imported but the runtime only supports direct media, M3U playlists, plain-text channel lists, or simple JSON play lists",
			null,
		)
	}

	private fun buildResourceCandidates(): List<ResourceCandidate> {
		val dedup = linkedSetOf<String>()
		val result = ArrayList<ResourceCandidate>()

		fun addCandidate(label: String, rawValue: String?, extraHeaders: Map<String, String> = emptyMap()) {
			val normalized = rawValue?.trim().orEmpty()
			if (normalized.isBlank()) {
				return
			}
			val resolved = resolveCandidateUrl(normalized) ?: return
			if (!dedup.add(resolved)) {
				return
			}
			result += ResourceCandidate(
				label = label,
				url = resolved,
				headers = buildHeadersForUrl(resolved, extraHeaders),
			)
		}

		addCandidate("api", config.site.api)
		addCandidate("site.url", config.site.raw.optStringOrNull("url"))
		addCandidate("site.link", config.site.raw.optStringOrNull("link"))
		addCandidate("site.file", config.site.raw.optStringOrNull("file"))
		addCandidate("site.m3u", config.site.raw.optStringOrNull("m3u"))
		addCandidate("site.m3u8", config.site.raw.optStringOrNull("m3u8"))
		addCandidate("playUrl", config.site.playUrl)
		addCandidatesFromValue(
			label = "ext",
			value = config.site.ext,
			inheritedHeaders = emptyMap(),
			addCandidate = ::addCandidate,
		)
		Log.d(TAG, "Resolved TVBox runtime candidates for ${source.name}: ${result.joinToString { "${it.label}=${it.url}" }}")
		return result
	}

	private fun buildDirectCatalog(candidate: ResourceCandidate): TVBoxCatalog {
		val streamTitle = config.site.name.ifBlank { config.site.key.ifBlank { source.displayName } }
		val group = config.site.categories.firstOrNull()
		val item = TVBoxMediaItem(
			id = positiveHash("${source.name}|direct|${candidate.url}"),
			token = positiveHash("${source.name}|direct|${candidate.url}").toString(),
			title = streamTitle,
			url = buildItemUrl(positiveHash("${source.name}|direct|${candidate.url}").toString()),
			publicUrl = buildItemUrl(positiveHash("${source.name}|direct|${candidate.url}").toString()),
			description = buildDescription(candidate.label, directUrl = candidate.url),
			coverUrl = firstNonBlankUrl(config.root.logo, config.root.wallpaper),
			group = group,
			tags = buildTags(group),
			streams = listOf(
				TVBoxStream(
					token = positiveHash("${candidate.url}|stream").toString(),
					title = streamTitle,
					url = candidate.url,
					headers = buildHeadersForUrl(candidate.url, candidate.headers),
				),
			),
		)
		return buildCatalogFromItems(listOf(item))
	}

	private fun parseCandidateDocument(candidate: ResourceCandidate, content: String): TVBoxCatalog {
		val normalized = content.removePrefix("\uFEFF").trim()
		if (normalized.isBlank()) {
			return TVBoxCatalog.empty()
		}
		if (looksLikeM3u(normalized)) {
			return buildCatalogFromItems(parseM3uItems(candidate, normalized))
		}
		if (looksLikeJson(normalized)) {
			return buildCatalogFromItems(parseJsonItems(candidate, normalized))
		}
		if (looksLikeXml(normalized)) {
			val parsed = parseCmsXmlResponse(candidate, normalized)
			if (parsed.items.isNotEmpty()) {
				return buildCatalogFromItems(parsed.items)
			}
		}
		return buildCatalogFromItems(parsePlainTextItems(candidate, normalized))
	}

	private fun parseM3uItems(candidate: ResourceCandidate, content: String): List<TVBoxMediaItem> {
		val items = mutableListOf<TVBoxMediaItem>()
		var pendingTitle: String? = null
		var pendingGroup: String? = null
		var pendingLogo: String? = null
		var currentGroup: String? = null
		for (rawLine in content.lineSequence()) {
			val line = rawLine.trim()
			if (line.isBlank()) {
				continue
			}
			when {
				line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
				line.startsWith("#EXTGRP:", ignoreCase = true) -> {
					currentGroup = line.substringAfter(':').trim().ifBlank { null }
				}
				line.startsWith("#EXTINF", ignoreCase = true) -> {
					pendingTitle = line.substringAfterLast(',').trim().ifBlank { null }
					pendingGroup = parseM3uAttribute(line, "group-title") ?: currentGroup
					pendingLogo = parseM3uAttribute(line, "tvg-logo")
				}
				line.startsWith('#') -> Unit
				else -> {
					val resolvedUrl = resolveUrl(candidate.url, line) ?: line
					if (!isPlayableStreamUrl(resolvedUrl)) {
						pendingTitle = null
						pendingGroup = null
						pendingLogo = null
						continue
					}
					val title = pendingTitle ?: resolvedUrl.substringAfterLast('/').substringBefore('?').ifBlank { source.displayName }
					items += createMediaItem(
						title = title,
						group = pendingGroup ?: currentGroup,
						description = buildDescription(candidate.label, directUrl = resolvedUrl),
						coverUrl = firstNonBlankUrl(pendingLogo, config.root.logo, config.root.wallpaper),
						streams = listOf(
							TVBoxStream(
								token = positiveHash("${title}|$resolvedUrl").toString(),
								title = title,
								url = resolvedUrl,
								headers = buildHeadersForUrl(resolvedUrl, candidate.headers),
							),
						),
					)
					pendingTitle = null
					pendingGroup = null
					pendingLogo = null
				}
			}
		}
		return items
	}

	private fun parsePlainTextItems(candidate: ResourceCandidate, content: String): List<TVBoxMediaItem> {
		val items = mutableListOf<TVBoxMediaItem>()
		var currentGroup: String? = null
		for (rawLine in content.lineSequence()) {
			val line = rawLine.trim()
			if (line.isBlank() || line.startsWith('#') || line.startsWith("//")) {
				continue
			}
			if (line.contains(",#genre#", ignoreCase = true)) {
				currentGroup = line.substringBefore(",#genre#", missingDelimiterValue = line).trim().ifBlank { null }
				continue
			}
			val name = line.substringBefore(',', "").trim()
			val payload = line.substringAfter(',', "").trim()
			if (name.isBlank() || payload.isBlank()) {
				continue
			}
			val streams = parseStreamSpec(
				title = name,
				spec = payload,
				baseUrl = candidate.url,
				baseHeaders = candidate.headers,
			)
			if (streams.isEmpty()) {
				continue
			}
			items += createMediaItem(
				title = name,
				group = currentGroup,
				description = buildDescription(candidate.label, currentGroup),
				coverUrl = firstNonBlankUrl(config.root.logo, config.root.wallpaper),
				streams = streams,
			)
		}
		return items
	}

	private fun parseJsonItems(candidate: ResourceCandidate, content: String): List<TVBoxMediaItem> {
		val root = runCatching { JSONTokener(content).nextValue() }.getOrNull() ?: return emptyList()
		val items = mutableListOf<TVBoxMediaItem>()
		extractJsonItems(
			node = root,
			candidate = candidate,
			items = items,
			path = emptyList(),
		)
		return items
	}

	private fun extractJsonItems(
		node: Any?,
		candidate: ResourceCandidate,
		items: MutableList<TVBoxMediaItem>,
		path: List<String>,
	) {
		when (node) {
			is JSONArray -> {
				for (index in 0 until node.length()) {
					extractJsonItems(node.opt(index), candidate, items, path)
				}
			}

			is JSONObject -> {
				val title = node.firstNonBlank("name", "title", "vod_name")
				val group = node.firstNonBlank("group", "groupTitle", "type_name")
					?: path.lastOrNull()
				val description = node.firstNonBlank("desc", "description", "remark", "vod_remarks", "vod_blurb", "vod_content")
				val coverUrl = resolveUrl(
					candidate.url,
					node.firstNonBlank(
						"cover",
						"cover_url",
						"pic",
						"pic_url",
						"logo",
						"img",
						"image",
						"thumb",
						"thumbnail",
						"thumbnail_url",
						"poster",
						"vod_pic",
						"vod_pic_thumb",
						"vod_pic_slide",
					),
				)
					?: firstNonBlankUrl(config.root.logo, config.root.wallpaper)

				val streams = mutableListOf<TVBoxStream>()
				val directSpec = node.firstNonBlank(
					"playUrl",
					"play_url",
					"url",
					"link",
					"src",
					"video",
					"videoUrl",
					"m3u8",
				)
				if (!title.isNullOrBlank() && !directSpec.isNullOrBlank()) {
					streams += parseStreamSpec(
						title = title,
						spec = directSpec,
						baseUrl = candidate.url,
						baseHeaders = candidate.headers,
					)
				}

				val vodPlayUrl = node.firstNonBlank("vod_play_url")
				if (!title.isNullOrBlank() && !vodPlayUrl.isNullOrBlank()) {
					streams += parseVodPlayUrl(
						title = title,
						payload = vodPlayUrl,
						playFrom = node.firstNonBlank("vod_play_from"),
						baseUrl = candidate.url,
						baseHeaders = candidate.headers,
					)
				}

				if (!title.isNullOrBlank() && streams.isNotEmpty()) {
					items += createMediaItem(
						title = title,
						group = group,
						description = description ?: buildDescription(candidate.label, group),
						coverUrl = coverUrl,
						streams = streams.distinctBy { it.url },
					)
				}

				val nextPath = path + listOfNotNull(node.firstNonBlank("title", "name", "group", "type_name"))
				val iterator = node.keys()
				while (iterator.hasNext()) {
					val key = iterator.next()
					if (key.equals("header", ignoreCase = true) || key.equals("headers", ignoreCase = true)) {
						continue
					}
					extractJsonItems(node.opt(key), candidate, items, nextPath)
				}
			}
		}
	}

	private fun parseVodPlayUrl(
		title: String,
		payload: String,
		playFrom: String? = null,
		baseUrl: String,
		baseHeaders: Map<String, String>,
	): List<TVBoxStream> {
		val groups = payload.split("$$$")
		val groupNames = playFrom
			?.split("$$$")
			?.map { it.trim() }
			.orEmpty()
		val streams = mutableListOf<TVBoxStream>()
		var droppedCount = 0
		groups.forEachIndexed { groupIndex, groupPayload ->
			val groupName = groupNames.getOrNull(groupIndex)?.takeIf { it.isNotBlank() }
			groupPayload.split('#')
				.map(String::trim)
				.filter(String::isNotBlank)
				.forEachIndexed { streamIndex, entry ->
					val rawStreamName = entry.substringBefore('$', "").trim().ifBlank {
						"线路${groupIndex + 1}-${streamIndex + 1}"
					}
					val streamName = if (!groupName.isNullOrBlank() && groups.size > 1) {
						"$groupName - $rawStreamName"
					} else {
						rawStreamName
					}
					val rawUrl = entry.substringAfter('$', "").trim()
					if (rawUrl.isBlank()) {
						droppedCount += 1
						return@forEachIndexed
					}
					val resolved = resolveUrl(baseUrl, rawUrl) ?: rawUrl
					if (!isPlayableStreamUrl(resolved)) {
						droppedCount += 1
						return@forEachIndexed
					}
					streams += TVBoxStream(
						token = positiveHash("${title}|$streamName|$resolved").toString(),
						title = streamName,
						url = resolved,
						headers = buildHeadersForUrl(resolved, baseHeaders),
					)
				}
		}
		if (droppedCount > 0 || groups.size > 1) {
			Log.d(
				TAG,
				"Parsed TVBox play urls for \"$title\": kept=${streams.size}, dropped=$droppedCount, groups=${groups.size}, playFrom=${playFrom.orEmpty()}",
			)
		}
		return streams
	}

	private fun parseStreamSpec(
		title: String,
		spec: String,
		baseUrl: String,
		baseHeaders: Map<String, String>,
	): List<TVBoxStream> {
		val segments = spec.split('#')
			.map(String::trim)
			.filter(String::isNotBlank)
		if (segments.isEmpty()) {
			return emptyList()
		}
		return segments.mapIndexedNotNull { index, rawSegment ->
			val hasCustomTitle = rawSegment.contains('$')
			val streamTitle = if (hasCustomTitle) {
				rawSegment.substringBefore('$').trim().ifBlank { title }
			} else if (segments.size > 1) {
				"$title 线路${index + 1}"
			} else {
				title
			}
			val rawUrl = if (hasCustomTitle) {
				rawSegment.substringAfter('$').trim()
			} else {
				rawSegment
			}
			if (rawUrl.isBlank()) {
				return@mapIndexedNotNull null
			}
			val resolvedUrl = resolveUrl(baseUrl, rawUrl) ?: rawUrl
			if (!isPlayableStreamUrl(resolvedUrl)) {
				return@mapIndexedNotNull null
			}
			TVBoxStream(
				token = positiveHash("${title}|$streamTitle|$resolvedUrl").toString(),
				title = streamTitle,
				url = resolvedUrl,
				headers = buildHeadersForUrl(resolvedUrl, baseHeaders),
			)
		}
	}

	private fun buildCatalogFromItems(items: List<TVBoxMediaItem>): TVBoxCatalog {
		if (items.isEmpty()) {
			return TVBoxCatalog.empty()
		}
		val uniqueItems = items.distinctBy { "${it.title}|${it.group.orEmpty()}|${it.streams.firstOrNull()?.url.orEmpty()}" }
		val availableTags = linkedSetOf<ContentTag>()
		uniqueItems.forEach { item -> availableTags += item.tags }
		config.site.categories.forEach { category ->
			availableTags += ContentTag(
				title = category,
				key = "group:${category.lowercase()}",
				source = source,
			)
		}
		return TVBoxCatalog(
			items = uniqueItems,
			availableTags = availableTags,
		)
	}

	private fun createMediaItem(
		title: String,
		group: String?,
		description: String?,
		coverUrl: String?,
		streams: List<TVBoxStream>,
	): TVBoxMediaItem {
		val token = positiveHash("${source.name}|$title|${group.orEmpty()}|${streams.firstOrNull()?.url.orEmpty()}").toString()
		return TVBoxMediaItem(
			id = positiveHash("item|$token"),
			token = token,
			title = title,
			url = buildItemUrl(token),
			publicUrl = buildItemUrl(token),
			description = description,
			coverUrl = coverUrl,
			group = group,
			tags = buildTags(group),
			streams = streams,
		)
	}

	private fun buildTags(group: String?): Set<ContentTag> {
		return if (group.isNullOrBlank()) {
			emptySet()
		} else {
			setOf(
				ContentTag(
					title = group,
					key = "group:${group.lowercase()}",
					source = source,
				),
			)
		}
	}

	private fun buildCmsTags(group: String?, typeId: String?): Set<ContentTag> {
		return when {
			group.isNullOrBlank() && typeId.isNullOrBlank() -> emptySet()
			else -> setOf(buildCmsTag(group ?: typeId.orEmpty(), typeId))
		}
	}

	private fun buildCmsTag(title: String, typeId: String?): ContentTag {
		return ContentTag(
			title = title,
			key = "cms_type:${typeId ?: title.lowercase()}",
			source = source,
		)
	}

	private fun parseCmsTagId(key: String): String? {
		if (!key.startsWith("cms_type:")) {
			return null
		}
		return key.substringAfter("cms_type:").trim().ifBlank { null }
	}

	private fun sortItems(
		items: List<TVBoxMediaItem>,
		order: SortOrder,
		query: String,
	): List<TVBoxMediaItem> {
		return when (order) {
			SortOrder.ALPHABETICAL -> items.sortedBy { it.title.lowercase() }
			SortOrder.RELEVANCE -> if (query.isBlank()) {
				items
			} else {
				items.sortedBy { item ->
					when {
						item.title.equals(query, ignoreCase = true) -> 0
						item.title.startsWith(query, ignoreCase = true) -> 1
						item.title.contains(query, ignoreCase = true) -> 2
						item.description.orEmpty().contains(query, ignoreCase = true) -> 3
						else -> 4
					}
				}
			}
			else -> items
		}
	}

	private suspend fun readTextResource(url: String, headers: Map<String, String>): String {
		val uri = Uri.parse(url)
		return when (uri.scheme?.lowercase()) {
			"http", "https" -> {
				Log.d(TAG, "Loading TVBox resource: $url")
				val response = httpClient.get(url, headers, source)
				try {
					if (!response.isSuccessful) {
						throw IllegalArgumentException("HTTP ${response.code} when loading TVBox resource")
					}
					response.body?.string().orEmpty()
				} finally {
					response.close()
				}
			}

			"content" -> {
				context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
					?: throw IllegalArgumentException("Unable to open TVBox content URI")
			}

			"file" -> File(checkNotNull(uri.path)).readText()
			null -> File(url).readText()
			else -> throw IllegalArgumentException("Unsupported TVBox resource scheme: ${uri.scheme}")
		}
	}

	private fun buildHeadersForUrl(url: String?, extraHeaders: Map<String, String>): Map<String, String> {
		val headers = linkedMapOf<String, String>()
		headers += config.site.staticHeaders
		headers += extraHeaders
		val normalizedUrl = url?.extractPrimaryLocator()
		val host = normalizedUrl?.toHttpUrlOrNull()?.host?.lowercase()
		if (!host.isNullOrBlank()) {
			config.root.headerRules
				.filter { host == it.host.lowercase() }
				.forEach { rule -> headers += rule.headers }
		}
		if (!headers.keys.any { it.equals(CommonHeaders.REFERER, ignoreCase = true) }) {
			normalizedUrl?.toHttpUrlOrNull()?.let { httpUrl ->
				headers[CommonHeaders.REFERER] = "${httpUrl.scheme}://${httpUrl.host}/"
			}
		}
		return headers
	}

	private fun buildDescription(label: String, group: String? = null, directUrl: String? = null): String {
		return buildString {
			append("TVBox ")
			append(label)
			group?.takeIf { it.isNotBlank() }?.let {
				append(" / ")
				append(it)
			}
			directUrl?.takeIf { it.isNotBlank() }?.let {
				append('\n')
				append(it)
			}
			config.meta.sourceLocator?.takeIf { it.isNotBlank() }?.let {
				append('\n')
				append("来源: ")
				append(it)
			}
		}
	}

	private fun unsupportedSpiderSource(): UnsupportedSourceException {
		val spider = config.root.spider?.takeIf { it.isNotBlank() }
		val jar = config.site.jar?.takeIf { it.isNotBlank() }
		val runtimeDescription = spiderRuntime?.describeCapability(config)
		val runtimeUnavailability = spiderRuntime?.describeUnavailability(config)
		return UnsupportedSourceException(
			buildString {
				append("TVBox spider/jar/csp source requires partial local runtime support: type=")
				append(config.site.type)
				append(", api=")
				append(config.site.api)
				spider?.let {
					append(", spider=")
					append(it)
				}
				jar?.let {
					append(", jar=")
					append(it)
				}
				runtimeDescription?.let {
					append(", runtime=")
					append(it)
				}
				runtimeUnavailability?.let {
					append(", note=")
					append(it)
				}
				append(", fallback=direct media, playlists, and simple CMS candidates are handled before this error")
			},
			null,
		)
	}

	private fun logRepositoryFailure(action: String, error: Throwable?, detail: String? = null) {
		TVBoxRuntimeDiagnostics.logFailure(
			tag = TAG,
			sourceName = source.name,
			runtimeId = spiderRuntime?.id,
			action = action,
			category = TVBoxRuntimeDiagnostics.classifyRepository(error, requiresSpiderRuntime()),
			error = error,
			detail = detail,
		)
	}

	private fun buildItemUrl(token: String): String = "tvbox://item/$token"

	private fun buildItemUrl(token: String, externalId: String?): String {
		if (externalId.isNullOrBlank()) {
			return buildItemUrl(token)
		}
		val encodedId = Uri.encode(externalId)
		return "tvbox://item/$token?id=$encodedId"
	}

	private fun buildChapterUrl(
		itemToken: String,
		streamToken: String,
		externalId: String? = null,
		streamUrl: String? = null,
	): String {
		val query = buildList {
			externalId?.trim()?.takeIf { it.isNotBlank() }?.let { add("id=${Uri.encode(it)}") }
			streamUrl?.trim()?.takeIf { it.isNotBlank() }?.let { add("play=${Uri.encode(it)}") }
		}
			.joinToString("&")
		return buildString {
			append("tvbox://chapter/")
			append(itemToken)
			append('/')
			append(streamToken)
			if (query.isNotBlank()) {
				append('?')
				append(query)
			}
		}
	}

	private fun parseChapterUrl(url: String): ChapterLocator? {
		val uri = Uri.parse(url)
		if (uri.scheme != "tvbox" || uri.host != "chapter") {
			return null
		}
		val segments = uri.pathSegments
		if (segments.size < 2) {
			return null
		}
		return ChapterLocator(
			itemToken = segments[0],
			streamToken = segments[1],
			externalId = uri.getQueryParameter("id"),
			streamUrl = uri.getQueryParameter("play"),
		)
	}

	private fun parseItemUrl(url: String?): ItemLocator? {
		val uri = url?.let(Uri::parse) ?: return null
		if (uri.scheme != "tvbox" || uri.host != "item") {
			return null
		}
		val token = uri.pathSegments.firstOrNull() ?: return null
		return ItemLocator(
			token = token,
			externalId = uri.getQueryParameter("id"),
		)
	}

	private fun firstNonBlankUrl(vararg candidates: String?): String? {
		return candidates.firstNotNullOfOrNull { candidate ->
			candidate
				?.extractPrimaryLocator()
				?.takeIf { it.isNotBlank() && looksLikeUrl(it) }
		}
	}

	private fun looksLikeJson(text: String): Boolean {
		return text.startsWith('{') || text.startsWith('[')
	}

	private fun looksLikeXml(text: String): Boolean {
		return text.startsWith('<')
	}

	private fun looksLikeM3u(text: String): Boolean {
		return text.contains("#EXTM3U", ignoreCase = true) || text.contains("#EXTINF", ignoreCase = true)
	}

	private fun looksLikeUrl(value: String): Boolean {
		val normalized = value.extractPrimaryLocator()
		return normalized.startsWith("http://", ignoreCase = true) ||
			normalized.startsWith("https://", ignoreCase = true) ||
			normalized.startsWith("content://", ignoreCase = true) ||
			normalized.startsWith("file://", ignoreCase = true) ||
			File(normalized).exists()
	}

	private fun isDirectCandidateUrl(value: String): Boolean {
		val normalized = value.extractPrimaryLocator().lowercase()
		return normalized.endsWith(".m3u8") ||
			normalized.endsWith(".mp4") ||
			normalized.endsWith(".flv") ||
			normalized.endsWith(".mpd") ||
			normalized.endsWith(".mkv") ||
			normalized.endsWith(".webm") ||
			normalized.endsWith(".avi") ||
			normalized.endsWith(".mov")
	}

	private fun isPlayableStreamUrl(value: String): Boolean {
		val normalized = value.extractPrimaryLocator().lowercase()
		if (looksLikeDirectPlaybackUrl(normalized)) {
			return true
		}
		if (normalized.startsWith("rtmp://") || normalized.startsWith("rtsp://")) {
			return true
		}
		if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
			return false
		}
		return false
	}

	private fun looksLikeDirectPlaybackUrl(value: String): Boolean {
		val normalized = value.extractPrimaryLocator()
		return normalized.contains(".m3u8") ||
			normalized.contains(".mp4") ||
			normalized.contains(".flv") ||
			normalized.contains(".mpd") ||
			normalized.contains(".mkv") ||
			normalized.contains(".webm") ||
			normalized.contains(".avi") ||
			normalized.contains(".mov")
	}

	private fun resolveCandidateUrl(rawValue: String): String? {
		val normalized = rawValue.extractPrimaryLocator()
		if (looksLikeUrl(normalized)) {
			return normalized
		}
		if (normalized.startsWith('/') || normalized.startsWith("./") || normalized.startsWith("../")) {
			return resolveUrl(config.meta.sourceLocator, normalized)
		}
		if (normalized.contains('/') || normalized.contains('\\') || normalized.contains('.')) {
			return resolveUrl(config.meta.sourceLocator, normalized)
		}
		return null
	}

	private fun buildUrl(baseUrl: String, params: List<Pair<String, String>>): String? {
		val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
		return httpUrl.newBuilder().apply {
			params.forEach { (name, value) ->
				addQueryParameter(name, value)
			}
		}.build().toString()
	}

	private fun mightBeCmsSource(): Boolean {
		val candidates = buildResourceCandidates()
			.asSequence()
			.map { it.url.lowercase() }
			.toList()
		return candidates.any { url ->
			url.startsWith("http://") || url.startsWith("https://")
		} && candidates.any { url ->
			url.contains("provide/vod") ||
				url.contains("api.php") ||
				url.contains(".php")
			}
	}

	private fun requiresSpiderRuntime(): Boolean {
		return config.site.type == 3 ||
			config.site.type == 4 ||
			config.site.api.startsWith("csp_", ignoreCase = true)
	}

	private suspend fun resolveCmsStream(locator: ChapterLocator): TVBoxStream? {
		val externalId = locator.externalId ?: return null
		val provider = resolveCmsProvider() ?: return null
		val requestUrls = buildCmsDetailUrls(provider.candidate.url, externalId)
		val items = fetchCmsItems(provider.candidate, requestUrls)
		val item = items.firstOrNull { it.externalId == externalId } ?: items.firstOrNull() ?: return null
		cmsItems[item.token] = item
		return item.streams.firstOrNull { it.token == locator.streamToken }
	}

	private fun buildCmsToken(externalId: String?, title: String): String {
		return "cms:${externalId ?: positiveHash(title)}"
	}

	private fun resolveUrl(baseUrl: String?, rawUrl: String?): String? {
		val value = rawUrl?.extractPrimaryLocator().orEmpty()
		if (value.isBlank()) {
			return null
		}
		if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
			return value
		}
		if (value.startsWith("content://", ignoreCase = true) || value.startsWith("file://", ignoreCase = true)) {
			return value
		}
		if (value.startsWith("//")) {
			return "https:$value"
		}
		val baseHttpUrl = baseUrl?.toHttpUrlOrNull()
		if (baseHttpUrl != null) {
			return baseHttpUrl.resolve(value)?.toString()
		}
		if (baseUrl.isNullOrBlank()) {
			return value
		}
		return runCatching {
			val baseFile = if (baseUrl.startsWith("file://", ignoreCase = true)) {
				File(checkNotNull(Uri.parse(baseUrl).path))
			} else {
				File(baseUrl)
			}
			baseFile.parentFile?.resolve(value)?.path ?: value
		}.getOrDefault(value)
	}

	private fun String.extractPrimaryLocator(): String {
		val trimmed = trim()
		if (trimmed.isBlank()) {
			return trimmed
		}
		val markers = listOf(";md5;", ";pk;")
		markers.forEach { marker ->
			val index = trimmed.indexOf(marker, ignoreCase = true)
			if (index >= 0) {
				return trimmed.substring(0, index).trim()
			}
		}
		val separatorIndex = trimmed.indexOf(';')
		return if (separatorIndex >= 0) {
			trimmed.substring(0, separatorIndex).trim()
		} else {
			trimmed
		}
	}

	private fun addCandidatesFromValue(
		label: String,
		value: Any?,
		inheritedHeaders: Map<String, String>,
		addCandidate: (label: String, rawValue: String?, extraHeaders: Map<String, String>) -> Unit,
	) {
		when (value) {
			null, JSONObject.NULL -> Unit
			is String -> addCandidate(label, value, inheritedHeaders)
			is JSONArray -> {
				for (index in 0 until value.length()) {
					addCandidatesFromValue(
						label = "$label[$index]",
						value = value.opt(index),
						inheritedHeaders = inheritedHeaders,
						addCandidate = addCandidate,
					)
				}
			}
			is JSONObject -> {
				val mergedHeaders = inheritedHeaders + value.optHeaderMap("headers")
					.ifEmpty { value.optHeaderMap("header") }
				listOf("url", "api", "playUrl", "link", "file", "m3u", "m3u8", "json")
					.forEach { key ->
						addCandidate("$label.$key", value.optStringOrNull(key), mergedHeaders)
					}
				val iterator = value.keys()
				while (iterator.hasNext()) {
					val key = iterator.next()
					if (key.equals("header", ignoreCase = true) || key.equals("headers", ignoreCase = true)) {
						continue
					}
					addCandidatesFromValue(
						label = "$label.$key",
						value = value.opt(key),
						inheritedHeaders = mergedHeaders,
						addCandidate = addCandidate,
					)
				}
			}
			else -> addCandidate(label, value.toString(), inheritedHeaders)
		}
	}

	private fun parseM3uAttribute(line: String, key: String): String? {
		val regex = Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE)
		return regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
	}

	private fun positiveHash(value: String): Long {
		return value.hashCode().toLong() and Long.MAX_VALUE
	}

	private data class ResourceCandidate(
		val label: String,
		val url: String,
		val headers: Map<String, String>,
	)

	private data class TVBoxCatalog(
		val items: List<TVBoxMediaItem>,
		val availableTags: Set<ContentTag>,
	) {
		companion object {
			fun empty() = TVBoxCatalog(
				items = emptyList(),
				availableTags = emptySet(),
			)
		}
	}

	private data class TVBoxMediaItem(
		val id: Long,
		val token: String,
		val title: String,
		val url: String,
		val publicUrl: String,
		val description: String?,
		val coverUrl: String?,
		val group: String?,
		val tags: Set<ContentTag>,
		val streams: List<TVBoxStream>,
		val externalId: String? = null,
	) {
		fun toContent(source: JsonContentSource): Content = Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			tags = tags,
			state = null,
			authors = emptySet(),
			description = description,
			chapters = null,
			source = source,
		)
	}

	private data class TVBoxStream(
		val token: String,
		val title: String,
		val url: String,
		val headers: Map<String, String>,
	)

	private data class ChapterLocator(
		val itemToken: String,
		val streamToken: String,
		val externalId: String?,
		val streamUrl: String?,
	)

	private data class ItemLocator(
		val token: String,
		val externalId: String?,
	)

	private data class CmsCategory(
		val id: String,
		val name: String,
	)

	private data class CmsParsedResponse(
		val categories: List<CmsCategory>,
		val items: List<TVBoxMediaItem>,
	) {
		companion object {
			fun empty() = CmsParsedResponse(
				categories = emptyList(),
				items = emptyList(),
			)
		}
	}

	private data class CmsProvider(
		val candidate: ResourceCandidate,
		val categories: List<CmsCategory>,
	)
}

private fun JSONObject.firstNonBlank(vararg keys: String): String? {
	keys.forEach { key ->
		val value = optStringOrNull(key)
		if (!value.isNullOrBlank()) {
			return value
		}
	}
	return null
}

private fun JSONObject.optStringOrNull(key: String): String? {
	if (!has(key)) {
		return null
	}
	val value = opt(key)
	if (value == null || value === JSONObject.NULL) {
		return null
	}
	return value.toString().trim().ifBlank { null }
}

private fun JSONObject.putIfNotBlank(key: String, value: String?) {
	val normalized = value?.trim().orEmpty()
	if (normalized.isNotBlank()) {
		put(key, normalized)
	}
}

private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
	runCatching { setFeature(name, value) }
}

private fun org.w3c.dom.NodeList.forEachElement(action: (Element) -> Unit) {
	for (index in 0 until length) {
		val element = item(index) as? Element ?: continue
		action(element)
	}
}

private fun Element.attrOrText(name: String): String {
	return getAttribute(name).trim().ifBlank { childText(name).orEmpty() }
}

private fun Element.childText(tagName: String): String? {
	val nodes = getElementsByTagName(tagName)
	for (index in 0 until nodes.length) {
		val node = nodes.item(index)
		if (node.parentNode != this) {
			continue
		}
		val text = when (node.nodeType) {
			Node.CDATA_SECTION_NODE,
			Node.TEXT_NODE,
			Node.ELEMENT_NODE,
			-> node.textContent
			else -> null
		}
		return text?.trim()?.ifBlank { null }
	}
	return null
}

private fun Element.extractMacCmsXmlPlayInfo(): MacCmsXmlPlayInfo {
	val playFrom = mutableListOf<String>()
	val playUrls = mutableListOf<String>()
	getElementsByTagName("dd").forEachElement { dd ->
		val payload = dd.textContent?.trim().orEmpty()
		if (payload.isBlank()) {
			return@forEachElement
		}
		playUrls += payload
		playFrom += dd.getAttribute("flag").trim().ifBlank { "线路${playUrls.size}" }
	}
	return MacCmsXmlPlayInfo(
		playFrom = playFrom.takeIf { it.isNotEmpty() }?.joinToString("$$$"),
		playUrl = playUrls.takeIf { it.isNotEmpty() }?.joinToString("$$$"),
	)
}

private data class MacCmsXmlPlayInfo(
	val playFrom: String?,
	val playUrl: String?,
)

private fun JSONObject.optHeaderMap(key: String): Map<String, String> {
	val value = opt(key)
	if (value !is JSONObject) {
		return emptyMap()
	}
	return buildMap {
		val iterator = value.keys()
		while (iterator.hasNext()) {
			val headerKey = iterator.next()
			val headerValue = value.opt(headerKey)?.toString()?.trim().orEmpty()
			if (headerValue.isNotBlank()) {
				put(headerKey, headerValue)
			}
		}
	}
}
