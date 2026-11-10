package org.skepsun.kototoro.core.parser.legado.bridge

import org.skepsun.kototoro.core.parser.legado.AnalyzeUrl
import org.skepsun.kototoro.core.parser.legado.RuleDataInterface
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext

/** Keeps Android's existing AnalyzeUrl behavior when a rule invokes its AJAX bridge. */
internal fun androidLegadoRuleAjax(
    url: String,
    baseUrl: String,
    ruleData: RuleDataInterface?,
    context: LegadoRuleRuntimeContext,
): String? {
    val executor = context.getHttpExecutor() ?: return ""
    val request = AnalyzeUrl(
        ruleUrl = url,
        baseUrl = baseUrl,
        ruleData = ruleData ?: context.getRuleData(),
        runtimeContext = context,
        httpExecutor = executor,
    )
    return runCatching { request.getStrResponse().body() }.getOrElse { it.stackTraceToString() }
}
