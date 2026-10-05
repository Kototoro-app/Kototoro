package org.skepsun.kototoro.bookmarks.domain

import org.jsoup.Jsoup

/** Android/JVM share the existing Jsoup dependency; only the HTML adapter is platform-specific. */
fun parseNovelBookmarkPreview(value: String?): String = normalizeNovelBookmarkPreview(value) { html ->
    Jsoup.parse(html).apply { select("script, style, meta, link").remove() }.body().text()
}
