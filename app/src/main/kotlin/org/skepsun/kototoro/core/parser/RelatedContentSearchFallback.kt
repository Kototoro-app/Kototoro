package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.splitByWhitespace

object RelatedContentSearchFallback {

	private const val MAX_QUERIES = 6

	suspend fun find(
		seed: Content,
		search: suspend (String) -> List<Content>,
	): List<Content> {
		val queries = buildQueries(seed)
		if (queries.isEmpty()) {
			return emptyList()
		}
		return queries.mapNotNull { query ->
			runCatching {
				search(query).filter { content ->
					content.isRelatedCandidate(seed, query)
				}
			}.getOrNull()?.takeIf { it.isNotEmpty() }
		}.minByOrNull { it.size }.orEmpty()
	}

	private fun buildQueries(seed: Content): List<String> {
		return linkedSetOf<String>().apply {
			add(seed.title)
			addAll(seed.altTitles)
			addAll(seed.title.splitByWhitespace().filter { it.length > 1 })
			seed.altTitles.forEach { title ->
				addAll(title.splitByWhitespace().filter { it.length > 1 })
			}
		}.map { it.trim() }
			.filter { it.isNotBlank() }
			.take(MAX_QUERIES)
	}

	private fun Content.isRelatedCandidate(seed: Content, query: String): Boolean {
		return id != seed.id &&
			url != seed.url &&
			publicUrl != seed.publicUrl &&
			(title.contains(query, ignoreCase = true) || altTitles.any { it.contains(query, ignoreCase = true) })
	}
}
