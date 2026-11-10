package org.skepsun.kototoro.core.parser.legado.runtime

import org.skepsun.kototoro.core.javascript.BookInfo
import org.skepsun.kototoro.core.javascript.ChapterInfo
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.AnalyzeRule
import org.skepsun.kototoro.core.parser.legado.LegadoRequestPlanner
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface
import org.skepsun.kototoro.core.parser.legado.bridge.StandaloneLegadoRuleRuntimeContext

/**
 * 无 sandbox 的最小列表链路试跑装配器。
 *
 * 范围刻意收窄：
 * - 只覆盖 URL 构建
 * - 只覆盖 HTTP 请求计划
 * - 只覆盖 AnalyzeRule 的字符串/列表解析
 *
 * 不覆盖：
 * - book/chapter 上下文
 * - callback / formatJs
 * - 正文与目录分页副作用
 */
class StandaloneLegadoListRuntime(
    private val jsEngine: JavaScriptEngine,
    private val source: LegadoBookSource,
    private val parserSourceName: String? = null,
    private val variableStore: LegadoVariableStore? = null,
    private val httpExecutor: LegadoHttpExecutor? = null,
    private val ajaxHandler: ((String, String, RuleDataInterface?, LegadoRuleRuntimeContext) -> String?)? = null,
) {

    data class PreparedRequest(
        val requestPlan: LegadoRequestPlan,
        val runtimeContext: StandaloneLegadoRuleRuntimeContext,
    )

    fun setBook(
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        book: BookInfo?,
    ) {
        runtimeContext.setBook(book)
    }

    fun setChapter(
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        chapter: ChapterInfo?,
    ) {
        runtimeContext.setChapter(chapter)
    }

    fun prepareRequest(
        ruleUrl: String,
        key: String? = null,
        page: Int = 1,
        baseUrl: String = source.bookSourceUrl,
    ): PreparedRequest {
        val runtimeContext = createRuntimeContext(
            key = key,
            page = page,
        )
        val requestPlan = if (ruleUrl.isBlank()) {
            LegadoRequestPlan(url = baseUrl)
        } else {
            LegadoRequestPlanner(
                key = key,
                page = page,
                baseUrl = baseUrl,
                jsEvaluator = runtimeContext.asJsEvaluator { baseUrl },
                enabledCookieJarDefault = source.enabledCookieJar != false,
                useWebViewDefault = false,
                webJsDefault = null,
                webViewDelayDefault = 0,
                defaultHeaders = emptyMap(),
            ).prepare(ruleUrl).plan
        }
        return PreparedRequest(
            requestPlan = requestPlan,
            runtimeContext = runtimeContext,
        )
    }

    fun createRuntimeContext(
        key: String? = null,
        page: Int? = null,
        state: StandaloneLegadoRuntimeState? = null,
        reGetBookAction: (() -> Unit)? = null,
        refreshTocUrlAction: (() -> Unit)? = null,
    ): StandaloneLegadoRuleRuntimeContext {
        val sourceKey = source.bookSourceUrl.trim().takeIf { it.isNotBlank() }
        return StandaloneLegadoRuleRuntimeContext(
            jsEngine = jsEngine,
            source = source,
            parserSourceName = parserSourceName,
            state = state ?: StandaloneLegadoRuntimeState(
                sourceKey = sourceKey,
                variableStore = variableStore,
            ),
            keyProvider = { key },
            pageProvider = { page },
            reGetBookAction = reGetBookAction,
            refreshTocUrlAction = refreshTocUrlAction,
            httpExecutor = httpExecutor,
            ajaxHandler = ajaxHandler,
        )
    }

    fun eval(
        script: String,
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        result: Any? = null,
        baseUrl: String = source.bookSourceUrl,
    ): Any? {
        return runtimeContext.evalJs(script, result, baseUrl)
    }

    fun execute(
        script: String,
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        result: Any? = null,
        baseUrl: String = source.bookSourceUrl,
    ): Any? {
        return runtimeContext.executeJs(script, result, baseUrl)
    }

    fun parseStringList(
        content: Any?,
        rule: String,
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        baseUrl: String,
        isUrl: Boolean = false,
    ): List<String>? {
        return AnalyzeRule(
            content = content,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
        ).getStringList(rule, mContent = content, isUrl = isUrl)
    }

    fun parseString(
        content: Any?,
        rule: String,
        runtimeContext: StandaloneLegadoRuleRuntimeContext,
        baseUrl: String,
        isUrl: Boolean = false,
    ): String {
        return AnalyzeRule(
            content = content,
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
        ).getString(rule, mContent = content, isUrl = isUrl)
    }
}
