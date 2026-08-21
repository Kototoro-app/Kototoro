package org.skepsun.kototoro.core.parser.legado

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Collector
import org.jsoup.select.Elements
import org.jsoup.select.Evaluator

/**
 * JSoup 解析（对齐 legado-with-MD3 的核心选择能力，精简日志与依赖）
 */
class AnalyzeByJSoup(private val element: Element) {

    constructor(content: Any) : this(
        when (content) {
            is Element -> content
            is LegadoXPathNode -> {
                if (content.isElement) {
                    content.asElement()
                } else {
                    Jsoup.parse(content.toString())
                }
            }
            is String -> {
                if (content.startsWith("<?xml", true)) {
                    Jsoup.parse(content, Parser.xmlParser())
                } else {
                    Jsoup.parse(content)
                }
            }
            else -> Jsoup.parse(content.toString())
        }
    )

    internal fun getElements(rule: String) = getElements(element, rule)

    internal fun getString(rule: String): List<String> {
        val textS = arrayListOf<String>()
        val sourceRule = SourceRule(rule)
        if (sourceRule.elementsRule.isEmpty()) {
            textS.add(element.data().orEmpty())
            return textS
        }
        val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
        val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (ruleStrS.isEmpty()) return textS

        val results = ArrayList<List<String>>()
        for (ruleStrX in ruleStrS) {

            val temp: ArrayList<String>? =
                if (sourceRule.isCss) {
                    val lastIndex = ruleStrX.lastIndexOf('@')
                    getResultLast(
                        element.select(ruleStrX.substring(0, lastIndex)),
                        ruleStrX.substring(lastIndex + 1)
                    )
                } else {
                    getResultList(ruleStrX)
                }

            if (!temp.isNullOrEmpty()) {
                results.add(temp)
                if (ruleAnalyzes.elementsType == "||") break
            }
        }
        if (results.size > 0) {
            if ("%%" == ruleAnalyzes.elementsType) {
                for (i in results[0].indices) {
                    for (temp in results) {
                        if (i < temp.size) {
                            textS.add(temp[i])
                        }
                    }
                }
            } else {
                for (temp in results) {
                    textS.addAll(temp)
                }
            }
        }
        return textS
    }

    /**
     * 合并内容列表,得到内容
     */
    internal fun getStringResult(ruleStr: String): String? {
        if (ruleStr.isEmpty()) {
            return null
        }
        val list = getString(ruleStr)
        if (list.isEmpty()) {
            return null
        }
        if (list.size == 1) {
            return list.first()
        }
        return list.joinToString("\n")
    }

    /**
     * 获取一个字符串
     */
    internal fun getString0(ruleStr: String) =
        getString(ruleStr).let { if (it.isEmpty()) "" else it[0] }

    /**
     * 获取string list别名（兼容老代码）
     */
    internal fun getStringList(rule: String): List<String> = getString(rule)

    /**
     * 获取Elements
     */
    internal fun getElements(temp: Element?, rule: String): Elements {

        if (temp == null || rule.isEmpty()) return Elements()

        val elements = Elements()

        val sourceRule = SourceRule(rule)
        val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
        val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")

        val elementsList = ArrayList<Elements>()
        if (sourceRule.isCss) {
            for (ruleStr in ruleStrS) {
                val tempS = temp.select(ruleStr)
                elementsList.add(tempS)
                if (tempS.size > 0 && ruleAnalyzes.elementsType == "||") {
                    break
                }
            }
        } else {
            for (ruleStr in ruleStrS) {

                val rsRule = RuleAnalyzer(ruleStr)

                rsRule.trim()  // 修剪当前规则之前的"@"或者空白符

                val rs = rsRule.splitRule("@")

                val el = if (rs.size > 1) {
                    val el = Elements()
                    el.add(temp)
                    for (rl in rs) {
                        val es = Elements()
                        for (et in el) {
                            es.addAll(getElements(et, rl))
                        }
                        el.clear()
                        el.addAll(es)
                    }
                    el
                } else ElementsSingle().getElementsSingle(temp, ruleStr)

                elementsList.add(el)
                if (el.size > 0 && ruleAnalyzes.elementsType == "||") {
                    break
                }
            }
        }
        if (elementsList.size > 0) {
            if ("%%" == ruleAnalyzes.elementsType) {
                for (i in 0 until elementsList[0].size) {
                    for (es in elementsList) {
                        if (i < es.size) {
                            elements.add(es[i])
                        }
                    }
                }
            } else {
                for (es in elementsList) {
                    elements.addAll(es)
                }
            }
        }
        return elements
    }

