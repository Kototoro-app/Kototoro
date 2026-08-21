package org.skepsun.kototoro.core.parser.rule

import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.skepsun.kototoro.core.javascript.JavaScriptContext
import org.skepsun.kototoro.core.javascript.JavaScriptRuleParser
import org.skepsun.kototoro.core.javascript.VariableReplacer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增强的规则引擎，集成 JavaScript 支持
 *
 * 在基础规则引擎的基础上添加：
 * - JavaScript 规则执行（<js> 和 @js:）
 * - 规则链解析（## 操作符）
 * - 或操作符（|| 操作符）
 * - 合并操作符（&& 操作符）
 * - 字符串操作符（@replace, @substring, @get）
 * - 属性提取操作符（@text, @html, @src, @href）
 *
 * 参考 Legado 的 AnalyzeRule.kt 实现
 * 操作符优先级和行为与 Legado 完全一致
 */
@Singleton
class EnhancedRuleEngine @Inject constructor(
    private val baseRuleEngine: RuleEngine,
    private val jsRuleParser: JavaScriptRuleParser,
    private val jsonPathParser: JsonPathParser,
    private val enhancedCssSelector: EnhancedCssSelector
) : RuleEngine {

    /**
     * 解析单个字段，支持 JavaScript 和高级操作符
     *
     * @param element HTML 元素
     * @param rule 规则字符串
     * @param context JavaScript 执行上下文（可选）
     * @return 提取的文本
     */
    fun parseField(element: Element, rule: String, context: JavaScriptContext? = null): String {
        if (rule.isBlank()) return ""

        return try {
            // 首先进行变量替换（如果有上下文）
            val processedRule = if (context != null && VariableReplacer.containsVariables(rule)) {
                VariableReplacer.replaceVariables(rule, context)
            } else {
                rule
            }

            // 检查是否包含 JavaScript
            if (jsRuleParser.containsJavaScript(processedRule)) {
                Log.i(TAG, "Detected JavaScript rule, executing: ${processedRule.take(50)}...")
                val result = jsRuleParser.executeRule(processedRule, element.html(), context ?: JavaScriptContext())
                Log.i(TAG, "JavaScript rule execution result: $result")
                return result?.toString() ?: ""
            }

            // 检查 JSONPath 表达式
            if (jsonPathParser.isJsonPath(processedRule)) {
                val json = element.html()
                val result = jsonPathParser.parse(json, processedRule)
                return result?.toString() ?: ""
            }

            // 检查增强的 CSS 选择器
            if (enhancedCssSelector.isEnhancedSelector(processedRule)) {
                val elements = enhancedCssSelector.select(element, processedRule)
                return if (elements.isNotEmpty()) {
                    elements.first()?.text() ?: ""
                } else {
                    ""
                }
            }

            // 检查规则链（## 操作符）
            if (processedRule.contains("##")) {
                return parseRuleChain(element, processedRule, context)
            }

            // 检查或操作符（|| 操作符）
            if (processedRule.contains("||")) {
                return parseOrOperator(element, processedRule, context)
            }

            // 检查合并操作符（&& 操作符）
            if (processedRule.contains("&&")) {
                return parseAndOperator(element, processedRule, context)
            }

            // 使用基础规则引擎
            baseRuleEngine.parseField(element, processedRule)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing field with rule: $rule", e)
            ""
        }
    }

    /**
     * 实现 RuleEngine 接口的 parseField 方法
     * 不带 context 参数的版本
     */
    override fun parseField(element: Element, rule: String): String {
        return parseField(element, rule, null)
    }

    override fun parseList(
        document: Document,
        listRule: String,
        itemRules: Map<String, String>
    ): List<Map<String, String>> {
        return parseList(document, listRule, itemRules, null)
    }

    /**
     * 解析列表，支持 JavaScript 和高级操作符
     *
     * @param document HTML 文档
     * @param listRule 列表规则
     * @param itemRules 项目规则映射
     * @param context JavaScript 执行上下文（可选）
     * @return 解析结果列表
     */
    fun parseList(
        document: Document,
        listRule: String,
        itemRules: Map<String, String>,
        context: JavaScriptContext? = null
    ): List<Map<String, String>> {
        if (listRule.isBlank()) return emptyList()

        return try {
            // 首先进行变量替换（如果有上下文）
            val processedListRule = if (context != null && VariableReplacer.containsVariables(listRule)) {
                VariableReplacer.replaceVariables(listRule, context)
            } else {
                listRule
            }

            // 检查列表规则是否包含 JavaScript
            if (jsRuleParser.containsJavaScript(processedListRule)) {
                Log.i(TAG, "Detected JavaScript in list rule, executing: ${processedListRule.take(50)}...")

                // 执行 JavaScript 获取列表元素选择器
                val jsResult = jsRuleParser.executeRule(processedListRule, document.html(), context ?: JavaScriptContext())
                val actualListRule = jsResult?.toString() ?: ""

                Log.i(TAG, "JavaScript list rule execution result: $actualListRule")

                if (actualListRule.isBlank()) {
                    Log.w(TAG, "JavaScript list rule returned empty result")

                    // 对于起点等网站，JavaScript规则可能没有显式返回值
                    // 但执行了DOM操作，我们需要使用默认的选择器
                    if (processedListRule.contains("class.res-book-item") || processedListRule.contains("getElement")) {
                        Log.i(TAG, "Using default selector for Qidian-style JavaScript rule: class.res-book-item")
                        return baseRuleEngine.parseList(document, "class.res-book-item", itemRules)
                    }

                    return emptyList()
                }

                // 使用 JavaScript 执行结果作为实际的列表规则
                return baseRuleEngine.parseList(document, actualListRule, itemRules)
            }

            // 检查 JSONPath 表达式
            if (jsonPathParser.isJsonPath(processedListRule)) {
                val json = document.html()
                val result = jsonPathParser.parse(json, processedListRule)
                // JSONPath 结果需要进一步处理为列表
                // 这里简化处理，实际可能需要更复杂的逻辑
                return emptyList()
            }

            // 检查增强的 CSS 选择器
            if (enhancedCssSelector.isEnhancedSelector(processedListRule)) {
                val bodyElement = document.body() ?: document
                val elements = enhancedCssSelector.select(bodyElement, processedListRule)
                // 将元素转换为列表项
                return elements.mapNotNull { element ->
                    if (element != null) {
                        val item = mutableMapOf<String, String>()
                        itemRules.forEach { (key, rule) ->
                            val value = parseField(element, rule, context)
                            item[key] = value
                        }
                        item
                    } else {
                        null
                    }
                }
            }

            // 使用基础规则引擎
            baseRuleEngine.parseList(document, processedListRule, itemRules)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing list with rule: $listRule", e)
            emptyList()
        }
    }

    override fun compileRule(rule: String): CompiledRule {
        // 使用基础规则引擎的编译逻辑
        return baseRuleEngine.compileRule(rule)
    }

    /**
     * 解析规则链（## 操作符）
     * 按顺序执行规则链，将前一个规则的结果传递给下一个规则
     *
     * 格式: "rule1##rule2##rule3"
     * 示例: "div.container##a@href##@regex:id=(\\d+)"
     *
     * 注意: 需要特殊处理 ##pattern##replacement 格式的正则替换
     *
     * 参考 Legado 的 AnalyzeRule.kt 中 ## 操作符的处理逻辑
     */
    private fun parseRuleChain(element: Element, rule: String, context: JavaScriptContext?): String {
        // 首先检查是否包含正则替换模式 ##pattern##replacement
        // 这需要特殊处理，因为它也使用 ## 分隔符
        val rules = splitRuleChain(rule)

        var currentElement = element
        var currentResult = ""

        for ((index, subRule) in rules.withIndex()) {
            if (subRule.isBlank()) continue

            currentResult = if (index == 0) {
                // 第一个规则：在原始元素上执行
                parseField(currentElement, subRule, context)
            } else {
                // 后续规则：在当前结果上执行
                // 如果是 JavaScript 规则，传递当前结果作为 input
                if (jsRuleParser.containsJavaScript(subRule)) {
                    val jsContext = context ?: JavaScriptContext()
                    jsContext.setVariable("result", currentResult)
                    val result = jsRuleParser.executeRule(subRule, currentResult, jsContext)
                    result?.toString() ?: ""
                } else if (jsonPathParser.isJsonPath(subRule)) {
                    // JSONPath 表达式：在当前结果（JSON字符串）上执行
                    val result = jsonPathParser.parse(currentResult, subRule)
                    result?.toString() ?: ""
                } else if (enhancedCssSelector.isEnhancedSelector(subRule)) {
                    // 增强的 CSS 选择器：在当前元素上执行
                    val elements = enhancedCssSelector.select(currentElement, subRule)
                    if (elements.isNotEmpty()) {
                        val selectedElement = elements.first()
                        if (selectedElement != null) {
                            currentElement = selectedElement
                            currentElement.text()
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }
                } else if (subRule.startsWith("@replace:")) {
                    // 字符串替换操作符
                    applyReplaceOperator(currentResult, subRule)
                } else if (subRule.startsWith("@substring:")) {
                    // 字符串截取操作符
                    applySubstringOperator(currentResult, subRule)
                } else if (subRule.startsWith("@get:")) {
                    // 获取指定索引的元素
                    applyGetOperator(currentResult, subRule)
                } else if (subRule.startsWith("@regex:") || subRule.startsWith("##")) {
                    // 正则表达式提取或替换
                    applyRegexOperator(currentResult, subRule)
                } else {
                    // 其他规则：尝试在当前元素上执行
                    parseField(currentElement, subRule, context)
                }
            }

            // 如果结果为空，停止链式处理
            if (currentResult.isEmpty()) {
                break
            }
        }

        return currentResult
    }

    /**
     * 分割规则链，正确处理 ##pattern##replacement 格式
     *
     * 这个方法需要区分：
     * - 规则链分隔符: rule1##rule2
     * - 正则替换: ##pattern##replacement
     *
     * 策略：如果遇到连续的 ##pattern##，将其视为一个完整的正则替换规则
     */
    private fun splitRuleChain(rule: String): List<String> {
        val rules = mutableListOf<String>()
        var currentRule = StringBuilder()
        var i = 0

        while (i < rule.length) {
            if (i < rule.length - 1 && rule[i] == '#' && rule[i + 1] == '#') {
                // 找到 ##
                // 检查这是否是正则替换的开始
                if (currentRule.isEmpty() || currentRule.toString().trim().isEmpty()) {
                    // 这可能是 ##pattern##replacement 的开始
                    // 查找下一个 ##
                    val nextHashPos = rule.indexOf("##", i + 2)
                    if (nextHashPos != -1) {
                        // 找到了完整的 ##pattern##replacement
                        val regexRule = rule.substring(i, nextHashPos + 2)
                        // 检查是否还有更多内容（replacement部分）
                        val endPos = findRegexReplacementEnd(rule, nextHashPos + 2)
                        val fullRegexRule = rule.substring(i, endPos)
                        rules.add(fullRegexRule)
                        i = endPos
                        currentRule.clear()
                        continue
                    }
                }

                // 否则这是规则链分隔符
                if (currentRule.isNotEmpty()) {
                    rules.add(currentRule.toString().trim())
                    currentRule.clear()
                }
                i += 2
            } else {
                currentRule.append(rule[i])
                i++
            }
        }

        // 添加最后一个规则
        if (currentRule.isNotEmpty()) {
            rules.add(currentRule.toString().trim())
        }

        return rules.filter { it.isNotEmpty() }
    }

    /**
     * 查找正则替换规则的结束位置
     * 从 ##pattern## 之后开始，找到 replacement 的结束位置
     */
    private fun findRegexReplacementEnd(rule: String, startPos: Int): Int {
        // 查找下一个 ## 或字符串结束
        val nextHashPos = rule.indexOf("##", startPos)
        return if (nextHashPos != -1) {
            nextHashPos
        } else {
            rule.length
        }
    }

    /**
     * 解析或操作符（|| 操作符）
     * 尝试第一个规则，失败时使用第二个规则
     *
     * 格式: "rule1||rule2||rule3"
     * 示例: "img@src||img@data-src||img@data-original"
     *
     * 参考 Legado 的 AnalyzeRule.kt 中 || 操作符的实现
     */
    private fun parseOrOperator(element: Element, rule: String, context: JavaScriptContext?): String {
        val rules = rule.split("||").map { it.trim() }

        for (subRule in rules) {
            if (subRule.isBlank()) continue

            val result = parseField(element, subRule, context)
            if (result.isNotEmpty()) {
                return result
            }
        }

        return ""
    }

    /**
     * 解析合并操作符（&& 操作符）
     * 合并多个规则的结果
     *
     * 格式: "rule1&&rule2&&rule3"
     * 示例: "div.title@text&&div.subtitle@text"
     *
     * 参考 Legado 的 AnalyzeRule.kt 中 && 操作符的实现
     */
    private fun parseAndOperator(element: Element, rule: String, context: JavaScriptContext?): String {
        val rules = rule.split("&&").map { it.trim() }

        val results = rules.mapNotNull { subRule ->
            if (subRule.isBlank()) return@mapNotNull null

            val result = parseField(element, subRule, context)
            if (result.isNotEmpty()) result else null
        }

        return results.joinToString("\n")
    }

    /**
     * 应用 @replace 操作符
     * 格式: @replace:old:new
     *
     * 参考 Legado 的 AnalyzeRule.kt 中字符串操作符的处理方法
     */
    private fun applyReplaceOperator(input: String, operator: String): String {
        val parts = operator.removePrefix("@replace:").split(":", limit = 2)
        if (parts.size != 2) return input

        val old = parts[0]
        val new = parts[1]

        return input.replace(old, new)
    }

    /**
     * 应用 @substring 操作符
     * 格式: @substring:start:end
     *
     * 参考 Legado 的 AnalyzeRule.kt 中字符串操作符的处理方法
     */
    private fun applySubstringOperator(input: String, operator: String): String {
        val parts = operator.removePrefix("@substring:").split(":")
        if (parts.isEmpty()) return input

        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: input.length

        return try {
            input.substring(start.coerceIn(0, input.length), end.coerceIn(0, input.length))
        } catch (e: Exception) {
            Log.e(TAG, "Error applying substring operator: $operator", e)
            input
        }
    }

    /**
     * 应用 @get 操作符
     * 格式: @get:{index} 或 @get:{variable}
     *
     * 参考 Legado 的 AnalyzeRule.kt 中字符串操作符的处理方法
     */
    private fun applyGetOperator(input: String, operator: String): String {
        val indexOrVar = operator.removePrefix("@get:").removeSurrounding("{", "}")

        // 尝试解析为索引
        val index = indexOrVar.toIntOrNull()
        if (index != null) {
            val parts = input.split(Regex("\\s+"))
            return parts.getOrNull(index) ?: ""
        }

        // 否则作为变量名（需要上下文支持）
        // TODO: 实现变量获取
        return input
    }

    /**
     * 应用 @regex 操作符
     * 格式: @regex:pattern 或 ##pattern##replacement 或 ##pattern
     *
     * 提取模式: @regex:pattern 或 ##pattern
     * - 如果有捕获组，返回第一个捕获组
     * - 如果有多个捕获组，返回第一个非空捕获组
     * - 如果没有捕获组，返回整个匹配
     * - 如果没有匹配，返回空字符串
     *
     * 替换模式: ##pattern##replacement
     * - 使用正则表达式替换文本
     * - 支持捕获组引用（$1, $2 等）
     *
     * 参考 Legado 的 AnalyzeRule.kt 中的正则表达式处理
     */
    private fun applyRegexOperator(input: String, operator: String): String {
        return try {
            // 检查是否是 ##pattern##replacement 格式（正则替换）
            if (operator.startsWith("##") && operator.count { it == '#' } >= 4) {
                return applyRegexReplacement(input, operator)
            }

            // 否则是提取模式
            val pattern = when {
                operator.startsWith("@regex:") -> operator.removePrefix("@regex:")
                operator.startsWith("##") -> operator.removePrefix("##")
                else -> operator
            }

            return applyRegexExtraction(input, pattern)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying regex operator: $operator", e)
            ""
        }
    }

    /**
     * 应用正则提取
     * 格式: pattern
     *
     * 提取逻辑：
     * 1. 如果有捕获组，返回第一个捕获组
     * 2. 如果有多个捕获组，返回第一个非空捕获组
     * 3. 如果没有捕获组，返回整个匹配
     * 4. 如果没有匹配，返回空字符串
     *
     * 示例:
     * - pattern: "id=(\\d+)" 从 "id=12345" 提取 "12345"
     * - pattern: "(\\w+)\\s+(\\w+)" 从 "John Doe" 提取 "John"
     * - pattern: "title" 从 "Book title here" 提取 "title"
     *
     * 参考 Legado 的 AnalyzeRule.kt 中的正则提取实现
     */
    private fun applyRegexExtraction(input: String, pattern: String): String {
        return try {
            val regex = Regex(pattern)
            val match = regex.find(input)

            if (match != null) {
                // 如果有捕获组，返回第一个捕获组
                if (match.groupValues.size > 1) {
                    // 查找第一个非空捕获组
                    for (i in 1 until match.groupValues.size) {
                        val groupValue = match.groupValues[i]
                        if (groupValue.isNotEmpty()) {
                            return groupValue
                        }
                    }
                    // 如果所有捕获组都为空，返回第一个捕获组
                    match.groupValues[1]
                } else {
                    // 没有捕获组，返回整个匹配
                    match.value
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying regex extraction with pattern: $pattern", e)
            ""
        }
    }

    /**
     * 应用正则替换
     * 格式: ##pattern##replacement
     *
     * 支持捕获组引用：
     * - $1, $2, ... 引用捕获组
     * - $0 引用整个匹配
     *
     * 示例:
     * - ##(\d+)##ID:$1 将 "123" 替换为 "ID:123"
     * - ##(\w+)\s+(\w+)##$2 $1 将 "John Doe" 替换为 "Doe John"
     *
     * 参考 Legado 的 AnalyzeRule.kt 中的正则替换实现
     */
    private fun applyRegexReplacement(input: String, operator: String): String {
        return try {
            // 解析 ##pattern##replacement 格式
            val parts = operator.split("##").filter { it.isNotEmpty() }
            if (parts.size < 2) {
                Log.w(TAG, "Invalid regex replacement format: $operator")
                return input
            }

            val pattern = parts[0]
            val replacement = parts[1]

            val regex = Regex(pattern)

            // 使用 Kotlin 的 replace 方法，它支持 $1, $2 等捕获组引用
            regex.replace(input, replacement)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying regex replacement: $operator", e)
            input
        }
    }

    companion object {
        private const val TAG = "EnhancedRuleEngine"
    }
}

