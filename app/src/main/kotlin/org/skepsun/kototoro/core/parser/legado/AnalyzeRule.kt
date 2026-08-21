package org.skepsun.kototoro.core.parser.legado

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import org.skepsun.kototoro.core.util.splitNotBlank
import java.net.URL
import java.util.regex.Pattern
import org.jsoup.nodes.Element
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.parser.legado.bridge.LegadoSandboxRuleRuntimeContext
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.sandbox.LegadoSandbox
import org.skepsun.kototoro.parsers.exception.ParseException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Undefined
import kotlin.coroutines.CoroutineContext

/**
 * Unified rule executor that delegates to appropriate analyzer based on rule syntax.
 *
 * Based on legado-with-MD3 AnalyzeRule pattern.
 * Supports:
 * - JSoup/CSS: Default or @css:
 * - JSONPath: @json: or $.
 * - XPath: //
 * - JavaScript: <js> or @js:
 */
class AnalyzeRule(
    private var content: Any?,
    private val runtimeContext: LegadoRuleRuntimeContext,
    private var baseUrl: String = "",
    private val preUpdateJs: Boolean = false,
    private val fromBookInfo: Boolean = false,
    allowUninitializedContent: Boolean = false,
) {
    private var nextChapterUrl: String? = null
    private var redirectUrl: URL? = null
    private var ruleName: String? = null
    private var ruleDataOverride: RuleDataInterface? = null
    private var ruleDataObjectOverride: Any? = null
    private val javaBridge by lazy { JavaBridge(this) }

    constructor(
        content: Any?,
        sandbox: LegadoSandbox,
        baseUrl: String = "",
    ) : this(
        content = content,
        runtimeContext = LegadoSandboxRuleRuntimeContext(sandbox),
        baseUrl = baseUrl,
    )

    constructor() : this(
        content = null,
        runtimeContext = EmptyLegadoRuleRuntimeContext,
        allowUninitializedContent = true,
    )

    constructor(
        ruleData: Any?,
        source: Any?,
        preUpdateJs: Boolean = false,
        fromBookInfo: Boolean = false,
    ) : this(
        content = null,
        runtimeContext = SourceOnlyLegadoRuleRuntimeContext(source),
        baseUrl = resolveSourceBaseUrl(source),
        preUpdateJs = preUpdateJs,
        fromBookInfo = fromBookInfo,
        allowUninitializedContent = true,
    ) {
        setRuleData(ruleData)
    }

    companion object {
        private const val TAG = "AnalyzeRule"
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val putPattern = Pattern.compile("@put:(\\{[^}]+?\\})", Pattern.CASE_INSENSITIVE)
        private val evalPattern = Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE)
        private val regexPattern = Pattern.compile("\\$\\d{1,2}")
        private val JS_PATTERN =
            Pattern.compile("<js>([\\s\\S]*?)</js>|@js:([\\s\\S]*)", Pattern.CASE_INSENSITIVE)

        fun isRule(ruleStr: String): Boolean {
            if (ruleStr.isBlank()) return false
            val trimmed = ruleStr.trim()
            return trimmed.startsWith("@") || trimmed.startsWith("$.") || trimmed.startsWith("$[") || trimmed.startsWith("//")
        }

        private fun resolveSourceBaseUrl(source: Any?): String {
            return when (source) {
                null -> ""
                is LegadoBookSource -> source.bookSourceUrl
                else -> LegadoReflectiveAccess.readProperty(source, "bookSourceUrl")?.toString().orEmpty()
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
    }

    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJsonPath: AnalyzeByJsonPath? = null
    private var analyzeByXPath: AnalyzeByXPath? = null

    private fun previewForLog(value: Any?, limit: Int = 120): String {
        if (value == null) return "null"
        val text = value.toString()
        if (text.length <= limit) return text
        // URLs are the most common source of confusion when truncated; keep both head and tail.
        if (text.startsWith("http", ignoreCase = true) || text.startsWith("//")) {
            val headLen = 80.coerceAtMost(limit - 20)
            val tailLen = (limit - headLen - 1).coerceAtLeast(20)
            return text.take(headLen) + "…" + text.takeLast(tailLen)
        }
        return text.take(limit) + "…"
    }

    private fun previewRuleForLog(rule: String, limit: Int = 160): String {
        val normalized = rule.replace("\r", "").replace("\n", "\\n").trim()
        return previewForLog(normalized, limit)
    }

    init {
        if (allowUninitializedContent) {
            this.content = content
        } else {
            setContent(content)
        }
    }

    fun setRuleName(name: String) {
        if (name.isNotBlank()) {
            ruleName = name
        }
    }

    fun setRuntimeBook(book: BookInfo?): AnalyzeRule {
        runtimeContext.setBook(book)
        return this
    }

    fun setCoroutineContext(context: CoroutineContext): AnalyzeRule {
        return this
    }

    fun setRuleData(ruleData: Any?): AnalyzeRule {
        when (ruleData) {
            null -> {
                ruleDataOverride = null
                ruleDataObjectOverride = null
            }
            is RuleDataInterface -> {
                ruleDataOverride = ruleData
                ruleDataObjectOverride = ruleData
            }
            is BookInfo -> {
                setRuntimeBook(ruleData)
                ruleDataOverride = ReflectiveRuleDataAdapter(ruleData)
                ruleDataObjectOverride = ruleData
            }
            is ChapterInfo -> {
                setRuntimeChapter(ruleData)
                ruleDataOverride = ReflectiveRuleDataAdapter(ruleData)
                ruleDataObjectOverride = ruleData
            }
            else -> {
                ruleDataOverride = ReflectiveRuleDataAdapter(ruleData)
                ruleDataObjectOverride = ruleData
            }
        }
        return this
    }

    fun setChapter(chapter: ChapterInfo?): AnalyzeRule {
        return setRuntimeChapter(chapter)
    }

    fun setRuntimeChapter(chapter: ChapterInfo?): AnalyzeRule {
        runtimeContext.setChapter(chapter)
        return this
    }

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content == null) {
            throw AssertionError("内容不可空（Content cannot be null）")
        }
        this.content = content
        setBaseUrl(baseUrl)
        analyzeByJSoup = null
        analyzeByJsonPath = null
        analyzeByXPath = null
        return this
    }

    fun setBaseUrl(baseUrl: String?): AnalyzeRule {
        if (baseUrl != null) {
            this.baseUrl = baseUrl
        }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        if (url.startsWith("data:", ignoreCase = true)) {
            return redirectUrl
        }
        return runCatching { URL(url) }
            .onSuccess { redirectUrl = it }
            .onFailure { Log.w(TAG, "setRedirectUrl failed for $url", it) }
            .getOrNull()
            ?: redirectUrl
    }

    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup {
        return if (o != content) {
            AnalyzeByJSoup(o)
        } else {
            if (analyzeByJSoup == null) {
                analyzeByJSoup = AnalyzeByJSoup(content ?: "")
            }
            analyzeByJSoup!!
        }
    }

    private fun getAnalyzeByJsonPath(o: Any): AnalyzeByJsonPath {
        return if (o != content) {
            AnalyzeByJsonPath(o)
        } else {
            if (analyzeByJsonPath == null) {
                analyzeByJsonPath = AnalyzeByJsonPath(content ?: "")
            }
            analyzeByJsonPath!!
        }
    }

    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath {
        return if (o != content) {
            AnalyzeByXPath(o)
        } else {
            if (analyzeByXPath == null) {
                analyzeByXPath = AnalyzeByXPath(content ?: "")
            }
            analyzeByXPath!!
        }
    }

    /**
     * 解析文本列表
     */
    @Suppress("UNCHECKED_CAST")
    fun getStringList(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (ruleStr.isNullOrBlank()) return null
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getStringList(ruleList, mContent = mContent, isUrl = isUrl)
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringList(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
    ): List<String>? {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            if (isDirectStructuredAccessForString(result)) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result = if (sourceRule.getParamSize() > 1) {
                    sourceRule.rule
                } else {
                    resolveStructuredValue(result, sourceRule.rule)
                }
                result?.let {
                    result = if (sourceRule.replaceRegex.isNotEmpty() && it is List<*>) {
                        it.map { item -> replaceRegex(item.toString(), sourceRule) }
                    } else if (sourceRule.replaceRegex.isNotEmpty()) {
                        replaceRegex(it.toString(), sourceRule)
                    } else {
                        it
                    }
                }
            } else {
            // 遍历所有规则，根据mode调用对应的解析器
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "[getStringList] Executing mode=${sourceRule.mode}, rule=${previewRuleForLog(rule)} on content=${result?.javaClass?.simpleName}"
                        )

                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJsonPath(result ?: content!!).getStringList(rule)
                            Mode.XPath -> getAnalyzeByXPath(result ?: content!!).getStringList(rule)
                            Mode.Regex -> listOf(rule)
                            else -> getAnalyzeByJSoup(result ?: "").getStringList(rule)
                        }
                    }
                    Log.d(TAG, "[getStringList] Result after rule processing: ${previewForLog(result)}")
                    if (sourceRule.replaceRegex.isNotEmpty() && result is List<*>) {
                        result = result.map { item -> replaceRegex(item.toString(), sourceRule) }
                    } else if (sourceRule.replaceRegex.isNotEmpty() && result != null) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        if (result == null) return null
        if (result is Undefined) return null
        if (result is String) {
            result = result.split("\n")
        }
        if (isUrl) {
            val urlList = ArrayList<String>()
            val rawUrls: Iterable<Any?> = when (result) {
                is List<*> -> result
                is NativeArray -> (0 until result.length.toInt()).map { idx -> result.get(idx, result) }
                else -> listOf(result)
            }
            for (url in rawUrls) {
                if (url == null || url is Undefined) continue
                val absoluteURL = resolveUrl(baseUrl, url.toString())
                if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) {
                    urlList.add(absoluteURL)
                }
            }
            return urlList
        }
        return when (result) {
            is List<*> -> result.mapNotNull { it?.takeIf { v -> v !is Undefined }?.toString() }
            is NativeArray -> (0 until result.length.toInt())
                .mapNotNull { idx -> result.get(idx, result)?.takeIf { v -> v !is Undefined }?.toString() }
            else -> listOf(result.toString())
        }
    }

    /**
     * 获取文本
     */
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrBlank()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, mContent = mContent, isUrl = isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrBlank()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, unescape = unescape)
    }

    fun getString(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true,
    ): String {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            if (isDirectStructuredAccessForStringList(result)) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result = if (sourceRule.getParamSize() > 1) {
                    sourceRule.rule
                } else {
                    resolveStructuredValue(result, sourceRule.rule)?.toString()
                }?.let {
                    if (sourceRule.replaceRegex.isNotEmpty()) {
                        replaceRegex(it, sourceRule)
                    } else {
                        it
                    }
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()) {
                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJsonPath(result ?: content).getString(rule)
                            Mode.XPath -> getAnalyzeByXPath(result ?: content).getString(rule)
                            Mode.Default -> if (isUrl) {
                                getAnalyzeByJSoup(result ?: "").getString0(rule)
                            } else {
                                getAnalyzeByJSoup(result ?: "").getStringResult(rule)
                            }
                            Mode.Regex -> rule
                        }
                    }
                    if (result != null && sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        val resultStr = result?.toString().orEmpty()
        val text = if (unescape) {
            Parser.unescapeEntities(resultStr, false)
        } else {
            resultStr
        }
        if (isUrl) {
            if (text.isBlank()) return baseUrl
            return resolveUrl(baseUrl, text)
        }
        return text
    }

    /**
     * 获取单个元素/对象
     */
    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isBlank()) return null
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElement(result.toString(), rule.splitNotBlank("&&").toTypedArray())
                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJsonPath(result).getObject(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
                if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        return result
    }

    /**
     * 获取元素对象
     */
    @Suppress("UNCHECKED_CAST")
    fun getElements(ruleStr: String): List<Any> {
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(result.toString(), rule.splitNotBlank("&&"))
                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJsonPath(result).getList(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
            }
        }
        result?.let {
            return it as List<Any>
        }
        return ArrayList()
    }

    /**
     * 保存变量
     */
    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) {
            put(key, getString(value))
        }
    }

    /**
     * 分离put规则
     */
    private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
        var vRuleStr = ruleStr
        val putMatcher = putPattern.matcher(vRuleStr)
        while (putMatcher.find()) {
            vRuleStr = vRuleStr.replace(putMatcher.group(), "")
            val putJsonStr = putMatcher.group(1) ?: continue
            try {
                val jsonObj = LegadoLenientJsonParser.parseObject(putJsonStr)
                jsonObj.keys().forEach { k ->
                    val keyStr = k.toString()
                    putMap[keyStr] = jsonObj.optString(keyStr)
                }
            } catch (_: Exception) {
                runCatching {
                    val normalized = if (putJsonStr.contains('\'')) {
                        putJsonStr.replace('\'', '"')
                    } else {
                        putJsonStr
                    }
                    lenientJson.decodeFromString<Map<String, String>>(normalized)
                }.getOrNull()?.let { parsed ->
                    putMap.putAll(parsed)
                }
            }
        }
        return vRuleStr
    }

    /**
     * 替换正则 (对齐 legado-with-MD3 AnalyzeRule.replaceRegex)
     *
     * 两层兜底：
     * 1. 先尝试编译后的 Regex 替换（捕获非法组引用如 $）
     * 2. 失败后回退到字面量 String.replace（不解析 $）
     */
    private fun replaceRegex(result: String, sourceRule: SourceRule): String {
        if (sourceRule.replaceRegex.isEmpty()) return result

        val regexStr = sourceRule.replaceRegex
        val replacement = sourceRule.replacement
        val compiledRegex = runCatching { Regex(regexStr) }.getOrNull()

        return if (sourceRule.replaceFirst) {
            if (compiledRegex != null) {
                runCatching {
                    val matcher = compiledRegex.toPattern().matcher(result)
                    if (matcher.find()) {
                        matcher.group().replaceFirst(compiledRegex, replacement)
                    } else ""
                }.getOrElse { replacement }
            } else {
                replacement
            }
        } else {
            if (compiledRegex != null) {
                runCatching {
                    result.replace(compiledRegex, replacement)
                }.getOrElse {
                    runCatching { result.replace(regexStr, replacement) }.getOrDefault(result)
                }
            } else {
                runCatching { result.replace(regexStr, replacement) }.getOrDefault(result)
            }
        }
    }

    /**
     * eval js
     */
    fun evalJS(rule: String, result: Any?): Any? {
        val currentRuleData = ruleDataObjectOverride
        runtimeContext.putVariableAny("result", result)
        runtimeContext.putVariableAny("src", content)
        runtimeContext.putVariable("baseUrl", baseUrl)
        runtimeContext.putVariable("title", runtimeContext.getVariable("chapterName").orEmpty())
        nextChapterUrl?.let { runtimeContext.putVariable("nextChapterUrl", it) }
        runtimeContext.putVariableAny("fromBookInfo", fromBookInfo)
        resolveJavaScriptBookBinding(currentRuleData)?.let { runtimeContext.putVariableAny("book", it) }
        resolveJavaScriptRssArticleBinding(currentRuleData)?.let { runtimeContext.putVariableAny("rssArticle", it) }
        return runtimeContext.withJavaBridge(javaBridge) {
            runtimeContext.evalJs(rule, result, baseUrl)
        }
    }

    fun reGetBook() {
        if (!preUpdateJs) {
            throw NoStackTraceException("只能在 preUpdateJs 中调用")
        }
        runtimeContext.reGetBook()
    }

    fun refreshTocUrl() {
        if (!preUpdateJs) {
            throw NoStackTraceException("只能在 preUpdateJs 中调用")
        }
        runtimeContext.refreshTocUrl()
    }

    /**
     * 保存变量
     */
    fun put(key: String, value: String): String {
        return when {
            runtimeContext.getChapter() != null -> {
                runtimeContext.getChapter()?.putVariable(key, value)
                value
            }
            runtimeContext.getBook() != null -> {
                runtimeContext.getBook()?.putVariable(key, value)
                value
            }
            ruleDataOverride != null -> {
                ruleDataOverride?.putVariable(key, value)
                value
            }
            runtimeContext.getSourceObject() != null -> runtimeContext.putSourceVariable(key, value)
            else -> {
                runtimeContext.putVariable(key, value)
                value
            }
        }
    }

    /**
     * 获取保存的数据
     */
    fun get(key: String): String {
        val ruleDataObject = ruleDataObjectOverride
        when (key) {
            "title" -> runtimeContext.getChapter()?.name?.takeIf { it.isNotEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("chapterName")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "name")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            "bookName" -> runtimeContext.getBook()?.name?.takeIf { !it.isNullOrEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("bookName")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "name")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            "bookAuthor" -> runtimeContext.getBook()?.author?.takeIf { !it.isNullOrEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("bookAuthor")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "author")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            "bookUrl" -> runtimeContext.getBook()?.bookUrl?.takeIf { it.isNotEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("bookUrl")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "bookUrl")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            "nextChapterUrl" -> nextChapterUrl?.let { return it }
            "chapterName" -> runtimeContext.getChapter()?.name?.takeIf { it.isNotEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("chapterName")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "name")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            "chapterUrl" -> runtimeContext.getChapter()?.chapterUrl?.takeIf { it.isNotEmpty() }?.let { return it }
                ?: runtimeContext.getVariable("chapterUrl")?.let { return it }
                ?: LegadoReflectiveAccess.readProperty(ruleDataObject, "chapterUrl")?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
        }
        runtimeContext.getChapter()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        runtimeContext.getBook()?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        ruleDataOverride?.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        runtimeContext.getVariable(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        runtimeContext.getSourceVariable(key).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    fun setNextChapterUrl(nextChapterUrl: String?): AnalyzeRule {
        this.nextChapterUrl = nextChapterUrl
        return this
    }

    fun getTag(): String? {
        return runtimeContext.getSourceTag() ?: ruleName
    }

    fun getSource(): Any? {
        return runtimeContext.getSourceObject()
    }

    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        val httpExecutor = runtimeContext.getHttpExecutor() ?: return ""
        val request = AnalyzeUrl(
            ruleUrl = urlStr,
            baseUrl = baseUrl,
            ruleData = ruleDataOverride ?: runtimeContext.getRuleData(),
            runtimeContext = runtimeContext,
            httpExecutor = httpExecutor,
        )
        return runCatching {
            request.getStrResponse().body()
        }.getOrElse {
            it.stackTraceToString()
        }
    }

    /**
     * 分离规则并缓存
     */
    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()

    private fun splitSourceRuleCacheString(rule: String): List<SourceRule> {
        return stringRuleCache.getOrPut(rule) { splitSourceRuleInternal(rule, false) }
    }

    /**
     * 分离规则 (仅分离 JS 块，内部逻辑运算符由具体解析器处理)
     */
    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrBlank()) return emptyList()
        return splitSourceRuleInternal(ruleStr, allInOne)
    }

    private fun splitSourceRuleInternal(ruleStr: String, isElements: Boolean): List<SourceRule> {
        val ruleList = ArrayList<SourceRule>()
        var mode: Mode = Mode.Default
        var startIndex = 0
        // 与 legado-with-MD3 对齐：getElements/getElement 下，首字符为 ':' 时启用 AllInOne 正则模式
        if (isElements && ruleStr.startsWith(":")) {
            mode = Mode.Regex
            startIndex = 1
        }
        ruleList.addAll(splitJSToSourceRule(ruleStr, startIndex, mode))
        return ruleList
    }

    /**
     * 拆分 js 到 SourceRule 列表
     */
    private fun splitJSToSourceRule(ruleStr: String, startIndex: Int, mMode: Mode): List<SourceRule> {
        val ruleList = ArrayList<SourceRule>()
        var start = startIndex
        var tmp: String
        val jsMatcher = JS_PATTERN.matcher(ruleStr)

        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp = ruleStr.substring(start, jsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleList.add(SourceRule(tmp, mMode))
                }
            }
            ruleList.add(SourceRule(jsMatcher.group(2) ?: jsMatcher.group(1), Mode.Js))
            start = jsMatcher.end()
        }

        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim { it <= ' ' }
            if (tmp.isNotEmpty()) {
                ruleList.add(SourceRule(tmp, mMode))
            }
        }

        return ruleList
    }

    private class JavaBridge(
        private val owner: AnalyzeRule,
    ) {
        fun ajax(url: Any): String? = owner.ajax(url)

        fun get(key: String): String = owner.get(key)

        fun put(key: String, value: String): String = owner.put(key, value)

        fun reGetBook() = owner.reGetBook()

        fun refreshTocUrl() = owner.refreshTocUrl()

        fun getSource(): Any? = owner.getSource()

        fun getTag(): String? = owner.getTag()
    }

    private class ReflectiveRuleDataAdapter(
        private val target: Any,
    ) : RuleDataInterface {
        override fun getVariable(key: String): String? {
            return invoke("getVariable", key)?.toString()
        }

        override fun putVariable(key: String, value: String?): String? {
            val previous = getVariable(key)
            invoke("putVariable", key, value)
            return previous
        }

        override fun getVariableMap(): Map<String, String> {
            val result = invoke("getVariableMap") ?: return emptyMap()
            @Suppress("UNCHECKED_CAST")
            return (result as? Map<Any?, Any?>)
                ?.mapNotNull { (key, value) ->
                    key?.toString()?.let { safeKey -> safeKey to value?.toString().orEmpty() }
                }
                ?.toMap()
                .orEmpty()
        }

        override fun clearVariables() {
            invoke("clearVariables")
        }

        private fun invoke(name: String, vararg args: Any?): Any? {
            val method = target::class.java.methods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterTypes.size == args.size
            } ?: return null
            return runCatching { method.invoke(target, *args) }.getOrNull()
        }
    }

    private fun resolveJavaScriptBookBinding(ruleData: Any?): Any? {
        return when {
            ruleData == null -> runtimeContext.getBook()
            ruleData is BookInfo -> ruleData
            LegadoReflectiveAccess.readProperty(ruleData, "bookUrl") != null -> ruleData
            else -> runtimeContext.getBook()
        }
    }

    private fun resolveJavaScriptRssArticleBinding(ruleData: Any?): Any? {
        if (ruleData == null) return null
        val origin = LegadoReflectiveAccess.readProperty(ruleData, "origin")
        val link = LegadoReflectiveAccess.readProperty(ruleData, "link")
        return if (origin != null && link != null) ruleData else null
    }

    private object EmptyLegadoRuleRuntimeContext : LegadoRuleRuntimeContext {
        override fun evalJs(script: String, result: Any?, baseUrl: String): Any? = result

        override fun executeJs(script: String, result: Any?, baseUrl: String): Any? = result

        override fun reGetBook() = Unit

        override fun refreshTocUrl() = Unit

        override fun putVariable(key: String, value: String) = Unit

        override fun putVariableAny(key: String, value: Any?) = Unit

        override fun getVariable(key: String): String? = null

        override fun getVariableAny(key: String): Any? = null

        override fun putSourceVariable(key: String, value: String): String = value

        override fun getSourceVariable(key: String): String = ""

        override fun setBook(book: BookInfo?) = Unit

        override fun getBook(): BookInfo? = null

        override fun setChapter(chapter: ChapterInfo?) = Unit

        override fun getChapter(): ChapterInfo? = null
    }

    private class SourceOnlyLegadoRuleRuntimeContext(
        private val source: Any?,
    ) : LegadoRuleRuntimeContext by EmptyLegadoRuleRuntimeContext {
        private val variables = linkedMapOf<String, Any?>()
        private val sourceVariables = linkedMapOf<String, String>()
        private var book: BookInfo? = null
        private var chapter: ChapterInfo? = null

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

        override fun getSourceObject(): Any? = source

        override fun getSourceTag(): String? = resolveSourceTag(source)
    }

    /**
     * 规则类（移植自 legado-with-MD3）
     */
    inner class SourceRule internal constructor(
        ruleStr: String,
        internal var mode: Mode = Mode.Default
    ) {
        internal var rule: String
        private val templateRule: String
        internal var replaceRegex = ""
        internal var replacement = ""
        internal var replaceFirst = false
        internal val putMap = HashMap<String, String>()
        private var segments: List<LegadoSourceRuleSegmentParser.Segment> = emptyList()

        init {
            val classifiedRule = LegadoSourceRuleClassifier.classify(
                ruleStr = ruleStr,
                mode = mode,
                content = content,
            ) { simpleKey ->
                Log.d(TAG, "[SourceRule] Converting simple key '$simpleKey' to JSONPath for JSON content")
            }
            mode = classifiedRule.mode
            rule = classifiedRule.rule
            //分离put
            rule = splitPutRule(rule, putMap)
            templateRule = rule
            val parseResult = LegadoSourceRuleSegmentParser.parse(rule = templateRule, mode = mode)
            segments = parseResult.segments
            mode = parseResult.mode
        }

        /**
         * 替换@get,{{ }}
         */
        fun makeUpRule(result: Any?) {
            val infoVal = StringBuilder()
            var workingRule = templateRule
            if (segments.isNotEmpty()) {
                var index = segments.size
                while (index-- > 0) {
                    when (val segment = segments[index]) {
                        is LegadoSourceRuleSegmentParser.Segment.RegexGroup -> {
                            @Suppress("UNCHECKED_CAST")
                            (result as? List<String?>)?.run {
                                if (this.size > segment.groupIndex) {
                                    this[segment.groupIndex]?.let {
                                        infoVal.insert(0, it)
                                    }
                                }
                            } ?: infoVal.insert(0, segment.token)
                        }

                        is LegadoSourceRuleSegmentParser.Segment.JavaScript -> {
                            val expr = segment.expression.trim()
                            if (isRule(expr)) {
                                val ruleList = splitSourceRuleCacheString(expr)
                                infoVal.insert(0, getString(ruleList))
                            } else {
                                val jsEval: Any? = evalJS(expr, result)
                                when {
                                    jsEval == null || jsEval is Undefined -> Unit
                                    jsEval is String -> infoVal.insert(0, jsEval)
                                    jsEval is Double && jsEval % 1.0 == 0.0 -> infoVal.insert(
                                        0,
                                        String.format(java.util.Locale.ROOT, "%.0f", jsEval)
                                    )
                                    else -> infoVal.insert(0, jsEval.toString())
                                }
                            }
                        }

                        is LegadoSourceRuleSegmentParser.Segment.GetVariable -> {
                            infoVal.insert(0, get(segment.key))
                        }

                        is LegadoSourceRuleSegmentParser.Segment.Literal -> {
                            infoVal.insert(0, segment.value)
                        }
                    }
                }
                workingRule = infoVal.toString()
            }

            //分离正则表达式
            replaceRegex = ""
            replacement = ""
            replaceFirst = false
            val ruleStrS = workingRule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) {
                replaceRegex = ruleStrS[1]
            }
            if (ruleStrS.size > 2) {
                replacement = ruleStrS[2]
            }
            if (ruleStrS.size > 3) {
                replaceFirst = true
            }

        }



        fun getParamSize(): Int {
            return segments.size
        }
    }

    enum class Mode {
        XPath, Json, Default, Js, Regex
    }

    private fun resolveIndexed(holder: Any?, key: String): Any? {
        return when (holder) {
            is Map<*, *> -> holder[key]
            is List<*> -> key.toIntOrNull()?.let { idx -> holder.getOrNull(idx) }
            is JSONObject -> holder.opt(key)
            is JSONArray -> key.toIntOrNull()?.takeIf { idx -> idx in 0 until holder.length() }?.let(holder::opt)
            is NativeObject -> holder.get(key, holder).takeIf { it !is Undefined }
            is NativeArray -> key.toIntOrNull()?.let { idx ->
                if (idx in 0 until holder.length.toInt()) holder.get(idx, holder) else null
            }?.takeIf { it !is Undefined }
            else -> null
        }
    }

    private fun resolveStructuredValue(holder: Any?, key: String): Any? {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return null
        return resolveIndexed(holder, trimmedKey)
    }

    private fun isDirectStructuredAccessForString(result: Any?): Boolean {
        return result is NativeObject
    }

    private fun isDirectStructuredAccessForStringList(result: Any?): Boolean {
        return result is NativeObject
    }

    private fun resolveUrl(base: String?, relative: String): String {
        if (relative.isBlank()) return relative
        if (relative.startsWith("http")) return relative
        redirectUrl?.let { redirect ->
            return runCatching { URL(redirect, relative).toString() }.getOrElse { relative }
        }
        return try {
            URL(URL(base ?: ""), relative).toString()
        } catch (_: Exception) {
            relative
        }
    }
}
