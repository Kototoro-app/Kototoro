package org.skepsun.kototoro.core.parser.tvbox

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

internal class TVBoxQuickJsSpiderRuntime(
	private val source: JsonContentSource,
	private val config: TVBoxStoredConfig,
	private val context: Context,
	private val httpClient: LegadoHttpClient,
	private val videoLocalCacheProxy: VideoLocalCacheProxy,
) : TVBoxSpiderRuntime {

	companion object {
		private const val TAG = "TVBoxQuickJsRuntime"
		private const val PREFS_NAME = "tvbox_js_runtime"
		private const val TAG_CATEGORY_PREFIX = "tvbox_category:"
		private const val CHAPTER_SCHEME = "tvbox-js://play"
		private const val HOME_CATEGORY_FALLBACK_LIMIT = 5
	}

	override val id: String = "quickjs"

	private val prefs by lazy(LazyThreadSafetyMode.NONE) {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}
	private val homeMutex = Mutex()
	private val filterOptionsMutex = Mutex()
	private val detailMutex = Mutex()
	private val detailCache = ConcurrentHashMap<String, TVBoxDetailResult>()

	@Volatile
	private var homeCache: TVBoxHomeResult? = null

	@Volatile
	private var filterOptionsCache: ContentListFilterOptions? = null

	override fun describeCapability(config: TVBoxStoredConfig): String {
		return "QuickJS(type=4, basic TVBox JS bridge)"
	}

	override fun describeUnavailability(config: TVBoxStoredConfig): String? {
		val scriptLocator = resolveScriptLocator()
		return when {
			scriptLocator == null -> "TVBox type=4 JS spider has no resolvable script entry in api/ext"
			scriptLocator.startsWith("//bb", ignoreCase = true) -> "TVBox //bb QuickJS bytecode is not supported by the current bridge"
			else -> "Advanced TVBox JS features such as ES modules, cat.js dependencies, and js2Proxy are not fully supported yet"
		}
	}

	override suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: ContentListFilter?,
	): List<Content>? {
		val page = offset + 1
		val query = filter?.query?.trim().orEmpty()
		val selectedCategoryId = filter?.tags
			?.firstNotNullOfOrNull { tag -> parseCategoryTagId(tag.key) }
		return runCatching {
			when {
				query.isNotBlank() && config.site.searchable -> search(query, page)
				selectedCategoryId != null -> loadCategory(selectedCategoryId, page)
				offset == 0 -> {
					val homeVodItems = loadHomeVod()
					if (homeVodItems.isNotEmpty()) {
						homeVodItems.map { it.toContent(source) }
					} else {
						loadInitialCategoryFallback(loadHome(), page)
					}
				}
				else -> {
					loadInitialCategoryFallback(loadHome(), page)
				}
			}
		}.onFailure {
			logQuickJsFailure("getList", it)
		}.getOrNull()
	}

	override suspend fun getDetails(manga: Content): Content? {
		return runCatching {
			val detail = loadDetail(manga) ?: return manga
			detail.toContent(source).copy(
				id = manga.id,
				url = manga.url,
				publicUrl = manga.publicUrl,
			)
		}.onFailure {
			logQuickJsFailure("getDetails", it)
		}.getOrNull()
	}

	override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage>? {
		return runCatching {
			val locator = parseChapterLocator(chapter.url)
				?: return listOf(
					ContentPage(
						id = positiveHash("${chapter.url}|page"),
						url = chapter.url,
						preview = null,
						headers = buildHeadersForUrl(chapter.url, emptyMap()),
						source = source,
					),
				)
			val directLocator = TVBoxPlayback.normalizeLocator(locator.id)
			if (directLocator.startsWith("http://", ignoreCase = true) || directLocator.startsWith("https://", ignoreCase = true)) {
				return listOf(
					ContentPage(
						id = positiveHash("${chapter.url}|page"),
						url = directLocator,
						preview = null,
						headers = buildHeadersForUrl(directLocator, emptyMap()),
						source = source,
					),
				)
			}
			val playResult = loadPlay(locator.flag, locator.id)
			val finalUrl = TVBoxPlayback.normalizeLocator(
				playResult?.url?.takeIf { it.isNotBlank() } ?: locator.id,
			)
			listOf(
				ContentPage(
					id = positiveHash("${chapter.url}|page"),
					url = finalUrl,
					preview = null,
					headers = buildHeadersForUrl(finalUrl, playResult?.headers.orEmpty()),
					source = source,
				),
			)
		}.onFailure {
			logQuickJsFailure("getPages", it)
		}.getOrNull()
	}

	override suspend fun getFilterOptions(): ContentListFilterOptions? {
		filterOptionsCache?.let { return it }
		return filterOptionsMutex.withLock {
			filterOptionsCache?.let { return it }
			runCatching {
				val home = loadHome()
				if (home.categories.isEmpty()) {
					ContentListFilterOptions()
				} else {
					val tags = home.categories.mapTo(linkedSetOf()) { category ->
						ContentTag(
							title = category.name,
							key = "$TAG_CATEGORY_PREFIX${category.id}",
							source = source,
						)
					}
					ContentListFilterOptions(
						availableTags = tags,
						tagGroups = listOf(ContentTagGroup("分类", tags)),
					)
				}
			}.onFailure {
				logQuickJsFailure("getFilterOptions", it)
			}.getOrNull()?.also {
				filterOptionsCache = it
			}
		}
	}

	override fun getRequestHeaders(): Map<String, String>? {
		return config.site.staticHeaders.takeIf { it.isNotEmpty() }
	}

	private suspend fun loadHome(): TVBoxHomeResult {
		homeCache?.let { return it }
		return homeMutex.withLock {
			homeCache?.let { return it }
			val raw = executeSpiderCall(
				action = "home",
				argsLiteral = "true",
			).orEmpty()
			parseHomeResult(raw).also { homeCache = it }
		}
	}

	private suspend fun loadHomeVod(): List<TVBoxVodItem> {
		val raw = executeSpiderCall(
			action = "homeVod",
			argsLiteral = "",
		).orEmpty()
		return parseVodList(raw)
	}

	private suspend fun loadCategory(categoryId: String, page: Int): List<Content> {
		Log.i(TAG, "Loading TVBox category for ${source.name}: categoryId=$categoryId page=$page")
		val raw = executeSpiderCall(
			action = "category",
			argsLiteral = listOf(
				categoryId.toJsStringLiteral(),
				page.toString().toJsStringLiteral(),
				"true",
				"{}",
			).joinToString(", "),
		).orEmpty()
		return parseVodList(raw).map { it.toContent(source) }
	}

	private suspend fun loadInitialCategoryFallback(
		home: TVBoxHomeResult,
		page: Int,
	): List<Content> {
		val categories = home.categories.take(HOME_CATEGORY_FALLBACK_LIMIT)
		if (categories.isEmpty()) {
			Log.i(TAG, "TVBox home has no categories for ${source.name}")
			return emptyList()
		}
		categories.forEach { category ->
			Log.i(TAG, "Trying fallback category for ${source.name}: categoryId=${category.id} name=${category.name} page=$page")
			val items = loadCategory(category.id, page)
			if (items.isNotEmpty()) {
				Log.i(TAG, "Fallback category resolved for ${source.name}: categoryId=${category.id} name=${category.name} count=${items.size}")
				return items
			}
		}
		Log.i(
			TAG,
			"All fallback categories are empty for ${source.name}: tried=${categories.joinToString { it.id }} page=$page",
		)
		return emptyList()
	}

	private suspend fun search(query: String, page: Int): List<Content> {
		val raw = executeSpiderCall(
			action = "search",
			argsLiteral = listOf(
				query.toJsStringLiteral(),
				"false",
				page.toString().toJsStringLiteral(),
			).joinToString(", "),
		).orEmpty()
		val items = parseVodList(raw)
		Log.d(
			TAG,
			"TVBox quickjs search result for ${source.name}: query=$query page=$page total=${items.size} covers=${
				items.joinToString(limit = 5) { "${it.title}=>${it.coverUrl ?: "<null>"}" }
			}",
		)
		return items.map { it.toContent(source) }
	}

	private suspend fun loadDetail(manga: Content): TVBoxDetailResult? {
		val itemId = (manga.url ?: manga.publicUrl).orEmpty().ifBlank { manga.id.toString() }
		detailCache[itemId]?.let { return it }
		return detailMutex.withLock {
			detailCache[itemId]?.let { return it }
			val raw = executeSpiderCall(
				action = "detail",
				argsLiteral = itemId.toJsStringLiteral(),
			).orEmpty()
			(parseDetailResult(raw) ?: buildFallbackDetailResult(raw, manga))
				?.also { detailCache[itemId] = it }
		}
	}

	private suspend fun loadPlay(flag: String, id: String): TVBoxPlayResult? {
		val raw = executeSpiderCall(
			action = "play",
			argsLiteral = listOf(
				flag.toJsStringLiteral(),
				id.toJsStringLiteral(),
				"[]",
			).joinToString(", "),
		).orEmpty()
		return parsePlayResult(raw)
	}

	private suspend fun executeSpiderCall(
		action: String,
		argsLiteral: String,
	): String? {
		val scriptLocator = resolveScriptLocator() ?: return null
		val executableScript = buildExecutableScript(scriptLocator) ?: return null
		val initLiteral = buildInitArgumentLiteral()
		return createQuickJs().use { qjs ->
			registerHostBridge(qjs)
			qjs.evaluate<Any?>(buildBootstrapScript(), "<tvbox-bootstrap>")
			qjs.evaluate<Any?>(executableScript, scriptLocator)
			val initError = qjs.evaluate<String?>(
				"""
				await (async function() {
				  try {
				    const spider = globalThis.__TVBOX_SPIDER__;
				    if (!spider || typeof spider.init !== 'function') return null;
				    const initArg = $initLiteral;
				    const res = spider.init(initArg);
				    if (res && typeof res.then === 'function') await res;
				    return null;
				  } catch (e) {
				    return (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
				  }
				})();
				""".trimIndent(),
				"<tvbox-init>",
			)
			if (!initError.isNullOrBlank()) {
				logQuickJsFailure("init", null, initError)
			}
			qjs.evaluate<String?>(
				"""
				await (async function() {
				  try {
				    const spider = globalThis.__TVBOX_SPIDER__;
				    if (!spider) {
				      return JSON.stringify({ error: "TVBox spider instance is missing" });
				    }
				    const fn = spider[${
						action.toJsStringLiteral()
					}];
				    if (typeof fn !== 'function') {
				      return JSON.stringify({ error: "TVBox action '${action}' is missing" });
				    }
				    let res;
				    if (${action.toJsStringLiteral()} === "search") {
				      if (fn.length >= 3) {
				        res = fn($argsLiteral);
				      } else if (fn.length >= 2) {
				        res = fn(${queryLiteralForSearch(argsLiteral)});
				      } else {
				        res = fn(${firstLiteralArg(argsLiteral)});
				      }
				    } else {
				      res = fn($argsLiteral);
				    }
				    if (res && typeof res.then === 'function') {
				      res = await res;
				    }
				    if (typeof res === "string") {
				      return res;
				    }
				    return JSON.stringify(res == null ? {} : res);
				  } catch (e) {
				    const err = (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
				    return JSON.stringify({ error: err });
				  }
				})();
				""".trimIndent(),
				"<tvbox-$action>",
			)
		}
	}

	private fun queryLiteralForSearch(argsLiteral: String): String {
		return argsLiteral.substringBeforeLast(",").trim()
	}

	private fun firstLiteralArg(argsLiteral: String): String {
		return argsLiteral.substringBefore(",").trim()
	}

	private suspend fun buildExecutableScript(scriptLocator: String): String? {
		val resolvedLocator = resolveCandidateUrl(scriptLocator) ?: scriptLocator.extractPrimaryUrl()
		if (resolvedLocator.isNullOrBlank()) {
			logQuickJsFailure("loadScript", null, "unresolved_locator=$scriptLocator")
			return null
		}
		val scriptContent = readTextResource(resolvedLocator, buildHeadersForUrl(resolvedLocator, emptyMap()))
		val normalized = scriptContent.removePrefix("\uFEFF").trimStart()
		if (normalized.startsWith("//bb")) {
			logQuickJsFailure("loadScript", null, "unsupported_bytecode=$resolvedLocator")
			return null
		}
		if (Regex("""^\s*import\s""", setOf(RegexOption.MULTILINE)).containsMatchIn(normalized)) {
			logQuickJsFailure("loadScript", null, "unsupported_module=$resolvedLocator")
			return null
		}
		val rewritten = normalized
			.replace(Regex("""__JS_SPIDER__\s*="""), "globalThis.__TVBOX_DECLARED_SPIDER__ =")
			.replace(Regex("""export\s+default"""), "globalThis.__TVBOX_EXPORT_DEFAULT__ =")
		return buildString {
			append(rewritten)
			append('\n')
			append(
				"""
				;(() => {
				  if (!globalThis.__TVBOX_SPIDER__) {
				    if (typeof __jsEvalReturn === "function") {
				      globalThis.req = http;
				      const spider = __jsEvalReturn();
				      if (spider && typeof spider === "object") {
				        spider.is_cat = true;
				      }
				      globalThis.__TVBOX_SPIDER__ = spider;
				    } else if (typeof globalThis.__TVBOX_DECLARED_SPIDER__ !== "undefined") {
				      globalThis.__TVBOX_SPIDER__ = globalThis.__TVBOX_DECLARED_SPIDER__;
				    } else if (typeof globalThis.__TVBOX_EXPORT_DEFAULT__ !== "undefined") {
				      globalThis.__TVBOX_SPIDER__ =
				        typeof globalThis.__TVBOX_EXPORT_DEFAULT__ === "function"
				          ? globalThis.__TVBOX_EXPORT_DEFAULT__()
				          : globalThis.__TVBOX_EXPORT_DEFAULT__;
				    }
				  }
				})();
				""".trimIndent(),
			)
		}
	}

	private fun buildInitArgumentLiteral(): String {
		val extLiteral = when (val ext = config.site.ext) {
			is JSONObject -> ext.toString()
			is JSONArray -> ext.toString()
			is String -> ext.trim().let { raw ->
				if (raw.startsWith("{") || raw.startsWith("[")) {
					runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.toString() ?: raw.toJsStringLiteral()
				} else {
					raw.toJsStringLiteral()
				}
			}
			null -> "null"
			else -> ext.toString().toJsStringLiteral()
		}
		return """
			(() => {
			  const ext = $extLiteral;
			  const spider = globalThis.__TVBOX_SPIDER__;
			  if (spider && spider.is_cat) {
			    return {
			      stype: 3,
			      skey: ${source.entity.id.toJsStringLiteral()},
			      ext: ext
			    };
			  }
			  return ext;
			})()
		""".trimIndent()
	}

	private fun registerHostBridge(qjs: QuickJs) {
		qjs.defineBinding("__native_tvbox", FunctionBinding<String?> { args ->
			try {
				val raw = args.getOrNull(0) as? String ?: return@FunctionBinding null
				val json = JSONObject(raw)
				val method = json.optString("method").trim()
				when (method) {
					"http" -> runBlocking { handleHttp(json) }.toString()
					"local_get" -> JSONObject().put(
						"value",
						prefs.getString(prefKey(json.optString("scope"), json.optString("key")), "").orEmpty(),
					).toString()
					"local_set" -> {
						prefs.edit()
							.putString(
								prefKey(json.optString("scope"), json.optString("key")),
								json.optString("value"),
							)
							.apply()
						JSONObject().put("ok", true).toString()
					}
					"local_delete" -> {
						prefs.edit()
							.remove(prefKey(json.optString("scope"), json.optString("key")))
							.apply()
						JSONObject().put("ok", true).toString()
					}
					"join_url" -> JSONObject().put(
						"value",
						runCatching {
							Uri.parse(json.optString("parent")).buildUpon()
							json.optString("parent").toHttpUrlOrNull()?.resolve(json.optString("child"))?.toString()
								?: json.optString("child")
						}.getOrDefault(json.optString("child")),
					).toString()
					"js2proxy" -> handleJs2Proxy(json).toString()
					else -> JSONObject().put("error", "Unsupported bridge method: $method").toString()
				}
			} catch (e: Exception) {
				logQuickJsFailure("bridgeInvoke", e)
				JSONObject().put("error", e.message ?: e.toString()).toString()
			}
		})
	}

	private suspend fun handleHttp(message: JSONObject): JSONObject {
		val url = message.optString("url").trim()
		val options = message.optJSONObject("options") ?: JSONObject()
		val method = options.optString("method").ifBlank {
			options.optString("http_method", "GET")
		}.uppercase()
		val headers = options.optHeaderMapFlexible("headers")
		val bufferMode = options.optInt("buffer", 0)
		val response = when (method) {
			"POST" -> {
				val bodyValue = when {
					options.has("body") -> options.opt("body")
					options.has("data") -> options.opt("data")
					else -> null
				}
				val postType = options.optString("postType").trim().ifBlank { "json" }
				httpClient.post(
					url = url,
					body = buildPostBody(bodyValue, postType),
					headers = headers,
					source = source,
				)
			}
			else -> httpClient.get(url, headers, source)
		}
		try {
			val rawBytes = response.body?.bytes() ?: ByteArray(0)
			val content = when (bufferMode) {
				1 -> JSONArray().apply {
					rawBytes.forEach { byte -> put(byte.toInt() and 0xFF) }
				}
				2 -> Base64.encodeToString(rawBytes, Base64.NO_WRAP)
				else -> decodeResponseBody(rawBytes, response.header("Content-Type"))
			}
			return JSONObject().apply {
				put("ok", response.isSuccessful)
				put("status", response.code)
				put("code", response.code)
				put("url", response.request.url.toString())
				put("content", content)
				put("body", content)
				put("headers", response.headers.toJsonObject())
			}
		} finally {
			response.close()
		}
	}

	private fun handleJs2Proxy(message: JSONObject): JSONObject {
		val targetUrl = message.optString("url").trim()
		if (targetUrl.isBlank()) {
			return JSONObject().put("url", "")
		}
		val siteType = message.optInt("siteType", config.site.type)
		val siteKey = message.optString("siteKey").ifBlank { config.site.key }
		val headers = message.optHeaderMapFlexible("headers")
		val identity = buildString {
			append(source.entity.id)
			append('|')
			append(siteType)
			append('|')
			append(siteKey)
			append('|')
			append(targetUrl)
			if (headers.isNotEmpty()) {
				headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
					append('|').append(key).append('=').append(value)
				}
			}
		}
		val proxyUrl = videoLocalCacheProxy.getDynamicProxyUrl(identity) {
			resolveProxyResponse(
				targetUrl = targetUrl,
				headers = headers,
				siteType = siteType,
				siteKey = siteKey,
			)
		}
		return JSONObject().put("url", proxyUrl)
	}

	private fun buildPostBody(bodyValue: Any?, postType: String): okhttp3.RequestBody {
		return when (postType.lowercase()) {
			"form" -> buildFormBody(bodyValue)
			"form-data", "multipart" -> buildMultipartBody(bodyValue)
			else -> {
				val mediaType = "application/json".toMediaTypeOrNull()
				when (bodyValue) {
					null -> ByteArray(0).toRequestBody(null)
					is JSONObject -> bodyValue.toString().toRequestBody(mediaType)
					is JSONArray -> bodyValue.toString().toRequestBody(mediaType)
					is String -> bodyValue.toRequestBody(mediaType)
					else -> bodyValue.toString().toRequestBody(mediaType)
				}
			}
		}
	}

	private fun buildFormBody(bodyValue: Any?): okhttp3.RequestBody {
		val builder = FormBody.Builder()
		when (bodyValue) {
			is JSONObject -> bodyValue.keys().forEach { key ->
				builder.add(key, bodyValue.opt(key)?.toString().orEmpty())
			}
			is String -> {
				if (bodyValue.contains('=')) {
					bodyValue.split('&').forEach { pair ->
						val key = pair.substringBefore('=')
						val value = pair.substringAfter('=', "")
						builder.add(key, value)
					}
				} else if (bodyValue.isNotBlank()) {
					builder.add("value", bodyValue)
				}
			}
			null -> Unit
			else -> builder.add("value", bodyValue.toString())
		}
		return builder.build()
	}

	private fun buildMultipartBody(bodyValue: Any?): okhttp3.RequestBody {
		val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
		when (bodyValue) {
			is JSONObject -> bodyValue.keys().forEach { key ->
				builder.addFormDataPart(key, bodyValue.opt(key)?.toString().orEmpty())
			}
			is String -> {
				if (bodyValue.isNotBlank()) {
					builder.addFormDataPart("value", bodyValue)
				}
			}
			null -> Unit
			else -> builder.addFormDataPart("value", bodyValue.toString())
		}
		return builder.build()
	}

	private fun decodeResponseBody(rawBytes: ByteArray, contentType: String?): String {
		val charsetName = contentType
			?.split(';')
			?.firstOrNull { it.contains("charset=", ignoreCase = true) }
			?.substringAfter('=')
			?.trim()
		val charset = runCatching { charsetName?.takeIf { it.isNotBlank() }?.let(Charset::forName) }.getOrNull()
			?: Charsets.UTF_8
		return runCatching { String(rawBytes, charset) }.getOrElse { String(rawBytes) }
	}

	private fun parseHomeResult(raw: String): TVBoxHomeResult {
		val root = raw.toJsonValue() as? JSONObject ?: return TVBoxHomeResult(emptyList())
		root.optString("error").takeIf { it.isNotBlank() }?.let {
			logQuickJsFailure("parseHome", null, it)
		}
		val categories = root.optJSONArray("class")
			?.toObjectList()
			?.mapNotNull { item ->
				val id = item.firstNonBlank("type_id", "id", "typeId") ?: return@mapNotNull null
				val name = item.firstNonBlank("type_name", "name", "title") ?: id
				TVBoxCategory(id = id, name = name)
			}
			.orEmpty()
		return TVBoxHomeResult(categories = categories)
	}

	private fun parseVodList(raw: String): List<TVBoxVodItem> {
		val jsonValue = raw.toJsonValue() ?: return emptyList()
		return when (jsonValue) {
			is JSONObject -> {
				jsonValue.optString("error").takeIf { it.isNotBlank() }?.let {
					logQuickJsFailure("parseList", null, it)
				}
				when {
					jsonValue.optJSONArray("list") != null -> {
						jsonValue.optJSONArray("list")!!.toObjectList().mapNotNull(::parseVodItem)
					}
					jsonValue.optJSONObject("data")?.optJSONArray("list") != null -> {
						jsonValue.optJSONObject("data")!!.optJSONArray("list")!!.toObjectList().mapNotNull(::parseVodItem)
					}
					jsonValue.has("vod_id") || jsonValue.has("vod_name") || jsonValue.has("name") -> {
						listOfNotNull(parseVodItem(jsonValue))
					}
					else -> emptyList()
				}
			}
			is JSONArray -> jsonValue.toObjectList().mapNotNull(::parseVodItem)
			else -> emptyList()
		}
	}

	private fun parseVodItem(node: JSONObject): TVBoxVodItem? {
		val itemId = node.firstNonBlank("vod_id", "id", "vodId", "url") ?: return null
		val title = node.firstNonBlank("vod_name", "title", "name") ?: itemId
		val cover = node.firstNonBlank(
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
		)
		val category = node.firstNonBlank("type_name", "vod_class", "class")
		val remarks = node.firstNonBlank("vod_remarks", "remarks", "note")
		val tags = buildSet {
			category?.let { add(ContentTag(it, "category:${it.lowercase()}", source)) }
			remarks?.let { add(ContentTag(it, "remark:${it.lowercase()}", source)) }
		}
		val description = buildString {
			node.firstNonBlank("vod_content", "content", "vod_blurb")?.let {
				append(it)
			}
			node.firstNonBlank("vod_year", "year")?.takeIf { it.isNotBlank() }?.let {
				if (isNotBlank()) append('\n')
				append("年份: ")
				append(it)
			}
			node.firstNonBlank("vod_area", "area")?.takeIf { it.isNotBlank() }?.let {
				if (isNotBlank()) append('\n')
				append("地区: ")
				append(it)
			}
			node.firstNonBlank("vod_actor", "actor")?.takeIf { it.isNotBlank() }?.let {
				if (isNotBlank()) append('\n')
				append("演员: ")
				append(it)
			}
		}.ifBlank { null }
		return TVBoxVodItem(
			id = positiveHash("${source.name}|$itemId|$title"),
			itemId = itemId,
			title = title,
			coverUrl = cover,
			description = description,
			tags = tags,
		)
	}

	private fun parseDetailResult(raw: String): TVBoxDetailResult? {
		val jsonValue = raw.toJsonValue() ?: return null
		val root = when (jsonValue) {
			is JSONObject -> jsonValue
			is JSONArray -> JSONObject().put("list", jsonValue)
			else -> return null
		}
		root.optString("error").takeIf { it.isNotBlank() }?.let {
			logQuickJsFailure("parseDetail", null, it)
		}
		val itemNode = when {
			root.optJSONArray("list")?.length() ?: 0 > 0 -> root.optJSONArray("list")?.optJSONObject(0)
			root.optJSONObject("data")?.optJSONArray("list")?.length() ?: 0 > 0 -> root.optJSONObject("data")?.optJSONArray("list")?.optJSONObject(0)
			root.has("vod_id") || root.has("vod_name") -> root
			else -> null
		} ?: return null
		val item = parseVodItem(itemNode) ?: return null
		val playSources = parsePlaySources(itemNode)
		val chapters = if (playSources.isNotEmpty()) {
			playSources.flatMapIndexed { groupIndex, sourceGroup ->
				sourceGroup.items.mapIndexed { index, playItem ->
					ContentChapter(
						id = positiveHash("${item.itemId}|${sourceGroup.flag}|${playItem.id}|$groupIndex|$index"),
						title = playItem.title,
						number = (index + 1).toFloat(),
						volume = 0,
						url = buildChapterUrl(sourceGroup.flag, playItem.id),
						scanlator = sourceGroup.flag,
						uploadDate = 0L,
						branch = sourceGroup.flag,
						source = source,
					)
				}
			}
		} else {
			listOf(
				ContentChapter(
					id = positiveHash("${item.itemId}|single"),
					title = item.title,
					number = 1f,
					volume = 0,
					url = buildChapterUrl(item.title, item.itemId),
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}
		return TVBoxDetailResult(
			item = item,
			chapters = chapters,
		)
	}

	private fun buildFallbackDetailResult(raw: String, seed: Content): TVBoxDetailResult? {
		val jsonValue = raw.toJsonValue() ?: return null
		val root = when (jsonValue) {
			is JSONObject -> jsonValue
			is JSONArray -> JSONObject().put("list", jsonValue)
			else -> return null
		}
		val message = root.firstNonBlank("msg", "message", "error")
		val hasPlaybackHints = root.has("parse") || root.has("jx") || root.has("url") || root.has("playUrl") || root.has("realUrl")
		if (message.isNullOrBlank() && !hasPlaybackHints) {
			return null
		}
		val itemId = (seed.url ?: seed.publicUrl).orEmpty().ifBlank { return null }
		val item = TVBoxVodItem(
			id = seed.id,
			itemId = itemId,
			title = seed.title,
			coverUrl = seed.coverUrl ?: seed.largeCoverUrl,
			description = mergeDescription(seed.description, message),
			tags = seed.tags,
		)
		val chapters = listOf(
			ContentChapter(
				id = positiveHash("${item.itemId}|fallback"),
				title = item.title,
				number = 1f,
				volume = 0,
				url = buildChapterUrl(item.title, item.itemId),
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			),
		)
		Log.i(TAG, "TVBox detail fallback applied for ${source.name}: itemId=$itemId msg=${message.orEmpty()}")
		return TVBoxDetailResult(item = item, chapters = chapters)
	}

	private fun parsePlaySources(node: JSONObject): List<TVBoxPlaySource> {
		val rawFlags = node.firstNonBlank("vod_play_from", "playFrom").orEmpty()
		val rawUrls = node.firstNonBlank("vod_play_url", "playUrl").orEmpty()
		if (rawUrls.isBlank()) {
			return emptyList()
		}
		val flags = rawFlags.split("$$$").map { it.trim() }
		val groups = rawUrls.split("$$$")
		return groups.mapIndexedNotNull { index, group ->
			val flag = flags.getOrNull(index).orEmpty().ifBlank { "播放源${index + 1}" }
			val items = group.split('#')
				.mapNotNull { entry ->
					val clean = entry.trim()
					if (clean.isBlank()) {
						return@mapNotNull null
					}
					val parts = clean.split('$', limit = 2)
					when (parts.size) {
						1 -> TVBoxPlayItem(
							title = "${flag} ${itemsSafeIndex(index, clean)}",
							id = parts[0].trim(),
						)
						else -> TVBoxPlayItem(
							title = parts[0].trim().ifBlank { "${flag} ${itemsSafeIndex(index, clean)}" },
							id = parts[1].trim(),
						)
					}
				}
			if (items.isEmpty()) {
				null
			} else {
				TVBoxPlaySource(flag = flag, items = items)
			}
		}
	}

	private fun itemsSafeIndex(groupIndex: Int, raw: String): String {
		return positiveHash("$groupIndex|$raw").toString()
	}

	private fun parsePlayResult(raw: String): TVBoxPlayResult? {
		val root = raw.toJsonValue() as? JSONObject ?: return null
		root.optString("error").takeIf { it.isNotBlank() }?.let {
			logQuickJsFailure("parsePlay", null, it)
		}
		val url = root.firstNonBlank("url", "playUrl", "realUrl") ?: return null
		return TVBoxPlayResult(
			url = url,
			headers = root.optHeaderMap("header").ifEmpty { root.optHeaderMap("headers") },
		)
	}

	private fun resolveProxyResponse(
		targetUrl: String,
		headers: Map<String, String>,
		siteType: Int,
		siteKey: String,
	): VideoLocalCacheProxy.DynamicResponse {
		val result = runBlocking {
			executeProxy(
				targetUrl = targetUrl,
				headers = headers,
				siteType = siteType,
				siteKey = siteKey,
			)
		}
		if (result == null) {
			return VideoLocalCacheProxy.DynamicResponse(
				statusCode = 500,
				contentType = "text/plain; charset=utf-8",
				body = "TVBox proxy returned empty result".toByteArray(Charsets.UTF_8),
			)
		}
		val redirectUrl = result.redirectUrl?.let { redirect ->
			if (redirect.startsWith("http://127.0.0.1:", ignoreCase = true) ||
				redirect.startsWith("http://localhost:", ignoreCase = true)
			) {
				redirect
			} else {
				videoLocalCacheProxy.getProxyUrl(redirect, buildHeadersForUrl(redirect, result.headers))
			}
		}
		return VideoLocalCacheProxy.DynamicResponse(
			statusCode = result.statusCode,
			contentType = result.contentType,
			headers = result.headers,
			body = result.body,
			redirectUrl = redirectUrl,
		)
	}

	private suspend fun executeProxy(
		targetUrl: String,
		headers: Map<String, String>,
		siteType: Int,
		siteKey: String,
	): TVBoxProxyResult? {
		val scriptLocator = resolveScriptLocator() ?: return null
		val executableScript = buildExecutableScript(scriptLocator) ?: return null
		val initLiteral = buildInitArgumentLiteral()
		val pathSegmentsLiteral = JSONArray(targetUrl.split('/')).toString()
		val headersLiteral = JSONObject(headers).toString()
		val paramsLiteral = JSONObject().apply {
			put("url", targetUrl)
			put("header", JSONObject(headers).toString())
			put("from", "catvod")
			put("siteType", siteType)
			put("siteKey", siteKey)
		}.toString()
		val raw = createQuickJs().use { qjs ->
			registerHostBridge(qjs)
			qjs.evaluate<Any?>(buildBootstrapScript(), "<tvbox-bootstrap>")
			qjs.evaluate<Any?>(executableScript, scriptLocator)
			qjs.evaluate<String?>(
				"""
				await (async function() {
				  try {
				    const spider = globalThis.__TVBOX_SPIDER__;
				    if (!spider || typeof spider.init !== 'function') return null;
				    const initArg = $initLiteral;
				    const res = spider.init(initArg);
				    if (res && typeof res.then === 'function') await res;
				    return null;
				  } catch (e) {
				    return (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
				  }
				})();
				""".trimIndent(),
				"<tvbox-proxy-init>",
			)?.takeIf { it.isNotBlank() }?.let { initError ->
				logQuickJsFailure("proxyInit", null, initError)
			}
			qjs.evaluate<String?>(
				"""
				await (async function() {
				  try {
				    const spider = globalThis.__TVBOX_SPIDER__;
				    if (!spider) {
				      return JSON.stringify({ error: "TVBox spider instance is missing" });
				    }
				    const fn = spider["proxy"];
				    if (typeof fn !== "function") {
				      return JSON.stringify({ error: "TVBox proxy() is missing" });
				    }
				    const pathSegments = $pathSegmentsLiteral;
				    const headerObject = $headersLiteral;
				    const params = $paramsLiteral;
				    let res = null;
				    let firstError = null;
				    try {
				      res = fn(pathSegments, headerObject);
				      if (res && typeof res.then === "function") {
				        res = await res;
				      }
				    } catch (e) {
				      firstError = e;
				    }
				    if (res == null || res === "" || (typeof res === "object" && !Array.isArray(res) && Object.keys(res).length === 0)) {
				      try {
				        const fallback = fn(params);
				        res = fallback && typeof fallback.then === "function" ? await fallback : fallback;
				      } catch (e) {
				        if (!firstError) {
				          firstError = e;
				        }
				      }
				    }
				    if (res == null) {
				      const err = firstError && (firstError.stack || firstError.message || (firstError.toString && firstError.toString()));
				      return JSON.stringify({ error: err || "TVBox proxy() returned null" });
				    }
				    if (typeof res === "string") {
				      return res;
				    }
				    return JSON.stringify(res);
				  } catch (e) {
				    const err = (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
				    return JSON.stringify({ error: err });
				  }
				})();
				""".trimIndent(),
				"<tvbox-proxy>",
			)
		}.orEmpty()
		return parseProxyResult(raw)
	}

	private fun parseProxyResult(raw: String): TVBoxProxyResult? {
		val trimmed = raw.trim()
		if (trimmed.isBlank()) {
			return null
		}
		val jsonValue = trimmed.toJsonValue()
		if (jsonValue !is JSONObject) {
			return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
				TVBoxProxyResult(
					statusCode = 302,
					contentType = "application/octet-stream",
					headers = emptyMap(),
					body = ByteArray(0),
					redirectUrl = trimmed,
				)
			} else {
				TVBoxProxyResult(
					statusCode = 200,
					contentType = "text/plain; charset=utf-8",
					headers = emptyMap(),
					body = trimmed.toByteArray(Charsets.UTF_8),
					redirectUrl = null,
				)
			}
		}
		jsonValue.optString("error").takeIf { it.isNotBlank() }?.let {
			logQuickJsFailure("proxy", null, it)
			return TVBoxProxyResult(
				statusCode = 500,
				contentType = "text/plain; charset=utf-8",
				headers = emptyMap(),
				body = it.toByteArray(Charsets.UTF_8),
				redirectUrl = null,
			)
		}
		val resultHeaders = jsonValue.optHeaderMapFlexible("headers")
			.ifEmpty { jsonValue.optHeaderMapFlexible("header") }
		val redirectUrl = jsonValue.firstNonBlank("url", "redirect", "location")
			?.takeIf { !jsonValue.has("content") && !jsonValue.has("body") }
		val contentType = resultHeaders.entries.firstNotNullOfOrNull { (key, value) ->
			value.takeIf { key.equals("Content-Type", ignoreCase = true) }
		} ?: if (redirectUrl != null) {
			"application/octet-stream"
		} else {
			"text/plain; charset=utf-8"
		}
		val body = when {
			redirectUrl != null -> ByteArray(0)
			jsonValue.has("content") -> decodeProxyBody(jsonValue.opt("content"), jsonValue.optInt("buffer", 0))
			jsonValue.has("body") -> decodeProxyBody(jsonValue.opt("body"), jsonValue.optInt("buffer", 0))
			else -> ByteArray(0)
		}
		return TVBoxProxyResult(
			statusCode = jsonValue.optInt("code", 200),
			contentType = contentType,
			headers = resultHeaders,
			body = body,
			redirectUrl = redirectUrl,
		)
	}

	private fun decodeProxyBody(value: Any?, bufferMode: Int): ByteArray {
		return when {
			value == null || value == JSONObject.NULL -> ByteArray(0)
			bufferMode == 2 && value is String -> Base64.decode(value, Base64.DEFAULT)
			value is JSONArray -> ByteArray(value.length()) { index -> value.optInt(index).toByte() }
			value is String -> value.toByteArray(Charsets.UTF_8)
			else -> value.toString().toByteArray(Charsets.UTF_8)
		}
	}

	private suspend fun readTextResource(url: String, headers: Map<String, String>): String {
		val uri = Uri.parse(url)
		return when (uri.scheme?.lowercase()) {
			"http", "https" -> {
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
		val host = url?.toHttpUrlOrNull()?.host?.lowercase()
		if (!host.isNullOrBlank()) {
			config.root.headerRules
				.filter { host == it.host.lowercase() }
				.forEach { rule -> headers += rule.headers }
		}
		if (!headers.keys.any { it.equals(CommonHeaders.REFERER, ignoreCase = true) }) {
			url?.toHttpUrlOrNull()?.let { httpUrl ->
				headers[CommonHeaders.REFERER] = "${httpUrl.scheme}://${httpUrl.host}/"
			}
		}
		return headers
	}

	private fun resolveScriptLocator(): String? {
		val api = config.site.api.trim()
			.takeIf { it.isNotBlank() && !it.startsWith("csp_", ignoreCase = true) }
		if (api != null) {
			return api
		}
		val ext = config.site.ext
		return when (ext) {
			is String -> ext.trim().ifBlank { null }
			is JSONObject -> listOf("url", "api", "file", "script")
				.firstNotNullOfOrNull { key -> ext.optString(key).trim().ifBlank { null } }
			else -> null
		}
	}

	private fun resolveCandidateUrl(rawValue: String?): String? {
		val value = rawValue?.trim().orEmpty().extractPrimaryUrl()
		if (value.isBlank()) {
			return null
		}
		if (value.startsWith("http://", ignoreCase = true) ||
			value.startsWith("https://", ignoreCase = true) ||
			value.startsWith("content://", ignoreCase = true) ||
			value.startsWith("file://", ignoreCase = true)
		) {
			return value
		}
		if (value.startsWith("//")) {
			return "https:$value"
		}
		val baseHttpUrl = config.meta.sourceLocator?.toHttpUrlOrNull()
		return baseHttpUrl?.resolve(value)?.toString() ?: value
	}

	private fun parseCategoryTagId(key: String): String? {
		return key.takeIf { it.startsWith(TAG_CATEGORY_PREFIX) }?.removePrefix(TAG_CATEGORY_PREFIX)?.ifBlank { null }
	}

	private fun buildChapterUrl(flag: String, id: String): String {
		return Uri.parse(CHAPTER_SCHEME).buildUpon()
			.appendQueryParameter("flag", flag)
			.appendQueryParameter("id", id)
			.build()
			.toString()
	}

	private fun parseChapterLocator(url: String): TVBoxChapterLocator? {
		val uri = Uri.parse(url)
		if (uri.scheme != "tvbox-js") {
			return null
		}
		val flag = uri.getQueryParameter("flag").orEmpty().ifBlank { return null }
		val id = uri.getQueryParameter("id").orEmpty().ifBlank { return null }
		return TVBoxChapterLocator(flag = flag, id = id)
	}

	private fun createQuickJs(): QuickJs {
		return QuickJs.create(jobDispatcher = Dispatchers.Default).apply {
			maxStackSize = 1L shl 20
			memoryLimit = 64L shl 20
		}
	}

	private fun buildBootstrapScript(): String {
		return """
			(() => {
			  if (typeof globalThis.console === "undefined") {
			    globalThis.console = {
			      log() {},
			      warn() {},
			      error() {}
			    };
			  }
			  if (typeof atob !== "function") {
			    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
			    globalThis.atob = function(input) {
			      let str = String(input).replace(/=+$/, "");
			      if (str.length % 4 === 1) throw new Error("Invalid base64");
			      let output = "";
			      for (let bc = 0, bs = 0, buffer, i = 0; (buffer = str.charAt(i++)); ) {
			        buffer = chars.indexOf(buffer);
			        if (~buffer) {
			          bs = bc % 4 ? bs * 64 + buffer : buffer;
			          if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
			        }
			      }
			      return output;
			    };
			  }
			  if (typeof btoa !== "function") {
			    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
			    globalThis.btoa = function(input) {
			      let str = String(input);
			      let output = "";
			      for (let block = 0, charCode, i = 0, map = chars; str.charAt(i | 0) || (map = "=", i % 1); output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))) {
			        charCode = str.charCodeAt(i += 3 / 4);
			        if (charCode > 0xFF) throw new Error("Invalid character");
			        block = (block << 8) | charCode;
			      }
			      return output;
			    };
			  }
			  const __bridge = (message) => {
			    const raw = __native_tvbox(JSON.stringify(message));
			    return raw ? JSON.parse(raw) : null;
			  };
			  globalThis._http = function(url, options = {}) {
			    return __bridge({ method: "http", url: String(url || ""), options: options || {} }) || { status: 500, content: "", headers: {} };
			  };
			  globalThis.http = function(url, options = {}) {
			    if (options && options.async === false) {
			      return globalThis._http(url, options);
			    }
			    return Promise.resolve(globalThis._http(url, options));
			  };
			  globalThis.req = function(url, options) {
			    return globalThis.http(url, Object.assign({ async: false }, options || {}));
			  };
			  globalThis.request = function(url, options) {
			    const response = globalThis.req(url, options);
			    if (response && typeof response === "object" && "content" in response) {
			      return response.content == null ? "" : String(response.content);
			    }
			    return response == null ? "" : String(response);
			  };
			  globalThis.fetch = async function(url, options = {}) {
			    const response = await globalThis.http(url, options || {});
			    const headers = response && typeof response.headers === "object" ? response.headers : {};
			    const content = response && Object.prototype.hasOwnProperty.call(response, "content") ? response.content : "";
			    return {
			      ok: !!(response && response.ok),
			      status: response && typeof response.status === "number" ? response.status : 500,
			      headers: headers,
			      url: response && response.url ? String(response.url) : String(url || ""),
			      text: async () => content == null ? "" : String(content),
			      json: async () => {
			        const text = content == null ? "" : String(content);
			        return text ? JSON.parse(text) : {};
			      },
			      arrayBuffer: async () => {
			        if (typeof content === "string") {
			          return Uint8Array.from(atob(content), c => c.charCodeAt(0)).buffer;
			        }
			        if (Array.isArray(content)) {
			          return Uint8Array.from(content).buffer;
			        }
			        return new ArrayBuffer(0);
			      }
			    };
			  };
			  globalThis.local = {
			    get(scope, key) {
			      const response = __bridge({ method: "local_get", scope: String(scope || ""), key: String(key || "") });
			      return response && response.value != null ? String(response.value) : "";
			    },
			    set(scope, key, value) {
			      __bridge({ method: "local_set", scope: String(scope || ""), key: String(key || ""), value: value == null ? "" : String(value) });
			    },
			    delete(scope, key) {
			      __bridge({ method: "local_delete", scope: String(scope || ""), key: String(key || "") });
			    }
			  };
			  globalThis.joinUrl = function(parent, child) {
			    const response = __bridge({ method: "join_url", parent: String(parent || ""), child: String(child || "") });
			    return response && response.value != null ? String(response.value) : String(child || "");
			  };
			  globalThis.js2Proxy = function(dynamic, siteType, siteKey, url, headers) {
			    const response = __bridge({
			      method: "js2proxy",
			      dynamic: !!dynamic,
			      siteType: typeof siteType === "number" ? siteType : Number(siteType || 0),
			      siteKey: siteKey == null ? "" : String(siteKey),
			      url: url == null ? "" : String(url),
			      headers: headers && typeof headers === "object" ? headers : {}
			    });
			    return response && response.url ? String(response.url) : "";
			  };
			  globalThis.print = function(...args) {
			    if (globalThis.console && typeof globalThis.console.log === "function") {
			      globalThis.console.log(...args);
			    }
			  };
			  globalThis.setTimeout = function(fn) {
			    if (typeof fn === "function") {
			      fn();
			    }
			    return 0;
			  };
			  globalThis.clearTimeout = function() {};
			})();
		""".trimIndent()
	}

	private fun prefKey(scope: String, key: String): String {
		return "${source.entity.id}:${scope.trim()}:${key.trim()}"
	}

	private fun logQuickJsFailure(action: String, error: Throwable?, detail: String? = null) {
		TVBoxRuntimeDiagnostics.logFailure(
			tag = TAG,
			sourceName = source.name,
			runtimeId = id,
			action = action,
			category = TVBoxRuntimeDiagnostics.classifyQuickJs(error, detail),
			error = error,
			detail = detail,
		)
	}

	private data class TVBoxHomeResult(
		val categories: List<TVBoxCategory>,
	)

	private data class TVBoxCategory(
		val id: String,
		val name: String,
	)

	private data class TVBoxVodItem(
		val id: Long,
		val itemId: String,
		val title: String,
		val coverUrl: String?,
		val description: String?,
		val tags: Set<ContentTag>,
	) {
		fun toContent(source: JsonContentSource): Content = Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = itemId,
			publicUrl = itemId,
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

	private data class TVBoxDetailResult(
		val item: TVBoxVodItem,
		val chapters: List<ContentChapter>,
	) {
		fun toContent(source: JsonContentSource): Content = item.toContent(source).copy(
			chapters = chapters,
		)
	}

	private data class TVBoxPlaySource(
		val flag: String,
		val items: List<TVBoxPlayItem>,
	)

	private data class TVBoxPlayItem(
		val title: String,
		val id: String,
	)

	private data class TVBoxPlayResult(
		val url: String,
		val headers: Map<String, String>,
	)

	private data class TVBoxProxyResult(
		val statusCode: Int,
		val contentType: String,
		val headers: Map<String, String>,
		val body: ByteArray,
		val redirectUrl: String?,
	)

	private data class TVBoxChapterLocator(
		val flag: String,
		val id: String,
	)
}

private fun String.toJsonValue(): Any? {
	val trimmed = trim()
	if (trimmed.isBlank()) {
		return null
	}
	return runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
}

private fun JSONArray.toObjectList(): List<JSONObject> {
	return buildList {
		for (index in 0 until length()) {
			optJSONObject(index)?.let(::add)
		}
	}
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

private fun mergeDescription(base: String?, extra: String?): String? {
	val parts = listOfNotNull(
		base?.trim()?.takeIf { it.isNotBlank() },
		extra?.trim()?.takeIf { it.isNotBlank() },
	).distinct()
	return parts.joinToString("\n").ifBlank { null }
}

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

private fun JSONObject.optHeaderMapFlexible(key: String): Map<String, String> {
	val value = opt(key) ?: return emptyMap()
	return when (value) {
		is JSONObject -> value.toHeaderMap()
		is String -> {
			val parsed = runCatching { JSONTokener(value).nextValue() as? JSONObject }.getOrNull()
			parsed?.toHeaderMap().orEmpty()
		}
		else -> emptyMap()
	}
}

private fun JSONObject.toHeaderMap(): Map<String, String> {
	return buildMap {
		val iterator = keys()
		while (iterator.hasNext()) {
			val headerKey = iterator.next()
			val headerValue = opt(headerKey)?.toString()?.trim().orEmpty()
			if (headerValue.isNotBlank()) {
				put(headerKey, headerValue)
			}
		}
	}
}

private fun okhttp3.Headers.toJsonObject(): JSONObject {
	val result = JSONObject()
	names().forEach { name ->
		val values = values(name)
		if (values.size == 1) {
			result.put(name, values.first())
		} else {
			val array = JSONArray()
			values.forEach(array::put)
			result.put(name, array)
		}
	}
	return result
}

private fun String.toJsStringLiteral(): String {
	val sb = StringBuilder(length + 2)
	sb.append('"')
	for (c in this) {
		when (c) {
			'\\' -> sb.append("\\\\")
			'"' -> sb.append("\\\"")
			'\n' -> sb.append("\\n")
			'\r' -> sb.append("\\r")
			'\t' -> sb.append("\\t")
			else -> sb.append(c)
		}
	}
	sb.append('"')
	return sb.toString()
}

private fun String.extractPrimaryUrl(): String {
	val trimmed = trim()
	if (trimmed.isBlank()) {
		return trimmed
	}
	val separatorIndex = trimmed.indexOf(';')
	return if (separatorIndex >= 0) {
		trimmed.substring(0, separatorIndex).trim()
	} else {
		trimmed
	}
}

private fun positiveHash(raw: String): Long {
	return raw.hashCode().toLong() and 0x7fffffffL
}