    /**
     * 获取内容列表
     */
    private fun getResultList(ruleStr: String): ArrayList<String>? {

        if (ruleStr.isEmpty()) return null

        var elements = Elements()

        elements.add(element)

        val rule = RuleAnalyzer(ruleStr) //创建解析

        rule.trim() //修建前置赘余符号

        val rules = rule.splitRule("@") // 切割成列表

        val last = rules.size - 1
        for (i in 0 until last) {
            val es = Elements()
            for (elt in elements) {
                es.addAll(ElementsSingle().getElementsSingle(elt, rules[i]))
            }
            elements = es
        }
        return if (elements.isEmpty()) null else getResultLast(elements, rules[last])
    }

    /**
     * 根据最后一个规则获取内容
     */
    private fun getResultLast(elements: Elements, lastRule: String): ArrayList<String> {
        val textS = ArrayList<String>()
        when (lastRule) {
            "text" -> for (element in elements) {
                val text = element.text()
                if (text.isNotEmpty()) {
                    textS.add(text)
                }
            }

            "textNodes" -> for (element in elements) {
                val tn = arrayListOf<String>()
                val contentEs = element.textNodes()
                for (item in contentEs) {
                    val text = item.text().trim { it <= ' ' }
                    if (text.isNotEmpty()) {
                        tn.add(text)
                    }
                }
                if (tn.isNotEmpty()) {
                    textS.add(tn.joinToString("\n"))
                }
            }

            "ownText" -> for (element in elements) {
                val text = element.ownText()
                if (text.isNotEmpty()) {
                    textS.add(text)
                }
            }

            "html" -> {
                // 与 legado-with-MD3 保持一致：移除 script 和 style，然后获取外部 HTML
                elements.select("script").remove()
                elements.select("style").remove()
                val html = elements.outerHtml()
                if (html.isNotEmpty()) {
                    textS.add(html)
                }
            }

            "all" -> {
                val html = elements.outerHtml()
                if (html.isNotEmpty()) {
                    textS.add(html)
                }
            }

            else -> if (lastRule.isNotEmpty()) for (element in elements) {
                val text = element.attr(lastRule)
                if (text.isBlank() || textS.contains(text)) continue
                if (text.isNotEmpty()) {
                    textS.add(text)
                }
            }
        }
        return textS
    }

    /**
     * Rule 选择器处理
     */
    internal inner class SourceRule(ruleStr: String) {
        var isCss = false
        var elementsRule: String = if (ruleStr.startsWith("@CSS:", true)) {
            isCss = true
            ruleStr.substring(5).trim { it <= ' ' }
        } else {
            ruleStr
        }
    }

