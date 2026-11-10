package org.skepsun.kototoro.core.parser.legado

import org.json.JSONObject
import org.mozilla.javascript.NativeObject

/**
 * 负责 SourceRule 的纯规则分类与标准化。
 */
object LegadoSourceRuleClassifier {

    data class ClassifiedRule(
        val rule: String,
        val mode: AnalyzeRule.Mode,
    )

    fun classify(
        ruleStr: String,
        mode: AnalyzeRule.Mode,
        content: Any?,
        onSimpleJsonKeyDetected: ((String) -> Unit)? = null,
    ): ClassifiedRule {
        if (mode == AnalyzeRule.Mode.Js || mode == AnalyzeRule.Mode.Regex) {
            return ClassifiedRule(rule = ruleStr, mode = mode)
        }

        return when {
            ruleStr.startsWith("@CSS:", true) -> {
                ClassifiedRule(rule = ruleStr, mode = AnalyzeRule.Mode.Default)
            }

            ruleStr.startsWith("@@") -> {
                ClassifiedRule(rule = ruleStr.substring(2), mode = AnalyzeRule.Mode.Default)
            }

            ruleStr.startsWith("@XPath:", true) -> {
                ClassifiedRule(rule = ruleStr.substring(7), mode = AnalyzeRule.Mode.XPath)
            }

            ruleStr.startsWith("@Json:", true) -> {
                ClassifiedRule(rule = ruleStr.substring(6), mode = AnalyzeRule.Mode.Json)
            }

            isJsonLike(content) || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                ClassifiedRule(rule = ruleStr, mode = AnalyzeRule.Mode.Json)
            }

            ruleStr.startsWith("/") -> {
                ClassifiedRule(rule = ruleStr, mode = AnalyzeRule.Mode.XPath)
            }

            else -> ClassifiedRule(rule = ruleStr, mode = AnalyzeRule.Mode.Default)
        }
    }

    private fun isJsonLike(content: Any?): Boolean {
        return content is JSONObject ||
            content is org.json.JSONArray ||
            (content is String && LegadoRuleTemplateResolver.looksLikeJsonText(content))
    }
}
