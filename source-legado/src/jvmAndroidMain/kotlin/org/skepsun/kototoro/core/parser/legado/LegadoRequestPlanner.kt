package org.skepsun.kototoro.core.parser.legado

import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRequestPlan

/** Evaluates a source URL template and turns it into the request the host must execute. */
class LegadoRequestPlanner(
    private val key: String?,
    private val page: Int,
    private val baseUrl: String,
    private val jsEvaluator: LegadoJsEvaluator,
    private val enabledCookieJarDefault: Boolean,
    private val useWebViewDefault: Boolean,
    private val webJsDefault: String?,
    private val webViewDelayDefault: Long,
    private val defaultHeaders: Map<String, String>,
    private val readTimeoutMs: Long? = null,
    private val callTimeoutMs: Long? = null,
) {
    data class Prepared(val evaluatedRuleUrl: String, val plan: LegadoRequestPlan)

    fun prepare(ruleUrl: String): Prepared {
        val evaluated = LegadoUrlTemplateEvaluator(key, page, baseUrl, jsEvaluator).evaluate(ruleUrl)
        val plan = LegadoRequestPlanBuilder(
            baseUrl = baseUrl,
            enabledCookieJarDefault = enabledCookieJarDefault,
            useWebViewDefault = useWebViewDefault,
            webJsDefault = webJsDefault,
            webViewDelayDefault = webViewDelayDefault,
            defaultHeaders = defaultHeaders,
            jsEvaluator = jsEvaluator,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
        ).build(evaluated)
        return Prepared(evaluated, plan)
    }
}
