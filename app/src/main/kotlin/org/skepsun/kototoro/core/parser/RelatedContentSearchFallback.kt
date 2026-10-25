package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.details.domain.RelatedCandidate
import org.skepsun.kototoro.details.domain.RelatedContentRules
import org.skepsun.kototoro.parsers.model.Content

/**
 * What Android does when a source has no related API of its own: search the source with the work's titles and keep
 * the results that name the work. The queries and the choice between result sets are [RelatedContentRules], which
 * Windows' details page runs through the same source search over its own content model.
 */
object RelatedContentSearchFallback {

    suspend fun find(
        seed: Content,
        search: suspend (String) -> List<Content>,
    ): List<Content> {
        val queries = RelatedContentRules.searchQueries(seed.title, seed.altTitles)
        if (queries.isEmpty()) {
            return emptyList()
        }
        return RelatedContentRules.smallestResult(
            queries.mapNotNull { query ->
                runCatching {
                    search(query).filter { content ->
                        content.isRelatedCandidate(seed, query)
                    }
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            },
        )
    }

    private fun Content.isRelatedCandidate(seed: Content, query: String): Boolean {
        return id != seed.id &&
            url != seed.url &&
            publicUrl != seed.publicUrl &&
            RelatedContentRules.isSearchCandidate(query, RelatedCandidate(title, altTitles))
    }
}
