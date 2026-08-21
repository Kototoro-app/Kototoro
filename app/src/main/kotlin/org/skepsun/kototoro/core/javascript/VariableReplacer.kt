package org.skepsun.kototoro.core.javascript

/**
 * 变量替换工具
 *
 * 实现 Legado 兼容的变量替换语法：
 * - {{变量名}}: 替换为变量值
 * - @get:{变量名}: 替换为变量值
 *
 * 参考 Legado 的 AnalyzeRule.kt 中的变量替换实现
 */
object VariableReplacer {

    // 匹配 {{变量名}} 语法
    private val DOUBLE_BRACE_PATTERN = Regex("""\{\{([^}]+)\}\}""")

    // 匹配 @get:{变量名} 语法
    private val GET_PATTERN = Regex("""@get:\{([^}]+)\}""")

    /**
     * 替换字符串中的所有变量
     *
     * @param text 包含变量的文本
     * @param context JavaScript 上下文
     * @return 替换后的文本
     */
    fun replaceVariables(text: String, context: JavaScriptContext): String {
        var result = text

        // 替换 {{变量名}} 语法
        result = DOUBLE_BRACE_PATTERN.replace(result) { matchResult ->
            val variableName = matchResult.groupValues[1].trim()
            val value = context.getVariable(variableName)
            value?.toString() ?: matchResult.value
        }

        // 替换 @get:{变量名} 语法
        result = GET_PATTERN.replace(result) { matchResult ->
            val variableName = matchResult.groupValues[1].trim()
            val value = context.getVariable(variableName)
            value?.toString() ?: matchResult.value
        }

        return result
    }

    /**
     * 检查文本是否包含变量
     *
     * @param text 要检查的文本
     * @return true 如果包含变量
     */
    fun containsVariables(text: String): Boolean {
        return DOUBLE_BRACE_PATTERN.containsMatchIn(text) ||
               GET_PATTERN.containsMatchIn(text)
    }

    /**
     * 提取文本中的所有变量名
     *
     * @param text 包含变量的文本
     * @return 变量名列表
     */
    fun extractVariableNames(text: String): List<String> {
        val variables = mutableListOf<String>()

        // 提取 {{变量名}}
        DOUBLE_BRACE_PATTERN.findAll(text).forEach { matchResult ->
            variables.add(matchResult.groupValues[1].trim())
        }

        // 提取 @get:{变量名}
        GET_PATTERN.findAll(text).forEach { matchResult ->
            variables.add(matchResult.groupValues[1].trim())
        }

        return variables.distinct()
    }
}
