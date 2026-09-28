package org.skepsun.kototoro.local.epub

/**
 * Plain text of one EPUB content document, as the novel reader shows it.
 *
 * @param keepImages whether `<img>` becomes a `📷 [图片: src]` placeholder paragraph (content
 * documents) or is dropped (plain text extraction).
 */
internal fun epubHtmlToText(html: String, keepImages: Boolean): String {
    var text = html
        // 移除script和style标签及其内容
        .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        // head（尤其是 <title>）不是正文：否则标题会与正文首个标题粘连成 "Part 1Part 1"
        .replace(HEAD, "")
        .replace(TITLE, "")
        // 块级元素各自成行，避免相邻块的文字粘在一起
        .replace(BLOCK_TAG, "\n")
    if (keepImages) {
        // 保留图片标签，转换为描述性文本（保留原始 src 以便后续相对路径解析）
        text = text.replace(IMAGE_TAG) { matchResult ->
            val src = matchResult.groupValues[1]
            val alt = matchResult.groupValues.getOrNull(2)
            val displayText = when {
                src.isNotBlank() -> src
                !alt.isNullOrBlank() -> alt
                else -> src
            }
            "\n\n📷 [图片: $displayText]\n\n"
        }
    }
    return text
        // 将<br>和<p>标签转换为换行
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        // 移除其他HTML标签
        .replace(Regex("<[^>]+>"), "")
        // 解码HTML实体
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        // 清理多余的空白
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n[ \\t]+"), "\n")
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private val HEAD = Regex("<head\\b[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private val TITLE = Regex("<title\\b[^>]*>.*?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private val BLOCK_TAG = Regex(
    "</?(?:h[1-6]|div|section|article|header|footer|aside|nav|blockquote|li|ul|ol|dl|dt|dd|tr|table|figure|figcaption|pre|hr)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

private val IMAGE_TAG = Regex(
    "<img[^>]*src=[\"']([^\"']+)[\"'][^>]*(?:alt=[\"']([^\"']*)[\"'])?[^>]*>",
    RegexOption.IGNORE_CASE,
)
