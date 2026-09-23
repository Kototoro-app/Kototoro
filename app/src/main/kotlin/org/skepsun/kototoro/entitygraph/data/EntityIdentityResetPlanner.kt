package org.skepsun.kototoro.entitygraph.data

import org.skepsun.kototoro.core.db.entity.MangaEntity
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
    val canonicalScoreByMangaId = buildResetCanonicalScores(workHistorySnapshot, workFavouriteSnapshot)
    return buildEquivalentProjectionGroups(mangaIds, mangaById)
        .map { equivalentGroup ->
            val sortedIds = equivalentGroup.mangaIds
            ResetProjectionGroup(
                mangaIds = sortedIds,
                canonicalMangaId = selectCanonicalProjectionMangaId(
                    mangaIds = sortedIds,
                    mangaById = mangaById,
                    priorityScore = { mangaId -> canonicalScoreByMangaId[mangaId] ?: 0L },
                ) ?: sortedIds.first(),
                contentType = mangaById[sortedIds.first()]?.resolvedProjectionContentType(),
            )
        }
        .sortedBy { it.canonicalMangaId }
        .toList()
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
