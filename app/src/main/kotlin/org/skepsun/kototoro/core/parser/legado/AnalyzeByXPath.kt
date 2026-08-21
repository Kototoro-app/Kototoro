package org.skepsun.kototoro.core.parser.legado

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import us.codecraft.xsoup.Xsoup

/**
 * XPath analyzer for complex HTML queries.
 *
 * Aligned with legado-with-MD3 AnalyzeByXPath pattern.
 * Supports && || %% rule combination.
 * Uses Xsoup for XPath evaluation.
 *
 * Syntax: `//meta[@property='og:title']/@content`
 */
class AnalyzeByXPath(doc: Any) {

    companion object {
        private const val TAG = "AnalyzeByXPath"
    }

    private var node: Any = parse(doc)

    private fun parse(doc: Any): Any {
        return when (doc) {
            is LegadoXPathNode -> if (doc.isElement) doc else strToDocument(doc.toString())
            is Document -> doc
            is Element -> doc
            is Elements -> if (doc.size == 1) doc.first()!! else Jsoup.parse(doc.outerHtml())
            is String -> strToDocument(doc)
            else -> strToDocument(doc.toString())
        }
    }

    private fun strToDocument(html: String): Document {
        var html1 = html
        if (html1.endsWith("</td>")) {
            html1 = "<tr>${html1}</tr>"
        }
        if (html1.endsWith("</tr>") || html1.endsWith("</tbody>")) {
            html1 = "<table>${html1}</table>"
        }
        kotlin.runCatching {
            if (html1.trim().startsWith("<?xml", true)) {
                return Jsoup.parse(html1, Parser.xmlParser())
            }
        }
        return Jsoup.parse(html1)
    }

    private fun getResult(xPath: String): List<LegadoXPathNode> {
        return try {
            val currentNode = node
            when (currentNode) {
                is Element -> {
                    val evaluation = Xsoup.compile(xPath).evaluate(currentNode)
                    val values = (evaluation.list() ?: emptyList()).map { LegadoXPathNode(rawValue = it, rawElement = null) }
                    val elements = evaluation.elements?.map { LegadoXPathNode(rawValue = null, rawElement = it) }.orEmpty()
                    if (shouldPreferScalarResult(xPath, values)) {
                        values
                    } else if (elements.isNotEmpty()) {
                        elements
                    } else {
                        values
                    }
                }
                is Document -> {
                    val evaluation = Xsoup.compile(xPath).evaluate(currentNode)
                    val values = (evaluation.list() ?: emptyList()).map { LegadoXPathNode(rawValue = it, rawElement = null) }
                    val elements = evaluation.elements?.map { LegadoXPathNode(rawValue = null, rawElement = it) }.orEmpty()
                    if (shouldPreferScalarResult(xPath, values)) {
                        values
                    } else if (elements.isNotEmpty()) {
                        elements
                    } else {
                        values
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing XPath: $xPath", e)
            emptyList()
        }
    }

    private fun shouldPreferScalarResult(
        xPath: String,
        values: List<LegadoXPathNode>,
    ): Boolean {
        if (values.isEmpty()) return false
        val normalized = xPath.lowercase()
        return normalized.contains("text()") ||
            normalized.contains("/@") ||
            normalized.startsWith("string(") ||
            normalized.startsWith("normalize-space(") ||
            normalized.startsWith("concat(") ||
            normalized.startsWith("substring(") ||
            normalized.startsWith("contains(") ||
            normalized.startsWith("starts-with(") ||
            normalized.startsWith("boolean(") ||
            normalized.startsWith("number(") ||
            normalized.startsWith("count(")
    }

    /**
     * Get elements matching XPath with && || %% support
     */
    fun getElements(xPath: String): List<LegadoXPathNode>? {
        if (xPath.isEmpty()) return null

        val elements = ArrayList<LegadoXPathNode>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            return getResult(rules[0])
        } else {
            val results = ArrayList<List<LegadoXPathNode>>()
            for (rl in rules) {
                val temp = getElements(rl)
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                elements.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        elements.addAll(temp)
                    }
                }
            }
        }
        return elements
    }

    /**
     * Get string list from XPath with && || %% support
     */
    fun getStringList(xPath: String): List<String> {
        val result = ArrayList<String>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            getResult(xPath).mapTo(result) { it.asString() }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                result.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
        }
        return result
    }

    /**
     * Get string from XPath with && || support
     */
    fun getString(rule: String): String? {
        val ruleAnalyzes = RuleAnalyzer(rule)
        val rules = ruleAnalyzes.splitRule("&&", "||")
        if (rules.size == 1) {
            val result = getResult(rule).map { it.asString() }
            return if (result.isNotEmpty()) result.joinToString("\n") else null
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    /**
     * Get single string from XPath
     */
    fun getStringFirst(rule: String): String? {
        return getStringList(rule).firstOrNull()
    }
}