    /**
     * 单条规则解析（移植 ElementsSingle）
     */
    internal inner class ElementsSingle {
        private var beforeRule: String = ""
        private var split: Char = '.'
        private val indexes = mutableListOf<Any>()
        private val indexDefault = mutableListOf<Int>()

        fun getElementsSingle(temp: Element, rule: String): Elements {
            findIndexSet(rule)

            var elements = if (beforeRule.isEmpty()) {
                temp.children()
            } else {
                val rules = beforeRule.split(".")
                when (rules[0]) {
                    "children" -> temp.children()
                    "class" -> temp.getElementsByClass(rules[1])
                    "tag" -> temp.getElementsByTag(rules[1])
                    "id" -> Collector.collect(Evaluator.Id(rules[1]), temp)
                    "text" -> temp.getElementsContainingOwnText(rules[1])
                    else -> temp.select(beforeRule)
                }
            }

            val len = elements.size
            if (len == 0) return elements

            // Default to return all if no indexes provided
            if (indexes.isEmpty() && indexDefault.isEmpty()) {
                return if (split == '!') Elements() else elements
            }

            val lastIndexes = (indexDefault.size - 1).takeIf { it != -1 } ?: (indexes.size - 1)
            val indexSet = mutableSetOf<Int>()

            if (indexes.isEmpty()) {
                for (ix in lastIndexes downTo 0) {
                    val it = indexDefault[ix]
                    if (it in 0 until len) indexSet.add(it)
                    else if (it < 0 && len >= -it) indexSet.add(it + len)
                }
            } else {
                for (ix in lastIndexes downTo 0) {
                    if (indexes[ix] is Triple<*, *, *>) {
                        val (startX, endX, stepX) = indexes[ix] as Triple<Int?, Int?, Int>
                        var start = startX ?: 0
                        if (start < 0) start += len
                        var end = endX ?: (len - 1)
                        if (end < 0) end += len
                        if ((start < 0 && end < 0) || (start >= len && end >= len)) continue
                        if (start >= len) start = len - 1
                        else if (start < 0) start = 0
                        if (end >= len) end = len - 1
                        else if (end < 0) end = 0
                        if (start == end || stepX >= len) {
                            indexSet.add(start)
                            continue
                        }
                        val step = if (stepX > 0) stepX else if (-stepX < len) stepX + len else 1
                        indexSet.addAll(if (end > start) start..end step step else start downTo end step step)
                    } else {
                        val it = indexes[ix] as Int
                        if (it in 0 until len) indexSet.add(it)
                        else if (it < 0 && len >= -it) indexSet.add(it + len)
                    }
                }
            }

            if (split == '!') {
                val filtered = Elements()
                elements.forEachIndexed { idx, el ->
                    if (!indexSet.contains(idx)) filtered.add(el)
                }
                elements = filtered
            } else if (split == '.') {
                val es = Elements()
                for (pcInt in indexSet) es.add(elements[pcInt])
                elements = es
            }

            return elements
        }

        private fun findIndexSet(rule: String) {
            val rus = rule.trim()
            if (rus.isEmpty()) {
                beforeRule = ""
                split = ' '
                return
            }

            indexes.clear()
            indexDefault.clear()
            split = '.'

            var len = rus.length
            var currentInt: Int?
            var currentMinus = false
            val currentRange = mutableListOf<Int?>()
            var numberText = ""
            val bracketStyle = rus.last() == ']'

            if (bracketStyle) {
                len--
                while (len-- >= 0) {
                    var ch = rus[len]
                    if (ch == ' ') continue

                    if (ch in '0'..'9') {
                        numberText = ch + numberText
                    } else if (ch == '-') {
                        currentMinus = true
                    } else {
                        currentInt =
                            if (numberText.isEmpty()) null
                            else if (currentMinus) -numberText.toInt() else numberText.toInt()

                        when (ch) {
                            ':' -> currentRange.add(currentInt)
                            else -> {
                                if (currentRange.isEmpty()) {
                                    if (currentInt == null) break
                                    indexes.add(currentInt)
                                } else {
                                    indexes.add(
                                        Triple(
                                            currentInt,
                                            currentRange.last(),
                                            if (currentRange.size == 2) currentRange.first() ?: 1 else 1,
                                        ),
                                    )
                                    currentRange.clear()
                                }

                                if (ch == '!') {
                                    split = '!'
                                    do {
                                        ch = rus[--len]
                                    } while (len > 0 && ch == ' ')
                                }

                                if (ch == '[') {
                                    beforeRule = rus.substring(0, len)
                                    return
                                }

                                if (ch != ',') break
                            }
                        }

                        numberText = ""
                        currentMinus = false
                    }
                }
            } else {
                while (len-- >= 0) {
                    val ch = rus[len]
                    if (ch == ' ') continue

                    if (ch in '0'..'9') {
                        numberText = ch + numberText
                    } else if (ch == '-') {
                        currentMinus = true
                    } else {
                        if (ch == '!' || ch == '.' || ch == ':') {
                            if (numberText.isEmpty()) break
                            indexDefault.add(if (currentMinus) -numberText.toInt() else numberText.toInt())
                            if (ch != ':') {
                                split = ch
                                beforeRule = rus.substring(0, len)
                                return
                            }
                        } else {
                            break
                        }

                        numberText = ""
                        currentMinus = false
                    }
                }
            }

            split = ' '
            beforeRule = rus
        }
    }
}
