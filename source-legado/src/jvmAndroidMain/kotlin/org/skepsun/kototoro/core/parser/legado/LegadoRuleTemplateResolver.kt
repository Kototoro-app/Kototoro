package org.skepsun.kototoro.core.parser.legado

/**
 * Legado 规则辅助函数。
 */
object LegadoRuleTemplateResolver {

    fun looksLikeJsonText(value: Any?): Boolean {
        val text = value as? String ?: return false
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }
}
