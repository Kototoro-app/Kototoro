package org.skepsun.kototoro.core.parser.legado.bridge

import org.mozilla.javascript.Undefined
import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoRuntimeState
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface

/**
 * 不依赖 LegadoSandbox 的最小 runtime context 原型。
 *
 * 目标：
 * - 支撑 AnalyzeRule / AnalyzeUrl 的最小试跑链
 * - 不覆盖 book/chapter/source.getVariable 等完整 sandbox 语义
 */
class StandaloneLegadoRuleRuntimeContext(
    private val jsEngine: JavaScriptEngine,
    private val source: LegadoBookSource? = null,
    private val parserSourceName: String? = null,
    private val state: StandaloneLegadoRuntimeState = StandaloneLegadoRuntimeState(),
    private val keyProvider: () -> String? = { null },
    private val pageProvider: () -> Int? = { null },
    private val reGetBookAction: (() -> Unit)? = null,
    private val refreshTocUrlAction: (() -> Unit)? = null,
    private val httpExecutor: LegadoHttpExecutor? = null,
    private val ajaxHandler: ((String, String, RuleDataInterface?, LegadoRuleRuntimeContext) -> String?)? = null,
) : LegadoRuleRuntimeContext {
    private var javaBridgeOverride: Any? = null

    inner class CacheBinding {
        fun put(key: String, value: String?) {
            state.putCache(key, value)
        }

        fun put(key: String, value: String?, ttl: Int) {
            state.putCache(key, value)
        }

        fun put(key: String, value: String?, ttl: Long) {
            state.putCache(key, value)
        }

        fun get(key: String): String? {
            return state.getCache(key)
        }

        fun delete(key: String) {
            state.removeCache(key)
        }
    }

    fun asJsEvaluator(baseUrlProvider: () -> String): LegadoJsEvaluator {
        return LegadoJsEvaluator { script, result ->
            evaluateWithContext(
                script = script,
                result = result,
                baseUrl = baseUrlProvider(),
            )
        }
    }

    fun ruleData() = state.ruleData()

    override fun getRuleData() = state.ruleData()

    override fun getHttpExecutor(): LegadoHttpExecutor? = httpExecutor

    override fun ajax(url: String, baseUrl: String, ruleData: RuleDataInterface?): String? =
        if (ajaxHandler != null) ajaxHandler.invoke(url, baseUrl, ruleData, this) else super.ajax(url, baseUrl, ruleData)

    override fun setBook(book: BookInfo?) {
        state.setBook(book)
    }

    override fun getBook(): BookInfo? = state.getBook()

    override fun setChapter(chapter: ChapterInfo?) {
        state.setChapter(chapter)
    }

    override fun getChapter(): ChapterInfo? = state.getChapter()

    override fun getSourceObject(): Any? = source

    override fun getSourceTag(): String? = source?.bookSourceName

    override fun getParserSourceName(): String? = parserSourceName

    override fun evalJs(script: String, result: Any?, baseUrl: String): Any? {
        return evaluateWithContext(script, result, baseUrl)
    }

    override fun executeJs(script: String, result: Any?, baseUrl: String): Any? {
        return executeWithContext(script, result, baseUrl)
    }

    override fun reGetBook() {
        reGetBookAction?.invoke()
    }

    override fun refreshTocUrl() {
        refreshTocUrlAction?.invoke()
    }

    override fun putVariable(key: String, value: String) {
        state.putVariable(key, value)
    }

    override fun putVariableAny(key: String, value: Any?) {
        state.putVariable(key, value)
    }

    override fun getVariable(key: String): String? {
        return state.getVariable(key)
    }

    override fun getVariableAny(key: String): Any? {
        return state.getVariableAny(key)
    }

    override fun putSourceVariable(key: String, value: String): String {
        return state.putSourceVariable(key, value)
    }

    override fun getSourceVariable(key: String): String {
        return state.getSourceVariable(key)
    }

    override fun <T> withJavaBridge(javaBridge: Any?, block: () -> T): T {
        val previousBridge = javaBridgeOverride
        javaBridgeOverride = javaBridge
        return try {
            block()
        } finally {
            javaBridgeOverride = previousBridge
        }
    }

    private fun evaluateWithContext(script: String, result: Any?, baseUrl: String): Any? {
        val jsContext = createJavaScriptContext(result, baseUrl)
        val jsEvalResult = jsEngine.evaluate(script, jsContext)
        syncJavaScriptContextBack(jsContext)
        val resultFromContext = jsContext.getVariable("result")
        val srcFromContext = jsContext.getVariable("src")

        val finalResult = when {
            jsEvalResult != null && jsEvalResult !is Undefined &&
                (jsEvalResult !is String || jsEvalResult.isNotBlank()) -> jsEvalResult
            resultFromContext != null && resultFromContext !is Undefined &&
                resultFromContext.toString() != "undefined" -> resultFromContext
            srcFromContext != null && srcFromContext !is Undefined &&
                srcFromContext.toString() != "undefined" -> srcFromContext
            else -> jsEvalResult
        }

        state.putVariable("result", finalResult)
        state.putVariable("src", srcFromContext?.takeIf {
            it !is Undefined && it.toString() != "undefined"
        } ?: finalResult)
        return finalResult
    }

    private fun executeWithContext(script: String, result: Any?, baseUrl: String): Any? {
        val jsContext = createJavaScriptContext(result, baseUrl)
        val jsResult = jsEngine.execute(script, jsContext)
        syncJavaScriptContextBack(jsContext)
        val resultFromContext = jsContext.getVariable("result")
        val srcFromContext = jsContext.getVariable("src")
        val finalResult = when {
            jsResult != null && jsResult !is Undefined &&
                (jsResult !is String || jsResult.isNotBlank()) -> jsResult
            resultFromContext != null && resultFromContext !is Undefined &&
                resultFromContext.toString() != "undefined" -> resultFromContext
            srcFromContext != null && srcFromContext !is Undefined &&
                srcFromContext.toString() != "undefined" -> srcFromContext
            else -> jsResult
        }
        state.putVariable("result", finalResult)
        state.putVariable("src", srcFromContext?.takeIf {
            it !is Undefined && it.toString() != "undefined"
        } ?: finalResult)
        return finalResult
    }

    private fun createJavaScriptContext(result: Any?, baseUrl: String): JavaScriptContext {
        state.putVariable("baseUrl", normalizeBaseUrlForJavaScript(baseUrl))
        keyProvider()?.let { state.putVariable("key", it) }
        pageProvider()?.let { state.putVariable("page", it) }
        state.putVariable("result", result)
        state.putVariable("src", result)

        val jsContext = JavaScriptContext(
            baseUrl = baseUrl,
            book = state.getBook(),
            chapter = state.getChapter(),
            source = source,
            sourceName = parserSourceName,
            runtimeContext = this,
            key = keyProvider(),
            page = pageProvider(),
            result = result,
            src = result,
        )
        state.getVariablesSnapshot().forEach { (name, value) ->
            jsContext.setVariable(name, value)
        }
        jsContext.setVariable("cache", CacheBinding())
        jsContext.javaBridge = javaBridgeOverride
        return jsContext
    }

    private fun syncJavaScriptContextBack(jsContext: JavaScriptContext) {
        jsContext.getMutableVariables().forEach { (name, value) ->
            state.putVariable(name, value)
        }
        state.setBook(jsContext.book)
        state.setChapter(jsContext.chapter)
        state.putVariable("result", jsContext.result)
        state.putVariable("src", jsContext.src)
    }

    private fun normalizeBaseUrlForJavaScript(url: String): String {
        if (url.isBlank()) return url

        val trimmed = url.trim()
        val commaIndex = trimmed.indexOf(",{").let { if (it >= 0) it else trimmed.indexOf(", {") }
        val withoutOptions = if (commaIndex >= 0) trimmed.substring(0, commaIndex).trim() else trimmed

        val splitIndex = withoutOptions.indexOfAny(charArrayOf('?', '#'))
        val head = if (splitIndex >= 0) withoutOptions.substring(0, splitIndex) else withoutOptions
        val tail = if (splitIndex >= 0) withoutOptions.substring(splitIndex) else ""

        if (head.endsWith("/")) return head + tail
        if (head.matches(Regex(".*/\\d+"))) return "$head/$tail"
        return head + tail
    }
}
