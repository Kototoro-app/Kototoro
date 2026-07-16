package org.skepsun.kototoro.entitygraph.data

import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity

internal data class ResetProjectionGroup(
	val mangaIds: List<Long>,
	val canonicalMangaId: Long,
	val contentType: String? = null,
)

internal data class ResetProjectionBindingKey(
	val source: String,
	val externalId: String,
)

internal fun buildResetProjectionGroups(
	mangaIds: Collection<Long>,
	mangaById: Map<Long, MangaEntity>,
	workHistorySnapshot: List<WorkHistoryEntity>,
	workFavouriteSnapshot: List<WorkFavouriteEntity>,
): List<ResetProjectionGroup> {
	val disjointSet = ResetProjectionDisjointSet(mangaIds)
	val ownerByStrongKey = LinkedHashMap<String, MutableMap<String?, Long>>()
	mangaIds.forEach { mangaId ->
		val manga = mangaById[mangaId] ?: return@forEach
		val contentType = manga.resolvedResetContentType()
		manga.resetStrongProjectionKeys().forEach { key ->
			val ownerByType = ownerByStrongKey.getOrPut(key) { LinkedHashMap() }
			val previousOwner = ownerByType.putIfAbsent(contentType, mangaId)
			if (previousOwner != null) {
				disjointSet.union(previousOwner, mangaId)
			}
		}
	}
	val canonicalScoreByMangaId = buildResetCanonicalScores(workHistorySnapshot, workFavouriteSnapshot)
	return disjointSet.groups()
		.map { ids ->
			val sortedIds = ids.sorted()
			ResetProjectionGroup(
				mangaIds = sortedIds,
				canonicalMangaId = sortedIds.minWithOrNull(
					compareByDescending<Long> { canonicalScoreByMangaId[it] ?: 0L }
						.thenBy { it },
				) ?: sortedIds.first(),
				contentType = mangaById[sortedIds.first()]?.resolvedResetContentType(),
			)
		}
		.sortedBy { it.canonicalMangaId }
		.toList()
}

private fun MangaEntity.resolvedResetContentType(): String? {
	return contentType?.takeIf { raw -> raw.isNotBlank() }
		?: ContentSource(source).resolvedContentTypeForSnapshot()?.name
}

internal fun buildResetProjectionBindingKeys(
	group: ResetProjectionGroup,
	mangaById: Map<Long, MangaEntity>,
): List<ResetProjectionBindingKey> {
	return group.mangaIds
		.asSequence()
		.mapNotNull { mangaById[it] }
		.mapNotNull { manga ->
			ProjectionIdentityKeys.bindingKey(manga.url, manga.publicUrl)?.let { key ->
				ResetProjectionBindingKey(
					source = manga.source,
					externalId = key,
				)
			}
		}
		.distinct()
		.toList()
}

internal fun resetProjectionSyncId(
	group: ResetProjectionGroup,
	mangaById: Map<Long, MangaEntity>,
): String? {
	val bindings = buildResetProjectionBindingKeys(group, mangaById)
	return bindings.singleOrNull()?.let { computeProjectionSyncId(it.source, it.externalId) }
}

private fun buildResetCanonicalScores(
	workHistorySnapshot: List<WorkHistoryEntity>,
	workFavouriteSnapshot: List<WorkFavouriteEntity>,
): Map<Long, Long> {
	val scores = LinkedHashMap<Long, Long>()
	workHistorySnapshot.forEach { entry ->
		scores[entry.anchorMangaId] = maxOf(scores[entry.anchorMangaId] ?: 0L, entry.updatedAt)
	}
	workFavouriteSnapshot.forEach { entry ->
		val anchorMangaId = entry.anchorMangaId ?: return@forEach
		scores[anchorMangaId] = maxOf(scores[anchorMangaId] ?: 0L, entry.updatedAt)
	}
	return scores
}

private fun MangaEntity.resetStrongProjectionKeys(): List<String> {
	val normalizedSource = source.trim()
	return listOfNotNull(
		url.trim().takeIf { it.isNotEmpty() },
		publicUrl.trim().takeIf { it.isNotEmpty() },
	).distinct()
		.map { "$normalizedSource|location|$it" }
}

private class ResetProjectionDisjointSet(
	mangaIds: Collection<Long>,
) {
	private val parent = mangaIds.associateWithTo(LinkedHashMap()) { it }

	fun union(left: Long, right: Long) {
		val leftRoot = find(left)
		val rightRoot = find(right)
		if (leftRoot != rightRoot) {
			parent[rightRoot] = leftRoot
		}
	}

	fun groups(): List<List<Long>> {
		val grouped = LinkedHashMap<Long, MutableList<Long>>()
		parent.keys.forEach { mangaId ->
			grouped.getOrPut(find(mangaId)) { ArrayList() } += mangaId
		}
		return grouped.values.toList()
	}

	private fun find(mangaId: Long): Long {
		val current = parent[mangaId] ?: return mangaId
		if (current == mangaId) {
			return current
		}
		val root = find(current)
		parent[mangaId] = root
		return root
	}
}
