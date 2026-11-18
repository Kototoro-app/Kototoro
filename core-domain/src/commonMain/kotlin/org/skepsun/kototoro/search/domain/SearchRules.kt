package org.skepsun.kototoro.search.domain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min

/** How many sources a global search asks at once. */
const val SEARCH_PARALLELISM = 4

/** Titles closer than this (edit distance over the mean length) count as the searched title. */
private const val TITLE_MATCH_THRESHOLD = 0.2f

/** What a search rule needs to know about a result, whichever platform model it came from. */
class SearchCandidate(
    val title: String,
    val altTitle: String?,
    val authors: Collection<String>,
    val tags: Collection<String>,
)

/**
 * What to ask one source for a search: the title and author text it can search, and the source tags that name the
 * wanted and unwanted genres. Null from [SearchRules.plan] means the source cannot answer this search.
 */
data class SourceSearchPlan(
    val query: String?,
    val author: String?,
    val tags: Set<String>,
    val tagsExclude: Set<String>,
)

/**
 * Android's search rules (the old `SearchV2Helper`) without any platform model: which filter a source gets for a kind
 * of search, which results are kept, and in what order. The Android search screen and the Windows global search both
 * run on this.
 */
object SearchRules {

    /** Splits "a, b, -c" into wanted ("a", "b") and unwanted ("c") tag names. */
    fun parseTagQuery(text: String?): Pair<List<String>, List<String>> {
        val parts = text.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.filter { it[0] != '-' } to parts.filter { it[0] == '-' }.map { it.substring(1) }
    }

    /**
     * [knownTags] are the source's tag titles; a tag query only counts when the source has that tag.
     * Returns null when this source cannot answer the search.
     */
    fun plan(
        kind: SearchKind,
        query: String,
        advanced: AdvancedSearchParams?,
        supportsSearch: Boolean,
        supportsAuthorSearch: Boolean,
        knownTags: Collection<String>,
    ): SourceSearchPlan? {
        fun known(names: List<String>): Set<String> = names.mapNotNullTo(linkedSetOf()) { name ->
            knownTags.firstOrNull { it.equals(name, ignoreCase = true) }
        }
        return when (kind) {
            SearchKind.SIMPLE, SearchKind.TITLE ->
                if (supportsSearch) SourceSearchPlan(query, null, emptySet(), emptySet()) else null

            SearchKind.AUTHOR -> when {
                supportsAuthorSearch -> SourceSearchPlan(null, query, emptySet(), emptySet())
                supportsSearch -> SourceSearchPlan(query, null, emptySet(), emptySet())
                else -> null
            }

            SearchKind.TAG -> {
                val (include, exclude) = parseTagQuery(query)
                val tags = known(include)
                val excluded = known(exclude)
                if (tags.isEmpty() && excluded.isEmpty()) null else SourceSearchPlan(null, null, tags, excluded)
            }

            SearchKind.ADVANCED -> {
                val (include, exclude) = parseTagQuery(advanced?.tags)
                val tags = known(include)
                val excluded = known(exclude)
                val title = if (supportsSearch) {
                    advanced?.title?.takeIf { it.isNotEmpty() } ?: advanced?.query?.takeIf { it.isNotEmpty() }
                } else null
                val author = if (supportsAuthorSearch) advanced?.author else null
                if (tags.isEmpty() && excluded.isEmpty() && title == null && author == null) null
                else SourceSearchPlan(title, author, tags, excluded)
            }
        }
    }

    /** Sources answer title searches best by relevance and tag searches by popularity. */
    fun prefersPopularity(kind: SearchKind): Boolean = kind == SearchKind.TAG

    /** The results a search keeps; the caller has already dropped blacklisted and (when switched off) adult ones. */
    fun accepts(candidate: SearchCandidate, kind: SearchKind, query: String, advanced: AdvancedSearchParams?): Boolean =
        when (kind) {
            SearchKind.TITLE -> matchesTitle(candidate, query)
            SearchKind.AUTHOR -> candidate.authors.isEmpty() || candidate.authors.containsIgnoreCase(query)
            SearchKind.SIMPLE, SearchKind.TAG -> true
            SearchKind.ADVANCED -> {
                val title = advanced?.title?.takeIf { it.isNotEmpty() }?.let { matchesTitle(candidate, it) }
                val author = advanced?.author?.takeIf { it.isNotEmpty() }?.let {
                    candidate.authors.isEmpty() || candidate.authors.containsIgnoreCase(it)
                }
                val tags = advanced?.tags?.takeIf { it.isNotEmpty() }?.let { text ->
                    val (include, exclude) = parseTagQuery(text)
                    include.all { candidate.hasTag(it) } && exclude.none { candidate.hasTag(it) }
                }
                title != false && author != false && tags != false
            }
        }

