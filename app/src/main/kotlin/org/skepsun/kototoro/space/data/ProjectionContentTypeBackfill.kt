package org.skepsun.kototoro.space.data

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

@Reusable
class ProjectionContentTypeBackfill @Inject constructor(
	private val db: MangaDatabase,
) {

	suspend fun backfill(
		resolvedSources: Collection<ContentSource>,
		limit: Int = DEFAULT_BATCH_SIZE,
	): Int {
		if (limit <= 0) return 0
		val contentTypesBySource = resolvedSources
			.mapNotNull { source ->
				source.resolvedContentTypeForSnapshot()?.name?.let { source.name to it }
			}
			.toMap()
		if (contentTypesBySource.isEmpty()) return 0

		val dao = db.getMangaDao()
		val candidates = contentTypesBySource.keys
			.chunked(MAX_SOURCE_QUERY_PARAMS)
			.flatMap { sources -> dao.findMissingContentTypes(sources, limit) }
			.distinctBy { it.id }
			.take(limit)
		return candidates.sumOf { candidate ->
			val contentType = contentTypesBySource[candidate.source] ?: return@sumOf 0
			dao.setContentTypeIfMissing(candidate.id, contentType)
		}
	}

	suspend fun backfillAll(resolvedSources: Collection<ContentSource>): Int {
		val sourcesByContentType = resolvedSources
			.mapNotNull { source ->
				source.resolvedContentTypeForSnapshot()?.name?.let { source.name to it }
			}
			.distinct()
			.groupBy(keySelector = Pair<String, String>::second, valueTransform = Pair<String, String>::first)
		if (sourcesByContentType.isEmpty()) return 0

		val dao = db.getMangaDao()
		return sourcesByContentType.entries.sumOf { (contentType, sources) ->
			sources.chunked(MAX_SOURCE_QUERY_PARAMS).sumOf { sourceChunk ->
				dao.setContentTypeIfMissingForSources(sourceChunk, contentType)
			}
		}
	}

	private companion object {
		const val DEFAULT_BATCH_SIZE = 500
		const val MAX_SOURCE_QUERY_PARAMS = 400
	}
}
