package org.skepsun.kototoro.core.parser.legado

import android.net.Uri
import androidx.media3.common.MediaItem
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxJsEvaluator
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoHttpExecutor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmName

/**
 * URL builder with placeholder substitution and JavaScript support.
 * Handles Legado URL templates and request configuration.
 *
 * Based on legado-with-MD3 AnalyzeUrl pattern.
 *
 * Supports:
 * - Template vars: {{key}}, {{page}}, {{baseUrl}}
 * - POST body with method specification
 * - Header configuration
 * - JavaScript URL generation
 */
class AnalyzeUrl(
    ruleUrl: String,
    private val key: String? = null,
    private val page: Int = 1,
    private val speakText: String? = null,
    private val speakSpeed: Int? = null,
    private var baseUrl: String = "",
    private val ruleData: RuleDataInterface? = null,
    private val runtimeContext: LegadoRuleRuntimeContext? = null,
    private val chapter: ChapterInfo? = null,
    private val infoMap: MutableMap<String, String>? = null,
    private val jsEvaluator: LegadoJsEvaluator? = null,
    private val sandbox: LegadoSandbox? = null,
    private val readTimeoutMs: Long? = null,
    private val callTimeoutMs: Long? = null,
    private val enabledCookieJarDefault: Boolean = true,
    private val useWebViewDefault: Boolean = false,
    private val webJsDefault: String? = null,
    private val webViewDelayDefault: Long = 0,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val httpExecutor: LegadoHttpExecutor? = null,
    private val fallbackSource: Any? = null,
) {
    var ruleUrl: String = ruleUrl
        private set

    private val runtimeHeaderOverrides = LinkedHashMap<String, String>()
    private val lastBuiltPlan = AtomicReference<LegadoRequestPlan?>()
    private var builtRuleUrl: String? = null
    private var initialized = false
    private val javaBridge by lazy { JavaBridge(this) }

    constructor(mUrl: String) : this(ruleUrl = mUrl)

    constructor(
        mUrl: String,
        key: String? = null,
        page: Int = 1,
        speakText: String? = null,
        speakSpeed: Int? = null,
        baseUrl: String = "",
        ruleData: RuleDataInterface? = null,
        chapter: ChapterInfo? = null,
        readTimeout: Long? = null,
        callTimeout: Long? = null,
        coroutineContext: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        headerMapF: Map<String, String>? = null,
        infoMap: MutableMap<String, String>? = null,
        runtimeContext: LegadoRuleRuntimeContext? = null,
        jsEvaluator: LegadoJsEvaluator? = null,
        sandbox: LegadoSandbox? = null,
        httpExecutor: LegadoHttpExecutor? = null,
    ) : this(
        ruleUrl = mUrl,
        key = key,
        page = page,
        speakText = speakText,
        speakSpeed = speakSpeed,
        baseUrl = baseUrl,
        ruleData = ruleData,
        runtimeContext = runtimeContext,
        chapter = chapter,
        infoMap = infoMap,
        jsEvaluator = jsEvaluator,
        sandbox = sandbox,
        readTimeoutMs = readTimeout,
        callTimeoutMs = callTimeout,
        defaultHeaders = headerMapF.orEmpty(),
        httpExecutor = httpExecutor,
        fallbackSource = null,
    )

    constructor(
        mUrl: String,
        source: Any?,
        coroutineContext: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        headerMapF: Map<String, String>? = null,
        key: String? = null,
        page: Int = 1,
        speakText: String? = null,
        speakSpeed: Int? = null,
        baseUrl: String = resolveSourceBaseUrl(source),
        ruleData: RuleDataInterface? = null,
        chapter: ChapterInfo? = null,
        readTimeout: Long? = null,
        callTimeout: Long? = null,
        hasLoginHeader: Boolean = true,
        infoMap: MutableMap<String, String>? = null,
        runtimeContext: LegadoRuleRuntimeContext? = null,
        jsEvaluator: LegadoJsEvaluator? = null,
        sandbox: LegadoSandbox? = null,
        httpExecutor: LegadoHttpExecutor? = null,
    ) : this(
        ruleUrl = mUrl,
        key = key,
        page = page,
        speakText = speakText,
        speakSpeed = speakSpeed,
        baseUrl = baseUrl,
        ruleData = ruleData,
        runtimeContext = runtimeContext,
        chapter = chapter,
        infoMap = infoMap,
        jsEvaluator = jsEvaluator,
        sandbox = sandbox,
        readTimeoutMs = readTimeout,
        callTimeoutMs = callTimeout,
        enabledCookieJarDefault = isCookieJarEnabledByDefault(source),
        defaultHeaders = buildSourceDefaultHeaders(
            source = source,
            headerMapF = headerMapF,
            hasLoginHeader = hasLoginHeader,
            runtimeContext = runtimeContext,
            jsEvaluator = jsEvaluator,
            sandbox = sandbox,
            key = key,
            page = page,
            baseUrl = baseUrl,
        ),
        httpExecutor = httpExecutor,
        fallbackSource = source,
    )

    val url: String
        get() = effectivePlan().url

    val type: String?
        get() = effectivePlan().type

    val urlNoQuery: String
        get() = url.substringBefore('#').substringBefore('?')

    val serverID: Long?
        get() = effectivePlan().serverId


    companion object {
        internal const val TAG = "AnalyzeUrl"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal val optionsSplitRegex = Regex("\\s*,\\s*(?=\\{)")

        // Regex for @js: or <js>...</js>
        internal val JS_PATTERN = Regex("@js:([\\s\\S]*)|<js>([\\s\\S]*?)</js>", RegexOption.IGNORE_CASE)
        private val DATA_URI_REGEX = Regex("^data:.*?;base64,(.*)", RegexOption.IGNORE_CASE)
        private const val BINARY_PLAN_MARKER = "__kototoro_binary__"
        private const val MEDIA_ITEM_SPLIT_TAG = "\uD83D\uDEA7"

        /**
         * Normalize URL for stable ID generation.
         * Removes Legado options (,{...}) and trailing slashes for GET.
         *
         * 注意：对于 POST（或带 body 的请求），options 会影响请求语义（同一路径不同 body 对应不同内容），
         * 若无区分会导致章节/页面缓存键冲突（表现为“所有章节都是同一个章节”）。
         */
        fun normalizeUrl(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val splitMatch = optionsSplitRegex.find(url)
            if (splitMatch == null) {
                var normalized = url.trim()
                if (normalized.endsWith("/") && normalized.length > 8) {
                    normalized = normalized.substring(0, normalized.length - 1)
                }
                return normalized
            }

            val urlPart = url.substring(0, splitMatch.range.first).trim()
            val optionsPart = url.substring(splitMatch.range.last + 1).trim()

            // 尝试解析 options；若是 POST/带 body，则把 options 的摘要拼到 key 上避免冲突
            val optionsKeySuffix = runCatching {
                val normalizedOptions = if (optionsPart.contains("'")) {
                    optionsPart.replace("'", "\"")
                } else optionsPart
                val optionsJson = JSONObject(normalizedOptions)
                val method = optionsJson.optString("method", "GET").uppercase()
                val body = optionsJson.optString("body", "")
                if (method != "GET" || body.isNotBlank()) {
                    val signature = buildString {
                        append(method)
                        if (body.isNotBlank()) {
                            append("|")
                            append(body)
                        }
                    }
                    "#opt=" + signature.hashCode().toUInt().toString(16)
                } else ""
            }.getOrDefault("")

            var normalized = urlPart + optionsKeySuffix

            // Remove trailing slash
            if (normalized.endsWith("/") && normalized.length > 8) {
                normalized = normalized.substring(0, normalized.length - 1)
            }

            return normalized
        }

        internal fun jsValueToTemplateString(value: Any?): String? {
            return when (value) {
                null -> null
                is String -> value
                is Double -> if (value % 1.0 == 0.0) {
                    String.format(java.util.Locale.ROOT, "%.0f", value)
                } else {
                    value.toString()
                }
                is Float -> if (value % 1f == 0f) {
                    String.format(java.util.Locale.ROOT, "%.0f", value.toDouble())
                } else {
                    value.toString()
                }
                else -> value.toString()
            }
        }

        internal fun resolveUrl(base: String, relative: String): String {
            if (relative.isBlank()) return base
            if (relative.startsWith("http://") || relative.startsWith("https://") || relative.startsWith("data:")) {
                return relative
            }

            if (relative.startsWith("//")) {
                val protocol = if (base.startsWith("https")) "https" else "http"
                return "$protocol:$relative"
            }

            val host = getHost(base)
            if (host.isNotEmpty() && relative.startsWith(host)) {
                val protocol = if (base.startsWith("https")) "https" else "http"
                return "$protocol://$relative"
            }

            return try {
                java.net.URL(java.net.URL(base), relative).toString()
            } catch (e: Exception) {
                if (relative.startsWith("/")) {
                    try {
                        val baseUri = java.net.URL(base)
                        "${baseUri.protocol}://${baseUri.host}$relative"
                    } catch (_: Exception) {
                        relative
                    }
                } else {
                    relative
                }
            }
        }

        private fun getHost(url: String?): String {
            if (url.isNullOrBlank()) return ""
            return try {
                java.net.URL(url).host ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        private fun resolveSourceBaseUrl(source: Any?): String {
            return when (source) {
                null -> ""
                is LegadoBookSource -> source.bookSourceUrl
                else -> LegadoReflectiveAccess.readProperty(source, "bookSourceUrl")?.toString()
                    ?: LegadoReflectiveAccess.readProperty(source, "sourceUrl")?.toString()
                    .orEmpty()
            }
        }

        private fun isCookieJarEnabledByDefault(source: Any?): Boolean {
            return when (source) {
                null -> true
                is LegadoBookSource -> source.enabledCookieJar != false
                else -> LegadoReflectiveAccess.readProperty(source, "enabledCookieJar") as? Boolean != false
            }
        }

        private fun resolveSourceTag(source: Any?): String? {
            return when (source) {
                null -> null
                is LegadoBookSource -> source.bookSourceName
                else -> invokeSourceMethod(source, "getTag")?.toString()
                    ?: LegadoReflectiveAccess.readProperty(source, "bookSourceName")?.toString()
                    ?: LegadoReflectiveAccess.readProperty(source, "sourceName")?.toString()
            }
        }

        private fun invokeSourceMethod(source: Any?, name: String, vararg args: Any?): Any? {
            val target = source ?: return null
            val method = target::class.java.methods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterTypes.size == args.size
            } ?: return null
            return runCatching { method.invoke(target, *args) }.getOrNull()
        }

        private fun buildSourceDefaultHeaders(
            source: Any?,
            headerMapF: Map<String, String>?,
            hasLoginHeader: Boolean = true,
            runtimeContext: LegadoRuleRuntimeContext? = null,
            jsEvaluator: LegadoJsEvaluator? = null,
            sandbox: LegadoSandbox? = null,
            key: String? = null,
            page: Int = 1,
            baseUrl: String = resolveSourceBaseUrl(source),
        ): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val sourceObject = runtimeContext?.getSourceObject() ?: source
            invokeSourceHeaderMap(sourceObject, hasLoginHeader)?.let(result::putAll)
                ?: parseHeaderConfig(
                    rawHeader = when (source) {
                        is LegadoBookSource -> source.header
                        else -> LegadoReflectiveAccess.readProperty(source, "header")?.toString()
                    },
                    sourceObject = sourceObject,
                    jsEvaluator = jsEvaluator,
                    sandbox = sandbox,
                    runtimeContext = runtimeContext,
                    key = key,
                    page = page,
                    baseUrl = baseUrl,
                ).let(result::putAll)
            if (hasLoginHeader) {
                invokeLoginHeaderMap(sourceObject)?.let(result::putAll)
            }
            headerMapF?.let(result::putAll)
            return result
        }

        private fun parseHeaderConfig(
            rawHeader: String?,
            sourceObject: Any?,
            jsEvaluator: LegadoJsEvaluator?,
            sandbox: LegadoSandbox?,
            runtimeContext: LegadoRuleRuntimeContext?,
            key: String?,
            page: Int,
            baseUrl: String,
        ): Map<String, String> {
            val headerText = rawHeader?.takeIf { it.isNotBlank() } ?: return emptyMap()
            val evaluated = evaluateSourceHeaderScript(
                rawHeader = headerText,
                sourceObject = sourceObject,
                jsEvaluator = jsEvaluator,
                sandbox = sandbox,
                runtimeContext = runtimeContext,
                key = key,
                page = page,
                baseUrl = baseUrl,
            )
            return KototoroLegadoHttpExecutor.parseHeaderJson(evaluated)
        }

        private fun evaluateSourceHeaderScript(
            rawHeader: String,
            sourceObject: Any?,
            jsEvaluator: LegadoJsEvaluator?,
            sandbox: LegadoSandbox?,
            runtimeContext: LegadoRuleRuntimeContext?,
            key: String?,
            page: Int,
            baseUrl: String,
        ): String {
            return runCatching {
                when {
                    rawHeader.startsWith("@js:", ignoreCase = true) -> {
                        evaluateHeaderScript(
                            script = rawHeader.substring(4),
                            sourceObject = sourceObject,
                            jsEvaluator = jsEvaluator,
                            sandbox = sandbox,
                            runtimeContext = runtimeContext,
                            key = key,
                            page = page,
                            baseUrl = baseUrl,
                        )
                    }
                    rawHeader.startsWith("<js>", ignoreCase = true) && rawHeader.lastIndexOf("<") > 4 -> {
                        evaluateHeaderScript(
                            script = rawHeader.substring(4, rawHeader.lastIndexOf("<")),
                            sourceObject = sourceObject,
                            jsEvaluator = jsEvaluator,
                            sandbox = sandbox,
                            runtimeContext = runtimeContext,
                            key = key,
                            page = page,
                            baseUrl = baseUrl,
                        )
                    }
                    else -> rawHeader
                }
            }.getOrDefault(rawHeader)
        }

        private fun evaluateHeaderScript(
            script: String,
            sourceObject: Any?,
            jsEvaluator: LegadoJsEvaluator?,
            sandbox: LegadoSandbox?,
            runtimeContext: LegadoRuleRuntimeContext?,
            key: String?,
            page: Int,
            baseUrl: String,
        ): String {
            val evaluator = jsEvaluator ?: LegadoSandboxJsEvaluator(
                sandbox = sandbox,
                runtimeContext = runtimeContext,
                key = key,
                page = page,
                baseUrl = baseUrl,
            )
            return runtimeContext?.withJavaBridge(sourceObject) {
                evaluator.evaluate(script, null).toString()
            } ?: evaluator.evaluate(script, null).toString()
        }

        private fun invokeSourceHeaderMap(sourceObject: Any?, hasLoginHeader: Boolean): Map<String, String>? {
            return runCatching {
                val method = sourceObject?.javaClass?.methods?.firstOrNull { candidate ->
                    candidate.name == "getHeaderMap" &&
                        (candidate.parameterCount == 0 || candidate.parameterCount == 1)
                } ?: return null
                val raw = when (method.parameterCount) {
                    0 -> method.invoke(sourceObject)
                    else -> method.invoke(sourceObject, hasLoginHeader)
                }
                raw as? Map<*, *>
            }.getOrNull()?.entries?.associateNotNull()
        }

        private fun invokeLoginHeaderMap(sourceObject: Any?): Map<String, String>? {
            return runCatching {
                val method = sourceObject?.javaClass?.methods?.firstOrNull { candidate ->
                    candidate.name == "getLoginHeaderMap" && candidate.parameterCount == 0
                } ?: return null
                method.invoke(sourceObject) as? Map<*, *>
            }.getOrNull()?.entries?.associateNotNull()
        }

        private fun Set<Map.Entry<*, *>>.associateNotNull(): Map<String, String> {
            return buildMap {
                for ((key, value) in this@associateNotNull) {
                    val headerName = key?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val headerValue = value?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    put(headerName, headerValue)
                }
            }
        }

        @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
        fun AnalyzeUrl.getMediaItem(): MediaItem = this.getMediaItem()
    }

    private val activeRuntimeContext: LegadoRuleRuntimeContext? by lazy {
        runtimeContext
            ?: sandbox?.let(::LegadoSandboxRuleRuntimeContext)
            ?: fallbackSource?.let(::SourceOnlyLegadoUrlRuntimeContext)
    }

    /**
     * Build the final URL with all substitutions applied
     */
    fun build(): LegadoRequestPlan {
        if (ruleUrl.isBlank()) {
            return LegadoRequestPlan(url = baseUrl)
        }
        val activeJsEvaluator = jsEvaluator ?: LegadoSandboxJsEvaluator(
            sandbox = sandbox,
            runtimeContext = activeRuntimeContext,
            key = key,
            page = page,
            baseUrl = baseUrl,
        )
        val evaluatedRuleUrl = LegadoUrlTemplateEvaluator(
            key = key,
            page = page,
            baseUrl = baseUrl,
            jsEvaluator = activeJsEvaluator,
        ).evaluate(ruleUrl)

        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = enabledCookieJarDefault,
            useWebViewDefault = useWebViewDefault,
            webJsDefault = webJsDefault,
            webViewDelayDefault = webViewDelayDefault,
            defaultHeaders = defaultHeaders,
            jsEvaluator = activeJsEvaluator,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        ).build(evaluatedRuleUrl)
        builtRuleUrl = evaluatedRuleUrl
        initialized = true
        val mergedHeaders = LinkedHashMap(plan.headers).apply {
            putAll(runtimeHeaderOverrides)
        }
        return plan.copy(headers = mergedHeaders).also(lastBuiltPlan::set)
    }

    fun initUrl() {
        runtimeHeaderOverrides.clear()
        lastBuiltPlan.set(null)
        builtRuleUrl = null
        initialized = false
        build()
    }

    @get:JvmName("getHeaderMapProperty")
    val headerMap: MutableMap<String, String>
        get() {
            if (runtimeHeaderOverrides.isEmpty()) {
                runtimeHeaderOverrides.putAll(build().headers)
            }
            return runtimeHeaderOverrides
        }

    fun getHeaderMap(): MutableMap<String, String> = headerMap

    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false,
        skipRateLimit: Boolean = false,
    ): org.skepsun.kototoro.core.javascript.StrResponse {
        return getStrResponseAwaitInternal(
            jsStr = jsStr,
            sourceRegex = sourceRegex,
            overrideUrlRegex = null,
            useWebView = useWebView,
            isTest = isTest,
            skipRateLimit = skipRateLimit,
        )
    }

    suspend fun getStrResponseAwaitWithOverrideUrlRegex(
        jsStr: String? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        useWebView: Boolean = true,
    ): org.skepsun.kototoro.core.javascript.StrResponse {
        return getStrResponseAwaitInternal(
            jsStr = jsStr,
            sourceRegex = sourceRegex,
            overrideUrlRegex = overrideUrlRegex,
            useWebView = useWebView,
        )
    }

    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): org.skepsun.kototoro.core.javascript.StrResponse {
        return runBlocking {
            getStrResponseAwait(jsStr, sourceRegex, useWebView)
        }
    }

    fun getStrResponseWithOverrideUrlRegex(
        jsStr: String? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        useWebView: Boolean = true,
    ): org.skepsun.kototoro.core.javascript.StrResponse {
        return runBlocking {
            getStrResponseAwaitWithOverrideUrlRegex(jsStr, sourceRegex, overrideUrlRegex, useWebView)
        }
    }

    private suspend fun getStrResponseAwaitInternal(
        jsStr: String? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false,
        skipRateLimit: Boolean = false,
    ): org.skepsun.kototoro.core.javascript.StrResponse {
        val startedAt = if (skipRateLimit) 0L else System.currentTimeMillis()
        return try {
            val plan = effectivePlan(jsStr, sourceRegex, overrideUrlRegex, useWebView)
            val response = executeStringPlan(plan)
            toStrResponse(response).apply {
                if (!skipRateLimit) {
                    putCallTime((System.currentTimeMillis() - startedAt).toInt())
                }
            }
        } catch (error: Throwable) {
            if (!isTest) {
                throw error
            }
            org.skepsun.kototoro.core.javascript.StrResponse(
                effectivePlan(jsStr, sourceRegex, overrideUrlRegex, useWebView).url,
                error.message,
            ).apply {
                putCallTime(errorCodeForTest(error))
            }
        }
    }

    private fun errorCodeForTest(error: Throwable): Int {
        return when (error) {
            is java.net.SocketTimeoutException -> -2
            is java.net.UnknownHostException -> -3
            is java.net.ConnectException -> -4
            is java.net.SocketException -> -5
            is javax.net.ssl.SSLException -> -6
            is java.io.InterruptedIOException -> {
                if (error.message?.contains("timeout", ignoreCase = true) == true) {
                    -1
                } else {
                    -7
                }
            }
            else -> -7
        }
    }

    suspend fun getResponseAwait(): Response {
        val plan = effectivePlan()
        getByteArrayIfDataUri(plan.url)?.let { bytes ->
            return buildOkHttpResponse(
                url = plan.url,
                code = 200,
                headers = emptyMap(),
                bodyBytes = bytes,
            )
        }
        if (plan.type != null) {
            val httpResponse = executeBinaryPlan(plan)
            return buildOkHttpResponse(
                url = httpResponse.url,
                code = httpResponse.code ?: 200,
                headers = httpResponse.headers,
                bodyBytes = decodeHexBody(httpResponse.body),
            )
        }
        return executePlan(plan).let { httpResponse ->
            buildOkHttpResponse(
                url = httpResponse.url,
                code = httpResponse.code ?: 200,
                headers = httpResponse.headers,
                bodyText = httpResponse.body,
            )
        }
    }

    fun getResponse(): Response {
        return runBlocking {
            getResponseAwait()
        }
    }

    fun getErrResponse(e: Throwable): Response {
        return buildOkHttpResponse(
            url = effectivePlan().url,
            code = 500,
            headers = emptyMap(),
            bodyText = e.stackTraceToString(),
            message = e.message ?: "Error Response",
        )
    }

    fun getErrStrResponse(e: Throwable): org.skepsun.kototoro.core.javascript.StrResponse {
        return org.skepsun.kototoro.core.javascript.StrResponse(getErrResponse(e), e.stackTraceToString())
    }

    suspend fun getByteArrayAwait(): ByteArray {
        val plan = effectivePlan()
        getByteArrayIfDataUri(plan.url)?.let { return it }
        return decodeHexBody(executeBinaryPlan(plan).body)
    }

    fun getByteArray(): ByteArray {
        return runBlocking {
            getByteArrayAwait()
        }
    }

    suspend fun getInputStreamAwait(): InputStream {
        return ByteArrayInputStream(getByteArrayAwait())
    }

    fun getInputStream(): InputStream {
        return runBlocking {
            getInputStreamAwait()
        }
    }

    fun getGlideUrl(): Any {
        return getUrlAndHeaders()
    }

    fun getMediaItem(): MediaItem {
        val plan = effectivePlan()
        val mediaUri = formatMediaItemUri(plan.url, plan.headers)
        return MediaItem.Builder()
            .setMediaId(mediaUri)
            .setUri(mediaUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(mediaUri))
                    .build(),
            )
            .build()
    }

    fun getUrlAndHeaders(): Pair<String, Map<String, String>> {
        val plan = effectivePlan()
        return plan.url to plan.headers
    }

    fun getUserAgent(): String {
        return headerMap.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()
    }

    fun isPost(): Boolean {
        return effectivePlan().method.equals("POST", ignoreCase = true)
    }

    fun getSource(): Any? {
        return activeRuntimeContext?.getSourceObject()
    }

    fun getTag(): String? {
        return activeRuntimeContext?.getSourceTag()
    }

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val evaluator = jsEvaluator ?: LegadoSandboxJsEvaluator(
            sandbox = sandbox,
            runtimeContext = activeRuntimeContext,
            key = key,
            page = page,
            baseUrl = baseUrl,
        )
        bindRuntimeVariables(result)
        return activeRuntimeContext?.withJavaBridge(javaBridge) {
            evaluator.evaluate(jsStr, result)
        } ?: evaluator.evaluate(jsStr, result)
    }

    fun put(key: String, value: String): String {
        chapter?.let {
            it.putVariable(key, value)
            return value
        }
        activeRuntimeContext?.getChapter()?.let {
            it.putVariable(key, value)
            return value
        }
        ruleData?.let {
            it.putVariable(key, value)
            return value
        }
        activeRuntimeContext?.getSourceObject()?.let {
            return activeRuntimeContext?.putSourceVariable(key, value) ?: value
        }
        infoMap?.let {
            it[key] = value
            return value
        }
        activeRuntimeContext?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        when (key) {
            "bookName" -> activeRuntimeContext?.getBook()?.name?.takeIf { !it.isNullOrEmpty() }?.let { return it }
            "title" -> chapter?.name?.takeIf { it.isNotEmpty() }?.let { return it }
                ?: activeRuntimeContext?.getChapter()?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        activeRuntimeContext?.getChapter()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        activeRuntimeContext?.getBook()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        infoMap?.get(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        activeRuntimeContext?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        activeRuntimeContext?.getSourceVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun bindRuntimeVariables(result: Any?) {
        val activeChapter = chapter ?: activeRuntimeContext?.getChapter()
        val activeBook = activeRuntimeContext?.getBook()
        val jsBookBinding = resolveJavaScriptBookBinding()
        activeRuntimeContext?.putVariableAny("page", page)
        key?.let { activeRuntimeContext?.putVariable("key", it) }
        speakText?.let { activeRuntimeContext?.putVariable("speakText", it) }
        speakSpeed?.let { activeRuntimeContext?.putVariableAny("speakSpeed", it) }
        activeRuntimeContext?.putVariable("baseUrl", baseUrl)
        if (activeBook != null) {
            activeRuntimeContext?.setBook(activeBook)
            activeRuntimeContext?.putVariable("bookName", activeBook.name.orEmpty())
            activeRuntimeContext?.putVariable("bookAuthor", activeBook.author.orEmpty())
            activeRuntimeContext?.putVariable("bookUrl", activeBook.bookUrl)
        }
        if (activeChapter != null) {
            activeRuntimeContext?.setChapter(activeChapter)
            activeRuntimeContext?.putVariable("title", activeChapter.name)
            activeRuntimeContext?.putVariable("chapterName", activeChapter.name)
            activeRuntimeContext?.putVariable("chapterUrl", activeChapter.chapterUrl)
        }
        activeRuntimeContext?.putVariableAny("result", result)
        activeRuntimeContext?.putVariableAny("src", result)
        activeRuntimeContext?.putVariableAny("book", jsBookBinding)
        activeRuntimeContext?.putVariableAny("chapter", activeChapter)
        activeRuntimeContext?.putVariableAny("infoMap", infoMap)
    }

    private fun resolveJavaScriptBookBinding(): Any? {
        return when {
            ruleData == null -> activeRuntimeContext?.getBook()
            LegadoReflectiveAccess.readProperty(ruleData, "bookUrl") != null -> ruleData
            else -> activeRuntimeContext?.getBook()
        }
    }

    private suspend fun executePlan(plan: LegadoRequestPlan): LegadoHttpResponse {
        val executor = httpExecutor ?: error("AnalyzeUrl requires httpExecutor for runtime execution")
        return executor.execute(plan)
    }

    private suspend fun executeStringPlan(plan: LegadoRequestPlan): LegadoHttpResponse {
        if (plan.type != null) {
            return executeBinaryPlan(plan)
        }
        val response = executePlan(plan)
        val bodyJs = plan.bodyJs?.takeIf { it.isNotBlank() } ?: return response
        val transformedBody = evalJS(bodyJs, response.body).toString()
        return response.copy(body = transformedBody)
    }

    private suspend fun executeBinaryPlan(plan: LegadoRequestPlan): LegadoHttpResponse {
        val binaryPlan = if (plan.type != null) plan else plan.copy(type = BINARY_PLAN_MARKER)
        return executePlan(binaryPlan)
    }

    private fun effectivePlan(
        jsStr: String? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        useWebView: Boolean? = null,
    ): LegadoRequestPlan {
        val basePlan = if (initialized) {
            lastBuiltPlan.get() ?: build()
        } else {
            build()
        }
        val planWithCurrentHeaders = if (runtimeHeaderOverrides.isEmpty()) {
            basePlan
        } else {
            basePlan.copy(
                headers = LinkedHashMap(basePlan.headers).apply {
                    putAll(runtimeHeaderOverrides)
                },
            )
        }
        val effectiveWebJs = jsStr?.takeIf { it.isNotBlank() } ?: basePlan.webJs
        val effectiveSourceRegex = sourceRegex?.takeIf { it.isNotBlank() } ?: basePlan.sourceRegex
        val effectiveOverrideUrlRegex =
            overrideUrlRegex?.takeIf { it.isNotBlank() } ?: basePlan.overrideUrlRegex
        val shouldUseWebView = useWebView ?: basePlan.useWebView
        if (
            effectiveWebJs == planWithCurrentHeaders.webJs &&
            effectiveSourceRegex == planWithCurrentHeaders.sourceRegex &&
            effectiveOverrideUrlRegex == planWithCurrentHeaders.overrideUrlRegex &&
            shouldUseWebView == planWithCurrentHeaders.useWebView
        ) {
            return planWithCurrentHeaders
        }
        return planWithCurrentHeaders.copy(
            useWebView = shouldUseWebView,
            webJs = effectiveWebJs,
            sourceRegex = effectiveSourceRegex,
            overrideUrlRegex = effectiveOverrideUrlRegex,
        )
    }

    private fun buildOkHttpResponse(
        url: String,
        code: Int,
        headers: Map<String, String>,
        bodyText: String,
        message: String = "",
    ): Response {
        return buildOkHttpResponse(
            url = url,
            code = code,
            headers = headers,
            bodyBytes = bodyText.toByteArray(Charsets.UTF_8),
            message = message,
            contentTypeOverride = "text/plain",
        )
    }

    private fun buildOkHttpResponse(
        url: String,
        code: Int,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        message: String = "",
        contentTypeOverride: String? = null,
    ): Response {
        val mediaType = contentTypeOverride
            ?: headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = runCatching { Request.Builder().url(url).build() }
            .getOrElse { Request.Builder().url("http://localhost/").build() }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .headers(
                Headers.Builder().apply {
                    headers.forEach { (name, value) -> add(name, value) }
                }.build(),
            )
            .body(bodyBytes.toResponseBody(mediaType?.toMediaTypeOrNull()))
            .build()
    }

    private fun getByteArrayIfDataUri(url: String): ByteArray? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val data = DATA_URI_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
        return java.util.Base64.getDecoder().decode(data)
    }

    private fun decodeHexBody(body: String): ByteArray {
        if (body.isEmpty()) return ByteArray(0)
        require(body.length % 2 == 0) { "Invalid hex body length: ${body.length}" }
        return ByteArray(body.length / 2) { index ->
            body.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun toStrResponse(response: LegadoHttpResponse): org.skepsun.kototoro.core.javascript.StrResponse {
        return org.skepsun.kototoro.core.javascript.StrResponse(
            url = response.url,
            bodyText = response.body,
            code = response.code ?: 200,
            headers = response.headers,
        )
    }

    private fun formatMediaItemUri(
        url: String,
        headers: Map<String, String>,
    ): String {
        if (headers.isEmpty()) return url
        val headersJson = JSONObject()
        headers.forEach { (key, value) ->
            headersJson.put(key, value)
        }
        return url + MEDIA_ITEM_SPLIT_TAG + headersJson.toString()
    }

    private class SourceOnlyLegadoUrlRuntimeContext(
        private val source: Any,
    ) : LegadoRuleRuntimeContext {
        private val variables = linkedMapOf<String, Any?>()
        private val sourceVariables = linkedMapOf<String, String>()
        private var book: BookInfo? = null
        private var chapter: ChapterInfo? = null

        override fun evalJs(script: String, result: Any?, baseUrl: String): Any? = result

        override fun executeJs(script: String, result: Any?, baseUrl: String): Any? = result

        override fun reGetBook() = Unit

        override fun refreshTocUrl() = Unit

        override fun putVariable(key: String, value: String) {
            variables[key] = value
        }

        override fun putVariableAny(key: String, value: Any?) {
            variables[key] = value
        }

        override fun getVariable(key: String): String? = variables[key]?.toString()

        override fun getVariableAny(key: String): Any? = variables[key]

        override fun putSourceVariable(key: String, value: String): String {
            sourceVariables[key] = value
            invokeSourceMethod(source, "put", key, value)
            return value
        }

        override fun getSourceVariable(key: String): String {
            return sourceVariables[key]
                ?: invokeSourceMethod(source, "get", key)?.toString().orEmpty()
        }

        override fun setBook(book: BookInfo?) {
            this.book = book
        }

        override fun getBook(): BookInfo? = book

        override fun setChapter(chapter: ChapterInfo?) {
            this.chapter = chapter
        }

        override fun getChapter(): ChapterInfo? = chapter

        override fun getSourceObject(): Any = source

        override fun getSourceTag(): String? = resolveSourceTag(source)
    }

    private class JavaBridge(
        private val owner: AnalyzeUrl,
    ) {
        fun initUrl() = owner.initUrl()

        fun getHeaderMap(): MutableMap<String, String> = owner.getHeaderMap()

        fun getResponse(): Response = owner.getResponse()

        fun getStrResponse(
            jsStr: String? = null,
            sourceRegex: String? = null,
            useWebView: Boolean = true,
        ) = owner.getStrResponse(jsStr, sourceRegex, useWebView = useWebView)

        fun getStrResponse(
            jsStr: String? = null,
            sourceRegex: String? = null,
            overrideUrlRegex: String? = null,
            useWebView: Boolean = true,
        ) = owner.getStrResponseWithOverrideUrlRegex(jsStr, sourceRegex, overrideUrlRegex, useWebView)

        fun getErrResponse(e: Throwable): Response = owner.getErrResponse(e)

        fun getErrStrResponse(e: Throwable) = owner.getErrStrResponse(e)

        fun getByteArray(): ByteArray = owner.getByteArray()

        fun getInputStream(): InputStream = owner.getInputStream()

        fun getGlideUrl(): Any = owner.getGlideUrl()

        fun getMediaItem(): MediaItem = owner.getMediaItem()

        fun getUrlAndHeaders(): Pair<String, Map<String, String>> = owner.getUrlAndHeaders()

        fun getUserAgent(): String = owner.getUserAgent()

        fun isPost(): Boolean = owner.isPost()

        fun put(key: String, value: String): String = owner.put(key, value)

        fun get(key: String): String = owner.get(key)

        fun getSource(): Any? = owner.getSource()

        fun getTag(): String? = owner.getTag()
    }
}
