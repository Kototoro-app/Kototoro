package org.skepsun.kototoro.details.domain

/**
 * Android's `RelatedContentSearchFallback` as rules both hosts share.
 *
 * Android asks a source for related works first and, when it cannot answer, searches the source with the work's own
 * titles. Windows's Mihon host has no related API at all, so the search is the only route there; keeping the queries
 * and the candidate test here means both hosts pick the same works out of the same search.
 */
object RelatedContentRules {

    /** How many searches one work may cost; Android stops at the same number. */
    const val MAX_QUERIES = 6

    private val WHITESPACE = Regex("\\s+")

    /**
     * The searches to run for a work: its title, its alternative titles, then the words of both — single characters
     * are too broad to narrow a search. Order is Android's, first occurrence of a duplicate wins.
     */
    fun searchQueries(title: String, altTitles: Iterable<String>): List<String> {
        val titles = listOf(title) + altTitles.toList()
        val queries = LinkedHashSet<String>()
        titles.forEach { queries.add(it) }
        titles.forEach { value -> queries.addAll(value.split(WHITESPACE).filter { it.length > 1 }) }
        return queries.asSequence().map(String::trim).filter(String::isNotBlank).take(MAX_QUERIES).toList()
    }

    /**
     * Whether [candidate] answers [query]: its title or one of its alternative titles contains the query,
     * case-insensitively. Whether the candidate is the work itself is the caller's business — it has the ids and URLs.
     */
    fun isSearchCandidate(query: String, candidate: RelatedCandidate): Boolean =
        candidate.title.contains(query, ignoreCase = true) ||
            candidate.altTitles.any { it.contains(query, ignoreCase = true) }

    /**
     * Android's choice between the result sets of [searchQueries]: the smallest non-empty one. A broad query returns
     * hundreds of unrelated works while the exact title returns a handful, and the smallest set is the one that
     * matched the work most closely.
     */
    fun <T> smallestResult(sets: List<List<T>>): List<T> = sets.minByOrNull { it.size }.orEmpty()
}

/**
 * The fields the candidate test looks at, so the rule stays free of both hosts' content models: Android passes its
 * parser `Content`, Windows its `SourceContent`.
 */
data class RelatedCandidate(
    val title: String,
    val altTitles: Iterable<String> = emptyList(),
)
