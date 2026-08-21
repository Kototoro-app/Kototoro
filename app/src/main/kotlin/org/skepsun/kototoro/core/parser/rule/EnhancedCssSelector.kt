package org.skepsun.kototoro.core.parser.rule

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增强的 CSS 选择器解析器
 *
 * 支持 Legado 特有的选择器语法：
 * - class.className - 按类名选择
 * - tag.tagName - 按标签名选择
 * - id.idName - 按 ID 选择
 * - @tag.tagName.index - 选择指定索引的子元素
 * - @textNodes - 选择所有文本节点
 * - @data-* - 提取 data 属性
 * - [-1:0] - 反向选择
 *
 * 参考 Legado 的 AnalyzeByJSoup.kt 实现
 */
@Singleton
class EnhancedCssSelector @Inject constructor() {

    /**
     * 解析增强的 CSS 选择器
     *
     * @param element 要查询的元素
     * @param selector 选择器字符串
     * @return 匹配的元素列表
     */
    fun select(element: Element?, selector: String): Elements {
        if (element == null || selector.isBlank()) {
            return Elements()
        }

        return try {
            // 检查是否是复杂的Legado链式选择器
            if (isComplexLegadoSelector(selector)) {
                return parseComplexLegadoSelector(element, selector)
            }

            when {
                // class.className 语法
                selector.startsWith("class.") -> {
                    val className = selector.removePrefix("class.")
                    element.select(".$className")
                }

                // tag.tagName 语法
                selector.startsWith("tag.") -> {
                    val tagName = selector.removePrefix("tag.")
                    element.select(tagName)
                }

                // id.idName 语法
                selector.startsWith("id.") -> {
                    val idName = selector.removePrefix("id.")
                    element.select("#$idName")
                }

                // @tag.tagName.index 语法
                selector.startsWith("@tag.") -> {
                    parseTagWithIndex(element, selector)
                }

                // @textNodes 选择器
                selector == "@textNodes" -> {
                    selectTextNodes(element)
                }

                // @data-* 属性提取
                selector.startsWith("@data-") -> {
                    selectDataAttribute(element, selector)
                }

                // 反向选择 [-1:0]
                selector.matches(Regex(""".*\[-?\d+:-?\d+\]$""")) -> {
                    selectReverse(element, selector)
                }

                // 索引选择器 .0, .1, .2 等
                selector.matches(Regex("""^\.\d+$""")) -> {
                    val index = selector.removePrefix(".").toIntOrNull() ?: 0
                    val children = element.children()
                    if (index in children.indices) {
                        Elements(children[index])
                    } else {
                        Elements()
                    }
                }

                // 标准 CSS 选择器
                else -> {
                    element.select(selector)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing enhanced CSS selector: $selector", e)
            Elements()
        }
    }

    /**
     * 解析 @tag.tagName.index 语法
     *
     * 示例: @tag.div.0 - 选择第一个 div 子元素
     */
    private fun parseTagWithIndex(element: Element, selector: String): Elements {
        val parts = selector.removePrefix("@tag.").split(".")
        if (parts.isEmpty()) {
            return Elements()
        }

        val tagName = parts[0]
        val index = parts.getOrNull(1)?.toIntOrNull()

        val children = element.children().filter { it.tagName() == tagName }

        return if (index != null && index in children.indices) {
            Elements(children[index])
        } else if (index == null) {
            Elements(children)
        } else {
            Elements()
        }
    }

    /**
     * 选择所有文本节点
     *
     * @textNodes 选择器返回元素的所有直接文本节点
     */
    private fun selectTextNodes(element: Element): Elements {
        val textNodes = element.textNodes()
        val result = Elements()

        // 为每个文本节点创建一个包装元素
        for (textNode in textNodes) {
            val text = textNode.text().trim()
            if (text.isNotEmpty()) {
                // 创建一个临时元素来包装文本
                val wrapper = Element("span")
                wrapper.text(text)
                result.add(wrapper)
            }
        }

        return result
    }

    /**
     * 选择 data 属性
     *
     * 示例: @data-src - 提取 data-src 属性值
     */
    private fun selectDataAttribute(element: Element, selector: String): Elements {
        val attrName = selector.removePrefix("@")
        val attrValue = element.attr(attrName)

        if (attrValue.isNotEmpty()) {
            // 创建一个包含属性值的临时元素
            val wrapper = Element("span")
            wrapper.text(attrValue)
            return Elements(wrapper)
        }

        return Elements()
    }

    /**
     * 反向选择元素
     *
     * 示例: div[-1:0] - 从最后一个到第一个选择所有 div
     */
    private fun selectReverse(element: Element, selector: String): Elements {
        // 提取基础选择器和范围
        val rangePattern = Regex("""^(.+)\[(-?\d+):(-?\d+)\]$""")
        val match = rangePattern.find(selector) ?: return Elements()

        val baseSelector = match.groupValues[1]
        val start = match.groupValues[2].toIntOrNull() ?: 0
        val end = match.groupValues[3].toIntOrNull() ?: 0

        // 选择基础元素
        val elements = element.select(baseSelector)
        val size = elements.size

        if (size == 0) {
            return Elements()
        }

        // 处理负索引
        val actualStart = if (start < 0) size + start else start
        val actualEnd = if (end < 0) size + end else end

        // 反向选择
        val result = Elements()
        if (actualStart > actualEnd) {
            // 从 start 到 end（反向）
            for (i in actualStart downTo actualEnd) {
                if (i in 0 until size) {
                    result.add(elements[i])
                }
            }
        } else {
            // 正向选择
            for (i in actualStart..actualEnd) {
                if (i in 0 until size) {
                    result.add(elements[i])
                }
            }
        }

        return result
    }

    /**
     * 检测是否是复杂的Legado链式选择器
     *
     * 例如: id.book-img-text.0@tag.li
     */
    private fun isComplexLegadoSelector(selector: String): Boolean {
        // 检查是否包含多个Legado语法元素
        val legadoPatterns = listOf(
            Regex("""^(id|class|tag)\.[^@]+\.\d+@"""),  // id.xxx.0@, class.xxx.0@, tag.xxx.0@
            Regex("""^(id|class|tag)\.[^@]+@"""),       // id.xxx@, class.xxx@, tag.xxx@
        )

        return legadoPatterns.any { it.containsMatchIn(selector) }
    }

    /**
     * 解析复杂的Legado链式选择器
     *
     * 例如: id.book-img-text.0@tag.li
     * 分解为: id.book-img-text -> .0 -> @tag.li
     */
    private fun parseComplexLegadoSelector(element: Element, selector: String): Elements {
        // 分割选择器
        val parts = splitComplexSelector(selector)

        var currentElements = Elements(element)

        for (part in parts) {
            if (part.isBlank()) continue

            val nextElements = Elements()

            for (currentElement in currentElements) {
                val partResult = select(currentElement, part)
                nextElements.addAll(partResult)
            }

            currentElements = nextElements

            // 如果某一步没有结果，直接返回空
            if (currentElements.isEmpty()) {
                break
            }
        }

        return currentElements
    }

    /**
     * 分割复杂选择器为多个部分
     *
     * 例如: "id.book-img-text.0@tag.li" -> ["id.book-img-text", ".0", "@tag.li"]
     */
    private fun splitComplexSelector(selector: String): List<String> {
        val parts = mutableListOf<String>()

        // 处理 @ 分割
        val atIndex = selector.indexOf('@')
        if (atIndex != -1) {
            val beforeAt = selector.substring(0, atIndex)
            val afterAt = selector.substring(atIndex)

            // 处理 @ 之前的部分
            parts.addAll(splitBeforeAt(beforeAt))

            // 添加 @ 之后的部分
            parts.add(afterAt)
        } else {
            // 没有 @，直接处理
            parts.addAll(splitBeforeAt(selector))
        }

        return parts.filter { it.isNotEmpty() }
    }

    /**
     * 分割 @ 之前的部分
     *
     * 例如: "id.book-img-text.0" -> ["id.book-img-text", ".0"]
     */
    private fun splitBeforeAt(part: String): List<String> {
        val parts = mutableListOf<String>()

        // 匹配 id.xxx.0, class.xxx.0, tag.xxx.0 格式
        val indexPattern = Regex("""^(id|class|tag)\.([^.]+)\.(\d+)$""")
        val match = indexPattern.find(part)

        if (match != null) {
            val prefix = match.groupValues[1]
            val name = match.groupValues[2]
            val index = match.groupValues[3]

            // 分解为两部分
            parts.add("$prefix.$name")
            parts.add(".$index")
        } else {
            // 不匹配复杂格式，直接添加
            parts.add(part)
        }

        return parts
    }

    /**
     * 检测选择器是否为增强语法
     *
     * @param selector 选择器字符串
     * @return true 如果是增强语法
     */
    fun isEnhancedSelector(selector: String): Boolean {
        return selector.startsWith("class.") ||
               selector.startsWith("tag.") ||
               selector.startsWith("id.") ||
               selector.startsWith("@tag.") ||
               selector == "@textNodes" ||
               selector.startsWith("@data-") ||
               selector.matches(Regex(""".*\[-?\d+:-?\d+\]$""")) ||
               isComplexLegadoSelector(selector)
    }

    companion object {
        private const val TAG = "EnhancedCssSelector"
    }
}
