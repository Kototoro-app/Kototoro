package org.skepsun.kototoro.migration.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

data class MatchCandidate(val content: Content, val score: Double)

data class SourceOutcome(val source: ContentSource, val candidates: List<MatchCandidate>, val error: Throwable?)

data class MatchResult(
    /** Best match with details (chapters) loaded, or null when nothing was eligible. */
    val best: Content?,
    /** Eligible candidates from every searched source, best first. */
    val candidates: List<MatchCandidate>,
    /** Source name → error for sources that failed. */
    val errors: Map<String, Throwable>,
)

/**
 * Finds the entry on other sources that most likely is the same work. [search] and
 * [fetchDetails] are injected so the engine stays free of Android and network types;
 * [withSourcePermit] lets the caller throttle requests per source.
 */
class SmartMatchEngine(
    private val search: suspend (ContentSource, String) -> List<Content>,
    private val fetchDetails: suspend (Content) -> Content,
    private val withSourcePermit: suspend (ContentSource, suspend () -> Unit) -> Unit = { _, block -> block() },
) {

    suspend fun match(
        origin: Content,
        sources: List<ContentSource>,
        mode: MatchMode,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult = when (mode) {
        MatchMode.FIRST_HIT -> matchFirstHit(origin, sources, extraQuery, deepSearch)
        MatchMode.MOST_CHAPTERS -> matchMostChapters(origin, sources, extraQuery, deepSearch)
    }

    /**
     * Searches one source with one query (used by manual search). A manual query is the
     * user's own judgement, so [minScore] lets it keep results unlike the original title.
     */
    suspend fun searchSource(
        origin: Content,
        source: ContentSource,
        query: String,
        minScore: Double = TitleSimilarity.MIN_ELIGIBLE,
    ): SourceOutcome = searchSource(
        origin = origin,
        source = source,
        queries = listOf(query),
        deepSearch = false,
        minScore = minScore,
        referenceTitles = if (minScore < TitleSimilarity.MIN_ELIGIBLE) listOf(query) else null,
    )

    private suspend fun matchFirstHit(
        origin: Content,
        sources: List<ContentSource>,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult {
        val candidates = mutableListOf<MatchCandidate>()
        val errors = mutableMapOf<String, Throwable>()
        for (source in sources) {
            val outcome = searchSource(origin, source, queriesFor(origin, extraQuery, deepSearch), deepSearch)
            outcome.error?.let { errors[source.name] = it }
            candidates += outcome.candidates
            val top = outcome.candidates.firstOrNull() ?: continue
            val details = runCatchingCancellable { fetchDetails(top.content) }
                .onFailure { errors[source.name] = it }
                .getOrNull() ?: continue
            return MatchResult(details, candidates.sortedByDescending { it.score }, errors)
        }
        return MatchResult(null, candidates.sortedByDescending { it.score }, errors)
    }

    private suspend fun matchMostChapters(
        origin: Content,
        sources: List<ContentSource>,
        extraQuery: String,
        deepSearch: Boolean,
    ): MatchResult = coroutineScope {
        val queries = queriesFor(origin, extraQuery, deepSearch)
        val outcomes = sources.map { source ->
            async {
                val outcome = searchSource(origin, source, queries, deepSearch)
                val top = outcome.candidates.firstOrNull()
                val details = top?.let {
                    runCatchingCancellable { fetchDetails(it.content) }.getOrNull()
                }
                Triple(outcome, top, details)
            }
        }.awaitAll()
        val errors = outcomes.mapNotNull { (o, _, _) -> o.error?.let { o.source.name to it } }.toMap()
        val best = outcomes
            .mapNotNull { (_, top, details) -> if (top != null && details != null) top.score to details else null }
            .maxWithOrNull(compareBy<Pair<Double, Content>> { it.second.chaptersCount() }.thenBy { it.first })
            ?.second
        MatchResult(best, outcomes.flatMap { it.first.candidates }.sortedByDescending { it.score }, errors)
    }

    private suspend fun searchSource(
        origin: Content,
        source: ContentSource,
        queries: List<String>,
        deepSearch: Boolean,
        minScore: Double = TitleSimilarity.MIN_ELIGIBLE,
        referenceTitles: List<String>? = null,
    ): SourceOutcome {
        var error: Throwable? = null
        val originTitles = referenceTitles ?: originTitles(origin, deepSearch)
        val found = LinkedHashMap<Long, MatchCandidate>()
        for (query in queries) {
            var results: List<Content> = emptyList()
            withSourcePermit(source) {
                results = runCatchingCancellable { search(source, query) }
                    .onFailure { error = it }
                    .getOrDefault(emptyList())
            }
            val filtered = results.filterNot { it.isSameEntryAs(origin) }
            for (candidate in filtered) {
                val score = if (queries.size == 1 && filtered.size == 1) {
                    1.0
                } else {
                    TitleSimilarity.bestSimilarity(originTitles, candidateTitles(candidate, deepSearch))
                }
                if (score < minScore) continue
                val previous = found[candidate.id]
                if (previous == null || previous.score < score) {
                    found[candidate.id] = MatchCandidate(candidate, score)
                }
            }
        }
        val limit = if (minScore < TitleSimilarity.MIN_ELIGIBLE) MAX_PER_SOURCE_MANUAL else MAX_PER_SOURCE
        return SourceOutcome(source, found.values.sortedByDescending { it.score }.take(limit), error)
    }

    private fun queriesFor(origin: Content, extraQuery: String, deepSearch: Boolean): List<String> {
        val base = if (deepSearch) {
            TitleSimilarity.deepSearchQueries(TitleSimilarity.cleanDeepSearchTitle(origin.title))
                .ifEmpty { listOf(origin.title) }
        } else {
            listOf(origin.title)
        }
        val extra = extraQuery.trim()
        return if (extra.isEmpty()) base else base.map { "$it $extra" }
    }

    private fun originTitles(origin: Content, deepSearch: Boolean): List<String> {
        val titles = listOf(origin.title) + origin.altTitles
        return if (deepSearch) titles.map(TitleSimilarity::cleanDeepSearchTitle) else titles
    }

    private fun candidateTitles(candidate: Content, deepSearch: Boolean): List<String> {
        val titles = listOf(candidate.title) + candidate.altTitles
        return if (deepSearch) titles.map(TitleSimilarity::cleanDeepSearchTitle) else titles
    }

    private fun Content.isSameEntryAs(origin: Content): Boolean =
        id == origin.id || (source.name == origin.source.name && url == origin.url)

    private companion object {
        const val MAX_PER_SOURCE = 3
        const val MAX_PER_SOURCE_MANUAL = 10
    }
}
