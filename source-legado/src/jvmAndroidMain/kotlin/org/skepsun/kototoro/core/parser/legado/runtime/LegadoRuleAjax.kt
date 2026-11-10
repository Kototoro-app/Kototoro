package org.skepsun.kototoro.core.parser.legado.runtime

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.parser.legado.LegadoRequestPlanner

/** Default AJAX bridge for hosts that provide an HTTP executor and a script context. */
internal object LegadoRuleAjax {
    fun execute(context: LegadoRuleRuntimeContext, url: String, baseUrl: String): String? {
        val executor = context.getHttpExecutor() ?: return ""
        return runCatching {
            val evaluator = LegadoJsEvaluator { script, result ->
                context.putVariableAny("page", 1)
                context.putVariable("baseUrl", baseUrl)
                context.putVariableAny("result", result)
                context.putVariableAny("src", result)
                context.evalJs(script, result, baseUrl) ?: result
            }
            val plan = LegadoRequestPlanner(
                key = null,
                page = 1,
                baseUrl = baseUrl,
                jsEvaluator = evaluator,
                enabledCookieJarDefault = true,
                useWebViewDefault = false,
                webJsDefault = null,
                webViewDelayDefault = 0,
                defaultHeaders = emptyMap(),
            ).prepare(url).plan.copy(useWebView = true)
            val response = runBlocking { executor.execute(plan) }
            plan.bodyJs?.takeIf(String::isNotBlank)?.let { evaluator.evaluate(it, response.body).toString() }
                ?: response.body
        }.getOrElse { it.stackTraceToString() }
    }
}
