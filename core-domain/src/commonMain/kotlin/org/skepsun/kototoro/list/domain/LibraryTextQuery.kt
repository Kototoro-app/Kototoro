package org.skepsun.kototoro.list.domain

/**
 * Text filter for library pages (history, favourites) that narrows an already loaded
 * snapshot in memory.
 *
 * The query is split on whitespace and every term must appear in at least one field,
 * so "one piece oda" finds a work whose title holds "one piece" and whose author is Oda.
 * Matching is case-insensitive substring matching; blank input matches everything.
 */
class LibraryTextQuery(raw: String) {

    private val terms: List<String> = raw.trim()
        .split(WHITESPACE)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .distinct()

    val isEmpty: Boolean
        get() = terms.isEmpty()

    fun matches(fields: Sequence<String?>): Boolean {
        if (terms.isEmpty()) return true
        val haystack = fields.mapNotNull { it?.takeIf(String::isNotEmpty)?.lowercase() }.toList()
        return terms.all { term -> haystack.any { it.contains(term) } }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
