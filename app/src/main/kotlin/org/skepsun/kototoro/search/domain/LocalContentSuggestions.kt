package org.skepsun.kototoro.search.domain

import org.skepsun.kototoro.parsers.model.Content

/**
 * How many stored rows to read per suggestion wanted. The library keeps several rows for one work, and
 * the duplicates are dropped after the query, so reading exactly the limit would let them crowd out
 * other works.
 */
internal const val LOCAL_SUGGESTION_OVERFETCH = 4

/**
 * The stored works to suggest for [query]: one entry per work, best matches first.
 *
 * A work is identified by its source and title, ignoring case and spacing, because the same work is
 * stored under several ids (for example once per place it was opened from). The most complete row of
 * a group represents it so the card can still be opened and has a cover. A blank [query] keeps the
 * incoming order, which is already the "top content" order.
 */
internal fun List<Content>.toLocalSuggestions(query: String, limit: Int): List<Content> {
    if (limit <= 0 || isEmpty()) return emptyList()
    val works = distinctWorks()
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return works.take(limit)
    // Stable, so rows the database already ordered keep that order within a rank.
    return works.sortedBy { matchRank(it, needle) }.take(limit)
}

private fun List<Content>.distinctWorks(): List<Content> {
    val representatives = LinkedHashMap<String, Content>()
    for (content in this) {
        val key = content.source.name + '\u0000' + content.title.trim().replace(WHITESPACE, " ").lowercase()
        val current = representatives[key]
        if (current == null || content.completeness() > current.completeness()) {
            representatives[key] = content
        }
    }
    return representatives.values.toList()
}

private val WHITESPACE = Regex("\\s+")

private fun Content.completeness(): Int =
    (if (url.isNotBlank()) 2 else 0) + (if (!coverUrl.isNullOrBlank()) 1 else 0)

/**
 * Lower is better: the title starts with the query as a whole word ("One Piece" for "one"), starts
 * with it inside a longer word ("Oneppyu"), a later title word starts with it, it appears anywhere
 * in the title, it appears in an alternative title.
 */
private fun matchRank(content: Content, needle: String): Int {
    val title = content.title.lowercase()
    return when {
        title.startsWith(needle) -> if (title.endsWordAt(needle.length)) 0 else 1
        title.hasWordStartingWith(needle) -> 2
        needle in title -> 3
        content.altTitles.any { needle in it.lowercase() } -> 4
        else -> 5
    }
}

/**
 * Whether a word ends at [end], i.e. the match is not cut out of a longer word. CJK text is not
 * written with word separators, so every character there counts as its own word.
 */
private fun String.endsWordAt(end: Int): Boolean =
    end >= length || !this[end].isWordChar() || !this[end - 1].isWordChar()

private fun String.hasWordStartingWith(needle: String): Boolean {
    var from = 0
    while (true) {
        val index = indexOf(needle, from)
        if (index < 0) return false
        if (index == 0 || !this[index - 1].isWordChar()) return true
        from = index + 1
    }
}

private fun Char.isWordChar(): Boolean = isLetterOrDigit() && code < FIRST_CJK_CODE_POINT

/** CJK radicals, kana, ideographs and Hangul all sit above this; Latin, Greek and Cyrillic below. */
private const val FIRST_CJK_CODE_POINT = 0x2E80
