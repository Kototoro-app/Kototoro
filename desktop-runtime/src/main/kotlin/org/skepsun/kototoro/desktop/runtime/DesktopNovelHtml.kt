package org.skepsun.kototoro.desktop.runtime

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A styled stretch of text inside a paragraph. */
data class NovelRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)

/** What the novel reader lays out; HTML is reduced to these and never rendered as a document. */
sealed interface NovelBlock {
    data class Paragraph(val runs: List<NovelRun>) : NovelBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    data class Heading(val level: Int, val text: String) : NovelBlock
    data class Image(val url: String) : NovelBlock
    data object Rule : NovelBlock
}

/**
 * Reduces chapter HTML to reader blocks. Scripts, frames and styles are dropped, so what a site (or a malicious
 * source) puts in the markup can only ever become text, headings and image URLs.
 *
 * Like the Android reader, a line break inside text starts a new paragraph: sources that return plain text
 * (Tsundoku's `fetchPageText`) separate paragraphs with newlines, not with markup.
 */
object DesktopNovelHtml {
    private val DROPPED = setOf("script", "style", "iframe", "noscript", "svg", "object", "embed", "canvas", "template")
    private val BLOCKS = setOf("p", "div", "li", "ul", "ol", "blockquote", "section", "article", "tr", "table", "pre", "dd", "dt", "figure", "figcaption")

    fun parse(html: String, baseUrl: String = ""): List<NovelBlock> {
        val blocks = mutableListOf<NovelBlock>()
        val runs = mutableListOf<NovelRun>()

        fun flush() {
            val trimmed = runs.toList().also { runs.clear() }
            val merged = mutableListOf<NovelRun>()
            for (run in trimmed) {
                val last = merged.lastOrNull()
                if (last != null && last.bold == run.bold && last.italic == run.italic) merged[merged.lastIndex] = last.copy(text = last.text + run.text)
                else merged += run
            }
            if (merged.isNotEmpty()) {
                merged[0] = merged[0].copy(text = merged[0].text.trimStart(' ', '\t', '\r'))
                merged[merged.lastIndex] = merged.last().copy(text = merged.last().text.trimEnd(' ', '\t', '\r'))
            }
            val visible = merged.filter { it.text.isNotEmpty() }
            if (visible.any { it.text.isNotBlank() }) blocks += NovelBlock.Paragraph(visible)
        }

        fun text(node: TextNode, bold: Boolean, italic: Boolean) {
            val lines = node.wholeText.split('\n')
            lines.forEachIndexed { index, line ->
                if (index > 0) flush()
                val collapsed = line.replace('\r', ' ').replace(Regex("[ \\t]+"), " ")
                if (collapsed.isNotBlank() || (collapsed.isNotEmpty() && runs.isNotEmpty())) runs += NovelRun(collapsed, bold, italic)
            }
        }

        fun walk(node: Node, bold: Boolean, italic: Boolean) {
            when (node) {
                is TextNode -> text(node, bold, italic)
                is Element -> {
                    val name = node.normalName()
                    when {
                        name in DROPPED -> Unit
                        name == "br" -> flush()
                        name == "hr" -> { flush(); blocks += NovelBlock.Rule }
                        name == "img" -> {
                            flush()
                            // Lazy-loading sites keep the real address in data-src.
                            val key = listOf("data-src", "data-original", "src").firstOrNull { node.attr(it).isNotBlank() }
                            if (key != null) {
                                val absolute = node.absUrl(key).ifEmpty { node.attr(key) }
                                if (absolute.startsWith("http://") || absolute.startsWith("https://")) blocks += NovelBlock.Image(absolute)
                            }
                        }
                        name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                            flush()
                            node.text().trim().takeIf(String::isNotEmpty)?.let { blocks += NovelBlock.Heading(name[1] - '0', it) }
                        }
                        name in BLOCKS -> {
                            flush()
                            node.childNodes().forEach { walk(it, bold, italic) }
                            flush()
                        }
                        else -> {
                            val strong = bold || name == "b" || name == "strong"
                            val slanted = italic || name == "i" || name == "em"
                            node.childNodes().forEach { walk(it, strong, slanted) }
                        }
                    }
                }
            }
        }

        Jsoup.parseBodyFragment(html, baseUrl).body().childNodes().forEach { walk(it, bold = false, italic = false) }
        flush()
        return blocks
    }
}
