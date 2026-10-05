package org.skepsun.kototoro.bookmarks.domain

import kotlin.io.encoding.Base64
import kotlin.math.abs

/** Shared preview policy; HTML parsing is supplied by the host without network or file access. */
fun normalizeNovelBookmarkPreview(value: String?, htmlText: (String) -> String): String {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if (listOf("http:", "https:", "file:", "content:").any { text.startsWith(it, ignoreCase = true) }) return ""
    return try {
        val decoded = if (text.startsWith("data:", ignoreCase = true)) {
            val header = text.substringBefore(',')
            if (!header.startsWith("data:text/html", true) && !header.startsWith("data:text/plain", true)) return ""
            if (!header.endsWith(";base64", true) || ',' !in text) return ""
            Base64.Default.decode(text.substringAfter(',').filterNot(Char::isWhitespace)).decodeToString()
        } else text
        val plain = if (decoded.contains('<') && decoded.contains('>')) htmlText(decoded) else decoded
        plain.replace(Regex("\\s+"), " ").take(200).trim()
    } catch (_: IllegalArgumentException) {
        ""
    }
}

/** All readers store progress within the chapter's branch, including the current segment. */
fun novelBookmarkProgress(chapterIndex: Int, chapterCount: Int, segmentIndex: Int, segmentCount: Int): Float {
    require(chapterCount > 0 && chapterIndex in 0 until chapterCount)
    require(segmentCount > 0 && segmentIndex in 0 until segmentCount)
    return (chapterIndex + (segmentIndex + 1f) / segmentCount) / chapterCount
}

fun novelBookmarkChapterProgress(progress: Float, chapterIndex: Int, chapterCount: Int): Float {
    require(chapterCount > 0 && chapterIndex in 0 until chapterCount)
    return if (progress.isFinite()) (progress * chapterCount - chapterIndex).coerceIn(0f, 1f) else 0f
}

/**
 * Restores to the segment containing the same text, independent of pagination, line breaks and paragraph splitting.
 * Empty previews keep legacy numeric restoration. A missing nonempty excerpt fails instead of jumping elsewhere.
 * Text previews use branch-relative progress to disambiguate repeats,
 * without interpreting another engine's page numbers.
 */
fun resolveNovelBookmarkPosition(
    segments: List<String>,
    preview: String,
    legacyIndex: Int,
    chapterProgress: Float,
): Int? = NovelBookmarkTextIndex(segments).resolve(preview, legacyIndex, chapterProgress)

/** Builds the chapter text once when resolving a list of bookmarks. */
class NovelBookmarkTextIndex(segments: List<String>) {
    private val ends = IntArray(segments.size)
    private val text = buildString {
        segments.forEachIndexed { index, segment ->
            append(segment.filterNot(Char::isWhitespace))
            ends[index] = length
        }
    }

    fun resolve(preview: String, legacyIndex: Int, chapterProgress: Float): Int? {
        val quote = preview.filterNot(Char::isWhitespace)
        if (quote.isEmpty()) return legacyIndex.takeIf { it in ends.indices }
        val hint = if (chapterProgress.isFinite()) chapterProgress.coerceIn(0f, 1f) else 0f
        var match = text.indexOf(quote)
        var best: Int? = null
        var distance = Float.POSITIVE_INFINITY
        while (match >= 0) {
            // Strict > skips image/empty segments and assigns an exact boundary to the following text segment.
            var left = 0
            var right = ends.size
            while (left < right) {
                val middle = (left + right) ushr 1
                if (ends[middle] <= match) left = middle + 1 else right = middle
            }
            val index = left
            val score = abs((index + 1f) / ends.size - hint)
            if (score < distance) {
                best = index
                distance = score
            }
            match = text.indexOf(quote, ends[index])
        }
        return best
    }
}
