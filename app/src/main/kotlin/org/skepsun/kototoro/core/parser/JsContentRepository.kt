package org.skepsun.kototoro.core.parser

import android.util.Log
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.parser.ContentLoaderContextImpl
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import java.io.IOException
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.EnumSet
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.skepsun.kototoro.core.network.CommonHeaders
import android.content.SharedPreferences

/**
 * Placeholder repository for JS sources until full runtime integration is implemented.
 *
 * Returns empty data instead of throwing UnsupportedSourceException to avoid UI crashes.
 */
class JsContentRepository(
    override val source: ContentSource,
    private val mangaLoaderContext: ContentLoaderContextImpl,
) : ContentRepository {

    private var cachedExploreTitle: String? = null

    init {
        Log.d("JsContentRepository", "Created JsContentRepository for ${source.name}")
        if (cachedExploreTitle.isNullOrBlank()) {
            val display = (source as? JsonContentSource)?.displayName
            if (!display.isNullOrBlank()) {
                cachedExploreTitle = display
            }
        }
    }

    override val sortOrders: Set<SortOrder> = EnumSet.allOf(SortOrder::class.java)

    override var defaultSortOrder: SortOrder = SortOrder.NEWEST

    override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(
        isMultipleTagsSupported = true,
    )

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
        val signature = buildExploreSignature(order, filter)
        if (offset == 0) {
            resetExploreTokens(signature)
            explorePageIndexCache[signature] = 0
        }
        val lastPage = explorePageIndexCache[signature] ?: 0
        val pageNumber = if (offset == 0) 1 else (lastPage + 1)
        val first = runCatching { executeExploreList(pageNumber, order, filter, signature, skipInit = false) }
        if (first.isSuccess) return first.getOrDefault(emptyList())
        val err = first.exceptionOrNull()
        if (err is com.dokar.quickjs.QuickJsException && err.message?.contains("stack overflow", true) == true) {
            Log.w("JsContentRepository", "JS list retry without init for ${source.name} due to stack overflow")
            return runCatching { executeExploreList(pageNumber, order, filter, signature, skipInit = true) }
                .onFailure { Log.w("JsContentRepository", "JS list failed retry for ${source.name}", it) }
                .getOrElse { emptyList() }
        }
        Log.w("JsContentRepository", "JS list failed for ${source.name}", err)
        return emptyList()
    }

    override suspend fun getDetails(manga: Content): Content {
        val idStr = manga.url ?: manga.publicUrl ?: manga.id.toString()
        val details = runCatching { executeDetail(idStr) }
            .onFailure { Log.w("JsContentRepository", "JS detail failed for ${source.name}", it) }
            .getOrNull()
        if (details == null) return manga
        return manga.copy(
            title = details.title.ifBlank { manga.title },
            coverUrl = details.coverUrl ?: manga.coverUrl,
            largeCoverUrl = details.coverUrl ?: manga.largeCoverUrl,
            description = details.description ?: manga.description,
            tags = if (details.tags.isNotEmpty()) details.tags else manga.tags,
            authors = details.authors.ifEmpty { manga.authors },
            publicUrl = details.url ?: manga.publicUrl,
            chapters = details.chapters.ifEmpty { manga.chapters.orEmpty() },
        )
    }

    override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
        if (chapter.url.startsWith("file://") || chapter.url.startsWith("zip://") || chapter.url.startsWith("content://")) {
            return org.skepsun.kototoro.local.data.input.LocalContentParser(android.net.Uri.parse(chapter.url)).getPages(chapter)
        }
        val parsed = parseChapterUrl(chapter.url)
        val mangaId = parsed?.mangaId ?: chapter.url.ifBlank { chapter.source.name }
        val epId = parsed?.chapterId ?: chapter.url
        val cacheKey = PageCacheKey(chapter.source.name, chapter.url)
        val res = pageCache[cacheKey] ?: try {
            executePages(mangaId, epId).also { pageCache[cacheKey] = it }
        } catch (e: Exception) {
            Log.w("JsContentRepository", "JS pages failed for ${source.name}", e)
            emptyList()
        }
        if (res.isEmpty()) return emptyList()
        return res.mapIndexed { idx, entry ->
            ContentPage(
                id = (chapter.id shl 32) + idx,
                url = entry.url,
                preview = entry.url,
                headers = entry.headers ?: emptyMap(),
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        return page.url
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        if (lastFilterOptions == null) {
            runCatching { ensureFilterOptions() }
        }
        return lastFilterOptions ?: ContentListFilterOptions()
    }

    override suspend fun getRelated(seed: Content): List<Content> = emptyList()

    // 复用 ContentHttpClient（带通用 UA/Referer/Cloudflare/RateLimit 拦截器）
    private val okHttp: OkHttpClient = mangaLoaderContext.httpClient
    private val runtimeBootstrap: String by lazy { buildBootstrapScript() }
    private var lastFilterOptions: ContentListFilterOptions? = null
    private val settingsPrefs: SharedPreferences =
        mangaLoaderContext.getSourcePreferences(source.name)
    private var cachedJsKey: String? = null
    private var cachedSettingsSchema: List<JsSettingItem>? = null

    private suspend fun executeExploreList(
        page: Int,
        order: SortOrder?,
        filter: ContentListFilter?,
        signature: String,
        skipInit: Boolean,
    ): List<Content> {
        Log.d("JsContentRepository", "executeExploreList start for ${source.name} page=$page skipInit=$skipInit")
        val jsSource = (source as? JsonContentSource)?.entity
            ?: return emptyList()
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)

        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            cachedJsKey = runCatching {
                qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: cachedJsKey
            initializeSettingsDefaults(qjs)
            // 预先解析 explore 元信息（标题/标签/排序）
            runCatching {
                val metaJson = qjs.evaluate<String?>(
                    """
					(() => {
					  if (typeof __source === 'undefined' || !__source || !__source.explore || !__source.explore[0]) return "{}";
					  const part = __source.explore[0];
					  const res = {};
					  res.title = part.title || (__source && (__source.name || __source.key)) || "";
					  if (Array.isArray(part.tags)) res.tags = part.tags;
					  else if (part.tags && typeof part.tags === 'object') res.tags = Object.values(part.tags);
					  if (Array.isArray(part.sort)) res.sorts = part.sort;
					  else if (Array.isArray(part.sorts)) res.sorts = part.sorts;
					  else if (Array.isArray(part.orders)) res.sorts = part.orders;
					  return JSON.stringify(res);
					})()
                    """.trimIndent()
                )
                metaJson?.let { parseExploreMetaString(it)?.let { meta -> applyFilterOptions(meta); meta.title?.let { cachedExploreTitle = it } } }
            }
            val categoryMeta = try {
                readCategoryMeta(qjs)
            } catch (e: Exception) {
                null
            }
            val optionGroups = try {
                readOptionGroups(qjs)
            } catch (e: Exception) {
                emptyList()
            }
            if (categoryMeta != null || optionGroups.isNotEmpty()) {
                applyFilterOptions(categoryMeta, optionGroups)
            }
            val exploreType = runCatching {
                qjs.evaluate<String?>(
                    """
					(() => {
					  if (typeof __source === 'undefined' || !__source || !__source.explore || !__source.explore[0]) return "";
					  return String(__source.explore[0].type || "");
					})()
                    """.trimIndent()
                )
            }.getOrNull()
            val hasCategoryComics = runCatching {
                qjs.evaluate<Boolean?>(
                    """
					(() => {
					  return !!(__source && __source.categoryComics && typeof __source.categoryComics.load === 'function');
					})()
                    """.trimIndent()
                )
            }.getOrNull() == true
            val hasExploreLoad = runCatching {
                qjs.evaluate<Boolean?>(
                    """
					(() => {
					  if (!__source || !__source.explore || !__source.explore[0]) return false;
					  return typeof __source.explore[0].load === 'function';
					})()
                    """.trimIndent()
                )
            }.getOrNull() == true
            val hasExploreLoadNext = runCatching {
                qjs.evaluate<Boolean?>(
                    """
					(() => {
					  if (!__source || !__source.explore || !__source.explore[0]) return false;
					  return typeof __source.explore[0].loadNext === 'function';
					})()
                    """.trimIndent()
                )
            }.getOrNull() == true
            if (!skipInit) {
                val initErr = runCatching {
                    runSourceInit(qjs)
                }.onFailure {
                    Log.w("JsContentRepository", "init() evaluation skipped for ${source.name}", it)
                }.getOrNull()
                if (!initErr.isNullOrBlank()) {
                    Log.w("JsContentRepository", "init() error for ${source.name}: $initErr")
                }
                }
            val hasCategoryFilters = filter?.tags?.any { tag ->
                tag.key.startsWith(TAG_CATEGORY_PREFIX) || tag.key.startsWith(TAG_OPTION_PREFIX)
            } == true
            // multiPageComicList 的 explore 页通常是首页；当用户选择分类/选项后，才切换到 categoryComics.load
            val shouldUseCategoryList = hasCategoryComics && (exploreType != "multiPageComicList" || hasCategoryFilters)
            if (Log.isLoggable("JsContentRepository", Log.DEBUG)) {
                val selectedCategoryTag = filter?.tags.orEmpty().lastOrNull { it.key.startsWith(TAG_CATEGORY_PREFIX) }?.key
                val selectedOptionTags = filter?.tags.orEmpty()
                    .filter { it.key.startsWith(TAG_OPTION_PREFIX) }
                    .map { it.key }
                    .sorted()
                Log.d(
                    "JsContentRepository",
                    "explore routing source=${source.name} page=$page exploreType=$exploreType hasCategoryComics=$hasCategoryComics " +
                        "hasCategoryFilters=$hasCategoryFilters shouldUseCategoryList=$shouldUseCategoryList " +
                        "selectedCategoryTag=$selectedCategoryTag selectedOptionTags=$selectedOptionTags"
                )
            }
            val jsonStr = if (shouldUseCategoryList) {
                val selection = resolveCategorySelection(filter, categoryMeta)
                val options = resolveOptionSelection(filter, optionGroups, selection?.label)
                if (Log.isLoggable("JsContentRepository", Log.DEBUG)) {
                    Log.d(
                        "JsContentRepository",
                        "category params source=${source.name} page=$page categoryLabel=${selection?.label} categoryParam=${selection?.param} options=$options"
                    )
                }
                qjs.evaluate<String?>(
                    """
					await (async function() {
					  const __page = ${page};
					  if (!__source || !__source.categoryComics || typeof __source.categoryComics.load !== 'function') {
					    return JSON.stringify({ error: "no categoryComics" });
					  }
					  try {
					    const v = __source.categoryComics.load(
					      ${selection?.label?.jsonStringLiteral() ?: "null"},
					      ${selection?.param?.jsonStringLiteral() ?: "null"},
					      ${options.toJsonArrayLiteral()},
					      __page
					    );
					    const r = (v && typeof v.then === 'function') ? await v : v;
					    return JSON.stringify(r == null ? {} : r);
					  } catch (e) {
					    const err = (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
					    return JSON.stringify({ error: err });
					  }
					})();
                    """.trimIndent(),
                    "<category_load>"
                ) ?: "{}"
            } else if (exploreType == "multiPageComicList" && hasExploreLoadNext && !hasExploreLoad) {
                val tokenMap = exploreTokenCache.getOrPut(signature) { mutableMapOf(1 to null) }
                val token = tokenMap[page]
                if (token == END_TOKEN) return emptyList()
                if (page > 1 && !tokenMap.containsKey(page)) return emptyList()
                val tokenLiteral = token?.jsonStringLiteral() ?: "null"
                qjs.evaluate<String?>(
                    """
					await (async function() {
					  if (!__source || !__source.explore || !__source.explore[0]) {
					    return JSON.stringify({ error: "no explore" });
					  }
					  const part = __source.explore[0];
					  try {
					    const v = part.loadNext ? part.loadNext(${tokenLiteral}) : null;
					    const r = (v && typeof v.then === 'function') ? await v : v;
					    return JSON.stringify(r == null ? {} : r);
					  } catch (e) {
					    const err = (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
					    return JSON.stringify({ error: err });
					  }
					})();
                    """.trimIndent(),
                    "<load_next>"
                ) ?: "{}"
            } else {
                qjs.evaluate<String?>(
                    """
					await (async function() {
					  const __page = ${page};
					  if (typeof __source === 'undefined' || !__source || !__source.explore || __source.explore.length === 0) {
					    return JSON.stringify({ error: "no explore" });
					  }
					  const part = __source.explore[0];
					  try {
					    const v = part.load ? part.load(__page) : null;
					    const r = (v && typeof v.then === 'function') ? await v : v;
					    return JSON.stringify(r == null ? {} : r);
					  } catch (e) {
					    const err = (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
					    return JSON.stringify({ error: err });
					  }
					})();
                    """.trimIndent(),
                    "<load>"
                ) ?: "{}"
            }
            val result = mapResultToContentResult(jsonStr)
            if (result.items.isNotEmpty()) {
                updatePageSize(signature, result.items.size)
                explorePageIndexCache[signature] = page
            }
            if (exploreType == "multiPageComicList" && hasExploreLoadNext && !hasExploreLoad) {
                val tokenMap = exploreTokenCache.getOrPut(signature) { mutableMapOf(1 to null) }
                val nextToken = result.nextToken
                tokenMap[page + 1] = nextToken ?: END_TOKEN
            }
            result.maxPage?.let { maxPage ->
                if (page >= maxPage) {
                    val tokenMap = exploreTokenCache.getOrPut(signature) { mutableMapOf(1 to null) }
                    tokenMap[page + 1] = END_TOKEN
                }
            }
            Log.d("JsContentRepository", "executeExploreList mapped ${result.items.size} items for ${source.name}")
            result.items
        }
    }

    private fun mapResultToContentResult(raw: String): ContentListResult {
        val list = mutableListOf<Content>()
        Log.d("JsContentRepository", "mapResultToContent raw snippet=${raw.take(4000)}")
        val resultObj = runCatching { jsonConverter.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: run {
                Log.w("JsContentRepository", "mapResultToContent cannot parse json")
                return ContentListResult(emptyList(), null, null)
            }
        resultObj["error"]?.jsonPrimitive?.contentOrNull?.let {
            Log.w("JsContentRepository", "mapResultToContent error=$it")
            return ContentListResult(emptyList(), null, null)
        }
        val nextToken = extractToken(resultObj["next"])
        val maxPage = resultObj["maxPage"]?.jsonPrimitive?.intOrNull
            ?: resultObj["max_page"]?.jsonPrimitive?.intOrNull
        // explore meta: title/tags/sort
        val meta = parseExploreMeta(resultObj)
        if (meta != null) {
            applyFilterOptions(meta)
        }
        // 保存 explore 标题用于 UI 显示
        meta?.title?.let { cachedExploreTitle = it }
        val comicsElement = resultObj["comics"]
            ?: resultObj["data"]
            ?: run {
                // 兼容 copy_manga 等返回 {推荐:[...], 完结:[...]} 的结构：合并所有数组对象
                val merged = resultObj.values.filterIsInstance<JsonArray>().flatMap { arr ->
                    arr.mapNotNull { it as? JsonObject }
                }
                if (merged.isNotEmpty()) JsonArray(merged) else return ContentListResult(emptyList(), nextToken, maxPage)
            }
        val comicsContainer = if (comicsElement is JsonObject && comicsElement.containsKey("comics")) {
            comicsElement["comics"]
        } else comicsElement
        val comics = when (comicsElement) {
            is JsonArray -> comicsElement.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(comicsElement)
            else -> emptyList()
        }.ifEmpty {
            when (comicsContainer) {
                is JsonArray -> comicsContainer.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(comicsContainer)
                else -> emptyList()
            }
        }
        Log.d("JsContentRepository", "mapResultToContent comicsElement type=${comicsContainer?.let { it::class.simpleName }} count=${comics.size}")
        comics.forEachIndexed { idx, obj ->
            val idStr = obj["id"]?.jsonPrimitive?.content ?: "${obj.hashCode()}"
            val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled"
            val publicUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: idStr
            val cover = obj["cover"]?.jsonPrimitive?.contentOrNull
            val description = obj["description"]?.jsonPrimitive?.contentOrNull
            val tagsJson = obj["tags"]
            val tags: Set<ContentTag> = try {
                when (tagsJson) {
                    is JsonArray -> tagsJson.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is JsonObject -> tagsJson.keys
                    else -> emptyList()
                }.map { ContentTag(it, it, source) }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            val authors: Set<String> = emptySet()
            val mangaId = idStr.hashCode().toLong() + idx
            list.add(
                Content(
                    id = mangaId,
                    title = title,
                    altTitles = emptySet(),
                    url = idStr,
                    publicUrl = publicUrl,
                    rating = 0f,
                    contentRating = null,
                    coverUrl = cover,
                    tags = tags,
                    state = null,
                    authors = authors,
                    description = description,
                    source = source,
                )
            )
        }
        return ContentListResult(list, nextToken, maxPage)
    }

    private fun parseExploreMeta(obj: JsonObject): ExploreMeta? {
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: obj["name"]?.jsonPrimitive?.contentOrNull
        val tags = when (val tagsElem = obj["tags"] ?: obj["tag"]) {
            is JsonArray -> tagsElem.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonObject -> tagsElem.values.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }
        val sorts = when (val sortElem = obj["sort"] ?: obj["sorts"] ?: obj["orders"]) {
            is JsonArray -> sortElem.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonObject -> sortElem.values.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }
        if (title.isNullOrBlank() && tags.isEmpty() && sorts.isEmpty()) return null
        return ExploreMeta(title = title, tags = tags, sorts = sorts)
    }

    private fun parseExploreMetaString(raw: String): ExploreMeta? {
        val obj = runCatching { jsonConverter.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return parseExploreMeta(obj)
    }

    private fun applyFilterOptions(meta: ExploreMeta) {
        val tags = meta.tags.map { ContentTag(it, it, source) }.toSet()
        val groups = if (tags.isNotEmpty()) {
            listOf(ContentTagGroup(meta.title ?: "Tags", tags))
        } else {
            emptyList()
        }
        mergeFilterOptions(tags, groups)
    }

    private fun applyFilterOptions(categoryMeta: CategoryMeta?, optionGroups: List<OptionGroup>) {
        val tagGroups = mutableListOf<ContentTagGroup>()
        val availableTags = mutableSetOf<ContentTag>()
        categoryMeta?.parts?.forEachIndexed { partIdx, part ->
            val tags = part.categories.mapIndexed { idx, entry ->
                ContentTag(entry.label, "$TAG_CATEGORY_PREFIX$partIdx:$idx", source)
            }.toSet()
            if (tags.isNotEmpty()) {
                tagGroups.add(ContentTagGroup(part.name.ifBlank { "分类${partIdx + 1}" }, tags))
                availableTags.addAll(tags)
            }
        }
        optionGroups.forEachIndexed { groupIdx, group ->
            val tags = group.options.mapIndexed { idx, option ->
                ContentTag(option.label, "$TAG_OPTION_PREFIX$groupIdx:$idx", source)
            }.toSet()
            if (tags.isNotEmpty()) {
                tagGroups.add(ContentTagGroup(group.title.ifBlank { "选项${groupIdx + 1}" }, tags))
                availableTags.addAll(tags)
            }
        }
        mergeFilterOptions(availableTags, tagGroups)
    }

    private data class JsDetailResult(
        val title: String,
        val description: String?,
        val coverUrl: String?,
        val tags: Set<ContentTag>,
        val authors: Set<String>,
        val url: String?,
        val chapters: List<ContentChapter>,
    )

    private data class ExploreMeta(
        val title: String?,
        val tags: List<String> = emptyList(),
        val sorts: List<String> = emptyList(),
    )

    private data class CategoryMeta(
        val title: String?,
        val parts: List<CategoryPart>,
    )

    private data class CategoryPart(
        val name: String,
        val categories: List<CategoryEntry>,
    )

    private data class CategoryEntry(
        val label: String,
        val param: String?,
    )

    private data class OptionGroup(
        val title: String,
        val options: List<OptionEntry>,
        val showWhen: List<String> = emptyList(),
        val notShowWhen: List<String> = emptyList(),
    )

    private data class OptionEntry(
        val label: String,
        val value: String,
        val isDefault: Boolean,
    )

    private data class CategorySelection(
        val label: String?,
        val param: String?,
    )

    private data class PageEntry(val url: String, val headers: Map<String, String>? = null)

    data class JsSettingOption(
        val value: String,
        val text: String,
    )

    data class JsSettingItem(
        val key: String,
        val title: String,
        val type: String,
        val options: List<JsSettingOption> = emptyList(),
        val defaultValue: String? = null,
        val validator: String? = null,
        val buttonText: String? = null,
    )

    @Serializable
    data class JsAccountMeta(
        val hasLogin: Boolean = false,
        val hasWebLogin: Boolean = false,
        val webLoginUrl: String? = null,
        val cookieFields: List<String> = emptyList(),
        val registerUrl: String? = null,
    )

    private fun getJsSourceContent(): Pair<String, String?>? {
        val jsSource = (source as? JsonContentSource)?.entity ?: return null
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)
        return jsContent to preferredClass
    }
    private data class ContentListResult(
        val items: List<Content>,
        val nextToken: String?,
        val maxPage: Int?,
    )

    private data class PageCacheKey(val source: String, val chapterUrl: String)
    private data class ParsedChapterUrl(
        val mangaId: String,
        val chapterId: String,
    )

    private fun parseChapterUrl(raw: String): ParsedChapterUrl? {
        val parts = raw.split(CHAPTER_URL_SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val mangaId = parts[0].trim()
        val chapterId = parts[1].trim()
        if (mangaId.isBlank() || chapterId.isBlank()) return null
        return ParsedChapterUrl(mangaId, chapterId)
    }

    private fun normalizeHeaders(headers: Map<String, String>?, pageUrl: String): Map<String, String>? {
        if (headers.isNullOrEmpty()) return null
        val lower = headers.keys.associateBy { it.lowercase() }
        val hasOrigin = lower.containsKey("origin")
        if (!hasOrigin) {
            val refererKey = lower["referer"]
            val referer = refererKey?.let { headers[it] }
            if (!referer.isNullOrBlank()) {
                runCatching {
                    val uri = java.net.URI(referer)
                    val origin = "${uri.scheme}://${uri.host}"
                    val newHeaders = headers.toMutableMap()
                    newHeaders.putIfAbsent("Origin", origin)
                    return newHeaders
                }
            }
        }
        return headers
    }

    private fun normalizePageUrl(url: String, headers: Map<String, String>?): String? {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) return url
        val referer = headers?.entries?.firstOrNull { it.key.equals("referer", true) }?.value
        if (!referer.isNullOrBlank()) {
            return runCatching {
                java.net.URI(referer).resolve(url).toString()
            }.getOrNull()
        }
        return null
    }

    private fun isValidHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")
    }

    private suspend fun executeDetail(id: String): JsDetailResult? {
        val jsSource = (source as? JsonContentSource)?.entity ?: return null
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)

            val quickJs = createQuickJs()
            return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            cachedJsKey = runCatching {
                qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: cachedJsKey
            initializeSettingsDefaults(qjs)
            runCatching { runSourceInit(qjs) }
                .onFailure { Log.w("JsContentRepository", "init() error for ${source.name}", it) }
            qjs.evaluate<Any?>(
                """
				var __detail_result = {};
				(async function(){
				  try {
				    if (!__source || !__source.comic || !__source.comic.loadInfo) throw new Error("comic.loadInfo missing");
				    const res = await __source.comic.loadInfo(${id.jsonStringLiteral()});
				    const toObj = (r) => {
				      const tagsObj = {};
				      if (r.tags) {
				        if (r.tags instanceof Map) {
				          r.tags.forEach((v,k)=>{ tagsObj[String(k)] = Array.isArray(v) ? v.map(String) : []; });
				        } else if (typeof r.tags === 'object') {
				          Object.entries(r.tags).forEach(([k,v])=>{ if (Array.isArray(v)) tagsObj[k]=v.map(String); });
				        }
				      }
				      const chapters = [];
				      const pushChapter = (branch, url, title) => {
				        if (!url) return;
				        chapters.push({ branch: branch ? String(branch) : null, url: String(url), title: title ? String(title) : String(url) });
				      };
				      const parseChapterMap = (branch, m) => {
				        if (!m) return;
				        // Map<id,title> or Object{id:title}
				        if (m instanceof Map) {
				          for (const [k, v] of m.entries()) {
				            if (v instanceof Map) {
				              // Map<branch, Map<id,title>>
				              parseChapterMap(k, v);
				            } else {
				              pushChapter(branch, k, v);
				            }
				          }
				          return;
				        }
				        if (Array.isArray(m)) {
				          m.forEach((v, idx) => {
				            if (v && typeof v === 'object') {
				              const cid = v.url || v.id || v.uuid || v.epId || v.ep_id || v.chapterId || v.chapter_id || (idx + 1);
				              const ctitle = v.title || v.name || v.text || v.label || `Chapter ${'$'}{idx + 1}`;
				              pushChapter(branch, cid, ctitle);
				            } else {
				              pushChapter(branch, idx + 1, v);
				            }
				          });
				          return;
				        }
				        if (typeof m === 'object') {
				          Object.entries(m).forEach(([k, v]) => {
				            if (v && typeof v === 'object' && !Array.isArray(v)) {
				              // {branch: {id:title}}
				              const looksLikeNested = Object.values(v).some(x => x && (typeof x === 'string' || typeof x === 'number'));
				              if (looksLikeNested) {
				                Object.entries(v).forEach(([ik, iv]) => pushChapter(k, ik, iv));
				              } else {
				                pushChapter(branch, k, v);
				              }
				            } else {
				              pushChapter(branch, k, v);
				            }
				          });
				          return;
				        }
				      };
				      if (r.chapters) parseChapterMap(null, r.chapters);
				      const authorFromTags = (tagsObj["作者"] || tagsObj["author"] || tagsObj["authors"] || []);
				      const authorsArr = Array.isArray(r.authors) ? r.authors.map(String) : (Array.isArray(authorFromTags) ? authorFromTags.map(String) : []);
				      const updateTime = r.updateTime || r.update_time || null;
				      return {
				        title: String(r.title || ""),
				        description: r.description ? String(r.description) : (updateTime ? String(updateTime) : null),
				        cover: r.cover || r.thumbnail || (Array.isArray(r.thumbnails) ? r.thumbnails[0] : null) || null,
				        url: r.url ? String(r.url) : "",
				        authors: authorsArr,
				        tags: tagsObj,
				        chapters: chapters,
				        updateTime: updateTime ? String(updateTime) : null,
				      };
				    };
				    __detail_result = toObj(res || {});
				  } catch(e){
				    __detail_result = {error: (e && (e.stack || e.message || e.toString())) ? (e.stack || e.message || e.toString()) : String(e)};
				  }
				})();
                """.trimIndent(),
                "<detail>"
            )
            val jsonStr = qjs.evaluate<String>(
                """
				(function(v){
				  try { return JSON.stringify(v); }
				  catch(e){ return JSON.stringify({error: e && (e.message||e.toString()) ? (e.message||e.toString()) : "stringify error"}); }
				})(__detail_result);
                """.trimIndent(),
                "<detail_result>"
            ) ?: "{}"
            val result = jsonConverter.parseToJsonElement(jsonStr).jsonObject
            result["error"]?.jsonPrimitive?.contentOrNull?.let {
                Log.w("JsContentRepository", "detail error=$it for ${source.name}")
                return null
            }
                Log.d(
                    "JsContentRepository",
                    "detail json snippet=${result.toString().take(4000)} for ${source.name}"
                )
            val title = result["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val updateTime = result["updateTime"]?.jsonPrimitive?.contentOrNull
            val tagsObj = result["tags"] as? JsonObject
            val description = result["description"]?.jsonPrimitive?.contentOrNull
                ?: tagsObj?.get("简介")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: updateTime?.takeIf { it.isNotBlank() }
            val cover = result["cover"]?.jsonPrimitive?.contentOrNull
            val url = result["url"]?.jsonPrimitive?.contentOrNull
            val authors = (result["authors"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            val tags = mutableSetOf<ContentTag>()
            tagsObj?.forEach { (k, v) ->
                if (v is JsonArray) {
                    v.mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { tags.add(ContentTag(it, k, source)) }
                }
            }
            val authorFallback = if (authors.isEmpty()) {
                tags.filter { it.key == "作者" || it.key.equals("author", true) }.map { it.title }.toSet()
            } else {
                emptySet()
            }
            val chapters = mutableListOf<ContentChapter>()
            val chaptersArr = result["chapters"] as? JsonArray
            chaptersArr?.forEachIndexed { idx, elem ->
                val obj = elem as? JsonObject ?: return@forEachIndexed
                val urlValue = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val titleValue = obj["title"]?.jsonPrimitive?.contentOrNull ?: urlValue
                val branchValue = obj["branch"]?.jsonPrimitive?.contentOrNull
                val compositeUrl = if (urlValue.contains(CHAPTER_URL_SEPARATOR)) {
                    urlValue
                } else {
                    "$id$CHAPTER_URL_SEPARATOR$urlValue"
                }
                val chNumericId = abs((urlValue.hashCode().toLong() shl 16) + idx)
                chapters.add(
                    ContentChapter(
                        id = chNumericId,
                        title = titleValue,
                        number = (idx + 1).toFloat(),
                        volume = 0,
                        url = compositeUrl,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = branchValue,
                        source = source,
                    )
                )
            }
            if (chapters.isEmpty()) {
                val chNumericId = abs((id.hashCode().toLong() shl 16) + 1)
                val compositeUrl = "$id$CHAPTER_URL_SEPARATOR$id"
                chapters.add(
                    ContentChapter(
                        id = chNumericId,
                        title = "Chapter 1",
                        number = 1f,
                        volume = 0,
                        url = compositeUrl,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source,
                    )
                )
            }
            val detail = JsDetailResult(
                title = title,
                description = description,
                coverUrl = cover,
                tags = tags,
                authors = if (authors.isEmpty()) authorFallback else authors,
                url = url,
                chapters = chapters,
            )
            Log.d(
                "JsContentRepository",
                "detail parsed title=$title chapters=${detail.chapters.size} authors=${detail.authors} desc=${detail.description} source=${source.name}"
            )
            detail
        }
    }

    private suspend fun executePages(id: String, epId: String?): List<PageEntry> {
        val jsSource = (source as? JsonContentSource)?.entity ?: return emptyList()
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)

            val quickJs = createQuickJs()
            return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
                cachedJsKey = runCatching {
                    qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: cachedJsKey
                initializeSettingsDefaults(qjs)
                runCatching { runSourceInit(qjs) }
                    .onFailure { Log.w("JsContentRepository", "init() error for ${source.name}", it) }
                qjs.evaluate<Any?>(
                """
				var __pages_result = [];
				(async function(){
				  try {
				    if (!__source || !__source.comic || !__source.comic.loadInfo || !__source.comic.loadEp) throw new Error("comic.loadEp missing");
				    await __source.comic.loadInfo(${id.jsonStringLiteral()});
				    const res = await __source.comic.loadEp(${id.jsonStringLiteral()}, ${epId?.jsonStringLiteral() ?: "null"});
				    let urls = [];
				    if (res && Array.isArray(res.images)) { urls = res.images; }
				    else if (Array.isArray(res)) { urls = res; }
				    else { __pages_result = {error: "invalid images"}; return; }
				    const configs = [];
				    let defaultHeaders = null;
				    try {
				      if (__source && __source.headers && typeof __source.headers === 'object') {
				        defaultHeaders = __source.headers;
				      }
				    } catch(e) { /* ignore */ }
				    const b64ToBytes = (b64) => {
				      try {
				        if (typeof atob === 'function') {
				          const bin = atob(b64);
				          const arr = new Uint8Array(bin.length);
				          for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
				          return arr;
				        }
				      } catch(_) {}
				      return new Uint8Array(0);
				    };
				    const toBytes = (val) => {
				      if (!val) return new Uint8Array(0);
				      if (val instanceof ArrayBuffer) return new Uint8Array(val);
				      if (ArrayBuffer.isView(val) && val.buffer instanceof ArrayBuffer) return new Uint8Array(val.buffer, val.byteOffset || 0, val.byteLength || val.buffer.byteLength);
				      if (typeof val === 'string') return b64ToBytes(val);
				      if (val.__type === 'bytes_base64' && val.data) return b64ToBytes(val.data);
				      if (val.data instanceof ArrayBuffer) return new Uint8Array(val.data);
				      return new Uint8Array(0);
				    };
				    const normalized = await Promise.all(urls.map(async function(u){
				      const baseCfg = (u && typeof u === 'object') ? u : null;
				      let finalUrl = baseCfg && baseCfg.url ? String(baseCfg.url) : String(u);
				      let finalHeaders = (baseCfg && baseCfg.headers) ? baseCfg.headers : null;
				      let onResp = (baseCfg && typeof baseCfg.onResponse === 'function') ? baseCfg.onResponse : null;
				      let mime = baseCfg && baseCfg.mimeType ? String(baseCfg.mimeType) : null;
				      // 先执行 onImageLoad，允许 JS 再次覆盖 url/headers/onResponse
				      if (__source && __source.comic && typeof __source.comic.onImageLoad === 'function') {
				        try {
				          const cfgRaw = __source.comic.onImageLoad(finalUrl, ${id.jsonStringLiteral()}, ${epId?.jsonStringLiteral() ?: "null"});
				          const cfg = (cfgRaw && typeof cfgRaw.then === 'function') ? await cfgRaw : cfgRaw;
				          if (cfg && typeof cfg === 'object') {
				            if (cfg.url) finalUrl = String(cfg.url);
				            if (cfg.headers) finalHeaders = cfg.headers;
				            if (typeof cfg.onResponse === 'function') onResp = cfg.onResponse;
				            if (cfg.mimeType) mime = String(cfg.mimeType);
				          }
				        } catch(e) { /* ignore and fallback */ }
				      }
				      // 如果提供了 onResponse，则主动请求字节并转换为 data URL
				      if (typeof onResp === 'function') {
				        try {
				          const resp = await __native_sendMessage(JSON.stringify({
				            method: "http",
				            url: finalUrl,
				            http_method: "GET",
				            bytes: true,
				            headers: finalHeaders || defaultHeaders || {},
				          }));
				          const parsed = JSON.parse(resp || "{}");
				          if (parsed && parsed.body) {
				            const rawBytes = toBytes(parsed.body);
				            const decrypted = onResp(rawBytes.length > 0 ? rawBytes : parsed.body);
				            const bytes = toBytes(decrypted);
				            if (bytes.length > 0) {
				              let binary = "";
				              for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
				              const b64 = btoa(binary);
				              const finalMime = mime || "image/jpeg";
				              finalUrl = "data:" + finalMime + ";base64," + b64;
				              finalHeaders = {};
				            }
				          }
				        } catch(e) { /* ignore decryption errors, fallback to original URL */ }
				      }
				      finalHeaders = finalHeaders || defaultHeaders || {};
				      configs.push({url: finalUrl, headers: finalHeaders});
				      return finalUrl;
				    }));
				    __pages_result = {images: normalized, configs: configs};
				  } catch(e){
				    __pages_result = {error: (e && (e.stack || e.message || e.toString())) ? (e.stack || e.message || e.toString()) : String(e)};
				  }
				})();
                """.trimIndent(),
                "<pages>"
            )
            val jsonStr = qjs.evaluate<String>(
                """
				(function(v){
				  try {
				    if (v && v.error) return JSON.stringify({error:v.error});
				    const images = Array.isArray(v) ? v : (v && Array.isArray(v.images) ? v.images : []);
				    const configs = v && Array.isArray(v.configs) ? v.configs : [];
				    const toStrArr = (arr) => arr.map(String);
				    const normConfigs = configs.map(function(c){
				      const headers = c && typeof c.headers === 'object' ? c.headers : {};
				      return {url: String(c.url || ""), headers};
				    });
				    return JSON.stringify({images: toStrArr(images), configs: normConfigs});
				  } catch(e){ return JSON.stringify({error: e && (e.message||e.toString()) ? (e.message||e.toString()) : "stringify error"}); }
				})(__pages_result);
                """.trimIndent(),
                "<pages_result>"
            ) ?: "{}"
            val result = jsonConverter.parseToJsonElement(jsonStr).jsonObject
            result["error"]?.jsonPrimitive?.contentOrNull?.let {
                Log.w("JsContentRepository", "pages error=$it for ${source.name}")
                return emptyList()
            }
            if (Log.isLoggable("JsContentRepository", Log.DEBUG)) {
                Log.d(
                    "JsContentRepository",
                    "pages raw images=${result["images"]} configs=${result["configs"]} source=${source.name}"
                )
            }
            val images = result["images"] as? JsonArray ?: return emptyList()
            val configsArr = result["configs"] as? JsonArray
            Log.d(
                "JsContentRepository",
                "pages configsCount=${configsArr?.size ?: 0} imagesCount=${images.size} for ${source.name}"
            )
            val configs = configsArr?.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                    val rawUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val headersObj = obj["headers"] as? JsonObject
                    val headers = headersObj?.mapNotNull { (k, v) ->
                        val hv = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                        k to hv
                    }?.toMap()
                    val normalizedHeaders = normalizeHeaders(headers, rawUrl)
                    val finalUrl = normalizePageUrl(rawUrl, normalizedHeaders ?: headers) ?: rawUrl
                    if (!isValidHttpUrl(finalUrl)) return@mapNotNull null
                    PageEntry(finalUrl, normalizedHeaders)
                }.orEmpty()
            val list = if (configs.isNotEmpty()) configs else images.mapNotNull { value ->
                val rawUrl = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                val finalUrl = normalizePageUrl(rawUrl, null) ?: rawUrl
                if (!isValidHttpUrl(finalUrl)) return@mapNotNull null
                PageEntry(finalUrl, null)
            }
            if (list.isNotEmpty()) {
                Log.d(
                    "JsContentRepository",
                    "pages mapped=${list.size} for ${source.name} first=${list.first().url} headers=${list.first().headers}"
                )
            }
            list
        }
    }

    private suspend fun executeJsAction(action: String, script: String): JsonObject? {
        val jsSource = (source as? JsonContentSource)?.entity ?: return null
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)

            val quickJs = createQuickJs()
            return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            cachedJsKey = runCatching {
                qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: cachedJsKey
            initializeSettingsDefaults(qjs)
            qjs.evaluate<Any?>(
                """
				var __js_result;
				(async function(){
				  __js_result = await ( ${script} );
				  if (__js_result === undefined || __js_result === null) __js_result = {};
				})();
                """.trimIndent(),
                "<$action>"
            )
            val jsonStr = qjs.evaluate<String>("JSON.stringify(__js_result)", "<${action}_result>") ?: "{}"
            jsonConverter.parseToJsonElement(jsonStr).jsonObject
        }
    }

    private fun mapToJsonMap(map: Map<*, *>): Map<String, kotlinx.serialization.json.JsonElement> {
        val result = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        map.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            val element = when (v) {
                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                is Map<*, *> -> kotlinx.serialization.json.JsonObject(mapToJsonMap(v))
                is List<*> -> kotlinx.serialization.json.JsonArray(v.mapNotNull {
                    when (it) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(it)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(it)
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(it)
                        is Map<*, *> -> kotlinx.serialization.json.JsonObject(mapToJsonMap(it))
                        else -> null
                    }
                })
                else -> kotlinx.serialization.json.JsonPrimitive(v?.toString() ?: "")
            }
            result[key] = element
        }
        return result
    }

    private fun registerSendMessage(qjs: QuickJs) {
        // 通过 JSON 字符串桥接，避免循环引用映射
        qjs.defineBinding("__native_sendMessage", FunctionBinding<String?> { args ->
            try {
                val raw = args.getOrNull(0) as? String ?: return@FunctionBinding null
                val msgJson = runCatching { jsonConverter.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                val method = msgJson["method"]?.jsonPrimitive?.contentOrNull ?: return@FunctionBinding null
                val result: Any? = when (method) {
                    "http" -> handleHttp(msgJson)
                    "load_data" -> {
                        val key = msgJson["key"]?.jsonPrimitive?.contentOrNull
                        val dataKey = msgJson["data_key"]?.jsonPrimitive?.contentOrNull
                        dataStore[key]?.get(dataKey)
                    }
                    "save_data" -> {
                        val key = msgJson["key"]?.jsonPrimitive?.contentOrNull ?: ""
                        val dataKey = msgJson["data_key"]?.jsonPrimitive?.contentOrNull ?: ""
                        val dataVal = jsonElementToAny(msgJson["data"])
                        val store = dataStore.getOrPut(key) { mutableMapOf() }
                        store[dataKey] = dataVal
                        null
                    }
                    "delete_data" -> {
                        val key = msgJson["key"]?.jsonPrimitive?.contentOrNull ?: ""
                        val dataKey = msgJson["data_key"]?.jsonPrimitive?.contentOrNull ?: ""
                        dataStore[key]?.remove(dataKey)
                        null
                    }
            "load_setting" -> {
                        val key = msgJson["key"]?.jsonPrimitive?.contentOrNull
                        val dataKey = msgJson["data_key"]?.jsonPrimitive?.contentOrNull
                            ?: msgJson["setting_key"]?.jsonPrimitive?.contentOrNull
                        loadJsSetting(key, dataKey)
                    }
                    "save_setting" -> {
                        val key = msgJson["key"]?.jsonPrimitive?.contentOrNull ?: ""
                        val dataKey = msgJson["data_key"]?.jsonPrimitive?.contentOrNull
                            ?: msgJson["setting_key"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val dataVal = jsonElementToAny(msgJson["data"])
                        saveJsSetting(key, dataKey, dataVal)
                        null
                    }
            "random" -> {
                val type = msgJson["type"]?.jsonPrimitive?.contentOrNull ?: "int"
                val min = msgJson["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val max = msgJson["max"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                if (type == "double") {
                    if (min == max) min else kotlin.random.Random.nextDouble(min, max)
                } else {
                    val minInt = min.toInt()
                    val maxInt = max.toInt()
                    if (minInt == maxInt) minInt else kotlin.random.Random.nextInt(minInt, maxInt + 1)
                }
            }
            "uuid" -> java.util.UUID.randomUUID().toString()
            "isLogged" -> false
            "convert" -> handleConvert(msgJson)
            "html" -> handleHtml(msgJson)
            else -> null
                }
                return@FunctionBinding toSafeJson(result)
            } catch (e: Exception) {
                Log.w("JsContentRepository", "sendMessage handler failed", e)
                return@FunctionBinding toSafeJson(mapOf("error" to (e.message ?: e.toString())))
            }
        })
    }

    fun saveJsCookies(url: String, cookies: Map<String, String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val list = cookies.mapNotNull { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@mapNotNull null
            Cookie.Builder()
                .hostOnlyDomain(httpUrl.host)
                .path("/")
                .name(name.trim())
                .value(value)
                .apply { if (httpUrl.isHttps) secure() }
                .build()
        }
        if (list.isNotEmpty()) {
            mangaLoaderContext.cookieJar.removeCookies(httpUrl) { true }
            mangaLoaderContext.cookieJar.saveFromResponse(httpUrl, list)
        }
    }

    private fun toSafeJson(value: Any?): String {
        // 将返回值编码为 JSON 字符串，ByteArray 会转为 base64 标识，避免循环引用
        fun encode(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
            null -> JsonPrimitive(value = null as String?)
            is kotlinx.serialization.json.JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, vv) ->
                (k?.toString() ?: "") to encode(vv)
            })
            is List<*> -> JsonArray(v.map { encode(it) })
            is ByteArray -> JsonObject(
                mapOf(
                    "__type" to JsonPrimitive("bytes_base64"),
                    "data" to JsonPrimitive(Base64.getEncoder().encodeToString(v)),
                )
            )
            else -> JsonPrimitive(v.toString())
        }
        return jsonConverter.encodeToString(encode(value))
    }

    private fun jsonElementToAny(value: kotlinx.serialization.json.JsonElement?): Any? {
        return when (value) {
            null -> null
            is JsonPrimitive -> {
                // 保持字符串类型，不要把 "123" 误转成数字，避免 GraphQL ID 等被强制为 float64
                if (value.isString) {
                    value.contentOrNull
                } else {
                    value.booleanOrNull
                        ?: value.longOrNull
                        ?: value.doubleOrNull
                        ?: value.contentOrNull
                }
            }
            is JsonArray -> value.map { jsonElementToAny(it) }
            is JsonObject -> {
                val type = value["__type"]?.jsonPrimitive?.contentOrNull
                if (type == "bytes_base64") {
                    val data = value["data"]?.jsonPrimitive?.contentOrNull ?: return null
                    return Base64.getDecoder().decode(data)
                }
                value.entries.associate { (k, v) -> k to jsonElementToAny(v) }
            }
            else -> value.toString()
        }
    }

    private fun handleHttp(msg: JsonObject): Map<String, Any?> {
        val url = msg["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val method = msg["http_method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headersAny = jsonElementToAny(msg["headers"])
        val bytes = msg["bytes"]?.jsonPrimitive?.booleanOrNull ?: false
        val data = msg["data"]
        val builder = Request.Builder()
            .url(url)
            // 传递来源，便于 CommonHeadersInterceptor 注入默认 UA/Referer
            .tag(ContentSource::class.java, source)
            .header(CommonHeaders.MANGA_SOURCE, source.name)
        (headersAny as? Map<*, *>)?.forEach { (k, v) ->
            val name = k?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            val headerVal = when (v) {
                null -> null
                is String -> v
                is Number, is Boolean -> v.toString()
                is ByteArray -> runCatching { String(v, Charsets.UTF_8) }.getOrNull()
                else -> v.toString()
            }?.takeIf { it.isNotBlank() }
            if (headerVal != null) builder.addHeader(name, headerVal)
        }
        if (builder.build().header("Accept") == null) {
            builder.header("Accept", "*/*")
        }
        val body: RequestBody? = when (val bodyAny = jsonElementToAny(data)) {
            null -> null
            is ByteArray -> bodyAny.toRequestBody(null)
            is String -> bodyAny.toRequestBody()
            is Map<*, *>, is List<*> -> toSafeJson(bodyAny).toRequestBody("application/json".toMediaTypeOrNull())
            else -> bodyAny.toString().toRequestBody()
        }
        when (method.uppercase()) {
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(body ?: ByteArray(0).toRequestBody(null))
            "PATCH" -> builder.patch(body ?: ByteArray(0).toRequestBody(null))
            else -> builder.get()
        }
        val request = builder.build()
        Log.d("JsContentRepository", "HTTP ${method.uppercase()} $url headers=${headersAny?.toString()?.take(200)} body=${data?.toString()?.take(200)}")
        val call: Call = okHttp.newCall(request)
        val response = runCatching { call.execute() }.getOrNull()
        if (response == null) {
            return mapOf("status" to 0, "body" to null, "error" to "network error")
        }
        val rawBytes = response.body?.bytes() ?: ByteArray(0)
        val status = response.code
        val headers = response.headers.associate { it.first.lowercase() to it.second }
        val bodyResult: Any? = if (bytes) {
            Log.d("JsContentRepository", "HTTP resp ${response.code} bytes len=${rawBytes.size}")
            rawBytes
        } else {
            val bodyStr = runCatching { String(rawBytes) }.getOrDefault(String(rawBytes, Charsets.ISO_8859_1))
            Log.d("JsContentRepository", "HTTP resp ${response.code} len=${rawBytes.size} bodySnippet=${bodyStr.take(300)}")
            bodyStr
            }
            response.close()
            val result = mutableMapOf<String, Any?>(
                "status" to status,
                "body" to bodyResult,
                "headers" to headers,
            )
        if (bytes) {
            // 为兼容旧 JS: 同时返回 bodyBytes
            result["bodyBytes"] = bodyResult
        }
        return result
    }

    private fun handleHtml(msg: JsonObject): Any? {
        val function = msg["function"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = msg["key"]?.jsonPrimitive?.intOrNull
        val docKey = msg["doc"]?.jsonPrimitive?.intOrNull ?: key
        val store = docKey?.let { htmlStores[it] }
        return when (function) {
            "parse" -> {
                val html = msg["data"]?.jsonPrimitive?.contentOrNull ?: ""
                val docKeyVal = key ?: return null
                val doc = Jsoup.parse(html)
                htmlStores[docKeyVal] = HtmlStore(doc, mutableMapOf(0 to doc), 0)
                null
            }
            "dispose" -> {
                if (key != null) htmlStores.remove(key)
                null
            }
            "querySelector" -> {
                val doc = store?.document ?: return null
                val query = msg["query"]?.jsonPrimitive?.contentOrNull ?: return null
                val el = doc.selectFirst(query) ?: return null
                store.storeNode(el)
            }
            "querySelectorAll" -> {
                val doc = store?.document ?: return emptyList<Int>()
                val query = msg["query"]?.jsonPrimitive?.contentOrNull ?: return emptyList<Int>()
                doc.select(query).map { store.storeNode(it) }
            }
            "dom_querySelector" -> {
                val el = store?.getElement(key) ?: return null
                val query = msg["query"]?.jsonPrimitive?.contentOrNull ?: return null
                val target = el.selectFirst(query) ?: return null
                store.storeNode(target)
            }
            "dom_querySelectorAll" -> {
                val el = store?.getElement(key) ?: return emptyList<Int>()
                val query = msg["query"]?.jsonPrimitive?.contentOrNull ?: return emptyList<Int>()
                el.select(query).map { store.storeNode(it) }
            }
            "getElementById" -> {
                val doc = store?.document ?: return null
                val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val el = doc.getElementById(id) ?: return null
                store.storeNode(el)
            }
            "getText" -> {
                val el = store?.getElement(key) ?: return ""
                el.text()
            }
            "getAttributes" -> {
                val el = store?.getElement(key) ?: return emptyMap<String, String>()
                el.attributes().associate { it.key to it.value }
            }
            "getChildren" -> {
                val el = store?.getElement(key) ?: return emptyList<Int>()
                el.children().map { child -> store.storeNode(child) }
            }
            "getNodes" -> {
                val el = store?.getNode(key) ?: return emptyList<Int>()
                el.childNodes().map { node -> store.storeNode(node) }
            }
            "getInnerHTML" -> {
                val el = store?.getElement(key) ?: return ""
                el.html()
            }
            "getParent" -> {
                val el = store?.getElement(key) ?: return null
                val parent = el.parent() ?: return null
                store.storeNode(parent)
            }
            "getClassNames" -> {
                val el = store?.getElement(key) ?: return emptyList<String>()
                el.classNames().toList()
            }
            "getId" -> {
                val el = store?.getElement(key) ?: return null
                val idStr = el.id()
                if (idStr.isBlank()) null else idStr
            }
            "getLocalName" -> {
                val el = store?.getElement(key) ?: return ""
                el.normalName()
            }
            "getPreviousSibling" -> {
                val el = store?.getElement(key) ?: return null
                val prev = el.previousElementSibling() ?: return null
                store.storeNode(prev)
            }
            "getNextSibling" -> {
                val el = store?.getElement(key) ?: return null
                val next = el.nextElementSibling() ?: return null
                store.storeNode(next)
            }
            "node_text" -> {
                val node = store?.getNode(key) ?: return ""
                when (node) {
                    is org.jsoup.nodes.TextNode -> node.text()
                    else -> node.outerHtml()
                }
            }
            "node_type" -> {
                val node = store?.getNode(key) ?: return "unknown"
                when (node) {
                    is Document -> "document"
                    is Element -> "element"
                    is org.jsoup.nodes.TextNode -> "text"
                    is org.jsoup.nodes.Comment -> "comment"
                    else -> "unknown"
                }
            }
            "node_toElement" -> {
                val node = store?.getNode(key) ?: return null
                val el = node as? Element ?: return null
                store.storeNode(el)
            }
            else -> null
        }
    }

    private fun handleConvert(msg: JsonObject): Any? {
        val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val isEncode = msg["isEncode"]?.jsonPrimitive?.booleanOrNull ?: true
        val value = msg["value"]
        return when (type) {
            "utf8" -> {
                if (isEncode) {
                    val str = value?.jsonPrimitive?.contentOrNull ?: ""
                    str.toByteArray(Charsets.UTF_8)
                } else {
                    val bytes = jsonElementToBytes(value)
                    String(bytes, Charsets.UTF_8)
                }
            }
            "base64" -> {
                if (isEncode) {
                    val bytes = jsonElementToBytes(value)
                    Base64.getEncoder().encodeToString(bytes)
                } else {
                    val str = value?.jsonPrimitive?.contentOrNull ?: ""
                    Base64.getDecoder().decode(str)
                }
            }
            "md5", "sha1", "sha256", "sha512" -> {
                val algo = when (type) {
                    "md5" -> "MD5"
                    "sha1" -> "SHA-1"
                    "sha256" -> "SHA-256"
                    else -> "SHA-512"
                }
                val bytes = jsonElementToBytes(value)
                MessageDigest.getInstance(algo).digest(bytes)
            }
            "hmac" -> {
                val keyElem = msg["key"]
                val hash = msg["hash"]?.jsonPrimitive?.contentOrNull ?: "sha256"
                val isString = msg["isString"]?.jsonPrimitive?.booleanOrNull ?: false
                val algo = when (hash.lowercase()) {
                    "md5" -> "HmacMD5"
                    "sha1" -> "HmacSHA1"
                    "sha512" -> "HmacSHA512"
                    else -> "HmacSHA256"
                }
                val keyBytes = jsonElementToBytes(keyElem)
                val valueBytes = jsonElementToBytes(value)
                if (keyBytes.isEmpty()) {
                    return mapOf("error" to "Empty key")
                }
                val mac = Mac.getInstance(algo)
                mac.init(SecretKeySpec(keyBytes, algo))
                val digest = mac.doFinal(valueBytes)
                if (isString) bytesToHex(digest) else digest
            }
            "gbk" -> {
                val charset = kotlin.runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)
                if (isEncode) {
                    val str = value?.jsonPrimitive?.contentOrNull ?: ""
                    str.toByteArray(charset)
                } else {
                    val bytes = jsonElementToBytes(value)
                    String(bytes, charset)
                }
            }
            else -> null
        }
    }

    private fun jsonElementToBytes(value: kotlinx.serialization.json.JsonElement?): ByteArray {
        return when (value) {
            is JsonObject -> {
                val type = value["__type"]?.jsonPrimitive?.contentOrNull
                if (type == "bytes") {
                    val arr = value["data"] as? JsonArray ?: return ByteArray(0)
                    val list = arr.mapNotNull { it.jsonPrimitive.intOrNull ?: it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                    ByteArray(list.size) { idx -> (list[idx] and 0xFF).toByte() }
                } else if (type == "bytes_base64") {
                    val data = value["data"]?.jsonPrimitive?.contentOrNull ?: return ByteArray(0)
                    Base64.getDecoder().decode(data)
                } else {
                    ByteArray(0)
                }
            }
            is JsonArray -> {
                val list = value.mapNotNull { it.jsonPrimitive.intOrNull ?: it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                ByteArray(list.size) { idx -> (list[idx] and 0xFF).toByte() }
            }
            is JsonPrimitive -> {
                value.contentOrNull?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            }
            else -> ByteArray(0)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun prefKey(sourceKey: String?, dataKey: String?): String? {
        if (sourceKey.isNullOrBlank() || dataKey.isNullOrBlank()) return null
        return "js_setting_${sourceKey}_${dataKey}"
    }

    private fun loadJsSetting(sourceKey: String?, dataKey: String?): Any? {
        val pk = prefKey(sourceKey, dataKey) ?: return null
        val stored = settingsPrefs.getString(pk, null)
        if (stored != null) {
            return runCatching { jsonConverter.parseToJsonElement(stored) }.mapCatching { jsonElementToAny(it) }.getOrNull()
        }
        return settingsStore[sourceKey]?.get(dataKey)
    }

    private fun saveJsSetting(sourceKey: String?, dataKey: String?, value: Any?) {
        val pk = prefKey(sourceKey, dataKey) ?: return
        settingsPrefs.edit().putString(pk, toSafeJson(value)).apply()
        val store = settingsStore.getOrPut(sourceKey ?: "") { mutableMapOf() }
        store[dataKey ?: ""] = value
    }

    private fun buildBootstrapScript(): String {
        val core = runCatching { mangaLoaderContext.loadAssetText("_venera_.js") }.getOrDefault("")
        val prefix = """
			// atob/btoa polyfill
			(() => {
				if (typeof atob !== 'function') {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					globalThis.atob = function(input) {
						let str = String(input).replace(/=+$/, '');
						if (str.length % 4 === 1) throw new Error('Invalid base64');
						let output = '';
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
				if (typeof btoa !== 'function') {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					globalThis.btoa = function(input) {
						let str = String(input);
						let output = '';
						for (let block = 0, charCode, i = 0, map = chars; str.charAt(i | 0) || (map = '=', i % 1); output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))) {
							charCode = str.charCodeAt(i += 3/4);
							if (charCode > 0xFF) throw new Error('Invalid character');
							block = (block << 8) | charCode;
						}
						return output;
					};
				}
			})();
			const __bytesToBase64 = (buf) => {
				let u8;
				if (buf instanceof ArrayBuffer) u8 = new Uint8Array(buf);
				else if (ArrayBuffer.isView(buf) && buf.buffer instanceof ArrayBuffer) u8 = new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength || buf.buffer.byteLength);
				else return null;
				let s = '';
				for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
				return btoa(s);
			};
			// venera runtime expects appVersion for APP.version getter
			if (typeof globalThis.appVersion === 'undefined') {
				globalThis.appVersion = '1.6.0';
			}
			const __safeStringify = (obj) => {
				const seen = new WeakSet();
				return JSON.stringify(obj, (k, v) => {
					if (v instanceof ArrayBuffer || (v && ArrayBuffer.isView(v))) {
						const b64 = __bytesToBase64(v);
						return b64 ? {__type:'bytes_base64', data:b64} : null;
					}
					if (v && typeof v === 'object') {
						if (seen.has(v)) return undefined;
						seen.add(v);
					}
					return v;
				});
			};
			const __reviveBytes = (val) => {
				if (!val) return val;
				if (val.__type === 'bytes_base64' && val.data) {
					return Uint8Array.from(atob(val.data), c => c.charCodeAt(0)).buffer;
				}
				if (Array.isArray(val)) return val.map(__reviveBytes);
				if (typeof val === 'object') {
					Object.keys(val).forEach(k => { val[k] = __reviveBytes(val[k]); });
					return val;
				}
				return val;
			};
			function sendMessage(msg) {
				const respStr = __native_sendMessage(__safeStringify(msg));
				if (respStr == null) return null;
				const parsed = JSON.parse(respStr);
				const revived = __reviveBytes(parsed);
				if (msg && msg.method === 'delay') {
					return Promise.resolve(revived);
				}
				return revived;
			}
			if (typeof TextDecoder !== 'function') {
				globalThis.TextDecoder = function() {
					this.decode = function(arr) {
						if (!arr) return "";
						if (arr instanceof ArrayBuffer) arr = new Uint8Array(arr);
						let str = "";
						for (let i = 0; i < arr.length; i++) str += String.fromCharCode(arr[i]);
						try { return decodeURIComponent(escape(str)); } catch(e) { return str; }
					};
				};
			}
        """.trimIndent()
        return listOf(prefix, core).joinToString("\n")
    }

    private suspend fun instantiateSource(qjs: QuickJs, preferredClass: String?) {
        val detector = """
			(function() {
			  const candidates = [];
			  ${preferredClass?.let { "if (typeof $it !== 'undefined') candidates.push($it);" } ?: ""}
			  for (const v of Object.values(globalThis)) {
			    if (typeof v === 'function' && v.prototype && v.prototype.constructor && v !== ComicSource && v.prototype instanceof ComicSource) {
			      candidates.push(v);
			    }
			  }
			  const C = candidates.find(v => v) || null;
			  if (C) { globalThis.__source = new C(); } else { globalThis.__source = null; }
			  return globalThis.__source;
			})();
        """.trimIndent()
        qjs.evaluate<Any?>(detector, "<instantiate>")
    }

    suspend fun getJsAccountMeta(): JsAccountMeta? {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return null
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            val metaJson = qjs.evaluate<String?>(
                """
				(() => {
				  const meta = {hasLogin:false, hasWebLogin:false, webLoginUrl:null, cookieFields:[], registerUrl:null};
				  if (!__source || !__source.account) return JSON.stringify(meta);
				  const acc = __source.account;
				  meta.hasLogin = typeof acc.login === 'function';
				  if (acc.loginWithWebview && typeof acc.loginWithWebview.url === 'string') {
				    meta.hasWebLogin = true;
				    meta.webLoginUrl = acc.loginWithWebview.url;
				  }
				  if (acc.loginWithCookies && Array.isArray(acc.loginWithCookies.fields)) {
				    meta.cookieFields = acc.loginWithCookies.fields.map(String);
				  }
				  if (acc.registerWebsite) meta.registerUrl = String(acc.registerWebsite);
				  return JSON.stringify(meta);
				})()
                """.trimIndent()
            )
            metaJson?.let {
                runCatching { jsonConverter.decodeFromString<JsAccountMeta>(it) }.getOrNull()
            }
        }
    }

    suspend fun jsLogin(username: String, password: String): Boolean {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return false
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            initializeSettingsDefaults(qjs)
            val res = qjs.evaluate<Any?>(
                """
				(async () => {
				  if (!__source || !__source.account || typeof __source.account.login !== 'function') return false;
				  const r = await __source.account.login(${username.jsonStringLiteral()}, ${password.jsonStringLiteral()});
				  return r === undefined ? true : !!r;
				})()
                """.trimIndent(),
                "<js-login>",
            )
            (res as? Boolean) ?: false
        }
    }

    suspend fun jsLogout(): Boolean {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return false
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            val res = qjs.evaluate<Any?>(
                """
				(async () => {
				  if (!__source || !__source.account || typeof __source.account.logout !== 'function') return false;
				  const r = await __source.account.logout();
				  return r === undefined ? true : !!r;
				})()
                """.trimIndent(),
                "<js-logout>",
            )
            (res as? Boolean) ?: false
        }
    }

    suspend fun jsLoginWithWebview(onStatus: (url: String, title: String) -> Boolean): Boolean {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return false
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            initializeSettingsDefaults(qjs)
            val hasWeb = qjs.evaluate<Boolean>(
                "(() => !!(__source && __source.account && __source.account.loginWithWebview && __source.account.loginWithWebview.url))()",
                "<js-web-meta>",
            ) ?: false
            if (!hasWeb) return@use false
            val url = qjs.evaluate<String?>("(() => __source.account.loginWithWebview.url)||null", "<js-web-url>") ?: return@use false
            onStatus(url, "")
        }
    }

    suspend fun jsCheckWebLogin(currentUrl: String?, title: String?): Boolean {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return false
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            initializeSettingsDefaults(qjs)
            val script = """
				(async () => {
				  if (!__source || !__source.account || !__source.account.loginWithWebview) return false;
				  const checker = __source.account.loginWithWebview.checkStatus;
				  if (typeof checker !== 'function') return false;
				  const r = checker(${(currentUrl ?: "").jsonStringLiteral()}, ${(title ?: "").jsonStringLiteral()});
				  return r && typeof r.then === 'function' ? !!(await r) : !!r;
				})()
            """.trimIndent()
            qjs.evaluate<Boolean>(script, "<js-web-check>") ?: false
        }
    }

    suspend fun jsNotifyWebLoginSuccess(): Boolean {
        val (jsContent, preferredClass) = getJsSourceContent() ?: return false
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            initializeSettingsDefaults(qjs)
            val script = """
				(async () => {
				  if (!__source || !__source.account || !__source.account.loginWithWebview) return false;
				  const cb = __source.account.loginWithWebview.onLoginSuccess;
				  if (typeof cb !== 'function') return true;
				  const r = cb();
				  return r && typeof r.then === 'function' ? !!(await r) : (r === undefined ? true : !!r);
				})()
            """.trimIndent()
            qjs.evaluate<Boolean>(script, "<js-web-success>") ?: true
        }
    }

    private suspend fun initializeSettingsDefaults(qjs: QuickJs) {
        qjs.evaluate<Any?>(
            """
			(() => {
			  if (!__source || !__source.settings) return;
			  const settings = __source.settings;
			  Object.keys(settings).forEach((key) => {
			    const conf = settings[key] || {};
			    const current = sendMessage({method:'load_setting', key: __source.key, setting_key: key});
			    if (current !== null && current !== undefined && current !== '') return;
			    let def = conf.default;
			    if ((def === null || def === undefined) && Array.isArray(conf.options) && conf.options.length > 0) {
			      const opt = conf.options[0];
			      if (opt && typeof opt === 'object' && 'value' in opt) def = opt.value;
			      else def = opt;
			    }
			    if (def !== null && def !== undefined) {
			      sendMessage({method:'save_setting', key: __source.key, setting_key: key, data: def});
			    }
			  });
			})()
            """.trimIndent(),
            "<init_settings>"
        )
    }

    private suspend fun runSourceInit(qjs: QuickJs): String? {
        return qjs.evaluate<String?>(
            """
			await (async function() {
			  try {
			    if (typeof __source !== 'undefined' && __source && typeof __source.init === 'function') {
			      const r = __source.init();
			      if (r && typeof r.then === 'function') await r;
			    }
			    return null;
			  } catch (e) {
			    return (e && (e.stack || e.message || (e.toString && e.toString()))) ? (e.stack || e.message || e.toString()) : String(e);
			  }
			})();
            """.trimIndent(),
            "<init>"
        )
    }

    suspend fun fetchSettingsSchema(): List<JsSettingItem> {
        cachedSettingsSchema?.let { return it }
        val jsSource = (source as? JsonContentSource)?.entity ?: return emptyList()
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)
        val quickJs = createQuickJs()
        return quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            cachedJsKey = runCatching { qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: cachedJsKey
            initializeSettingsDefaults(qjs)
            val raw = qjs.evaluate<String?>(
                """
				(() => {
				  if (!__source || !__source.settings) return "[]";
				  const res = [];
				  Object.entries(__source.settings).forEach(([key, conf]) => {
				    if (!conf || typeof conf !== 'object') return;
				    const type = conf.type || (typeof conf.callback === 'function' ? 'callback' : (Array.isArray(conf.options) ? 'select' : 'input'));
				    const options = [];
				    if (Array.isArray(conf.options)) {
				      conf.options.forEach((opt) => {
				        if (typeof opt === 'string') {
				          const isDefault = opt.startsWith('*');
				          const clean = isDefault ? opt.substring(1) : opt;
				          const valuePart = clean.split('-')[0]; // 即便为空也要保留，避免 "-全部" 变成 "-全部"
				          const label = clean.includes('-') ? clean.split('-').slice(1).join('-') : clean;
				          options.push({ value: (isDefault ? '*' : '') + valuePart, text: label });
				        } else if (opt && typeof opt === 'object') {
				          const v = opt.value ?? opt.val ?? opt.v ?? "";
				          const t = opt.text ?? opt.title ?? v ?? "";
				          options.push({ value: String(v), text: String(t) });
				        }
				      });
				    }
				    res.push({
				      key: key,
				      title: String(conf.title || key),
				      type: String(type),
				      options: options,
				      default: conf.default ?? null,
				      validator: conf.validator ?? null,
				      buttonText: conf.buttonText || conf.buttontext || null
				    });
				  });
				  return JSON.stringify(res);
				})()
                """.trimIndent(),
                "<settings_schema>"
            ).orEmpty()
            val arr = runCatching { jsonConverter.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
            val schema = arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: key
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "input"
                val options = (obj["options"] as? JsonArray)?.mapNotNull { o ->
                    val oObj = o as? JsonObject ?: return@mapNotNull null
                    val v = oObj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val t = oObj["text"]?.jsonPrimitive?.contentOrNull ?: v
                    JsSettingOption(value = v, text = t)
                }.orEmpty()
                val defaultVal = obj["default"]?.jsonPrimitive?.contentOrNull
                val validator = obj["validator"]?.jsonPrimitive?.contentOrNull
                val buttonText = obj["buttonText"]?.jsonPrimitive?.contentOrNull
                JsSettingItem(key, title, type.lowercase(), options, defaultVal, validator, buttonText)
            }
            cachedSettingsSchema = schema
            schema
        }
    }

    suspend fun executeSettingCallback(settingKey: String) {
        val jsSource = (source as? JsonContentSource)?.entity ?: return
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)
        val quickJs = createQuickJs()
        quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            cachedJsKey = runCatching { qjs.evaluate<String?>("(()=> (__source && __source.key) || '')()", "<key>") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: cachedJsKey
            initializeSettingsDefaults(qjs)
            qjs.evaluate<Any?>(
                """
				await (async function(){
				  if (!__source || !__source.settings || !__source.settings[${settingKey.jsonStringLiteral()}]) return;
				  const cfg = __source.settings[${settingKey.jsonStringLiteral()}];
				  if (cfg && typeof cfg.callback === 'function') {
				    const r = cfg.callback();
				    if (r && typeof r.then === 'function') await r;
				  }
				})();
                """.trimIndent(),
                "<settings_callback>"
            )
        }
    }

    fun getJsSettingValue(settingKey: String): Any? {
        return loadJsSetting(cachedJsKey ?: (source as? JsonContentSource)?.entity?.id ?: source.name, settingKey)
    }

    fun saveJsSettingValue(settingKey: String, value: Any?) {
        saveJsSetting(cachedJsKey ?: (source as? JsonContentSource)?.entity?.id ?: source.name, settingKey, value)
    }

    fun getJsKey(): String? = cachedJsKey

    private fun createQuickJs(): QuickJs {
        return QuickJs.create(jobDispatcher = Dispatchers.Default).apply {
            maxStackSize = 1L shl 20
            memoryLimit = 64L shl 20
        }
    }

    private data class HtmlStore(
        val document: Document,
        val nodes: MutableMap<Int, Node>,
        var counter: Int,
    ) {
        fun storeNode(node: Node): Int {
            nodes.entries.firstOrNull { it.value === node }?.let { return it.key }
            counter += 1
            nodes[counter] = node
            return counter
        }

        fun getElement(id: Int?): Element? = nodes[id] as? Element
        fun getNode(id: Int?): Node? = nodes[id]
    }

    private fun String.jsonStringLiteral(): String {
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

    private fun List<String>.toJsonArrayLiteral(): String {
        return joinToString(prefix = "[", postfix = "]") { it.jsonStringLiteral() }
    }

    private fun buildExploreSignature(order: SortOrder?, filter: ContentListFilter?): String {
        val query = filter?.query.orEmpty()
        val author = filter?.author.orEmpty()
        val tagKeys = filter?.tags?.map { it.key }?.sorted()?.joinToString(",").orEmpty()
        val orderKey = order?.name.orEmpty()
        return "$orderKey|$query|$author|$tagKeys"
    }

    private fun resetExploreTokens(signature: String) {
        exploreTokenCache[signature] = mutableMapOf(1 to null)
        // reset page size to default
        explorePageSizeCache[signature] = JS_PAGE_SIZE
    }

    private fun extractToken(element: kotlinx.serialization.json.JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element["token"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun currentPageSize(signature: String): Int {
        return explorePageSizeCache[signature] ?: JS_PAGE_SIZE
    }

    private fun updatePageSize(signature: String, count: Int) {
        if (count <= 0) return
        val current = explorePageSizeCache[signature] ?: JS_PAGE_SIZE
        if (count > current) {
            explorePageSizeCache[signature] = count
        }
    }

    private suspend fun ensureFilterOptions() {
        if (lastFilterOptions != null) return
        val jsSource = (source as? JsonContentSource)?.entity ?: return
        val jsContent = jsSource.config
        val preferredClass = classNameRegex.find(jsContent)?.groupValues?.getOrNull(1)
        val quickJs = createQuickJs()
        quickJs.use { qjs ->
            registerSendMessage(qjs)
            qjs.evaluate<Any?>(runtimeBootstrap, "<bootstrap>")
            qjs.evaluate<Any?>(jsContent, "<js-source>")
            instantiateSource(qjs, preferredClass)
            runCatching {
                val metaJson = qjs.evaluate<String?>(
                    """
					(() => {
					  if (typeof __source === 'undefined' || !__source || !__source.explore || !__source.explore[0]) return "{}";
					  const part = __source.explore[0];
					  const res = {};
					  res.title = part.title || (__source && (__source.name || __source.key)) || "";
					  if (Array.isArray(part.tags)) res.tags = part.tags;
					  else if (part.tags && typeof part.tags === 'object') res.tags = Object.values(part.tags);
					  if (Array.isArray(part.sort)) res.sorts = part.sort;
					  else if (Array.isArray(part.sorts)) res.sorts = part.sorts;
					  else if (Array.isArray(part.orders)) res.sorts = part.orders;
					  return JSON.stringify(res);
					})()
                    """.trimIndent()
                )
                metaJson?.let { parseExploreMetaString(it)?.let { meta -> applyFilterOptions(meta); meta.title?.let { cachedExploreTitle = it } } }
            }
            val categoryMeta = runCatching { readCategoryMeta(qjs) }.getOrNull()
            val optionGroups = runCatching { readOptionGroups(qjs) }.getOrDefault(emptyList())
            if (categoryMeta != null || optionGroups.isNotEmpty()) {
                applyFilterOptions(categoryMeta, optionGroups)
            }
        }
    }

    private fun mergeFilterOptions(newTags: Set<ContentTag>, newGroups: List<ContentTagGroup>) {
        val existing = lastFilterOptions
        val tagMap = linkedMapOf<String, ContentTag>()
        existing?.availableTags?.forEach { tagMap[it.key] = it }
        newTags.forEach { tagMap[it.key] = it }

        val groupMap = linkedMapOf<String, MutableSet<ContentTag>>()
        existing?.tagGroups?.forEach { groupMap[it.title] = it.tags.toMutableSet() }
        newGroups.forEach { group ->
            val set = groupMap.getOrPut(group.title) { mutableSetOf() }
            set.addAll(group.tags)
        }
        val mergedGroups = groupMap.map { (title, tags) -> ContentTagGroup(title, tags.toSet()) }

        lastFilterOptions = ContentListFilterOptions(
            availableTags = tagMap.values.toSet(),
            tagGroups = mergedGroups,
        )
    }

    private suspend fun readCategoryMeta(qjs: QuickJs): CategoryMeta? {
        val raw = qjs.evaluate<String?>(
            """
			(() => {
			  if (!__source || !__source.category) return "";
			  const cat = __source.category;
			  const res = { title: cat.title || "", parts: [] };
			  if (Array.isArray(cat.parts)) {
			    cat.parts.forEach((part) => {
			      const name = part && (part.name || part.title) ? String(part.name || part.title) : "";
			      const categories = [];
			      if (Array.isArray(part.categories)) {
			        part.categories.forEach((c, idx) => {
			          if (typeof c === 'string') {
			            const param = Array.isArray(part.categoryParams) ? part.categoryParams[idx] : null;
			            categories.push({ label: c, param: param || null });
			          } else if (c && typeof c === 'object') {
			            const label = c.label || c.name || c.title || "";
			            const param = c.param || (c.target && c.target.attributes && (c.target.attributes.param || c.target.attributes.category)) || null;
			            categories.push({ label: String(label), param: param ? String(param) : null });
			          }
			        });
			      }
			      res.parts.push({ name: String(name), categories: categories });
			    });
			  }
			  return JSON.stringify(res);
			})()
            """.trimIndent()
        ).orEmpty()
        if (raw.isBlank()) return null
        val obj = runCatching { jsonConverter.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val partsJson = obj["parts"] as? JsonArray ?: JsonArray(emptyList())
        val parts = partsJson.mapNotNull { partElem ->
            val partObj = partElem as? JsonObject ?: return@mapNotNull null
            val name = partObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val categories = (partObj["categories"] as? JsonArray)?.mapNotNull { catElem ->
                val catObj = catElem as? JsonObject ?: return@mapNotNull null
                val label = catObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val param = catObj["param"]?.jsonPrimitive?.contentOrNull
                CategoryEntry(label, param)
            }.orEmpty()
            CategoryPart(name, categories)
        }
        if (parts.isEmpty()) return null
        return CategoryMeta(title, parts)
    }

    private suspend fun readOptionGroups(qjs: QuickJs): List<OptionGroup> {
        val raw = qjs.evaluate<String?>(
            """
			(() => {
			  if (!__source || !__source.categoryComics || !Array.isArray(__source.categoryComics.optionList)) return "[]";
			  const list = __source.categoryComics.optionList;
			  const res = [];
			  list.forEach((group, idx) => {
			    const name = group && (group.name || group.title) ? String(group.name || group.title) : `选项${'$'}{idx + 1}`;
			    const opts = [];
			    if (Array.isArray(group.options)) {
			      group.options.forEach((opt) => {
			        const raw = String(opt);
			        const isDefault = raw.startsWith("*");
			        const clean = isDefault ? raw.substring(1) : raw;
			        const valuePart = clean.split("-")[0]; // 即便为空也要保留，避免 "-全部" 变成 "-全部"
			        const parts = clean.split("-");
			        const label = parts.length > 1 ? parts.slice(1).join("-") : clean;
			        opts.push({ label: label, value: (isDefault ? "*" : "") + valuePart, isDefault: isDefault });
			      });
			    }
			    const showWhen = Array.isArray(group.showWhen) ? group.showWhen.map(String) : [];
			    const notShowWhen = Array.isArray(group.notShowWhen) ? group.notShowWhen.map(String) : [];
			    res.push({ title: name, options: opts, showWhen, notShowWhen });
			  });
			  return JSON.stringify(res);
			})()
            """.trimIndent()
        ).orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { jsonConverter.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { groupElem ->
            val groupObj = groupElem as? JsonObject ?: return@mapNotNull null
            val title = groupObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val options = (groupObj["options"] as? JsonArray)?.mapNotNull { optElem ->
                val optObj = optElem as? JsonObject ?: return@mapNotNull null
                val label = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = optObj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val isDefault = optObj["isDefault"]?.jsonPrimitive?.booleanOrNull ?: false
                OptionEntry(label, value, isDefault)
            }.orEmpty()
            val showWhen = (groupObj["showWhen"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val notShowWhen = (groupObj["notShowWhen"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            OptionGroup(title, options, showWhen, notShowWhen)
        }
    }

    private fun resolveCategorySelection(filter: ContentListFilter?, categoryMeta: CategoryMeta?): CategorySelection? {
        if (categoryMeta == null) return null
        // FilterCoordinator 允许多选；分类在 venera 语义上是“单选”，这里取最后一次选中的分类
        val selected = filter?.tags.orEmpty().toList().lastOrNull { it.key.startsWith(TAG_CATEGORY_PREFIX) }?.key
        if (selected != null) {
            val parts = selected.removePrefix(TAG_CATEGORY_PREFIX).split(":")
            val partIdx = parts.getOrNull(0)?.toIntOrNull()
            val catIdx = parts.getOrNull(1)?.toIntOrNull()
            if (partIdx != null && catIdx != null) {
                val part = categoryMeta.parts.getOrNull(partIdx)
                val entry = part?.categories?.getOrNull(catIdx)
                if (entry != null) return CategorySelection(entry.label, entry.param)
            }
        }
        val defaultPart = categoryMeta.parts.firstOrNull()
        val defaultEntry = defaultPart?.categories?.firstOrNull()
        return defaultEntry?.let { CategorySelection(it.label, it.param) }
    }

    private fun resolveOptionSelection(filter: ContentListFilter?, optionGroups: List<OptionGroup>, categoryLabel: String? = null): List<String> {
        if (optionGroups.isEmpty()) return emptyList()
        val selectedMap = mutableMapOf<Int, Int>()
        // option 在 venera 语义上也是“每组单选”，这里用“后写覆盖前写”来近似最后一次选择
        filter?.tags?.forEach { tag ->
            if (!tag.key.startsWith(TAG_OPTION_PREFIX)) return@forEach
            val parts = tag.key.removePrefix(TAG_OPTION_PREFIX).split(":")
            val groupIdx = parts.getOrNull(0)?.toIntOrNull()
            val optIdx = parts.getOrNull(1)?.toIntOrNull()
            if (groupIdx != null && optIdx != null) selectedMap[groupIdx] = optIdx
        }
        val filteredGroups = optionGroups.mapIndexed { originalIdx, g -> originalIdx to g }.filter { (_, g) ->
            val showOk = g.showWhen.isEmpty() || (categoryLabel != null && g.showWhen.contains(categoryLabel))
            val notOk = categoryLabel != null && g.notShowWhen.contains(categoryLabel)
            showOk && !notOk
        }
        if (Log.isLoggable("JsContentRepository", Log.DEBUG)) {
            val filteredIdx = filteredGroups.map { it.first }
            Log.d(
                "JsContentRepository",
                "option select source=${source.name} categoryLabel=$categoryLabel selectedMap=$selectedMap filteredGroupIdx=$filteredIdx"
            )
        }
        return filteredGroups.map { (originalIdx, group) ->
            val optIdx = selectedMap[originalIdx]
            val entry = if (optIdx != null) {
                group.options.getOrNull(optIdx)
            } else {
                group.options.firstOrNull { it.isDefault } ?: group.options.firstOrNull()
            }
            val rawVal = entry?.value ?: ""
            rawVal.trimStart('*')
        }
    }

    companion object {
        private val classNameRegex = Regex("""class\s+([A-Za-z0-9_]+)\s+extends\s+ComicSource""")
        private val jsonConverter = Json { ignoreUnknownKeys = true; isLenient = true }
        private val dataStore = mutableMapOf<String, MutableMap<String, Any?>>()
        private val settingsStore = mutableMapOf<String, MutableMap<String, Any?>>()
        private val htmlStores = mutableMapOf<Int, HtmlStore>()
        private val pageCache = mutableMapOf<PageCacheKey, List<PageEntry>>()
        private val exploreTokenCache = mutableMapOf<String, MutableMap<Int, String?>>()
        private val explorePageSizeCache = mutableMapOf<String, Int>()
        private val explorePageIndexCache = mutableMapOf<String, Int>()
        private const val JS_PAGE_SIZE = 25
        private const val TAG_CATEGORY_PREFIX = "category:"
        private const val TAG_OPTION_PREFIX = "option:"
        private const val CHAPTER_URL_SEPARATOR = "::"
        private const val END_TOKEN = "__END__"

    }
}
