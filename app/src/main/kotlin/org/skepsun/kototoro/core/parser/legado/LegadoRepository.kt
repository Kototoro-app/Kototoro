package org.skepsun.kototoro.core.parser.legado

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.json.JSONObject
import okhttp3.Response
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoRuntimeBridge
import org.skepsun.kototoro.core.parser.legado.book.BookChapterList
import org.skepsun.kototoro.core.parser.legado.book.BookContent
import org.skepsun.kototoro.core.parser.legado.book.BookInfo
import org.skepsun.kototoro.core.parser.legado.book.BookList
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoListRuntime
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoRuntimeState
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoCookieStore
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoVariableStore
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext
import org.skepsun.kototoro.parsers.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.Charset
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.cache.SafeDeferred
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers

import java.util.*
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Main repository implementation for Legado JSON sources.
 * Orchestrates modular components for search, details, TOC, and content parsing.
 * 
 * Target size: ~200 lines (Refactored from 1875 lines)
 */
class LegadoRepository(
    override val source: ContentSource,
    private val httpClient: LegadoHttpClient,
    private val jsEngine: JavaScriptEngine,
    private val memoryCache: MemoryContentCache? = null,
    private val browserLauncher: org.skepsun.kototoro.core.javascript.BrowserLauncher? = null,
    private val legadoPrefs: android.content.SharedPreferences? = null
) : ContentRepository, org.skepsun.kototoro.parsers.ContentParserAuthProvider {

    companion object {
        private const val TAG = "LegadoRepository"
        private const val MAX_AUTO_TOC_PAGES = 200
        private const val PREF_DEBUG_STANDALONE_LIST_RUNTIME = "debug_standalone_legado_list_runtime"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            allowTrailingComma = true
        }
    }

    private val jsonSource: JsonContentSource = source as JsonContentSource
    private val config: LegadoBookSource by lazy {
        json.decodeFromString<LegadoBookSource>(jsonSource.entity.config)
    }

    private val runtimeBridge by lazy {
        val variableStore = legadoPrefs?.let(::KototoroLegadoVariableStore)
        val cookieStore = KototoroLegadoCookieStore(
            org.skepsun.kototoro.core.javascript.LegadoCookieAPI(
                org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar(httpClient.getCookieJar()),
            ),
        )
        KototoroLegadoRuntimeBridge(
            httpExecutor = httpExecutor,
            cookieStore = cookieStore,
            variableStore = variableStore ?: error("LegadoRepository requires SharedPreferences-backed variable store"),
            logger = org.skepsun.kototoro.core.parser.legado.runtime.NoOpLegadoRuntimeLogger,
        )
    }

    private val sandbox: LegadoSandbox by lazy {
        LegadoSandbox(
            jsEngine = jsEngine,
            httpClient = httpClient,
            source = config,
            parserSourceName = source.name,
            prefs = legadoPrefs,
            runtimeBridge = runtimeBridge,
        )
    }

    private val ruleRuntimeContext by lazy {
        LegadoSandboxRuleRuntimeContext(sandbox)
    }

    private val standaloneListRuntime by lazy {
        StandaloneLegadoListRuntime(
            jsEngine = jsEngine,
            source = config,
            parserSourceName = source.name,
            variableStore = legadoPrefs?.let(::KototoroLegadoVariableStore),
            httpExecutor = httpExecutor,
        )
    }

    private val manhuataiChapterNewIdCache = ConcurrentHashMap<Int, Map<String, String>>()

    override val listPagingMode: ContentRepository.ListPagingMode
        get() = ContentRepository.ListPagingMode.PAGE_INDEX

    /**
     * 清理该源的内存缓存（详情/目录/页面）。
     *
     * 用途：
     * - 在设置页修改 source/book 变量后，让规则重新生效
     * - 排查网络错误导致的“脏缓存”
     */
    fun invalidateCache() {
        memoryCache?.clear(source)
    }

    /**
     * 为设置页（loginUi 等）提供的脚本执行入口。
     *
     * 说明：
     * - 使用与解析流程相同的 sandbox，确保缓存/变量/JS 全局函数可复用，行为更接近 legado-with-MD3。
     * - 仅用于用户主动触发的“登录/配置”按钮，不应在后台自动高频调用。
     */
    fun runUserScript(script: String): Any? {
        if (shouldUseStandaloneListRuntimeForDebug()) {
            val runtimeContext = createStandaloneRuntimeContext()
            return standaloneListRuntime.execute(
                script = script,
                runtimeContext = runtimeContext,
                baseUrl = effectiveBaseUrlForRequest(config.bookSourceUrl),
            )
        }
        return sandbox.execute(script)
    }

    fun evalUserExpression(expression: String): Any? {
        if (shouldUseStandaloneListRuntimeForDebug()) {
            val runtimeContext = createStandaloneRuntimeContext()
            return standaloneListRuntime.eval(
                script = expression,
                runtimeContext = runtimeContext,
                baseUrl = effectiveBaseUrlForRequest(config.bookSourceUrl),
            )
        }
        return sandbox.eval(expression)
    }

    // ---- ContentParserAuthProvider for loginUrl WebView flow ----

    override val authUrl: String
        get() = config.loginUrl?.trim().orEmpty()

    override suspend fun isAuthorized(): Boolean {
        val checkJs = config.loginCheckJs?.trim() ?: return false
        if (checkJs.isBlank()) return false
        val result = runUserScript(checkJs)?.toString().orEmpty()
        return result.isNotEmpty() && result != "false" && result != "0"
    }

    override suspend fun getUsername(): String {
        val checkJs = config.loginCheckJs?.trim().orEmpty()
        if (checkJs.isBlank()) return ""
        return runUserScript(checkJs)?.toString().orEmpty()
    }

    /**
     * Get headers from source config, evaluating JS each time (like legado's getHeaderMap)
     * This allows dynamic headers from JS scripts to work properly
     */
    private fun getConfigHeaders(): Map<String, String> {
        return parseConfigHeaders(config.header)
    }

    private fun getLoginHeaders(): Map<String, String> {
        val sourceKey = config.bookSourceUrl.trim().takeIf { it.isNotBlank() } ?: return emptyMap()
        val raw = legadoPrefs?.let(::KototoroLegadoVariableStore)?.getLoginHeader(sourceKey)
        return KototoroLegadoHttpExecutor.parseHeaderJson(raw)
    }

    private fun getLoginInfoMap(): MutableMap<String, String> {
        val sourceKey = config.bookSourceUrl.trim().takeIf { it.isNotBlank() } ?: return linkedMapOf()
        val raw = legadoPrefs?.let(::KototoroLegadoVariableStore)?.getLoginInfo(sourceKey).orEmpty()
        if (raw.isBlank()) return linkedMapOf()
        return runCatching {
            val json = org.json.JSONObject(raw)
            linkedMapOf<String, String>().apply {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key, ""))
                }
            }
        }.getOrElse { linkedMapOf() }
    }

    /**
     * Default UA for when source doesn't specify one
     * Using Windows Chrome desktop UA like legado does for better source compatibility
     * Many sources return different HTML for mobile vs desktop
     */
    private val defaultUserAgent: String = 
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /**
     * Get User-Agent, preferring source config over default (case-insensitive)
     */
    private fun getSourceUserAgent(): String {
        val headers = getConfigHeaders()
        return headers.entries.find { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: defaultUserAgent
    }

    override val sortOrders: Set<SortOrder> = EnumSet.allOf(SortOrder::class.java)
    override var defaultSortOrder: SortOrder = SortOrder.NEWEST

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = !config.searchUrl.isNullOrBlank(),
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isAuthorSearchSupported = false
        )

    /**
     * Parses exploreUrl into a list of ExploreKind categories.
     * Supports:
     * 1. JSON array format: [{'title':'...', 'url':'...'}, ...]
     * 2. Text format: title1::url1\ntitle2::url2 (or separated by &&)
     */
    private fun parseExploreKinds(): List<ExploreKind> {
        val exploreUrl = config.exploreUrl
        if (exploreUrl.isNullOrBlank()) return emptyList()
        
        val trimmed = exploreUrl.trim()
        val effectiveText = resolveExploreUrlText(trimmed).trim()
        if (effectiveText.isEmpty()) return emptyList()
        
        return try {
            if (effectiveText.startsWith("[")) {
                // JSON array format
                val normalized = effectiveText.replace("'", "\"")
                runCatching { json.decodeFromString<List<ExploreKind>>(normalized) }
                    .recoverCatching {
                        // 部分三方脚本会把 `{{...Get(\"url\")...}}` 直接写进字符串，
                        // 若内容中出现未转义的 `"` 会导致严格 JSON 解析失败。
                        val fixed = fixExploreJsonString(normalized)
                        json.decodeFromString<List<ExploreKind>>(fixed)
                    }
                    .getOrThrow()
            } else {
                // Text format: title::url separated by && or newline
                effectiveText.split("(&&|\n)+".toRegex()).mapNotNull { kindStr ->
                    val parts = kindStr.trim().split("::")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        ExploreKind(parts[0].trim(), parts.getOrNull(1)?.trim())
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse exploreUrl", e)
            emptyList()
        }
    }

    private fun fixExploreJsonString(raw: String): String {
        // 仅修复 JSON 字符串内部的 `{{...}}` 模板段落：
        // - 当模板表达式包含双引号（例如 Get("url")）时，将其转义为 `\"`，保证 JSON 合法。
        if (!raw.contains("{{") || !raw.contains("\"")) return raw

        val sb = StringBuilder(raw.length + 16)
        var i = 0
        while (i < raw.length) {
            // 进入模板段落
            if (i + 1 < raw.length && raw[i] == '{' && raw[i + 1] == '{') {
                sb.append("{{")
                i += 2
                while (i < raw.length) {
                    if (i + 1 < raw.length && raw[i] == '}' && raw[i + 1] == '}') {
                        sb.append("}}")
                        i += 2
                        break
                    }
                    val ch = raw[i]
                    if (ch == '"' && (sb.isEmpty() || sb.last() != '\\')) {
                        sb.append("\\\"")
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
                continue
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }

    /**
     * legado-with-MD3 兼容：
     * - exploreUrl 允许使用 `<js>...</js>`/`@js:` 动态生成分类列表（title::url 多行文本，或 JSON 数组）。
     *
     * 注意：这里仅执行 exploreUrl 自身的脚本，不做 `{{page}}` 等模板变量替换；
     * `{{page}}` 会在后续实际请求时（AnalyzeUrl）按 offset->page 计算替换。
     */
    private fun resolveExploreUrlText(exploreUrl: String): String {
        val trimmed = exploreUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (!looksLikeJsRule(trimmed)) return trimmed

        val analyzer = AnalyzeRule(
            content = "",
            runtimeContext = ruleRuntimeContext,
            baseUrl = config.bookSourceUrl,
        )
        val lines = analyzer.getStringList(trimmed, mContent = "", isUrl = false).orEmpty()
        return lines.joinToString("\n")
    }

    private fun looksLikeJsRule(rule: String): Boolean {
        val trimmed = rule.trim()
        return trimmed.startsWith("@js:", ignoreCase = true) ||
            trimmed.startsWith("<js>", ignoreCase = true) ||
            trimmed.contains("</js>", ignoreCase = true)
    }

    /**
     * Gets the first valid URL from explore kinds (fallback behavior).
     */
    private fun getFirstExploreUrl(): String? {
        return parseExploreKinds().firstOrNull { !it.url.isNullOrBlank() }?.url
    }

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
        val query = filter?.query
        val isSearch = !query.isNullOrBlank()
        val selectedCategoryUrl = filter?.tags?.find { it.key.startsWith("category:") }
            ?.key?.removePrefix("category:")

        val firstExploreUrl = getFirstExploreUrl()
        val ruleUrl = when {
            isSearch -> config.searchUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
            selectedCategoryUrl != null -> selectedCategoryUrl
            firstExploreUrl != null -> firstExploreUrl
            // 对齐 legado-with-MD3：发现/列表必须由 exploreUrl 提供；仅有 searchUrl 的源不自动用空 key/默认 key 生成“首页列表”。
            else -> return emptyList()
        }

        // 对齐 legado-with-MD3：按页号翻页（{{page}}），并使用 0-based pageIndex 作为入参（page = pageIndex + 1）。
        val page = (offset + 1).coerceAtLeast(1)

        if (shouldUseStandaloneListRuntimeForDebug()) {
            return getListWithStandaloneRuntime(
                ruleUrl = ruleUrl,
                query = query,
                page = page,
                isSearch = isSearch,
            )
        }
        
        fun buildRequest(key: String?): LegadoRequestPlan {
            return AnalyzeUrl(
                ruleUrl = ruleUrl,
                key = key,
                page = page,
                baseUrl = effectiveBaseUrlForRequest(ruleUrl),
                ruleData = sandbox.getRuleData(),
                sandbox = sandbox,
                enabledCookieJarDefault = config.enabledCookieJar != false,
                useWebViewDefault = config.ruleSearch?.webView == true,
            ).build()
        }

        val firstResult = executeRequestWithLoginCheck(buildRequest(query)) ?: return emptyList()
        val (firstContent, firstFinalUrl) = firstResult
        val firstParsed = BookList.parse(firstContent, firstFinalUrl, source, config, isSearch, sandbox)

        return firstParsed
    }

    private suspend fun getListWithStandaloneRuntime(
        ruleUrl: String,
        query: String?,
        page: Int,
        isSearch: Boolean,
    ): List<Content> {
        Log.d(TAG, "[StandaloneListRuntime] Enabled for source=${source.name}")
        val prepared = standaloneListRuntime.prepareRequest(
            ruleUrl = ruleUrl,
            key = query,
            page = page,
            baseUrl = effectiveBaseUrlForRequest(ruleUrl),
        )
        val response = executeRequestWithLoginCheck(
            plan = prepared.requestPlan,
            runtimeContext = prepared.runtimeContext,
        ) ?: return emptyList()
        val (content, finalUrl) = response
        return BookList.parseWithRuntimeContext(
            content = content,
            baseUrl = finalUrl,
            source = source,
            config = config,
            isSearch = isSearch,
            runtimeContext = prepared.runtimeContext,
        )
    }

    private fun shouldUseStandaloneListRuntimeForDebug(): Boolean {
        if (!org.skepsun.kototoro.BuildConfig.DEBUG) return false
        return legadoPrefs?.getBoolean(PREF_DEBUG_STANDALONE_LIST_RUNTIME, false) == true
    }

    private fun createStandaloneRuntimeState(): StandaloneLegadoRuntimeState {
        return StandaloneLegadoRuntimeState(
            sourceKey = config.bookSourceUrl.trim().takeIf { it.isNotBlank() },
            variableStore = legadoPrefs?.let(::KototoroLegadoVariableStore),
        )
    }

    private fun createStandaloneRuntimeContext(
        key: String? = null,
        page: Int? = null,
        state: StandaloneLegadoRuntimeState = createStandaloneRuntimeState(),
        reGetBookAction: (() -> Unit)? = null,
        refreshTocUrlAction: (() -> Unit)? = null,
    ): StandaloneLegadoRuleRuntimeContext {
        return standaloneListRuntime.createRuntimeContext(
            key = key,
            page = page,
            state = state,
            reGetBookAction = reGetBookAction,
            refreshTocUrlAction = refreshTocUrlAction,
        )
    }

    private val rateLimiter by lazy {
        ConcurrentRateLimiter(config.bookSourceUrl, config.concurrentRate)
    }

    private val httpExecutor by lazy {
        KototoroLegadoHttpExecutor(
            source = source,
            config = config,
            httpClient = httpClient,
            rateLimiter = rateLimiter,
            configHeadersProvider = ::getConfigHeaders,
            loginHeadersProvider = ::getLoginHeaders,
            sourceUserAgentProvider = ::getSourceUserAgent,
        )
    }


    /**
     * Centralized request executor with retry and protection logic
     */
    private suspend fun executeRequest(plan: LegadoRequestPlan): Pair<String, String>? {
        return try {
            val response = httpExecutor.execute(plan)
            applyBodyJsIfNeeded(
                plan = plan,
                body = response.body,
                finalUrl = response.url,
                runtimeContext = null,
            ) to response.url
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException) throw e
            Log.e(TAG, "Request failed via runtime executor: ${plan.url}", e)
            null
        }
    }

    private suspend fun executeRequestWithLoginCheck(
        plan: LegadoRequestPlan,
        runtimeContext: StandaloneLegadoRuleRuntimeContext? = null,
        ): Pair<String, String>? {
        val response = try {
            httpExecutor.execute(plan)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException) throw e
            Log.e(TAG, "Request failed via runtime executor: ${plan.url}", e)
            return null
        }
        val processedBody = applyBodyJsIfNeeded(
            plan = plan,
            body = response.body,
            finalUrl = response.url,
            runtimeContext = runtimeContext,
        )
        val checkJs = config.loginCheckJs?.trim().orEmpty()
        if (checkJs.isBlank()) return processedBody to response.url

        val loginJsBridge = AnalyzeUrlLoginJsBridge(
            basePlan = plan,
            httpExecutor = httpExecutor,
            initialHeaders = getConfigHeaders() + getLoginHeaders() + plan.headers,
            sourceTag = config.bookSourceName,
            sourceObject = config,
            infoMap = getLoginInfoMap(),
        )
        val jsInput = LoginCheckStrResponse(
            response = loginJsBridge.buildOkHttpResponse(
                url = response.url,
                code = response.code ?: 200,
                headers = response.headers,
                bodyText = processedBody,
            ),
            bodyText = processedBody,
        )
        val jsResult = if (runtimeContext != null) {
            runtimeContext.withJavaBridge(loginJsBridge) {
                standaloneListRuntime.eval(
                    script = checkJs,
                    runtimeContext = runtimeContext,
                    result = jsInput,
                    baseUrl = response.url,
                )
            }
        } else {
            sandbox.putVariable("url", response.url)
            sandbox.setResult(jsInput)
            sandbox.withJavaBridge(loginJsBridge) {
                sandbox.eval(checkJs)
            }
        }

        return when (jsResult) {
            is LoginCheckStrResponse -> jsResult.body.toString() to jsResult.url
            is org.skepsun.kototoro.core.javascript.StrResponse -> jsResult.body() to jsResult.url()
            is okhttp3.Response -> jsResult.body?.string().orEmpty() to jsResult.request.url.toString()
            is String -> jsResult to response.url
            null -> processedBody to response.url
            else -> jsResult.toString() to response.url
        }
    }

    private fun applyBodyJsIfNeeded(
        plan: LegadoRequestPlan,
        body: String,
        finalUrl: String,
        runtimeContext: StandaloneLegadoRuleRuntimeContext?,
    ): String {
        if (plan.type != null) return body
        val bodyJs = plan.bodyJs?.trim().orEmpty()
        if (bodyJs.isBlank()) return body

        return if (runtimeContext != null) {
            val jsResult = runtimeContext.evalJs(bodyJs, body, finalUrl)
            when (jsResult) {
                null -> body
                is String -> jsResult
                else -> jsResult.toString()
            }
        } else {
            sandbox.putVariable("url", finalUrl)
            sandbox.putVariable("baseUrl", finalUrl)
            sandbox.setResult(body)
            val jsResult = sandbox.eval(bodyJs)
            when (jsResult) {
                null -> sandbox.getVariable("result") ?: body
                is String -> jsResult
                else -> jsResult.toString()
            }
        }
    }

    private fun originForReferer(url: String): String? {
        return runCatching {
            val parsed = java.net.URL(url)
            val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
            "${parsed.protocol}://$host/"
        }.getOrNull()
    }

    private fun effectiveBaseUrlForRequest(url: String): String {
        val configured = config.bookSourceUrl.trim()
        return when {
            configured.startsWith("http", ignoreCase = true) -> configured
            url.startsWith("http", ignoreCase = true) -> url
            else -> ""
        }
    }

    private fun isHeaderValueSafe(value: String): Boolean {
        // OkHttp 对 header value 有严格限制：不允许控制字符与非 ASCII。
        for (ch in value) {
            if (ch == '\t') continue
            val code = ch.code
            if (code < 0x20 || code >= 0x7f) return false
        }
        return true
    }

    override suspend fun getDetails(manga: Content): Content = getDetails(
        manga = manga,
        fetchMode = ContentRepository.DetailsFetchMode.ALLOW_CACHE,
    )

    override suspend fun getDetails(
        manga: Content,
        fetchMode: ContentRepository.DetailsFetchMode,
    ): Content {
        val normalizedContentUrl = AnalyzeUrl.normalizeUrl(manga.url)
        if (fetchMode == ContentRepository.DetailsFetchMode.ALLOW_CACHE) {
            memoryCache?.getDetails(source, normalizedContentUrl)?.let {
                val hasTocRule = !config.ruleToc?.chapterList.isNullOrBlank()
                val cachedChapters = it.chapters.orEmpty()
                if (!hasTocRule || cachedChapters.isNotEmpty()) {
                    android.util.Log.d(TAG, "Memory cache HIT (getDetails) for book: ${manga.title}")
                    return it
                }
                // 章节为空且存在目录规则：视为“脏缓存”，触发重新拉取（常见于网络错误/规则调整后）。
                android.util.Log.w(TAG, "Memory cache BYPASS (getDetails) for book: ${manga.title} (cached chapters empty)")
            }
        }

        if (shouldUseStandaloneListRuntimeForDebug()) {
            return getDetailsWithStandaloneRuntime(manga, normalizedContentUrl)
        }

        val request = AnalyzeUrl(
            manga.url, 
            baseUrl = effectiveBaseUrlForRequest(manga.url),
            sandbox = sandbox,
            enabledCookieJarDefault = config.enabledCookieJar != false,
            useWebViewDefault = config.ruleBookInfo?.webView == true,
        ).build()
        val result = executeRequestWithLoginCheck(request)
        
        if (result == null) {
            return manga
        }
        val (content, finalUrl) = result
        
        val infoResult = BookInfo.parse(manga, content, finalUrl, config, sandbox)
        
        // TOC parsing - often combined or needed for chapters
        // 对聚合源/POST JSON 目录：必须保留 options（body/method），否则无法翻页（表现为永远 20 章）。
        // `finalUrl` 是请求的 urlPart（AnalyzeUrl.build 后不含 ",{...}"），更适合作为解析基址而非“目录请求串”。
        val tocUrl = infoResult.tocUrl
            ?.takeIf { it.isNotBlank() }
            ?: manga.url.takeIf { it.contains(",{") || it.contains(", {") }
            ?: finalUrl
        
        val chapters = if (tocUrl == manga.url) {
            // 复用首包响应，避免重复请求目录第一页
            getChaptersHelper(infoResult.manga, tocUrl, initialContent = content, initialFinalUrl = finalUrl)
        } else {
            getChaptersHelper(infoResult.manga, tocUrl)
        }
        
        val finalContent = infoResult.manga.copy(chapters = chapters)
        
        android.util.Log.d(TAG, "Memory cache FILL (getDetails) for book: ${manga.title}")
        memoryCache?.putDetails(
            source, 
            normalizedContentUrl, 
            SafeDeferred(processLifecycleScope.async(Dispatchers.Default) { Result.success(finalContent) })
        )
        
        return finalContent
    }

    private suspend fun getDetailsWithStandaloneRuntime(
        manga: Content,
        normalizedContentUrl: String,
    ): Content {
        val runtimeState = createStandaloneRuntimeState()
        val runtimeContext = createStandaloneRuntimeContext(state = runtimeState)
        val request = AnalyzeUrl(
            manga.url,
            baseUrl = effectiveBaseUrlForRequest(manga.url),
            ruleData = runtimeState.ruleData(),
            runtimeContext = runtimeContext,
            jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(manga.url) },
            enabledCookieJarDefault = config.enabledCookieJar != false,
            useWebViewDefault = config.ruleBookInfo?.webView == true,
        ).build()
        val result = executeRequestWithLoginCheck(
            plan = request,
            runtimeContext = runtimeContext,
        ) ?: return manga
        val (content, finalUrl) = result

        val infoResult = BookInfo.parseWithRuntimeContext(
            manga = manga,
            content = content,
            baseUrl = finalUrl,
            config = config,
            runtimeContext = runtimeContext,
        )

        val tocUrl = infoResult.tocUrl
            ?.takeIf { it.isNotBlank() }
            ?: manga.url.takeIf { it.contains(",{") || it.contains(", {") }
            ?: finalUrl

        val chapters = if (tocUrl == manga.url) {
            getChaptersHelperWithStandaloneRuntime(
                manga = infoResult.manga,
                tocUrl = tocUrl,
                runtimeState = runtimeState,
                initialContent = content,
                initialFinalUrl = finalUrl,
            )
        } else {
            getChaptersHelperWithStandaloneRuntime(
                manga = infoResult.manga,
                tocUrl = tocUrl,
                runtimeState = runtimeState,
            )
        }

        val finalContent = infoResult.manga.copy(chapters = chapters)
        android.util.Log.d(TAG, "Memory cache FILL (getDetails standalone) for book: ${manga.title}")
        memoryCache?.putDetails(
            source,
            normalizedContentUrl,
            SafeDeferred(processLifecycleScope.async(Dispatchers.Default) { Result.success(finalContent) }),
        )
        return finalContent
    }


    override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
        val normalizedUrl = AnalyzeUrl.normalizeUrl(chapter.url)
        memoryCache?.getPages(source, normalizedUrl)?.let {
            android.util.Log.d(TAG, "Memory cache HIT (getPages) for chapter: ${chapter.title}")
            return it
        }
        return getPagesFlow(chapter, nextChapterUrl).toList().lastOrNull() ?: emptyList()
    }

    override fun getPagesFlow(chapter: ContentChapter, nextChapterUrl: String?): Flow<List<ContentPage>> = kotlinx.coroutines.flow.channelFlow {
        val normalizedUrl = AnalyzeUrl.normalizeUrl(chapter.url)
        memoryCache?.getPages(source, normalizedUrl)?.let {
            android.util.Log.d(TAG, "Memory cache HIT (getPagesFlow) for chapter: ${chapter.title}")
            send(it)
            return@channelFlow
        }

        if (shouldUseStandaloneListRuntimeForDebug()) {
            sendStandalonePagesFlow(chapter, nextChapterUrl, normalizedUrl)
            return@channelFlow
        }

        sandbox.setChapter(
            LegadoSandbox.ChapterContext(
                title = chapter.title.orEmpty(),
                url = chapter.url,
                index = chapter.number.toInt()
            )
        )
        android.util.Log.d(TAG, "[getPagesFlow] setChapter: title='${chapter.title}' chapterName='${sandbox.getVariable("chapterName")}'")

        val visited = mutableSetOf<String>()
        val pages = mutableListOf<ContentPage>()
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(chapter.url)

        var pageCount = 0
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue
            
            // 安全检查：如果这个 URL 是下一章的 URL，或者是已经处理过的下一章 URL，停止加载
            // 注意：Legado URL 可能带有 ",{'webView': true}" 这种后缀，需要剥离后再对比
            val normalizedUrl = url.substringBefore(",")
            val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
            android.util.Log.d("LegadoRepository", "[BoundaryCheck] url=$normalizedUrl, nextChapterUrl=$normalizedNextChapter")
            if (normalizedNextChapter != null && normalizedUrl == normalizedNextChapter) {
                android.util.Log.w("LegadoRepository", "Reached next chapter URL: $url, stopping page load.")
                break
            }

            val contentRule = config.ruleContent
            val request = AnalyzeUrl(
                url, 
                baseUrl = effectiveBaseUrlForRequest(url),
                sandbox = sandbox,
                enabledCookieJarDefault = config.enabledCookieJar != false,
                useWebViewDefault = isWebViewEnabled(contentRule?.webView),
                webJsDefault = contentRule?.webJs,
                webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
            ).build()
            val result = executeRequestWithLoginCheck(request) ?: run {
                continue
            }
            val (content, finalUrl) = result
            val parseResult = try {
                BookContent.parse(
                    content = content,
                    baseUrl = finalUrl,
                    source = source,
                    config = config,
                    sandbox = sandbox,
                    nextChapterUrl = nextChapterUrl,
                )
            } catch (e: org.skepsun.kototoro.parsers.exception.ContentUnavailableException) {
                // manhuatai/kanman 站点：目录页的 href 可能不是 chapter_newid，导致接口返回“章节不存在!”。
                // legado-with-MD3 通常通过正确的目录接口拿到 chapter_newid；这里做兜底映射以提升兼容性。
                val fallbackUrl = resolveManhuataiChapterInfoUrl(url, chapter.title.orEmpty())
                if (fallbackUrl != null && fallbackUrl != url) {
                    val retryRequest = AnalyzeUrl(
                        fallbackUrl,
                        baseUrl = effectiveBaseUrlForRequest(fallbackUrl),
                        sandbox = sandbox,
                        enabledCookieJarDefault = config.enabledCookieJar != false,
                        useWebViewDefault = isWebViewEnabled(contentRule?.webView),
                        webJsDefault = contentRule?.webJs,
                        webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
                    ).build()
                    val retryResult = executeRequestWithLoginCheck(retryRequest) ?: continue
                    val (retryContent, retryFinalUrl) = retryResult
                    BookContent.parse(
                        content = retryContent,
                        baseUrl = retryFinalUrl,
                        source = source,
                        config = config,
                        sandbox = sandbox,
                        nextChapterUrl = nextChapterUrl,
                    )
                } else {
                    throw e
                }
            }
            val startIndex = pages.size
            parseResult.pages.forEachIndexed { index, page ->
                val pageId = (source.name.hashCode().toLong() shl 32) + startIndex + index
                pages.add(page.copy(id = pageId))
            }
            
            // Emit current progress
            this.send(pages.toList())
            pageCount++

            // 安全限制：单章节页数超过 500 页（通常是规则误触了下一章）
            if (pages.size > 500) {
                android.util.Log.e("LegadoRepository", "Chapter has too many pages (>500), possible rule leakage. Stopping.")
                break
            }

            parseResult.nextPageUrls.forEach { next ->
                val normalizedNext = next.substringBefore(",")
                val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
                android.util.Log.d("LegadoRepository", "[NextPageFilter] next=$normalizedNext, nextChapter=$normalizedNextChapter, match=${normalizedNext == normalizedNextChapter}")
                
                if (!visited.contains(next)) {
                    // 再次检查 next URL
                    if (normalizedNextChapter != null && normalizedNext == normalizedNextChapter) {
                        android.util.Log.d("LegadoRepository", "[NextPageFilter] SKIPPING next chapter URL: $next")
                        // Skip adding next chapter to queue
                    } else {
                        queue.add(next)
                    }
                }
            }
        }
        
        if (pages.isNotEmpty()) {
            android.util.Log.d(TAG, "Memory cache FILL for chapter: ${chapter.title}, pages: ${pages.size}")
            memoryCache?.putPages(
                source, 
                normalizedUrl, 
                SafeDeferred(processLifecycleScope.async(Dispatchers.Default) { Result.success(pages.toList()) })
            )
        }
    }

    private suspend fun ProducerScope<List<ContentPage>>.sendStandalonePagesFlow(
        chapter: ContentChapter,
        nextChapterUrl: String?,
        normalizedUrl: String,
    ) {
        val runtimeState = createStandaloneRuntimeState()
        val runtimeContext = createStandaloneRuntimeContext(state = runtimeState)
        runtimeContext.setChapter(
            org.skepsun.kototoro.core.javascript.ChapterInfo(
                chapterUrl = chapter.url,
                name = chapter.title.orEmpty(),
                index = chapter.number.toInt(),
            ),
        )
        android.util.Log.d(
            TAG,
            "[getPagesFlow standalone] setChapter: title='${chapter.title}' chapterName='${runtimeContext.getVariable("chapterName")}'",
        )

        val visited = mutableSetOf<String>()
        val pages = mutableListOf<ContentPage>()
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(chapter.url)

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue

            val normalizedCurrentUrl = url.substringBefore(",")
            val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
            android.util.Log.d("LegadoRepository", "[BoundaryCheck] url=$normalizedCurrentUrl, nextChapterUrl=$normalizedNextChapter")
            if (normalizedNextChapter != null && normalizedCurrentUrl == normalizedNextChapter) {
                android.util.Log.w("LegadoRepository", "Reached next chapter URL: $url, stopping page load.")
                break
            }

            val contentRule = config.ruleContent
            val request = AnalyzeUrl(
                url,
                baseUrl = effectiveBaseUrlForRequest(url),
                ruleData = runtimeState.ruleData(),
                runtimeContext = runtimeContext,
                jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(url) },
                enabledCookieJarDefault = config.enabledCookieJar != false,
                useWebViewDefault = isWebViewEnabled(contentRule?.webView, runtimeContext),
                webJsDefault = contentRule?.webJs,
                webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
            ).build()
            val result = executeRequestWithLoginCheck(
                plan = request,
                runtimeContext = runtimeContext,
            ) ?: continue
            val (content, finalUrl) = result
            val parseResult = try {
                BookContent.parseWithRuntimeContext(
                    content = content,
                    baseUrl = finalUrl,
                    source = source,
                    config = config,
                    runtimeContext = runtimeContext,
                    nextChapterUrl = nextChapterUrl,
                )
            } catch (e: org.skepsun.kototoro.parsers.exception.ContentUnavailableException) {
                val fallbackUrl = resolveManhuataiChapterInfoUrl(url, chapter.title.orEmpty(), runtimeState, runtimeContext)
                if (fallbackUrl != null && fallbackUrl != url) {
                    val retryRequest = AnalyzeUrl(
                        fallbackUrl,
                        baseUrl = effectiveBaseUrlForRequest(fallbackUrl),
                        ruleData = runtimeState.ruleData(),
                        runtimeContext = runtimeContext,
                        jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(fallbackUrl) },
                        enabledCookieJarDefault = config.enabledCookieJar != false,
                        useWebViewDefault = isWebViewEnabled(contentRule?.webView, runtimeContext),
                        webJsDefault = contentRule?.webJs,
                        webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
                    ).build()
                    val retryResult = executeRequestWithLoginCheck(
                        plan = retryRequest,
                        runtimeContext = runtimeContext,
                    ) ?: continue
                    val (retryContent, retryFinalUrl) = retryResult
                    BookContent.parseWithRuntimeContext(
                        content = retryContent,
                        baseUrl = retryFinalUrl,
                        source = source,
                        config = config,
                        runtimeContext = runtimeContext,
                        nextChapterUrl = nextChapterUrl,
                    )
                } else {
                    throw e
                }
            }

            val startIndex = pages.size
            parseResult.pages.forEachIndexed { index, page ->
                val pageId = (source.name.hashCode().toLong() shl 32) + startIndex + index
                pages.add(page.copy(id = pageId))
            }

            send(pages.toList())

            if (pages.size > 500) {
                android.util.Log.e("LegadoRepository", "Chapter has too many pages (>500), possible rule leakage. Stopping.")
                break
            }

            parseResult.nextPageUrls.forEach { next ->
                val normalizedNext = next.substringBefore(",")
                val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
                android.util.Log.d("LegadoRepository", "[NextPageFilter] next=$normalizedNext, nextChapter=$normalizedNextChapter, match=${normalizedNext == normalizedNextChapter}")
                if (!visited.contains(next)) {
                    if (normalizedNextChapter != null && normalizedNext == normalizedNextChapter) {
                        android.util.Log.d("LegadoRepository", "[NextPageFilter] SKIPPING next chapter URL: $next")
                    } else {
                        queue.add(next)
                    }
                }
            }
        }

        if (pages.isNotEmpty()) {
            android.util.Log.d(TAG, "Memory cache FILL for chapter: ${chapter.title}, pages: ${pages.size}")
            memoryCache?.putPages(
                source,
                normalizedUrl,
                SafeDeferred(processLifecycleScope.async(Dispatchers.Default) { Result.success(pages.toList()) }),
            )
        }
    }

    private suspend fun resolveManhuataiChapterInfoUrl(
        originalUrl: String,
        chapterTitle: String,
        runtimeState: StandaloneLegadoRuntimeState? = null,
        runtimeContext: StandaloneLegadoRuleRuntimeContext? = null,
    ): String? {
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return null
        if (!httpUrl.encodedPath.contains("/api/getchapterinfov2")) return null

        val comicId = httpUrl.queryParameter("comic_id")?.toIntOrNull() ?: return null
        val currentNewId = httpUrl.queryParameter("chapter_newid").orEmpty()
        if (currentNewId.isBlank()) return null

        val mapping = manhuataiChapterNewIdCache[comicId] ?: run {
            val listUrl = "${httpUrl.scheme}://${httpUrl.host}/api/getchapterlist/?comic_id=$comicId"
            val req = if (runtimeState != null && runtimeContext != null) {
                AnalyzeUrl(
                    listUrl,
                    baseUrl = effectiveBaseUrlForRequest(listUrl),
                    ruleData = runtimeState.ruleData(),
                    runtimeContext = runtimeContext,
                    jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(listUrl) },
                    enabledCookieJarDefault = config.enabledCookieJar != false,
                ).build()
            } else {
                AnalyzeUrl(
                    listUrl,
                    baseUrl = effectiveBaseUrlForRequest(listUrl),
                    sandbox = sandbox,
                    enabledCookieJarDefault = config.enabledCookieJar != false,
                ).build()
            }
            val resp = executeRequestWithLoginCheck(
                plan = req,
                runtimeContext = runtimeContext,
            ) ?: return null
            val body = resp.first
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val arr = obj.optJSONArray("data") ?: return null
            val map = LinkedHashMap<String, String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("chapter_name").trim()
                val newId = item.optString("chapter_newid").trim()
                if (name.isNotBlank() && newId.isNotBlank()) {
                    map[normalizeChapterName(name)] = newId
                }
            }
            manhuataiChapterNewIdCache[comicId] = map
            map
        }

        val wanted = normalizeChapterName(chapterTitle)
        val matchedNewId = mapping[wanted]
            ?: mapping.entries.firstOrNull { (k, _) -> k.endsWith(wanted) || wanted.endsWith(k) }?.value
            ?: return null

        if (matchedNewId == currentNewId) return null

        return httpUrl.newBuilder()
            .setQueryParameter("chapter_newid", matchedNewId)
            .build()
            .toString()
    }

    private fun normalizeChapterName(title: String): String {
        // 妙华台藏目录常见前缀：日期 + 空格，例如 "09-06 第1话 重生"
        return title
            .trim()
            .replace(Regex("^\\d{2}-\\d{2}\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        return page.url
    }

    override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): NovelChapterContent? {
        return if (shouldUseStandaloneListRuntimeForDebug()) {
            getStandaloneNovelChapterContent(chapter, nextChapterUrl)
        } else {
            getSandboxNovelChapterContent(chapter, nextChapterUrl)
        }
    }

    private fun decodeDataUrl(url: String): NovelChapterContent? {
        val data = url.removePrefix("data:")
        val commaIndex = data.indexOf(',')
        if (commaIndex <= 0) return null
        val meta = data.substring(0, commaIndex)
        val contentPart = data.substring(commaIndex + 1)
        val isBase64 = meta.contains(";base64", ignoreCase = true)

        return try {
            val html = if (isBase64) {
                String(Base64.getDecoder().decode(contentPart), Charsets.UTF_8)
            } else {
                URLDecoder.decode(contentPart, "UTF-8")
            }
            NovelChapterContent(html = html, images = emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode data URL", e)
            null
        }
    }

    private suspend fun getSandboxNovelChapterContent(
        chapter: ContentChapter,
        nextChapterUrl: String?,
    ): NovelChapterContent? {
        if (config.bookSourceType == 2) {
            return getPages(chapter, nextChapterUrl).toNovelChapterContentFromPages()
        }

        sandbox.setChapter(
            LegadoSandbox.ChapterContext(
                title = chapter.title.orEmpty(),
                url = chapter.url,
                index = chapter.number.toInt(),
            ),
        )

        val visited = mutableSetOf<String>()
        val pageContents = mutableListOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(chapter.url)

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue

            val normalizedCurrentUrl = url.substringBefore(",")
            val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
            if (normalizedNextChapter != null && normalizedCurrentUrl == normalizedNextChapter) {
                android.util.Log.w(TAG, "Reached next chapter URL while building novel chapter content: $url")
                break
            }

            val contentRule = config.ruleContent
            val request = AnalyzeUrl(
                url,
                baseUrl = effectiveBaseUrlForRequest(url),
                sandbox = sandbox,
                enabledCookieJarDefault = config.enabledCookieJar != false,
                useWebViewDefault = isWebViewEnabled(contentRule?.webView),
                webJsDefault = contentRule?.webJs,
                webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
            ).build()
            val result = executeRequestWithLoginCheck(request) ?: continue
            val (content, finalUrl) = result
            val parseResult = try {
                BookContent.parseNovelPage(
                    content = content,
                    baseUrl = finalUrl,
                    source = source,
                    config = config,
                    sandbox = sandbox,
                    nextChapterUrl = nextChapterUrl,
                )
            } catch (e: org.skepsun.kototoro.parsers.exception.ContentUnavailableException) {
                val fallbackUrl = resolveManhuataiChapterInfoUrl(url, chapter.title.orEmpty())
                if (fallbackUrl != null && fallbackUrl != url) {
                    val retryRequest = AnalyzeUrl(
                        fallbackUrl,
                        baseUrl = effectiveBaseUrlForRequest(fallbackUrl),
                        sandbox = sandbox,
                        enabledCookieJarDefault = config.enabledCookieJar != false,
                        useWebViewDefault = isWebViewEnabled(contentRule?.webView),
                        webJsDefault = contentRule?.webJs,
                        webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
                    ).build()
                    val retryResult = executeRequestWithLoginCheck(retryRequest) ?: continue
                    val (retryContent, retryFinalUrl) = retryResult
                    BookContent.parseNovelPage(
                        content = retryContent,
                        baseUrl = retryFinalUrl,
                        source = source,
                        config = config,
                        sandbox = sandbox,
                        nextChapterUrl = nextChapterUrl,
                    )
                } else {
                    throw e
                }
            }
            if (parseResult.content.isNotBlank()) {
                pageContents.add(parseResult.content)
            }
            parseResult.nextPageUrls.forEach { next ->
                val normalizedNext = next.substringBefore(",")
                if (normalizedNextChapter != null && normalizedNext == normalizedNextChapter) return@forEach
                if (!visited.contains(next)) {
                    queue.add(next)
                }
            }
        }

        val finalHtml = BookContent.finalizeNovelChapter(
            pageContents = pageContents,
            baseUrl = effectiveBaseUrlForRequest(chapter.url),
            config = config,
            runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox),
            nextChapterUrl = nextChapterUrl,
        )
        return finalHtml
            .takeIf { it.isNotBlank() }
            ?.let { NovelChapterContent(html = it, images = emptyList()) }
    }

    private suspend fun getStandaloneNovelChapterContent(
        chapter: ContentChapter,
        nextChapterUrl: String?,
    ): NovelChapterContent? {
        if (config.bookSourceType == 2) {
            return getPages(chapter, nextChapterUrl).toNovelChapterContentFromPages()
        }

        val runtimeState = createStandaloneRuntimeState()
        val runtimeContext = createStandaloneRuntimeContext(state = runtimeState)
        runtimeContext.setChapter(
            org.skepsun.kototoro.core.javascript.ChapterInfo(
                chapterUrl = chapter.url,
                name = chapter.title.orEmpty(),
                index = chapter.number.toInt(),
            ),
        )

        val visited = mutableSetOf<String>()
        val pageContents = mutableListOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(chapter.url)

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue

            val normalizedCurrentUrl = url.substringBefore(",")
            val normalizedNextChapter = nextChapterUrl?.substringBefore(",")
            if (normalizedNextChapter != null && normalizedCurrentUrl == normalizedNextChapter) {
                android.util.Log.w(TAG, "Reached next chapter URL while building standalone novel chapter content: $url")
                break
            }

            val contentRule = config.ruleContent
            val request = AnalyzeUrl(
                url,
                baseUrl = effectiveBaseUrlForRequest(url),
                ruleData = runtimeState.ruleData(),
                runtimeContext = runtimeContext,
                jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(url) },
                enabledCookieJarDefault = config.enabledCookieJar != false,
                useWebViewDefault = isWebViewEnabled(contentRule?.webView, runtimeContext),
                webJsDefault = contentRule?.webJs,
                webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
            ).build()
            val result = executeRequestWithLoginCheck(
                plan = request,
                runtimeContext = runtimeContext,
            ) ?: continue
            val (content, finalUrl) = result
            val parseResult = try {
                BookContent.parseNovelPageWithRuntimeContext(
                    content = content,
                    baseUrl = finalUrl,
                    source = source,
                    config = config,
                    runtimeContext = runtimeContext,
                    nextChapterUrl = nextChapterUrl,
                )
            } catch (e: org.skepsun.kototoro.parsers.exception.ContentUnavailableException) {
                val fallbackUrl = resolveManhuataiChapterInfoUrl(url, chapter.title.orEmpty(), runtimeState, runtimeContext)
                if (fallbackUrl != null && fallbackUrl != url) {
                    val retryRequest = AnalyzeUrl(
                        fallbackUrl,
                        baseUrl = effectiveBaseUrlForRequest(fallbackUrl),
                        ruleData = runtimeState.ruleData(),
                        runtimeContext = runtimeContext,
                        jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(fallbackUrl) },
                        enabledCookieJarDefault = config.enabledCookieJar != false,
                        useWebViewDefault = isWebViewEnabled(contentRule?.webView, runtimeContext),
                        webJsDefault = contentRule?.webJs,
                        webViewDelayDefault = contentRule?.webViewDelayTime ?: 0L,
                    ).build()
                    val retryResult = executeRequestWithLoginCheck(
                        plan = retryRequest,
                        runtimeContext = runtimeContext,
                    ) ?: continue
                    val (retryContent, retryFinalUrl) = retryResult
                    BookContent.parseNovelPageWithRuntimeContext(
                        content = retryContent,
                        baseUrl = retryFinalUrl,
                        source = source,
                        config = config,
                        runtimeContext = runtimeContext,
                        nextChapterUrl = nextChapterUrl,
                    )
                } else {
                    throw e
                }
            }
            if (parseResult.content.isNotBlank()) {
                pageContents.add(parseResult.content)
            }
            parseResult.nextPageUrls.forEach { next ->
                val normalizedNext = next.substringBefore(",")
                if (normalizedNextChapter != null && normalizedNext == normalizedNextChapter) return@forEach
                if (!visited.contains(next)) {
                    queue.add(next)
                }
            }
        }

        val finalHtml = BookContent.finalizeNovelChapter(
            pageContents = pageContents,
            baseUrl = effectiveBaseUrlForRequest(chapter.url),
            config = config,
            runtimeContext = runtimeContext,
            nextChapterUrl = nextChapterUrl,
        )
        return finalHtml
            .takeIf { it.isNotBlank() }
            ?.let { NovelChapterContent(html = it, images = emptyList()) }
    }

    private fun List<ContentPage>.toNovelChapterContentFromPages(): NovelChapterContent? {
        if (isEmpty()) return null
        val htmlBuilder = StringBuilder()
        val images = mutableListOf<NovelChapterContent.NovelImage>()
        forEach { page ->
            if (page.url.startsWith("data:", ignoreCase = true)) {
                decodeDataUrl(page.url)?.let { decoded ->
                    htmlBuilder.append(decoded.html)
                    images.addAll(decoded.images)
                }
            } else {
                images.add(NovelChapterContent.NovelImage(page.url, page.headers ?: emptyMap()))
            }
        }
        return NovelChapterContent(
            html = htmlBuilder.toString(),
            images = images,
        )
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val kinds = parseExploreKinds()
        
        // Convert ExploreKind to ContentTag for the filter system
        val categoryTags = kinds.mapNotNull { kind ->
            if (kind.url.isNullOrBlank()) null
            else ContentTag(kind.title, "category:${kind.url}", source)
        }.toSet()
        
        if (categoryTags.isEmpty()) {
            return ContentListFilterOptions()
        }
        
        return ContentListFilterOptions(
            availableTags = categoryTags,
            tagGroups = listOf(ContentTagGroup("分类", categoryTags))
        )
    }

    override fun getRequestHeaders(): Map<String, String> {
        val headers = getConfigHeaders().toMutableMap()

        // Ensure User-Agent is included in headers for Browser/WebView consistency
        if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
            headers["User-Agent"] = getSourceUserAgent()
        }

        // 图片站常见防盗链：没有 Referer 会直接 403
        val hasReferer = headers.keys.any { it.equals("Referer", ignoreCase = true) }
        if (!hasReferer) {
            val fallback = config.bookSourceUrl.takeIf { it.startsWith("http", ignoreCase = true) }
            originForReferer(fallback ?: "")?.let { headers["Referer"] = it }
        }

        return headers.filterValues { isHeaderValueSafe(it) }
    }

    override suspend fun getRelated(seed: Content): List<Content> {
        return RelatedContentSearchFallback.find(seed) { query ->
            getList(
                offset = 0,
                order = defaultSortOrder,
                filter = ContentListFilter(query = query),
            )
        }
    }

    private suspend fun getChaptersHelper(
        manga: Content,
        tocUrl: String,
        initialContent: String? = null,
        initialFinalUrl: String? = null
    ): List<ContentChapter> {
        // 设置当前书籍上下文，供 JS 侧 book.getVariable("custom") 等读取。
        sandbox.setBook(
            LegadoSandbox.BookContext(
                name = manga.title,
                url = manga.url,
                tocUrl = tocUrl
            )
        )
        sandbox.withPreUpdateActions(
            reGetBookAction = {
                runBlocking {
                    reGetBookForSandboxPreUpdate()
                }
            },
            refreshTocUrlAction = {
                runBlocking {
                    refreshTocUrlForSandboxPreUpdate()
                }
            },
        ) {
            runTocPreUpdateJsIfNeeded()
        }
        val effectiveTocUrl = sandbox.getVariable("tocUrl")
            ?.takeIf { it.isNotBlank() }
            ?: tocUrl

        // legado-with-MD3 聚合源常用：通过 source/book 变量控制目录翻页上限（cmpVariable）。
        // 返回值语义：0 表示不限制（含 -1 的约定），>0 表示最多加载多少“目录页”。
        val tocPageLimit = computeTocPageLimitFromSourceComment() // nullable

        val visited = mutableSetOf<String>()
        val chapters = mutableListOf<ContentChapter>()
        var shouldReverse = false
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(effectiveTocUrl)
        var usedInitial = false
        
        var pageCount = 0

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            
            if (!visited.add(url)) {
                continue
            }
            
            pageCount++

            val (content, finalUrl) = if (!usedInitial && url == effectiveTocUrl && initialContent != null && initialFinalUrl != null) {
                usedInitial = true
                initialContent to initialFinalUrl
            } else {
                val request = AnalyzeUrl(
                    url,
                    baseUrl = effectiveBaseUrlForRequest(url),
                    sandbox = sandbox,
                    enabledCookieJarDefault = config.enabledCookieJar != false,
                    useWebViewDefault = config.ruleToc?.webView == true,
                ).build()
                val result = executeRequestWithLoginCheck(request) ?: continue
                result
            }

            val parseResult = BookChapterList.parse(content, finalUrl, source, config, sandbox)
            
            shouldReverse = shouldReverse || parseResult.shouldReverse
            chapters.addAll(parseResult.chapters)
            
            android.util.Log.d(TAG, "TOC page $pageCount: loaded ${parseResult.chapters.size} chapters, total: ${chapters.size}, nextPages: ${parseResult.nextPageUrls.size}")

            parseResult.nextPageUrls.forEach { next ->
                if (!visited.contains(next)) {
                    queue.add(next)
                }
            }

            // 若源未提供 nextTocUrl，但 tocUrl 是“带 page 的 POST JSON 请求”，则尝试自动翻页：
            // - 依赖书籍变量/源变量（cmpVariable）控制最多翻多少页；0 表示直到无数据为止
            // - 仅对 options URL 生效，避免对普通 HTML URL 造成意外请求
            if (parseResult.nextPageUrls.isEmpty()) {
                enqueueAutoNextTocPageIfPossible(
                    rawUrl = url,
                    tocPageLimit = tocPageLimit,
                    visited = visited,
                    queue = queue,
                    lastParseHadItems = parseResult.chapters.isNotEmpty()
                )
            }
        }
        
        val deduped = linkedMapOf<String, ContentChapter>()
        chapters.forEach { chapter ->
            deduped.putIfAbsent(chapter.url, chapter)
        }
        val ordered = deduped.values.toMutableList()
        
        // 默认保持源返回顺序；只有规则显式以 '-' 标记时才反转。
        if (shouldReverse) ordered.reverse()

        val formatted = applyTocFormatJsForSandbox(ordered, effectiveBaseUrlForRequest(effectiveTocUrl))

        // Re-assign sequential indices and numbers to ensure they are 1, 2, 3... in ascending order.
        val result = formatted.mapIndexed { index, chapter ->
            // Use stable ID based on normalized URL hash instead of list index
            val normalizedUrl = AnalyzeUrl.normalizeUrl(chapter.url)
            val stableId = (source.name.hashCode().toLong() shl 32) + (normalizedUrl.hashCode().toLong() and 0xFFFFFFFFL)
            chapter.copy(
                id = stableId,
                number = index.toFloat() + 1f
            )
        }
        android.util.Log.d(
            TAG,
            "getChaptersHelper: book=${manga.title}, tocUrl=$effectiveTocUrl, visitedPages=${visited.size}, rawCount=${chapters.size}, dedupedCount=${ordered.size}, finalCount=${result.size}, shouldReverse=$shouldReverse, branches=${result.groupBy { it.branch }.mapValues { it.value.size }}, first=${result.take(3).map { "${it.id}|${it.branch}|${it.title}|${it.url}" }}, last=${result.takeLast(3).map { "${it.id}|${it.branch}|${it.title}|${it.url}" }}",
        )
        return result
    }

    private suspend fun getChaptersHelperWithStandaloneRuntime(
        manga: Content,
        tocUrl: String,
        runtimeState: StandaloneLegadoRuntimeState,
        initialContent: String? = null,
        initialFinalUrl: String? = null,
    ): List<ContentChapter> {
        val runtimeContext = createStandaloneRuntimeContext(
            state = runtimeState,
            reGetBookAction = {
                runBlocking {
                    reGetBookForPreUpdate(runtimeState)
                }
            },
            refreshTocUrlAction = {
                runBlocking {
                    refreshTocUrlForPreUpdate(runtimeState)
                }
            },
        )
        runtimeContext.setBook(
            org.skepsun.kototoro.core.javascript.BookInfo(
                bookUrl = manga.url,
                name = manga.title,
                author = manga.authors.firstOrNull(),
                tocUrl = tocUrl,
            ),
        )
        runTocPreUpdateJsIfNeeded(runtimeContext)
        val effectiveTocUrl = runtimeContext.getVariable("tocUrl")
            ?.takeIf { it.isNotBlank() }
            ?: runtimeContext.getBook()?.tocUrl
            ?.takeIf { it.isNotBlank() }
            ?: tocUrl

        val tocPageLimit = computeTocPageLimitFromSourceComment(runtimeContext)
        val visited = mutableSetOf<String>()
        val chapters = mutableListOf<ContentChapter>()
        var shouldReverse = false
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(effectiveTocUrl)
        var usedInitial = false
        var pageCount = 0

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue
            pageCount++

            val (content, finalUrl) = if (!usedInitial && url == effectiveTocUrl && initialContent != null && initialFinalUrl != null) {
                usedInitial = true
                initialContent to initialFinalUrl
            } else {
                val request = AnalyzeUrl(
                    url,
                    baseUrl = effectiveBaseUrlForRequest(url),
                    ruleData = runtimeState.ruleData(),
                    runtimeContext = runtimeContext,
                    jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(url) },
                    enabledCookieJarDefault = config.enabledCookieJar != false,
                    useWebViewDefault = config.ruleToc?.webView == true,
                ).build()
                executeRequestWithLoginCheck(
                    plan = request,
                    runtimeContext = runtimeContext,
                ) ?: continue
            }

            val parseResult = BookChapterList.parseWithRuntimeContext(
                content = content,
                baseUrl = finalUrl,
                source = source,
                config = config,
                runtimeContext = runtimeContext,
            )

            shouldReverse = shouldReverse || parseResult.shouldReverse
            chapters.addAll(parseResult.chapters)

            android.util.Log.d(TAG, "TOC page $pageCount: loaded ${parseResult.chapters.size} chapters, total: ${chapters.size}, nextPages: ${parseResult.nextPageUrls.size}")

            parseResult.nextPageUrls.forEach { next ->
                if (!visited.contains(next)) {
                    queue.add(next)
                }
            }

            if (parseResult.nextPageUrls.isEmpty()) {
                enqueueAutoNextTocPageIfPossible(
                    rawUrl = url,
                    tocPageLimit = tocPageLimit,
                    visited = visited,
                    queue = queue,
                    lastParseHadItems = parseResult.chapters.isNotEmpty(),
                )
            }
        }

        val deduped = linkedMapOf<String, ContentChapter>()
        chapters.forEach { chapter ->
            deduped.putIfAbsent(chapter.url, chapter)
        }
        val ordered = deduped.values.toMutableList()
        if (shouldReverse) ordered.reverse()

        val formatted = applyTocFormatJsForStandalone(ordered, runtimeContext, effectiveBaseUrlForRequest(effectiveTocUrl))

        val result = formatted.mapIndexed { index, chapter ->
            val normalizedUrl = AnalyzeUrl.normalizeUrl(chapter.url)
            val stableId = (source.name.hashCode().toLong() shl 32) + (normalizedUrl.hashCode().toLong() and 0xFFFFFFFFL)
            chapter.copy(
                id = stableId,
                number = index.toFloat() + 1f,
            )
        }
        android.util.Log.d(
            TAG,
            "getChaptersHelperWithStandaloneRuntime: book=${manga.title}, tocUrl=$effectiveTocUrl, visitedPages=${visited.size}, rawCount=${chapters.size}, dedupedCount=${ordered.size}, finalCount=${result.size}, shouldReverse=$shouldReverse, branches=${result.groupBy { it.branch }.mapValues { it.value.size }}, first=${result.take(3).map { "${it.id}|${it.branch}|${it.title}|${it.url}" }}, last=${result.takeLast(3).map { "${it.id}|${it.branch}|${it.title}|${it.url}" }}",
        )
        return result
    }

    private fun runTocPreUpdateJsIfNeeded(runtimeContext: LegadoRuleRuntimeContext? = null) {
        val script = config.ruleToc?.preUpdateJs?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            if (runtimeContext != null) {
                AnalyzeRule(
                    content = "",
                    runtimeContext = runtimeContext,
                    baseUrl = config.bookSourceUrl,
                    preUpdateJs = true,
                ).evalJS(script, "")
            } else {
                sandbox.putVariable("baseUrl", config.bookSourceUrl)
                sandbox.eval(script)
            }
        }.onFailure {
            Log.w(TAG, "Failed to execute toc preUpdateJs", it)
        }
    }

    private fun applyTocFormatJsForSandbox(
        chapters: List<ContentChapter>,
        baseUrl: String,
    ): List<ContentChapter> {
        val formatJs = config.ruleToc?.formatJs?.takeIf { it.isNotBlank() } ?: return chapters
        sandbox.putVariable("gInt", "0")
        return chapters.mapIndexed { index, chapter ->
            val chapterContext = org.skepsun.kototoro.core.javascript.ChapterInfo(
                chapterUrl = chapter.url,
                name = chapter.title.orEmpty(),
                index = index + 1,
            )
            sandbox.setChapter(
                LegadoSandbox.ChapterContext(
                    title = chapterContext.name,
                    url = chapterContext.chapterUrl,
                    index = chapterContext.index,
                ),
            )
            sandbox.putVariable("index", (index + 1).toString())
            sandbox.putVariable("title", chapterContext.name)
            sandbox.setResult(chapterContext)
            val formatResult = sandbox.execute(formatJs)
            if (formatResult is String && formatResult.isNotEmpty()) {
                chapter.copy(title = formatResult)
            } else {
                chapter
            }
        }
    }

    private fun applyTocFormatJsForStandalone(
        chapters: List<ContentChapter>,
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        baseUrl: String,
    ): List<ContentChapter> {
        val formatJs = config.ruleToc?.formatJs?.takeIf { it.isNotBlank() } ?: return chapters
        runtimeContext.putVariable("gInt", "0")
        return chapters.mapIndexed { index, chapter ->
            val chapterContext = org.skepsun.kototoro.core.javascript.ChapterInfo(
                chapterUrl = chapter.url,
                name = chapter.title.orEmpty(),
                index = index + 1,
            )
            runtimeContext.setChapter(chapterContext)
            runtimeContext.putVariable("index", (index + 1).toString())
            runtimeContext.putVariable("title", chapterContext.name)
            val formatResult = runtimeContext.executeJs(formatJs, chapterContext, baseUrl)
            if (formatResult is String && formatResult.isNotEmpty()) {
                chapter.copy(title = formatResult)
            } else {
                chapter
            }
        }
    }

    private suspend fun reGetBookForPreUpdate(runtimeState: StandaloneLegadoRuntimeState) {
        val currentBook = runtimeState.getBook() ?: return
        val name = currentBook.name?.trim().orEmpty()
        if (name.isBlank()) return

        val author = currentBook.author?.trim().orEmpty()
        val matched = getList(
            offset = 0,
            order = null,
            filter = ContentListFilter(query = name),
        )
            .asSequence()
            .firstOrNull { item ->
                val itemTitle = item.title.trim()
                val itemAuthor = item.authors.firstOrNull()?.trim().orEmpty()
                itemTitle == name && (author.isBlank() || itemAuthor == author)
            } ?: return

        val refreshed = getDetailsWithStandaloneRuntime(matched, AnalyzeUrl.normalizeUrl(matched.url))
        runtimeState.setBook(
            org.skepsun.kototoro.core.javascript.BookInfo(
                bookUrl = refreshed.url,
                name = refreshed.title,
                author = refreshed.authors.firstOrNull(),
                coverUrl = refreshed.coverUrl,
                intro = refreshed.description,
                kind = refreshed.tags.joinToString(",") { it.title },
                tocUrl = runtimeState.getVariable("tocUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?: currentBook.tocUrl,
            ),
        )
    }

    private suspend fun reGetBookForSandboxPreUpdate() {
        val name = sandbox.getVariable("bookName")?.trim().orEmpty()
        if (name.isBlank()) return
        val author = sandbox.getVariable("bookAuthor")?.trim().orEmpty()

        val matched = getList(
            offset = 0,
            order = null,
            filter = ContentListFilter(query = name),
        ).asSequence().firstOrNull { item ->
            val itemTitle = item.title.trim()
            val itemAuthor = item.authors.firstOrNull()?.trim().orEmpty()
            itemTitle == name && (author.isBlank() || itemAuthor == author)
        } ?: return

        val refreshed = getDetails(matched)
        sandbox.setBook(
            LegadoSandbox.BookContext(
                name = refreshed.title,
                author = refreshed.authors.firstOrNull().orEmpty(),
                url = refreshed.url,
                coverUrl = refreshed.coverUrl.orEmpty(),
                intro = refreshed.description.orEmpty(),
                kind = refreshed.tags.joinToString(",") { it.title },
                tocUrl = sandbox.getVariable("tocUrl").orEmpty(),
            ),
        )
    }

    private suspend fun refreshTocUrlForPreUpdate(runtimeState: StandaloneLegadoRuntimeState) {
        val currentBook = runtimeState.getBook() ?: return
        val runtimeContext = createStandaloneRuntimeContext(state = runtimeState)
        val request = AnalyzeUrl(
            currentBook.bookUrl,
            baseUrl = effectiveBaseUrlForRequest(currentBook.bookUrl),
            ruleData = runtimeState.ruleData(),
            runtimeContext = runtimeContext,
            jsEvaluator = runtimeContext.asJsEvaluator { effectiveBaseUrlForRequest(currentBook.bookUrl) },
            enabledCookieJarDefault = config.enabledCookieJar != false,
            useWebViewDefault = config.ruleBookInfo?.webView == true,
        ).build()
        val result = executeRequestWithLoginCheck(
            plan = request,
            runtimeContext = runtimeContext,
        ) ?: return
        val (content, finalUrl) = result
        val infoSeed = Content(
            id = currentBook.bookUrl.hashCode().toLong(),
            title = currentBook.name.orEmpty(),
            altTitles = emptySet(),
            url = currentBook.bookUrl,
            publicUrl = currentBook.bookUrl,
            rating = -1f,
            contentRating = null,
            coverUrl = currentBook.coverUrl,
            tags = emptySet(),
            state = null,
            authors = currentBook.author?.takeIf { it.isNotBlank() }?.let(::setOf) ?: emptySet(),
            largeCoverUrl = null,
            description = currentBook.intro,
            chapters = null,
            source = source,
        )
        val infoResult = BookInfo.parseWithRuntimeContext(
            manga = infoSeed,
            content = content,
            baseUrl = finalUrl,
            config = config,
            runtimeContext = runtimeContext,
        )
        val refreshed = infoResult.manga
        val updatedTocUrl = infoResult.tocUrl
            ?.takeIf { it.isNotBlank() }
            ?: runtimeContext.getVariable("tocUrl")
            ?.takeIf { it.isNotBlank() }
            ?: currentBook.tocUrl
        runtimeState.setBook(
            currentBook.copy(
                name = refreshed.title.ifBlank { currentBook.name },
                author = refreshed.authors.firstOrNull() ?: currentBook.author,
                coverUrl = refreshed.coverUrl ?: currentBook.coverUrl,
                intro = refreshed.description ?: currentBook.intro,
                tocUrl = updatedTocUrl,
            ),
        )
    }

    private suspend fun refreshTocUrlForSandboxPreUpdate() {
        val bookUrl = sandbox.getVariable("bookUrl")?.trim().orEmpty()
        if (bookUrl.isBlank()) return
        val seed = Content(
            id = bookUrl.hashCode().toLong(),
            title = sandbox.getVariable("bookName").orEmpty(),
            altTitles = emptySet(),
            url = bookUrl,
            publicUrl = bookUrl,
            rating = -1f,
            contentRating = null,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = sandbox.getVariable("bookAuthor")?.takeIf { it.isNotBlank() }?.let(::setOf) ?: emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = null,
            source = source,
        )
        val request = AnalyzeUrl(
            seed.url,
            baseUrl = effectiveBaseUrlForRequest(seed.url),
            sandbox = sandbox,
            enabledCookieJarDefault = config.enabledCookieJar != false,
            useWebViewDefault = config.ruleBookInfo?.webView == true,
        ).build()
        val result = executeRequestWithLoginCheck(request) ?: return
        val (content, finalUrl) = result
        val infoResult = BookInfo.parse(
            manga = seed,
            content = content,
            baseUrl = finalUrl,
            config = config,
            sandbox = sandbox,
        )
        sandbox.setBook(
            LegadoSandbox.BookContext(
                name = infoResult.manga.title,
                author = infoResult.manga.authors.firstOrNull().orEmpty(),
                url = infoResult.manga.url,
                coverUrl = infoResult.manga.coverUrl.orEmpty(),
                intro = infoResult.manga.description.orEmpty(),
                kind = infoResult.manga.tags.joinToString(",") { it.title },
                tocUrl = infoResult.tocUrl.orEmpty(),
            ),
        )
    }

    private fun computeTocPageLimitFromSourceComment(
        runtimeContext: StandaloneLegadoRuleRuntimeContext? = null,
    ): Int? {
        val comment = config.bookSourceComment?.trim().orEmpty()
        if (comment.isBlank()) return null
        // 仅当源脚本定义了 cmpVariable 时才尝试，避免对其它源产生副作用。
        if (!comment.contains("cmpVariable", ignoreCase = true)) return null

        // 兼容 legado-with-MD3 的写法：eval(String(source.bookSourceComment)); cmpVariable();
        val script = """
            try {
              eval(String(source.bookSourceComment));
              var n = (typeof cmpVariable === 'function') ? cmpVariable() : null;
              n;
            } catch (e) {
              null;
            }
        """.trimIndent()

        val result = runtimeContext?.let {
            standaloneListRuntime.eval(
                script = script,
                runtimeContext = it,
                baseUrl = effectiveBaseUrlForRequest(config.bookSourceUrl),
            )
        } ?: sandbox.eval(script) ?: return null
        val value = when (result) {
            is Number -> result.toInt()
            is String -> result.trim().toIntOrNull()
            else -> result.toString().trim().toIntOrNull()
        } ?: return null
        return value.coerceAtLeast(0)
    }

    private fun enqueueAutoNextTocPageIfPossible(
        rawUrl: String,
        tocPageLimit: Int?,
        visited: Set<String>,
        queue: ArrayDeque<String>,
        lastParseHadItems: Boolean
    ) {
        // 无数据则不再翻页（避免无限请求空页）。
        if (!lastParseHadItems) return

        val parsed = parsePagedPostJsonOptions(rawUrl) ?: return
        val currentPage = parsed.page ?: return

        // 若 cmpVariable 未定义：不自动翻页（保持当前行为）。
        val limit = tocPageLimit ?: return

        // limit == 0 表示不限制（含 -1 的约定）。
        if (limit > 0 && currentPage >= limit) return

        val nextPage = currentPage + 1

        // 额外安全阈值：即使“不限制”，也避免规则/站点异常导致无限增长。
        if (nextPage > MAX_AUTO_TOC_PAGES) return

        val nextUrl = buildPagedPostJsonOptionsUrl(parsed, nextPage) ?: return
        if (!visited.contains(nextUrl)) {
            android.util.Log.d(TAG, "[TOC-AutoPage] page=$currentPage -> $nextPage, limit=$limit, urlPart=${parsed.urlPart}")
            queue.add(nextUrl)
        }
    }

    private data class PagedPostJsonOptions(
        val urlPart: String,
        val optionsJson: JSONObject,
        val bodyJson: JSONObject,
        val page: Int?
    )

    private fun parsePagedPostJsonOptions(rawUrl: String): PagedPostJsonOptions? {
        val splitMatch = Regex("\\s*,\\s*(?=\\{)").find(rawUrl) ?: return null
        val urlPart = rawUrl.substring(0, splitMatch.range.first).trim()
        val optionsPart = rawUrl.substring(splitMatch.range.last + 1).trim()

        val optionsJson = runCatching {
            val normalized = if (optionsPart.contains("'")) optionsPart.replace("'", "\"") else optionsPart
            JSONObject(normalized)
        }.getOrNull() ?: return null

        val method = optionsJson.optString("method", "GET").uppercase()
        if (method == "GET") return null

        val bodyAny = optionsJson.opt("body") ?: return null
        val bodyText = when (bodyAny) {
            is String -> bodyAny
            is JSONObject -> bodyAny.toString()
            else -> bodyAny.toString()
        }.trim()
        if (bodyText.isBlank() || !(bodyText.startsWith("{") && bodyText.endsWith("}"))) return null

        val bodyJson = runCatching { JSONObject(bodyText) }.getOrNull() ?: return null
        val page = when (val p = bodyJson.opt("page")) {
            is Number -> p.toInt()
            is String -> p.trim().toIntOrNull()
            else -> null
        }

        return PagedPostJsonOptions(urlPart = urlPart, optionsJson = optionsJson, bodyJson = bodyJson, page = page)
    }

    private fun buildPagedPostJsonOptionsUrl(parsed: PagedPostJsonOptions, page: Int): String? {
        val newBody = JSONObject(parsed.bodyJson.toString())
        newBody.put("page", page)
        val newOptions = JSONObject(parsed.optionsJson.toString())
        // 为兼容 legado 源常见写法，保持 body 为 JSON 字符串（而非嵌套对象）。
        newOptions.put("body", newBody.toString())
        return parsed.urlPart + "," + newOptions.toString()
    }


    private fun String.equalsIgnoreCase(other: String): Boolean = this.equals(other, ignoreCase = true)

    private fun parseConfigHeaders(
        headerStr: String?,
        runtimeContext: StandaloneLegadoRuleRuntimeContext? = null,
    ): Map<String, String> {
        if (headerStr.isNullOrBlank()) return emptyMap()
        
        var jsonStr = headerStr.trim()
        
        // Check if header is JavaScript code that needs execution
        if (jsonStr.startsWith("<js>", ignoreCase = true) || 
            jsonStr.startsWith("@js:", ignoreCase = true)) {
            
            // Extract and execute JavaScript
            val jsCode = when {
                jsonStr.startsWith("<js>", ignoreCase = true) -> {
                    jsonStr.removePrefix("<js>").removeSuffix("</js>").trim()
                }
                jsonStr.startsWith("@js:", ignoreCase = true) -> {
                    jsonStr.removePrefix("@js:").trim()
                }
                else -> jsonStr
            }
            
            try {
                val result = runtimeContext?.let {
                    standaloneListRuntime.eval(
                        script = jsCode.trim(),
                        runtimeContext = it,
                        baseUrl = effectiveBaseUrlForRequest(config.bookSourceUrl),
                    )
                }?.toString() ?: sandbox.eval(jsCode.trim())?.toString()
                if (!result.isNullOrBlank()) {
                    jsonStr = result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute header JS: ${e.message}", e)
                return emptyMap()
            }
        }
        
        return try {
            val headers = mutableMapOf<String, String>()
            val normalized = jsonStr.replace("'", "\"")
            val jsonObj = JSONObject(normalized)
            jsonObj.keys().forEach { key ->
                val value = jsonObj.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    val content = value.toString().removeSurrounding("\"")
                    if (content.isNotBlank()) {
                        headers[key] = content
                    }
                }
            }
            headers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse headers JSON: $jsonStr", e)
            emptyMap()
        }
    }

    private fun isWebViewEnabled(
        ruleValue: String?,
        runtimeContext: StandaloneLegadoRuleRuntimeContext? = null,
    ): Boolean {
        if (ruleValue == null) return false
        if (ruleValue == "true" || ruleValue == "1") return true
        if (ruleValue == "false" || ruleValue == "0") return false
        
        // Evaluate as JS if it looks like a script
        if (ruleValue.startsWith("<js>") || ruleValue.startsWith("@js:")) {
            return try {
                val script = if (ruleValue.startsWith("<js>")) {
                    ruleValue.removePrefix("<js>").removeSuffix("</js>")
                } else {
                    ruleValue.removePrefix("@js:")
                }
                val result = runtimeContext?.let {
                    standaloneListRuntime.eval(
                        script = script,
                        runtimeContext = it,
                        baseUrl = effectiveBaseUrlForRequest(config.bookSourceUrl),
                    )
                } ?: sandbox.eval(script)
                result?.toString() == "true"
            } catch (e: Exception) {
                false
            }
        }
        
        return false
    }
}
