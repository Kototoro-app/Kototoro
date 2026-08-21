package org.skepsun.kototoro.core.parser.tvbox

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.catvod.Proxy as CatVodProxy
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import com.github.catvod.utils.Path
import com.github.tvbox.osc.base.App
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.video.data.VideoLocalCacheProxy
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile

internal class TVBoxJarSpiderRuntime(
    private val source: JsonContentSource,
    private val config: TVBoxStoredConfig,
    private val context: Context,
    private val httpClient: LegadoHttpClient,
    private val videoLocalCacheProxy: VideoLocalCacheProxy,
) : TVBoxSpiderRuntime {

    companion object {
        private const val TAG = "TVBoxJarRuntime"
        private const val TAG_CATEGORY_PREFIX = "tvbox_csp_category:"
        private const val CHAPTER_SCHEME = "tvbox-csp://play"
        private const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val SPIDER_CALL_TIMEOUT_MS = 20_000L
        private const val HOME_CATEGORY_FALLBACK_LIMIT = 5
        private const val HOME_CATEGORY_FALLBACK_TIMEOUT_MS = 6_000L

        private val loadedJars = ConcurrentHashMap<String, LoadedJar>()
        private val runtimes = ConcurrentHashMap.newKeySet<TVBoxJarSpiderRuntime>()
        private val loadMutex = Mutex()

        fun clearAll() {
            runtimes.forEach(TVBoxJarSpiderRuntime::clearRuntimeState)
            loadedJars.clear()
            Log.i(TAG, "Cleared all TVBox JAR runtime state")
        }
    }

    override val id: String = "jar-csp"

    private val spiderMutex = Mutex()
    private val homeMutex = Mutex()
    private val detailMutex = Mutex()
    private val filterOptionsMutex = Mutex()
    private val detailCache = ConcurrentHashMap<String, TVBoxJarDetailResult>()
    private val spiderExecutor by lazy(LazyThreadSafetyMode.NONE) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tvbox-jar-spider-${source.name}").apply {
                isDaemon = true
            }
        }
    }
    private val dynamicEndpointIds = ConcurrentHashMap.newKeySet<String>()

    init {
        runtimes += this
    }

    @Volatile
    private var spiderCache: Spider? = null

    @Volatile
    private var homeCache: TVBoxJarHomeResult? = null

    @Volatile
    private var filterOptionsCache: ContentListFilterOptions? = null

    @Volatile
    private var spiderBinding: SpiderBinding? = null

    override fun describeCapability(config: TVBoxStoredConfig): String {
        return "DexClassLoader(type=3/csp, FongMi-style in-process host runtime)"
    }

    override fun describeUnavailability(config: TVBoxStoredConfig): String? {
        if (!config.site.api.startsWith("csp_", ignoreCase = true)) {
            return "TVBox type=3 source is not a csp_* spider entry"
        }
        val jarSpec = resolveJarSpec()
        if (jarSpec == null) {
            return "TVBox csp source is missing spider/jar locator"
        }
        if (jarSpec.url.isBlank()) {
            return "TVBox csp source has an empty spider/jar locator"
        }
        return null
    }

    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: ContentListFilter?,
    ): List<Content>? {
        val spider = getSpiderOrNull() ?: return null
        val page = offset + 1
        val query = filter?.query?.trim().orEmpty()
        val selectedCategoryId = filter?.tags
            ?.firstNotNullOfOrNull { tag -> parseCategoryTagId(tag.key) }
        return runCatching {
            when {
                query.isNotBlank() && config.site.searchable -> search(spider, query, page)
                selectedCategoryId != null -> loadCategory(spider, selectedCategoryId, page)
                offset == 0 -> {
                    val homeVodItems = loadHomeVod(spider)
                    if (homeVodItems.isNotEmpty()) {
                        homeVodItems.map { it.toContent(source) }
                    } else {
                        loadInitialCategoryFallback(spider, loadHome(spider), page)
                    }
                }
                else -> {
                    loadInitialCategoryFallback(spider, loadHome(spider), page)
                }
            }
        }.onFailure {
            logJarFailure("getList", it)
        }.getOrNull()
    }

    override suspend fun getDetails(manga: Content, forceRefresh: Boolean): Content? {
        val spider = getSpiderOrNull() ?: return null
        return runCatching {
            val detail = loadDetail(spider, manga, forceRefresh) ?: return manga
            detail.toContent(source).copy(
                id = manga.id,
                url = manga.url,
                publicUrl = manga.publicUrl,
            )
        }.onFailure {
            logJarFailure("getDetails", it)
        }.getOrNull()
    }

    override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage>? {
        val spider = getSpiderOrNull() ?: return null
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
            val playResult = loadPlay(spider, locator.flag, locator.id)
            val resolvedUrl = TVBoxPlayback.resolvePlayerUrl(playResult?.url, locator.id) ?: return emptyList()
            val finalUrl = if (resolvedUrl.startsWith("proxy://", ignoreCase = true)) {
                createProxyPlaybackUrl(spider, resolvedUrl)
            } else {
                resolvedUrl
            }
            listOf(
                ContentPage(
                    id = positiveHash("${chapter.url}|page"),
                    url = finalUrl,
                    preview = null,
                    headers = if (finalUrl.startsWith("http://127.0.0.1:", ignoreCase = true) || finalUrl.startsWith("http://localhost:", ignoreCase = true)) {
                        emptyMap()
                    } else {
                        buildHeadersForUrl(finalUrl, playResult?.headers.orEmpty())
                    },
                    source = source,
                ),
            )
        }.onFailure {
            logJarFailure("getPages", it)
        }.getOrNull()
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions? {
        filterOptionsCache?.let { return it }
        val spider = getSpiderOrNull() ?: return null
        return filterOptionsMutex.withLock {
            filterOptionsCache?.let { return it }
            runCatching {
                val home = loadHome(spider)
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
                logJarFailure("getFilterOptions", it)
            }.getOrNull()?.also {
                filterOptionsCache = it
            }
        }
    }

    override suspend fun executeAction(action: String): TVBoxActionResult? {
        val spider = getSpiderOrNull() ?: return null
        return runCatching {
            val raw = invokeSpider("action(length=${action.length})") {
                spider.action(action)
            }.orEmpty()
            TVBoxActionResult.parse(raw)
        }.onFailure {
            logJarFailure("action", it)
        }.getOrNull()
    }

    override fun getRequestHeaders(): Map<String, String>? {
        return config.site.staticHeaders.takeIf { it.isNotEmpty() }
    }

    private suspend fun getSpiderOrNull(): Spider? {
        spiderCache?.let { return it }
        return spiderMutex.withLock {
            spiderCache?.let { return it }
            createSpider().also { spiderCache = it }
        }
    }

    private fun clearRuntimeState() {
        runCatching { spiderCache?.destroy() }
            .onFailure { Log.w(TAG, "Unable to destroy TVBox spider for ${source.name}", it) }
        spiderCache = null
        spiderBinding = null
        homeCache = null
        filterOptionsCache = null
        detailCache.clear()
        dynamicEndpointIds.forEach(videoLocalCacheProxy::unregisterDynamicEndpoint)
        dynamicEndpointIds.clear()
    }

    private suspend fun createSpider(): Spider? = withContext(Dispatchers.IO) {
        if (!config.site.api.startsWith("csp_", ignoreCase = true)) {
            return@withContext null
        }
        val loadedJar = ensureLoadedJar() ?: return@withContext null
        val bridgeApp = App.getInstance()
        val className = "com.github.catvod.spider.${config.site.api.removePrefix("csp_")}"
        Log.i(TAG, "Creating TVBox spider instance for ${source.name}: class=$className jar=${loadedJar.spec.url}")
        val spider = runCatching {
            withJarEnvironment(loadedJar.classLoader, loadedJar.initEndpoint) {
                loadedJar.classLoader.loadClass(className)
                    .getDeclaredConstructor()
                    .newInstance() as? Spider
            }
        }.getOrElse {
            logJarFailure("instantiate", it, "class=$className")
            null
        } ?: return@withContext null
        spider.siteKey = config.site.key
        val binding = registerSpiderBinding(loadedJar, spider)
        spiderBinding = binding
        runCatching {
            withJarEnvironment(binding) {
                spider.initApi(
                    SpiderApi(
                        binding.endpoint.localUrl,
                        binding.endpoint.lanUrl ?: binding.endpoint.localUrl,
                        binding.endpoint.port.toString(),
                    ),
                )
            }
        }.onFailure {
            logJarFailure("initApi", it)
            logReflectiveFailure("initApi", it)
            Log.w(TAG, "Optional TVBox SpiderApi initialization failed for ${source.name}; continuing with Spider.init", it)
        }
        val extLiteral = buildExtLiteral()
        runCatching {
            withJarEnvironment(binding) {
                spider.init(bridgeApp, extLiteral)
            }
        }.recoverCatching {
            withJarEnvironment(binding) {
                spider.init(bridgeApp)
            }
        }.onFailure {
            spiderBinding = null
            logJarFailure("init", it)
            logReflectiveFailure("init", it)
            return@withContext null
        }
        withJarEnvironment(binding) {
            logSpiderShellState(spider, "after-init")
        }
        spider
    }

    private suspend fun ensureLoadedJar(): LoadedJar? = withContext(Dispatchers.IO) {
        val jarSpec = resolveJarSpec() ?: return@withContext null
        val cacheKey = jarSpec.cacheKey
        loadedJars[cacheKey]?.let { return@withContext it }
        loadMutex.withLock {
            loadedJars[cacheKey]?.let { return@withLock it }
            App.init(context)
            val bridgeApp = App.getInstance()
            TVBoxHiddenApiCompat.enableActivityLookup()
            val cacheDir = Path.jar()
            val cachePath = cacheDir.absolutePath
            val jarFile = Path.jar(jarSpec.url)
            Log.i(TAG, "Preparing TVBox spider jar for ${source.name}: url=${jarSpec.url} cache=${jarFile.absolutePath}")
            if (!isUsableJarCache(jarFile, jarSpec.md5)) {
                downloadJar(jarSpec, jarFile)
            }
            // FongMi only uses ;md5; to validate an existing cache. A remote
            // JAR may legitimately have been replaced while its old config
            // remains published, so a valid downloaded ZIP must still be tried.
            if (!isLoadableJarFile(jarFile)) {
                logJarFailure("loadJar", null, "cache_unusable=${jarFile.absolutePath}")
                return@withLock null
            }
            Log.i(
                TAG,
                "TVBox spider jar fingerprint for ${source.name}: sha256=${jarFile.sha256Hex()} url=${jarSpec.url}",
            )
            prepareJarForLoading(jarFile)
            val classLoader = DexClassLoader(
                jarFile.absolutePath,
                cachePath,
                cachePath,
                bridgeApp.classLoader,
            )
            val proxyMethod = runCatching {
                classLoader.loadClass("com.github.catvod.spider.Proxy")
                    .getMethod("proxy", Map::class.java)
            }.getOrNull()
            lateinit var endpoint: VideoLocalCacheProxy.DynamicEndpoint
            val endpointId = "tvbox-jar|$cacheKey"
            dynamicEndpointIds += endpointId
            endpoint = videoLocalCacheProxy.registerDynamicEndpoint(endpointId) { request ->
                withJarEnvironment(classLoader, endpoint) {
                    handleProxyRequest(request, emptyMap(), null, proxyMethod)
                }
            }
            // FongMi/TVBoxOSC inject the local control-server port into both the
            // host bridge and the loaded JAR before Init/Spider code runs. Native
            // spiders commonly read Proxy.getPort() directly instead of using the
            // newer thread-local endpoint API.
            CatVodProxy.set(endpoint.port)
            withJarEnvironment(classLoader, endpoint) {
                setJarProxyPort(classLoader, endpoint.port)
            }
            withJarEnvironment(classLoader, endpoint) {
                initializeJar(classLoader, jarFile, bridgeApp)
            }
            withJarEnvironment(classLoader, endpoint) {
                logJarInitState(classLoader, "after-init")
            }
            LoadedJar(
                spec = jarSpec,
                classLoader = classLoader,
                proxyMethod = proxyMethod,
                initEndpoint = endpoint,
            ).also { loadedJars[cacheKey] = it }
        }
    }

    private fun isUsableJarCache(file: File, expectedMd5: String?): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            return false
        }
        if (!expectedMd5.isNullOrBlank()) {
            return file.md5Hex().equals(expectedMd5, ignoreCase = true)
        }
        return System.currentTimeMillis() - file.lastModified() <= CACHE_MAX_AGE_MS
    }

    private fun isLoadableJarFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            return false
        }
        return runCatching { ZipFile(file).use { it.entries().hasMoreElements() } }.getOrDefault(false)
    }

    private suspend fun downloadJar(spec: JarSpec, destination: File) {
        val response = httpClient.get(spec.url, buildHeadersForUrl(spec.url, emptyMap()), source)
        try {
            if (!response.isSuccessful) {
                throw IllegalArgumentException("HTTP ${response.code} when loading TVBox spider jar")
            }
            val bytes = response.body?.bytes()
                ?: throw IllegalArgumentException("TVBox spider jar response body is empty")
            destination.parentFile?.mkdirs()
            if (destination.exists() && !destination.setWritable(true, true)) {
                Log.w(TAG, "Unable to mark TVBox spider jar writable before overwrite: ${destination.absolutePath}")
            }
            destination.writeBytes(bytes)
            Log.i(TAG, "Downloaded TVBox spider jar for ${source.name}: bytes=${bytes.size} file=${destination.absolutePath}")
            if (!spec.md5.isNullOrBlank() && !destination.md5Hex().equals(spec.md5, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "TVBox spider jar MD5 mismatch for ${source.name}: expected=${spec.md5}, actual=${destination.md5Hex()}, url=${spec.url}",
                )
            }
            prepareJarForLoading(destination)
        } finally {
            response.close()
        }
    }

    private fun prepareJarForLoading(file: File) {
        if (!file.exists()) {
            return
        }
        file.setReadable(true, true)
        file.setExecutable(false, false)
        if (!file.setWritable(false, false) && !file.setReadOnly()) {
            Log.w(TAG, "Unable to mark TVBox spider jar read-only: ${file.absolutePath}")
        }
    }

    private suspend fun loadHome(spider: Spider): TVBoxJarHomeResult {
        homeCache?.let { return it }
        return homeMutex.withLock {
            homeCache?.let { return it }
            val raw = invokeSpider("homeContent(true)") {
                spider.homeContent(true)
            }.orEmpty()
            parseHomeResult(raw).also { homeCache = it }
        }
    }

    private suspend fun loadHomeVod(spider: Spider): List<TVBoxJarVodItem> {
        val raw = invokeSpider("homeVideoContent()") {
            spider.homeVideoContent()
        }.orEmpty()
        return parseVodList(raw)
    }

    private suspend fun loadCategory(
        spider: Spider,
        categoryId: String,
        page: Int,
        timeoutMs: Long = SPIDER_CALL_TIMEOUT_MS,
    ): List<Content> {
        Log.i(TAG, "Loading TVBox category for ${source.name}: categoryId=$categoryId page=$page")
        val raw = invokeSpider(
            action = "categoryContent(tid=$categoryId, pg=$page)",
            timeoutMs = timeoutMs,
        ) {
            spider.categoryContent(categoryId, page.toString(), true, hashMapOf())
        }.orEmpty()
        return parseVodList(raw).map { it.toContent(source) }
    }

    private suspend fun loadInitialCategoryFallback(
        spider: Spider,
        home: TVBoxJarHomeResult,
        page: Int,
    ): List<Content> {
        val categories = home.categories.take(HOME_CATEGORY_FALLBACK_LIMIT)
        if (categories.isEmpty()) {
            Log.i(TAG, "TVBox home has no categories for ${source.name}")
            return emptyList()
        }
        categories.forEach { category ->
            Log.i(TAG, "Trying fallback category for ${source.name}: categoryId=${category.id} name=${category.name} page=$page")
            val items = loadCategory(
                spider = spider,
                categoryId = category.id,
                page = page,
                timeoutMs = HOME_CATEGORY_FALLBACK_TIMEOUT_MS,
            )
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

    private suspend fun search(spider: Spider, query: String, page: Int): List<Content> {
        val raw = invokeSpider("searchContent(query=$query, page=$page)") {
            runCatching {
                spider.searchContent(query, false, page.toString())
            }.getOrElse {
                spider.searchContent(query, false)
            }
        }.orEmpty()
        val items = parseVodList(raw)
        Log.d(
            TAG,
            "TVBox jar search result for ${source.name}: query=$query page=$page total=${items.size} covers=${
                items.joinToString(limit = 5) { "${it.title}=>${it.coverUrl ?: "<null>"}" }
            }",
        )
        return items.map { it.toContent(source) }
    }

    private suspend fun loadDetail(
        spider: Spider,
        manga: Content,
        forceRefresh: Boolean = false,
    ): TVBoxJarDetailResult? {
        val itemId = (manga.url ?: manga.publicUrl).orEmpty().ifBlank { manga.id.toString() }
        if (!forceRefresh) detailCache[itemId]?.let { return it }
        return detailMutex.withLock {
            if (!forceRefresh) detailCache[itemId]?.let { return it }
            val raw = invokeSpider("detailContent(ids=$itemId)") {
                spider.detailContent(listOf(itemId))
            }.orEmpty()
            (parseDetailResult(raw, itemId) ?: buildFallbackDetailResult(raw, manga))
                ?.also { detailCache[itemId] = it }
        }
    }

    private suspend fun loadPlay(spider: Spider, flag: String, id: String): TVBoxJarPlayResult? {
        val raw = invokeSpider("playerContent(flag=$flag, id=$id)") {
            spider.playerContent(flag, id, emptyList())
        }.orEmpty()
        return parsePlayResult(raw)
    }

    private fun createProxyPlaybackUrl(spider: Spider, proxySpec: String): String {
        val params = parseProxyParams(proxySpec)
        val dynamicId = "${source.name}|${params.toSortedMap()}"
        val binding = spiderBinding ?: return proxySpec
        dynamicEndpointIds += dynamicId
        lateinit var endpoint: VideoLocalCacheProxy.DynamicEndpoint
        endpoint = videoLocalCacheProxy.registerDynamicEndpoint(dynamicId) { request ->
            withJarEnvironment(binding.loadedJar.classLoader, endpoint) {
                handleProxyRequest(request, params, spider, binding.loadedJar.proxyMethod)
            }
        }
        return endpoint.localUrl
    }

    private fun registerSpiderBinding(loadedJar: LoadedJar, spider: Spider): SpiderBinding {
        val endpointId = "tvbox-spider|${source.entity.id}|${config.site.key}|${loadedJar.spec.cacheKey}"
        dynamicEndpointIds += endpointId
        lateinit var endpoint: VideoLocalCacheProxy.DynamicEndpoint
        endpoint = videoLocalCacheProxy.registerDynamicEndpoint(
            endpointId,
        ) { request ->
            withJarEnvironment(loadedJar.classLoader, endpoint) {
                handleProxyRequest(request, emptyMap(), spider, loadedJar.proxyMethod)
            }
        }
        return SpiderBinding(loadedJar, endpoint)
    }

    private fun handleProxyRequest(
        request: VideoLocalCacheProxy.DynamicRequest,
        initialParams: Map<String, String>,
        spider: Spider?,
        proxyMethod: Method?,
    ): VideoLocalCacheProxy.DynamicResponse {
        val mergedParams = LinkedHashMap<String, String>()
        mergedParams.putAll(initialParams)
        mergedParams.putAll(request.queryParameters)
        mergedParams.putAll(request.headers)
        mergedParams["request-headers"] = JSONObject(request.headers).toString()
        val result = when {
            mergedParams.containsKey("do") && spider != null -> runCatching { spider.proxyLocal(mergedParams) }.getOrNull()
            mergedParams.containsKey("go") -> runCatching { invokeStaticProxy(proxyMethod, mergedParams) }.getOrNull()
            else -> null
        }
        return result.toDynamicResponse()
    }

    private fun invokeStaticProxy(proxyMethod: Method?, params: Map<String, String>): Array<Any?>? {
        if (proxyMethod == null) return null
        val result = proxyMethod.invoke(null, params)
        return if (result is Array<*>) {
            arrayOfNulls<Any?>(result.size).also { array ->
                result.indices.forEach { index -> array[index] = result[index] }
            }
        } else {
            null
        }
    }

    private fun parseProxyParams(proxySpec: String): Map<String, String> {
        val raw = proxySpec.removePrefix("proxy://")
        val uri = Uri.parse("http://127.0.0.1/proxy?$raw")
        return buildMap {
            uri.queryParameterNames.forEach { name ->
                val value = uri.getQueryParameter(name)
                if (!value.isNullOrBlank()) {
                    put(name, value)
                }
            }
        }
    }

    private fun Array<Any?>?.toDynamicResponse(): VideoLocalCacheProxy.DynamicResponse {
        if (this == null || isEmpty()) {
            return VideoLocalCacheProxy.DynamicResponse(
                statusCode = 500,
                contentType = "text/plain; charset=utf-8",
                body = "TVBox proxy returned empty result".toByteArray(Charsets.UTF_8),
            )
        }
        val statusCode = (getOrNull(0) as? Number)?.toInt() ?: 500
        val contentType = getOrNull(1)?.toString().orEmpty().ifBlank { "application/octet-stream" }
        val rawBody = getOrNull(2)
        val headers = (getOrNull(3) as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                val headerKey = key?.toString()?.trim().orEmpty()
                val headerValue = value?.toString()?.trim().orEmpty()
                if (headerKey.isBlank() || headerValue.isBlank()) {
                    null
                } else {
                    headerKey to headerValue
                }
            }
            ?.toMap()
            .orEmpty()
        val redirectUrl = headers.entries.firstNotNullOfOrNull { (key, value) ->
            value.takeIf { key.equals("Location", ignoreCase = true) }
        }
        val bodyStream = rawBody as? InputStream
        val bodyBytes = if (bodyStream == null) {
            when (rawBody) {
                null -> ByteArray(0)
                is ByteArray -> rawBody
                else -> rawBody.toString().toByteArray(Charsets.UTF_8)
            }
        } else {
            ByteArray(0)
        }
        return VideoLocalCacheProxy.DynamicResponse(
            statusCode = statusCode,
            contentType = contentType,
            headers = headers,
            body = if (redirectUrl != null) ByteArray(0) else bodyBytes,
            bodyStream = if (redirectUrl != null) null else bodyStream,
            redirectUrl = redirectUrl,
        )
    }

    private fun resolveJarSpec(): JarSpec? {
        val rawValue = config.site.jar?.takeIf { it.isNotBlank() }
            ?: config.root.spider?.takeIf { it.isNotBlank() }
            ?: return null
        val trimmed = rawValue.trim()
        val md5Index = trimmed.indexOf(";md5;", ignoreCase = true)
        val pkIndex = trimmed.indexOf(";pk;", ignoreCase = true)
        val cutIndex = listOf(md5Index, pkIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: -1
        val urlPart = if (cutIndex >= 0) trimmed.substring(0, cutIndex).trim() else trimmed
        val md5 = if (md5Index >= 0) {
            trimmed.substring(md5Index + 5).substringBefore(';').trim().ifBlank { null }
        } else {
            null
        }
        val resolvedUrl = resolveCandidateUrl(urlPart) ?: return null
        return JarSpec(
            raw = rawValue,
            url = resolvedUrl,
            md5 = md5,
            cacheKey = resolvedUrl.md5Hex(),
        )
    }

    private fun buildExtLiteral(): String {
        val ext = config.site.ext ?: return ""
        return when (ext) {
            is String -> resolveSpiderExt(ext)
            is JSONObject -> ext.toString()
            is JSONArray -> ext.toString()
            else -> ext.toString()
        }
    }

    private fun resolveSpiderExt(ext: String): String {
        val value = ext.trim()
        val isLocator = value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("content://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true) ||
            value.startsWith("//") ||
            value.startsWith("./") ||
            value.startsWith("../") ||
            value.startsWith("/")
        return if (isLocator) resolveCandidateUrl(value) ?: value else ext
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

    private fun resolveCandidateUrl(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty().extractPrimaryLocator()
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
        return key.takeIf { it.startsWith(TAG_CATEGORY_PREFIX) }
            ?.removePrefix(TAG_CATEGORY_PREFIX)
            ?.ifBlank { null }
    }

    private fun buildChapterUrl(flag: String, id: String): String {
        return Uri.parse(CHAPTER_SCHEME).buildUpon()
            .appendQueryParameter("flag", flag)
            .appendQueryParameter("id", id)
            .build()
            .toString()
    }

    private fun parseChapterLocator(url: String): TVBoxJarChapterLocator? {
        val uri = Uri.parse(url)
        if (uri.scheme != "tvbox-csp") {
            return null
        }
        val flag = uri.getQueryParameter("flag").orEmpty().ifBlank { return null }
        val id = uri.getQueryParameter("id").orEmpty().ifBlank { return null }
        return TVBoxJarChapterLocator(flag = flag, id = id)
    }

    private fun parseHomeResult(raw: String): TVBoxJarHomeResult {
        val root = raw.toJsonValue() as? JSONObject ?: return TVBoxJarHomeResult(emptyList())
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            Log.w(TAG, "TVBox jar home error for ${source.name}: $it")
        }
        val categories = root.optJSONArray("class")
            ?.toObjectList()
            ?.mapNotNull { item ->
                val id = item.firstNonBlank("type_id", "id", "typeId") ?: return@mapNotNull null
                val name = item.firstNonBlank("type_name", "name", "title") ?: id
                TVBoxJarCategory(id = id, name = name)
            }
            .orEmpty()
        return TVBoxJarHomeResult(categories = categories)
    }

    private fun parseVodList(raw: String): List<TVBoxJarVodItem> {
        val jsonValue = raw.toJsonValue() ?: return emptyList()
        return when (jsonValue) {
            is JSONObject -> {
                jsonValue.optString("error").takeIf { it.isNotBlank() }?.let {
                    Log.w(TAG, "TVBox jar list error for ${source.name}: $it")
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

    private fun parseVodItem(node: JSONObject, requestedId: String = ""): TVBoxJarVodItem? {
        val action = node.firstNonBlank("action")
        val itemId = TVBoxPlayback.resolveDetailItemId(
            explicitId = node.firstNonBlank("vod_id", "id", "vodId", "url"),
            requestedId = requestedId,
        ) ?: action?.let { "tvbox-action:${it.hashCode()}" } ?: return null
        val title = node.firstNonBlank("vod_name", "title", "name") ?: itemId
        if (action != null || title.contains("配置")) {
            Log.i(
                TAG,
                "TVBox configuration item for ${source.name}: title=$title id=$itemId " +
                    "action=${action != null} actionLength=${action?.length ?: 0} keys=${node.keys().asSequence().sorted().joinToString()}",
            )
        }
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
            node.firstNonBlank("vod_content", "content", "vod_blurb")?.let { append(it) }
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
        return TVBoxJarVodItem(
            id = positiveHash("${source.name}|$itemId|$title"),
            itemId = itemId,
            title = title,
            coverUrl = cover,
            description = description,
            tags = tags,
            action = action,
        )
    }

    private fun parseDetailResult(raw: String, requestedId: String): TVBoxJarDetailResult? {
        val jsonValue = raw.toJsonValue() ?: return null
        val root = when (jsonValue) {
            is JSONObject -> jsonValue
            is JSONArray -> JSONObject().put("list", jsonValue)
            else -> return null
        }
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            Log.w(TAG, "TVBox jar detail error for ${source.name}: $it")
        }
        val itemNode = when {
            (root.optJSONArray("list")?.length() ?: 0) > 0 -> root.optJSONArray("list")?.optJSONObject(0)
            (root.optJSONObject("data")?.optJSONArray("list")?.length() ?: 0) > 0 -> root.optJSONObject("data")?.optJSONArray("list")?.optJSONObject(0)
            root.has("vod_id") || root.has("vod_name") -> root
            else -> null
        } ?: return null
        val item = parseVodItem(itemNode, requestedId) ?: return null
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
        return TVBoxJarDetailResult(item = item, chapters = chapters)
    }

    private fun buildFallbackDetailResult(raw: String, seed: Content): TVBoxJarDetailResult? {
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
        val item = TVBoxJarVodItem(
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
        return TVBoxJarDetailResult(item = item, chapters = chapters)
    }

    private fun parsePlaySources(node: JSONObject): List<TVBoxJarPlaySource> {
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
                        1 -> TVBoxJarPlayItem(
                            title = "${flag} ${safeHashIndex(index, clean)}",
                            id = parts[0].trim(),
                        )
                        else -> TVBoxJarPlayItem(
                            title = parts[0].trim().ifBlank { "${flag} ${safeHashIndex(index, clean)}" },
                            id = parts[1].trim(),
                        )
                    }
                }
            if (items.isEmpty()) null else TVBoxJarPlaySource(flag = flag, items = items)
        }
    }

    private fun parsePlayResult(raw: String): TVBoxJarPlayResult? {
        val root = raw.toJsonValue() as? JSONObject ?: return null
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            Log.w(TAG, "TVBox jar play error for ${source.name}: $it")
        }
        val url = root.firstNonBlank("url", "playUrl", "realUrl") ?: return null
        return TVBoxJarPlayResult(
            url = url,
            headers = root.optHeaderMapFlexible("header").ifEmpty { root.optHeaderMapFlexible("headers") },
        )
    }

    private fun safeHashIndex(groupIndex: Int, raw: String): String {
        return positiveHash("$groupIndex|$raw").toString()
    }

    private fun positiveHash(raw: String): Long {
        return raw.hashCode().toLong() and 0x7fffffffL
    }

    private suspend fun invokeSpider(
        action: String,
        timeoutMs: Long = SPIDER_CALL_TIMEOUT_MS,
        block: () -> String,
    ): String? {
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "TVBox spider call start for ${source.name}: $action")
            runCatching { executeSpiderCall(action, timeoutMs, block) }
                .onSuccess { result ->
                    Log.i(
                        TAG,
                        "TVBox spider call succeeded for ${source.name}: $action, resultLength=${result.length}, preview=${result.take(160)}",
                    )
                }
                .onFailure { error ->
                    Log.w(TAG, "TVBox spider call failed for ${source.name}: $action", error)
                    logReflectiveFailure(action, error)
                }
                .getOrNull()
        }
    }

    private fun executeSpiderCall(action: String, timeoutMs: Long, block: () -> String): String {
        var future: Future<String>? = null
        try {
            future = spiderExecutor.submit<String> {
                val binding = spiderBinding
                if (binding == null) block() else withJarEnvironment(binding, block)
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future?.cancel(true)
            Log.e(TAG, "TVBox spider call timed out for ${source.name}: $action after ${timeoutMs}ms")
            throw error
        } catch (error: Throwable) {
            future?.cancel(true)
            throw error
        }
    }

    private fun initializeJar(classLoader: DexClassLoader, jarFile: File, bridgeApp: Context) {
        runCatching {
            val initClass = classLoader.loadClass("com.github.catvod.spider.Init")
            if (TVBoxProtectedInitDetector.isProtected(jarFile, App.getInstance().realPackageName)) {
                Log.i(TAG, "Using protected TVBox Init strategy for ${source.name}")
                initializeProtectedJar(initClass, bridgeApp)
            } else {
                Log.i(TAG, "Using standard TVBox Init strategy for ${source.name}")
                initClass.getMethod("init", Context::class.java).invoke(null, bridgeApp)
            }
        }.onFailure {
            logReflectiveFailure("initializeJar", it)
            Log.w(TAG, "Unable to initialize TVBox jar for ${source.name}", it)
        }
    }

    private fun initializeProtectedJar(initClass: Class<*>, bridgeApp: Context) {
        val init = runCatching {
            requireNotNull(initClass.getMethod("get").invoke(null)).also { instance ->
                TVBoxProtectedInitCompat.seedContext(instance, bridgeApp)
            }
        }.onFailure {
            Log.w(TAG, "Unable to seed protected TVBox Init context for ${source.name}", it)
        }.getOrNull()
        if (init != null) {
            runCatching {
                TVBoxProtectedInitCompat.loadNativeLibraries(init)
            }.onFailure {
                Log.w(TAG, "Unable to load protected TVBox Init native libraries for ${source.name}", it)
            }
        }
        runCatching {
            initClass.getMethod("replaceCloudDiskNames").invoke(null)
        }.onFailure {
            Log.d(TAG, "Protected TVBox Init has no replaceCloudDiskNames hook for ${source.name}")
        }
        runCatching {
            initClass.getMethod("startGoProxy", Context::class.java).invoke(null, bridgeApp)
        }.onFailure {
            Log.d(TAG, "Protected TVBox Init has no startGoProxy hook for ${source.name}")
        }
    }

    private fun setJarProxyPort(classLoader: DexClassLoader, port: Int) {
        runCatching {
            classLoader.loadClass("com.github.catvod.Proxy")
                .getMethod("set", Int::class.javaPrimitiveType)
                .invoke(null, port)
        }.onFailure {
            Log.d(TAG, "TVBox jar has no static Proxy.set port hook for ${source.name}")
        }
}

    private fun collectClassHierarchyFields(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields
            current = current.superclass
        }
        return fields
    }

    private inline fun <T> withContextClassLoader(classLoader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private inline fun <T> withJarEnvironment(binding: SpiderBinding, block: () -> T): T {
        return withJarEnvironment(binding.loadedJar.classLoader, binding.endpoint, block)
    }

    private inline fun <T> withJarEnvironment(
        classLoader: ClassLoader,
        endpoint: VideoLocalCacheProxy.DynamicEndpoint,
        block: () -> T,
    ): T {
        return withContextClassLoader(classLoader) {
            val previous = CatVodProxy.getEndpoint()
            val previousHostIdentity = App.enterHostIdentity()
            CatVodProxy.setEndpoint(endpoint.localUrl, endpoint.lanUrl)
            try {
                block()
            } finally {
                App.restoreHostIdentity(previousHostIdentity)
                if (previous == null) {
                    CatVodProxy.clearEndpoint()
                } else {
                    CatVodProxy.setEndpoint(previous[0], previous[1])
                }
            }
        }
    }

    private fun logReflectiveFailure(action: String, error: Throwable) {
        when (error) {
            is InvocationTargetException -> {
                val target = error.targetException ?: error.cause
                Log.e(
                    TAG,
                    "TVBox spider reflective target failure for ${source.name}: action=$action, target=${target?.javaClass?.name}, message=${target?.message}",
                    target ?: error,
                )
            }
            is NoClassDefFoundError -> {
                Log.e(
                    TAG,
                    "TVBox spider missing class for ${source.name}: action=$action, message=${error.message}",
                    error,
                )
            }
            is ClassNotFoundException -> {
                Log.e(
                    TAG,
                    "TVBox spider class not found for ${source.name}: action=$action, message=${error.message}",
                    error,
                )
            }
        }
        error.cause?.let { cause ->
            if (cause !== error) {
                logReflectiveFailure("$action.cause", cause)
            }
        }
    }

    private fun logJarFailure(action: String, error: Throwable?, detail: String? = null) {
        TVBoxRuntimeDiagnostics.logFailure(
            tag = TAG,
            sourceName = source.name,
            runtimeId = id,
            action = action,
            category = TVBoxRuntimeDiagnostics.classifyJar(error, action),
            error = error,
            detail = detail,
        )
    }

    private fun logSpiderShellState(spider: Spider, stage: String) {
        runCatching {
            val spiderFields = collectClassHierarchyFields(spider.javaClass)
                .filter { Spider::class.java.isAssignableFrom(it.type) }
            if (spiderFields.isEmpty()) {
                return@runCatching
            }
            val states = spiderFields.mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    val value = field.get(spider) as? Spider
                    val state = value?.javaClass?.name ?: "null"
                    "${field.declaringClass.simpleName}.${field.name}=$state"
                }.getOrNull()
            }
            if (states.isNotEmpty()) {
                Log.i(
                    TAG,
                    "TVBox spider shell state for ${source.name}: stage=$stage class=${spider.javaClass.name} ${states.joinToString()}",
                )
            }
        }.onFailure {
            Log.w(TAG, "Unable to inspect TVBox spider shell state for ${source.name}: stage=$stage", it)
        }
    }

    private fun logJarInitState(classLoader: DexClassLoader, stage: String) {
        runCatching {
            val initClass = classLoader.loadClass("com.github.catvod.spider.Init")
            val contextValue = runCatching {
                initClass.getDeclaredMethod("context").apply { isAccessible = true }.invoke(null)
            }.getOrNull()
            val loaderValue = runCatching {
                initClass.getDeclaredMethod("loader").apply { isAccessible = true }.invoke(null)
            }.getOrNull()
            val classLoaderValue = runCatching {
                initClass.getDeclaredMethod("classLoader").apply { isAccessible = true }.invoke(null)
            }.getOrNull()
            Log.i(
                TAG,
                "TVBox jar Init state for ${source.name}: stage=$stage context=${contextValue?.javaClass?.name ?: "null"} loader=${loaderValue?.javaClass?.name ?: "null"} classLoader=${classLoaderValue?.javaClass?.name ?: "null"}",
            )
        }.onFailure {
            Log.w(TAG, "Unable to inspect TVBox jar Init state for ${source.name}: stage=$stage", it)
        }
    }

    private data class JarSpec(
        val raw: String,
        val url: String,
        val md5: String?,
        val cacheKey: String,
    )

    private data class LoadedJar(
        val spec: JarSpec,
        val classLoader: DexClassLoader,
        val proxyMethod: Method?,
        val initEndpoint: VideoLocalCacheProxy.DynamicEndpoint,
    )

    private data class SpiderBinding(
        val loadedJar: LoadedJar,
        val endpoint: VideoLocalCacheProxy.DynamicEndpoint,
    )

    private data class TVBoxJarHomeResult(
        val categories: List<TVBoxJarCategory>,
    )

    private data class TVBoxJarCategory(
        val id: String,
        val name: String,
    )

    private data class TVBoxJarVodItem(
        val id: Long,
        val itemId: String,
        val title: String,
        val coverUrl: String?,
        val description: String?,
        val tags: Set<ContentTag>,
        val action: String? = null,
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
            sourceData = action?.let(TVBoxActionMetadata::encode),
        )
    }

    private data class TVBoxJarDetailResult(
        val item: TVBoxJarVodItem,
        val chapters: List<ContentChapter>,
    ) {
        fun toContent(source: JsonContentSource): Content = item.toContent(source).copy(chapters = chapters)
    }

    private data class TVBoxJarPlaySource(
        val flag: String,
        val items: List<TVBoxJarPlayItem>,
    )

    private data class TVBoxJarPlayItem(
        val title: String,
        val id: String,
    )

    private data class TVBoxJarPlayResult(
        val url: String,
        val headers: Map<String, String>,
    )

    private data class TVBoxJarChapterLocator(
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

private fun String.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun File.md5Hex(): String {
    return digestHex("MD5")
}

private fun File.sha256Hex(): String {
    return digestHex("SHA-256")
}

private fun File.digestHex(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
