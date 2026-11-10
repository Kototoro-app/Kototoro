package org.skepsun.kototoro.core.parser.legado

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

/**
 * 用于在不引入 JXNode 依赖的前提下，尽量贴近 MD3 XPath 结果对象语义。
 */
class LegadoXPathNode(
    private val rawValue: String?,
    private val rawElement: Element?,
) {

    val isElement: Boolean
        get() = rawElement != null

    fun asElement(): Element {
        return rawElement ?: Jsoup.parse(rawValue.orEmpty()).body()
    }

    fun asString(): String {
        rawElement?.let { element ->
            val clone = element.clone()
            clone.ownerDocument()?.outputSettings()?.prettyPrint(false)?.escapeMode(Entities.EscapeMode.base)
            return clone.outerHtml()
        }
        return rawValue.orEmpty()
    }

    override fun toString(): String {
        return asString()
    }
}