    /**
     * Whether a work the user already has (history, favourites, local) answers the search. This is a plain text match,
     * unlike [accepts], which judges what a source returned for a query it already searched.
     */
    fun matchesLibrary(candidate: SearchCandidate, kind: SearchKind, query: String, advanced: AdvancedSearchParams?): Boolean {
        fun titleHas(text: String) = candidate.title.contains(text, ignoreCase = true) ||
            candidate.altTitle?.contains(text, ignoreCase = true) == true
        return when (kind) {
            SearchKind.SIMPLE, SearchKind.TITLE -> titleHas(query)
            SearchKind.AUTHOR -> candidate.authors.containsIgnoreCase(query)
            SearchKind.TAG -> {
                val (include, exclude) = parseTagQuery(query)
                (include.isNotEmpty() || exclude.isNotEmpty()) &&
                    include.all { candidate.hasTag(it) } && exclude.none { candidate.hasTag(it) }
            }
            SearchKind.ADVANCED -> {
                val title = advanced?.title?.ifEmpty { null } ?: advanced?.query?.ifEmpty { null }
                val author = advanced?.author?.ifEmpty { null }
                val (include, exclude) = parseTagQuery(advanced?.tags)
                (title != null || author != null || include.isNotEmpty() || exclude.isNotEmpty()) &&
                    (title == null || titleHas(title)) &&
                    (author == null || candidate.authors.containsIgnoreCase(author)) &&
                    include.all { candidate.hasTag(it) } && exclude.none { candidate.hasTag(it) }
            }
        }
    }

    /** Higher is more relevant; sort the results by it, descending. */
    fun relevance(candidate: SearchCandidate, kind: SearchKind, query: String, advanced: AdvancedSearchParams?): Int =
        when (kind) {
            SearchKind.SIMPLE, SearchKind.TITLE -> -titleDistance(candidate, query)
            SearchKind.AUTHOR -> if (candidate.authors.containsIgnoreCase(query)) 1 else 0
            SearchKind.TAG -> tagScore(candidate, query)
            SearchKind.ADVANCED -> {
                val tagScore = tagScore(candidate, advanced?.tags.orEmpty())
                val authorScore = if (candidate.authors.containsIgnoreCase(advanced?.author.orEmpty())) 5 else 0
                -titleDistance(candidate, advanced?.query.orEmpty()) -
                    titleDistance(candidate, advanced?.title.orEmpty()) + tagScore + authorScore
            }
        }

    /** Stable: equally relevant results keep the order the source gave them. */
    fun <T> sortByRelevance(
        items: List<T>,
        kind: SearchKind,
        query: String,
        advanced: AdvancedSearchParams?,
        candidate: (T) -> SearchCandidate,
    ): List<T> = items.sortedByDescending { relevance(candidate(it), kind, query, advanced) }

    private fun tagScore(candidate: SearchCandidate, text: String): Int {
        val (include, exclude) = parseTagQuery(text)
        return candidate.tags.count { tag -> include.any { it.equals(tag, ignoreCase = true) } } -
            candidate.tags.count { tag -> exclude.any { it.equals(tag, ignoreCase = true) } }
    }

    private fun SearchCandidate.hasTag(name: String) = tags.any { it.equals(name, ignoreCase = true) }

    private fun Collection<String>.containsIgnoreCase(text: String) = any { it.contains(text, ignoreCase = true) }

    private fun matchesTitle(candidate: SearchCandidate, query: String): Boolean =
        titlesAlmostEqual(candidate.title, query) || titlesAlmostEqual(candidate.altTitle, query)

    private fun titlesAlmostEqual(a: String?, b: String?): Boolean =
        !a.isNullOrEmpty() && !b.isNullOrEmpty() && almostEquals(a, b, TITLE_MATCH_THRESHOLD)

    private fun titleDistance(candidate: SearchCandidate, query: String): Int =
        min(levenshteinDistance(candidate.title, query), candidate.altTitle?.let { levenshteinDistance(it, query) } ?: Int.MAX_VALUE)

    internal fun almostEquals(a: String, b: String, threshold: Float): Boolean {
        if (threshold <= 0f) return a.equals(b, ignoreCase = true)
        return levenshteinDistance(a.lowercase(), b.lowercase()) / ((a.length + b.length) / 2f) < threshold
    }

    internal fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var cost = IntArray(a.length + 1) { it }
        var next = IntArray(a.length + 1)
        for (i in 1..b.length) {
            next[0] = i
            for (j in 1..a.length) {
                val replace = cost[j - 1] + if (a[j - 1] == b[i - 1]) 0 else 1
                next[j] = min(min(cost[j] + 1, next[j - 1] + 1), replace)
            }
            val swap = cost
            cost = next
            next = swap
        }
        return cost[a.length]
    }
}

/**
 * Asks every source of [sources] with at most [parallelism] in flight and reports each answer as it arrives, in
 * completion order. The caller's [search] decides what a failure means; an exception it lets through ends the search.
 */
suspend fun <S, R> searchSourcesInParallel(
    sources: List<S>,
    parallelism: Int = SEARCH_PARALLELISM,
    search: suspend (S) -> R,
    onResult: suspend (S, R) -> Unit,
) = coroutineScope {
    val gate = Semaphore(parallelism)
    sources.map { source ->
        launch { gate.withPermit { onResult(source, search(source)) } }
    }.joinAll()
}

/** The queries a user searched for, newest first: what the search box offers again and completes from. */
object SearchHistoryRules {

    const val LIMIT = 20

    /** [query] goes to the front; an earlier copy of it (ignoring case) and the oldest entries beyond [limit] drop out. */
    fun remember(history: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val text = query.trim()
        if (text.length < 2) return history
        return (listOf(text) + history.filterNot { it.equals(text, ignoreCase = true) }).take(limit)
    }

    /** Earlier queries that contain what is typed, other than the typed text itself, newest first. */
    fun suggest(history: List<String>, typed: String, limit: Int = 5): List<String> {
        val text = typed.trim()
        if (text.isEmpty()) return history.take(limit)
        return history.filter { it.contains(text, ignoreCase = true) && !it.equals(text, ignoreCase = true) }.take(limit)
    }
}
