package org.skepsun.kototoro.search.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.levenshteinDistance

internal class LocalContentSearchQuery(
    query: String,
    private val kind: SearchKind,
    advanced: AdvancedSearchParams? = null,
) {
    private val query = query.trim()
    private val advanced = advanced.takeIf { kind == SearchKind.ADVANCED }
    private val title = this.advanced?.title.orEmpty().trim()
    private val author = this.advanced?.author.orEmpty().trim()
    private val tags = this.advanced?.tags.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    private val includedTags = tags.filterNot { it.startsWith('-') }
    private val excludedTags = tags.filter { it.startsWith('-') }.map { it.drop(1) }.filter(String::isNotEmpty)

    val hasCriteria: Boolean
        get() = query.isNotEmpty() || title.isNotEmpty() || author.isNotEmpty() ||
            includedTags.isNotEmpty() || excludedTags.isNotEmpty()

    fun search(contents: List<Content>, limit: Int): List<Content> {
        if (limit <= 0 || !hasCriteria) return emptyList()
        val matches = contents.asSequence().filter(::matches)
        val rankingQuery = query.ifEmpty { title }
        val sorted = if (
            rankingQuery.isNotEmpty() && kind in setOf(SearchKind.SIMPLE, SearchKind.TITLE, SearchKind.ADVANCED)
        ) {
            matches.sortedWith(compareBy<Content> { it.title.levenshteinDistance(rankingQuery) }.thenBy { it.title })
        } else {
            matches
        }
        return sorted.take(limit).toList()
    }

    private fun matches(content: Content): Boolean = when (kind) {
        SearchKind.SIMPLE, SearchKind.TITLE -> content.matchesTitle(query)
        SearchKind.AUTHOR -> content.authors.any { it.contains(query, ignoreCase = true) }
        SearchKind.TAG -> content.tags.any { it.title.contains(query, ignoreCase = true) }
        SearchKind.ADVANCED ->
            (query.isEmpty() || content.matchesTitle(query)) &&
                (title.isEmpty() || content.matchesTitle(title)) &&
                (author.isEmpty() || content.authors.any { it.contains(author, ignoreCase = true) }) &&
                includedTags.all { tag -> content.tags.any { it.title.equals(tag, ignoreCase = true) } } &&
                excludedTags.none { tag -> content.tags.any { it.title.equals(tag, ignoreCase = true) } }
    }

    private fun Content.matchesTitle(query: String): Boolean =
        title.contains(query, ignoreCase = true) || altTitles.any { it.contains(query, ignoreCase = true) }
}
