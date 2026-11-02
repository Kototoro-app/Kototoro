package org.skepsun.kototoro.core.ui.markdown

/**
 * One block of a Markdown document, as far as release notes and changelogs use them.
 *
 * Android renders those with Markwon; the shared hosts render this subset instead, so the text that reaches the
 * screen is the same on both. Anything outside the subset (tables, footnotes, raw HTML beyond a line break) is
 * kept as paragraph text rather than dropped, so a note never silently loses its content.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    /** A `> quoted` paragraph. */
    data class Quote(val text: String) : MarkdownBlock

    /**
     * One list item: [marker] is `•` for bullets, `☐`/`☑` for task items, or the number as `1.` for ordered lists;
     * [depth] counts the two-space indents in front of it.
     */
    data class ListItem(val marker: String, val text: String, val depth: Int = 0) : MarkdownBlock

    /** A fenced code block; [language] is the word after the opening fence, if any. */
    data class Code(val language: String?, val text: String) : MarkdownBlock

    /** A `---` / `***` / `___` horizontal rule. */
    data object Rule : MarkdownBlock
}

/** An inline run of a text block: literal text with a few toggles, or a link. */
sealed interface MarkdownSpan {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikeThrough: Boolean = false,
        val code: Boolean = false,
    ) : MarkdownSpan

    data class Link(val text: String, val url: String) : MarkdownSpan
}

/**
 * Splits [markdown] into blocks: ATX headings, paragraphs with soft/hard line breaks, block quotes, bullet, task and
 * ordered lists, fenced code and horizontal rules.
 *
 * The parser is deliberately forgiving: release notes are written by hand and by GitHub's own generator, so an
 * unclosed fence keeps the rest as code, an unclosed inline marker stays literal, and HTML comments (which the
 * generator leaves behind as release-template placeholders) never reach the screen.
 */
fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = stripComments(markdown).replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = ArrayList<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = joinLines(paragraph)
        paragraph.clear()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> {
                flushParagraph()
                index++
            }

            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph()
                val fence = trimmed.take(3)
                val language = trimmed.removePrefix(fence).trim().takeIf { it.isNotEmpty() }
                val code = ArrayList<String>()
                index++
                while (index < lines.size && !lines[index].trim().startsWith(fence)) {
                    code += lines[index]
                    index++
                }
                // Skip the closing fence when there is one; an unclosed fence keeps everything up to the end.
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(language, code.joinToString("\n"))
            }

            ATX_HEADING.matches(trimmed) -> {
                flushParagraph()
                val match = ATX_HEADING.matchEntire(trimmed)!!
                // GitHub closes headings with optional trailing hashes: "## Notes ##".
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim().trimEnd('#').trim(),
                )
                index++
            }

            RULE.matches(trimmed) -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
                index++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = ArrayList<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quote += lines[index].trim().removePrefix(">").removePrefix(" ").trimEnd()
                    index++
                }
                blocks += MarkdownBlock.Quote(joinLines(quote))
            }

            else -> {
                val bullet = BULLET.matchEntire(line)
                val ordered = ORDERED.matchEntire(line)
                when {
                    bullet != null -> {
                        flushParagraph()
                        val body = bullet.groupValues[3].trim()
                        val task = TASK_PREFIX.find(body)
                        blocks += if (task != null) {
                            MarkdownBlock.ListItem(
                                marker = if (task.groupValues[1].equals("x", ignoreCase = true)) "☑" else "☐",
                                text = body.substring(task.range.last + 1).trim(),
                                depth = depthOf(bullet.groupValues[1]),
                            )
                        } else {
                            MarkdownBlock.ListItem("•", body, depthOf(bullet.groupValues[1]))
                        }
                        index++
                    }

                    ordered != null -> {
                        flushParagraph()
                        blocks += MarkdownBlock.ListItem(
                            marker = "${ordered.groupValues[2]}.",
                            text = ordered.groupValues[3].trim(),
                            depth = depthOf(ordered.groupValues[1]),
                        )
                        index++
                    }

                    else -> {
                        // Kept raw: a paragraph line's trailing spaces are a Markdown hard line break.
                        paragraph += line
                        index++
                    }
                }
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * Splits one line of inline Markdown into runs: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `[text](url)` and
 * `![alt](url)`. Backslash escapes the next character, `<br>` becomes a newline and other tags are dropped.
 *
 * Emphases do not nest: `**bold**` inside a link label is reported as part of the [MarkdownSpan.Link]. Underscore
 * emphases must sit on word boundaries, so `snake_case_name` stays literal.
 */
fun parseMarkdownInline(markdown: String): List<MarkdownSpan> {
    val spans = ArrayList<MarkdownSpan>()
    val literal = StringBuilder()
    var index = 0

    fun flush() {
        if (literal.isEmpty()) return
        spans += MarkdownSpan.Text(literal.toString())
        literal.clear()
    }

    while (index < markdown.length) {
        val char = markdown[index]
        if (char == '<') {
            val close = markdown.indexOf('>', index + 1)
            if (close > index) {
                val tag = markdown.substring(index + 1, close).trim().lowercase()
                if (tag == "br" || tag == "br/") literal.append('\n')
                index = close + 1
                continue
            }
        }
        if (char == '\\' && index + 1 < markdown.length) {
            literal.append(markdown[index + 1])
            index += 2
            continue
        }
        if (char == '[' || (char == '!' && index + 1 < markdown.length && markdown[index + 1] == '[')) {
            val link = LINK.find(markdown, index)?.takeIf { it.range.first == index }
            if (link != null) {
                flush()
                val label = link.groupValues[1]
                val url = link.groupValues[2]
                spans += MarkdownSpan.Link(label.ifEmpty { url }, url)
                index = link.range.last + 1
                continue
            }
        }
        val marker = markerAt(markdown, index)
        if (marker == null) {
            literal.append(char)
            index++
            continue
        }
        val start = index + marker.length
        val end = closingMarker(markdown, marker, start)
        if (end <= start) {
            literal.append(marker)
            index = start
            continue
        }
        val inner = markdown.substring(start, end)
        flush()
        spans += when (marker) {
            "**", "__" -> MarkdownSpan.Text(inner, bold = true)
            "~~" -> MarkdownSpan.Text(inner, strikeThrough = true)
            "`" -> MarkdownSpan.Text(inner, code = true)
            else -> MarkdownSpan.Text(inner, italic = true)
        }
        index = end + marker.length
    }
    flush()
    return spans
}

/** The emphasis marker that starts at [index], if any; underscores only count on a word boundary. */
private fun markerAt(markdown: String, index: Int): String? {
    val char = markdown[index]
    return when {
        markdown.startsWith("**", index) -> "**"
        markdown.startsWith("~~", index) -> "~~"
        char == '`' -> "`"
        char == '*' -> "*"
        markdown.startsWith("__", index) ->
            if (isWordBoundaryBefore(markdown, index)) "__" else null
        char == '_' -> if (isWordBoundaryBefore(markdown, index)) "_" else null
        else -> null
    }
}

/** Where the marker that opened at [start] closes, or -1 when it never does. */
private fun closingMarker(markdown: String, marker: String, start: Int): Int {
    var found = markdown.indexOf(marker, start)
    while (found > 0) {
        val closesWord = marker.all { it == '_' } && found + marker.length < markdown.length &&
            markdown[found + marker.length].isLetterOrDigit()
        if (!closesWord) return found
        found = markdown.indexOf(marker, found + marker.length)
    }
    return -1
}

private fun isWordBoundaryBefore(markdown: String, index: Int): Boolean =
    index == 0 || !markdown[index - 1].isLetterOrDigit()

/** `#`…`######` followed by the heading text; GitHub requires the space, and so does this. */
private val ATX_HEADING = Regex("^(#{1,6})\\s+(.*)$")

/** Three or more of the same rule character, optionally spaced: `---`, `***`, `_ _ _`. */
private val RULE = Regex("^([-*_])(?:\\s*\\1){2,}$")

/** Bullet: indentation, the marker, then the text. */
private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")

/** Ordered item: indentation, the number with `.` or `)`, then the text. */
private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")

/** GitHub task list prefix inside a bullet: `[ ]` or `[x]`. */
private val TASK_PREFIX = Regex("^\\[([ xX])]\\s*")

/** `[text](url)`, `[text](url "title")` or `![alt](url)`; callers anchor it by comparing the match start. */
private val LINK = Regex("!?\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")

/** Two spaces per nesting level; a tab counts as one level because notes are written by hand. */
private fun depthOf(indent: String): Int =
    (indent.count { it == ' ' } / 2 + indent.count { it == '\t' }).coerceAtMost(3)

/**
 * Joins the lines of one paragraph the way Markdown does: a soft break becomes a space, a hard break (two trailing
 * spaces or a trailing backslash) stays a newline.
 */
private fun joinLines(lines: List<String>): String {
    val builder = StringBuilder()
    lines.forEachIndexed { position, raw ->
        if (position > 0) {
            val previous = lines[position - 1]
            val previousBreak = previous.endsWith("  ") || previous.endsWith("\\")
            builder.append(if (previousBreak) '\n' else ' ')
        }
        builder.append(raw.removeSuffix("\\").trim())
    }
    return builder.toString().trim()
}

/** Drops `<!-- … -->` blocks: GitHub's release template leaves those as placeholders for maintainers. */
private fun stripComments(markdown: String): String {
    if (!markdown.contains("<!--")) return markdown
    val builder = StringBuilder(markdown.length)
    var index = 0
    while (index < markdown.length) {
        val start = markdown.indexOf("<!--", index)
        if (start < 0) {
            builder.append(markdown, index, markdown.length)
            break
        }
        builder.append(markdown, index, start)
        val end = markdown.indexOf("-->", start + 4)
        index = if (end < 0) markdown.length else end + 3
    }
    return builder.toString()
}
