package org.skepsun.kototoro.core.javascript

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JavaScript 规则解析器
 *
 * 检测和执行 Legado 书源中的 JavaScript 规则
 * 支持两种语法：
 * 1. <js>...</js> - 完整的 JavaScript 代码块
 * 2. @js: - JavaScript 表达式前缀
 *
 * 参考 Legado 的 AnalyzeRule.kt 实现
 */
@Singleton
class JavaScriptRuleParser @Inject constructor(
    private val engine: JavaScriptEngine
) {

    /**
     * 检测规则是否包含 JavaScript
     *
     * @param rule 规则字符串
     * @return true 如果规则包含 JavaScript
     */
    fun containsJavaScript(rule: String): Boolean {
        if (rule.isBlank()) return false

        // 检测 <js> 标签
        if (rule.contains("<js>", ignoreCase = true)) {
            return true
        }

        // 检测 @js: 前缀
        if (rule.trimStart().startsWith("@js:", ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * 提取 JavaScript 代码
     *
     * @param rule 规则字符串
     * @return 提取的 JavaScript 代码，如果不包含 JavaScript 则返回 null
     */
    fun extractJavaScript(rule: String): String? {
        if (rule.isBlank()) return null

        // 提取 <js>...</js> 之间的代码
        val jsTagPattern = Regex("<js>(.*?)</js>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val jsTagMatch = jsTagPattern.find(rule)
        if (jsTagMatch != null) {
            return jsTagMatch.groupValues.getOrNull(1)?.trim()
        }

        // 提取 @js: 后的代码
        val trimmedRule = rule.trim()
        if (trimmedRule.startsWith("@js:", ignoreCase = true)) {
            return trimmedRule.substring(4).trim()
        }

        return null
    }

    /**
     * 执行 JavaScript 规则
     *
     * @param rule 规则字符串
     * @param input 输入数据（将作为 result 变量传递给 JavaScript）
     * @param context JavaScript 执行上下文
     * @return 执行结果，如果执行失败则返回 null
     */
    fun executeRule(rule: String, input: Any?, context: JavaScriptContext): Any? {
        Log.i(TAG, "Executing JavaScript rule: ${rule.take(100)}...")
        // 检测是否包含 JavaScript
        if (!containsJavaScript(rule)) {
            Log.w(TAG, "Rule does not contain JavaScript: $rule")
            return null
        }

        // 提取 JavaScript 代码
        val jsCode = extractJavaScript(rule)
        if (jsCode.isNullOrBlank()) {
            Log.w(TAG, "Failed to extract JavaScript from rule: $rule")
            return null
        }

        // 设置 result 变量 - 确保 result 是字符串类型
        val resultValue = when (input) {
            is String -> input
            null -> ""
            else -> input.toString()
        }
        context.setVariable("result", resultValue)
        Log.d(TAG, "Set result variable: ${resultValue.take(100)}...")

        // 执行 JavaScript
        return try {
            val result = engine.execute(jsCode, context)
            Log.d(TAG, "JavaScript execution successful: $jsCode -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "JavaScript execution failed: $jsCode", e)
            null
        }
    }

    companion object {
        private const val TAG = "JavaScriptRuleParser"
    }
}
