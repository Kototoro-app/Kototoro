package org.skepsun.kototoro.local.epub

/**
 * Plain text of one EPUB content document.
 *
 * Block elements are separated by a blank line and `<br>` ends a line; `<head>`, `<title>`, `<script>` and
 * `<style>` are not text. Entities are decoded once. Pictures become `📷 [图片: src]` paragraphs when
 * [keepImages] is set, keeping the original src so relative paths can be resolved afterwards.
 */
internal fun epubHtmlToText(html: String, keepImages: Boolean): String {
    val text = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        val lt = html.indexOf('<', i)
        if (lt < 0) {
            text.append(decodeEntities(html.substring(i)))
            break
        }
        if (lt > i) text.append(decodeEntities(html.substring(i, lt)))
        when {
            html.startsWith("<!--", lt) -> {
                i = html.indexOf("-->", lt + 4).let { if (it < 0) html.length else it + 3 }
            }

            html.startsWith("<![CDATA[", lt) -> {
                val end = html.indexOf("]]>", lt + 9).let { if (it < 0) html.length else it }
                text.append(html, lt + 9, end)
                i = minOf(html.length, end + 3)
            }

            html.startsWith("<!", lt) || html.startsWith("<?", lt) -> {
                i = html.indexOf('>', lt + 2).let { if (it < 0) html.length else it + 1 }
            }

            else -> {
                val gt = html.indexOf('>', lt + 1).let { if (it < 0) html.length else it }
                val raw = html.substring(lt + 1, gt)
                i = gt + 1
                val name = raw.trimStart('/').substringBefore(' ').substringBefore('/').substringBefore('\n').lowercase()
                when {
                    raw.startsWith("/") -> if (name in BLOCK_TAGS) text.append("\n\n")
                    name in SKIPPED_TAGS -> {
                        val close = html.indexOf("</$name", i, ignoreCase = true)
                        val end = if (close < 0) html.length else {
                            html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
                        }
                        i = end
                    }

                    name == "br" -> text.append('\n')
                    (name == "img" || name == "image") && keepImages -> {
                        val src = attribute(raw, "src") ?: attribute(raw, "data-src")
                            ?: attribute(raw, "xlink:href") ?: attribute(raw, "href")
                        if (!src.isNullOrBlank()) text.append("\n\n📷 [图片: ").append(src.trim()).append("]\n\n")
                    }

                    name in BLOCK_TAGS -> text.append("\n\n")
                }
            }
        }
    }
    return text.toString()
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "chapter", "blockquote", "pre", "li", "ul", "ol", "dl", "dt", "dd",
    "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "th", "table", "figure", "figcaption", "hr", "body",
)

private val SKIPPED_TAGS = setOf("head", "title", "script", "style", "rt", "rp")

private fun attribute(tag: String, name: String): String? {
    val marker = "$name="
    var from = 0
    while (true) {
        val at = tag.indexOf(marker, from, ignoreCase = true)
        if (at < 0) return null
        val before = tag.getOrNull(at - 1)
        if (before == null || before.isWhitespace()) {
            val start = at + marker.length
            val quote = tag.getOrNull(start)
            return when (quote) {
                '"', '\'' -> tag.substring(start + 1, tag.indexOf(quote, start + 1).takeIf { it > 0 } ?: tag.length)
                else -> tag.substring(start).substringBefore(' ').substringBefore('>')
            }
        }
        from = at + marker.length
    }
}

private val NUMERIC_ENTITY = Regex("&#(?:[xX]([0-9a-fA-F]+)|([0-9]+));")

private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val named = text.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
        .replace("&amp;", "&")
    return NUMERIC_ENTITY.replace(named) { match ->
        val hex = match.groupValues[1]
        val code = if (hex.isNotEmpty()) hex.toIntOrNull(16) else match.groupValues[2].toIntOrNull()
        code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
}
