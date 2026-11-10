package org.skepsun.kototoro.core.parser.legado

import java.util.regex.Pattern

/**
 * Regex analyzer for pattern matching.
 *
 * Aligned with legado-with-MD3 AnalyzeByRegex pattern. The patterns come from imported source rules,
 * so every match a rule makes, across all of its `&&` steps, shares one [BoundedRegex] deadline: a
 * catastrophically backtracking pattern gives up with no result instead of pinning the parser thread.
 */
object AnalyzeByRegex {

    fun getElement(res: String, regs: Array<String>, index: Int = 0): List<String>? =
        getElement(res, regs, index, BoundedRegex.regexDeadline())

    fun getElements(res: String, regs: Array<String>, index: Int = 0): List<List<String>> =
        getElements(res, regs, index, BoundedRegex.regexDeadline())

    /**
     * Convenience overload accepting List<String> for patterns
     */
    fun getElements(text: String, patterns: List<String>): List<Any> {
        if (patterns.isEmpty()) return emptyList()
        return getElements(text, patterns.toTypedArray()).map { it as Any }
    }

    private fun getElement(res: String, regs: Array<String>, index: Int, deadline: Long): List<String>? {
        val pattern = Pattern.compile(regs[index])
        // 判断索引的规则是最后一个规则
        if (index + 1 == regs.size) {
            return BoundedRegex.withRegexDeadline(res, deadline) { input ->
                val resM = pattern.matcher(input)
                if (!resM.find()) return@withRegexDeadline null
                // 新建容器
                (0..resM.groupCount()).map { resM.group(it) ?: "" }
            }
        }
        val joined = joinMatches(res, pattern, deadline) ?: return null
        return getElement(joined, regs, index + 1, deadline)
    }

    private fun getElements(res: String, regs: Array<String>, index: Int, deadline: Long): List<List<String>> {
        val pattern = Pattern.compile(regs[index])
        // 判断索引的规则是最后一个规则
        if (index + 1 == regs.size) {
            return BoundedRegex.withRegexDeadline(res, deadline) { input ->
                val resM = pattern.matcher(input)
                // 创建书息缓存数组, 提取列表
                val books = ArrayList<List<String>>()
                while (resM.find()) {
                    books.add((0..resM.groupCount()).map { resM.group(it) ?: "" })
                }
                books
            }.orEmpty()
        }
        val joined = joinMatches(res, pattern, deadline) ?: return emptyList()
        return getElements(joined, regs, index + 1, deadline)
    }

    /** Every match of [pattern] in [res] run together, the input of the next step; null when none or out of time. */
    private fun joinMatches(res: String, pattern: Pattern, deadline: Long): String? =
        BoundedRegex.withRegexDeadline(res, deadline) { input ->
            val resM = pattern.matcher(input)
            if (!resM.find()) return@withRegexDeadline null
            val result = StringBuilder()
            do {
                result.append(resM.group())
            } while (resM.find())
            result.toString()
        }
}
