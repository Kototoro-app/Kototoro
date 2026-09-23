package org.skepsun.kototoro.entitygraph.data

import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.entitygraph.domain.normalizeStrictTitleKey
import java.net.URI

/** A source-scoped set of stored rows that point at the same remote work. */
internal data class EquivalentProjectionGroup(
    val source: String,
    val mangaIds: List<Long>,
)

internal data class CrossEntityProjectionMergePlan(
    val group: EquivalentProjectionGroup,
    val canonicalMangaId: Long,
    val targetEntityId: Long,
    val sourceEntityIds: List<Long>,
)

private data class ProjectionSnapshotKey(
    val source: String,
    val contentType: String?,
    val title: String,
    val altTitles: String?,
    val rating: Float,
    val isNsfw: Boolean,
    val contentRating: String?,
    val coverUrl: String,
    val largeCoverUrl: String?,
    val state: String?,
    val authors: String?,
    val description: String?,
    val sourceData: String?,
)

/**
 * Groups duplicate projection rows using every remote identity representation.
 *
 * HTTP mirrors owned by the same source are equivalent when only their origin changed. Path and
 * query remain part of the identity, so different works on those mirrors stay separate.
 */
internal fun buildEquivalentProjectionGroups(
    mangaIds: Collection<Long>,
    mangaById: Map<Long, MangaEntity>,
): List<EquivalentProjectionGroup> {
    val distinctIds = mangaIds.distinct()
    val disjointSet = ProjectionDisjointSet(distinctIds)
    val ownerByKey = LinkedHashMap<String, Long>()
    val ownerBySnapshot = LinkedHashMap<ProjectionSnapshotKey, Long>()
    distinctIds.forEach { mangaId ->
        val manga = mangaById[mangaId] ?: return@forEach
        val contentType = manga.resolvedProjectionContentType()
        manga.equivalenceKeys(contentType).forEach { key ->
            ownerByKey.putIfAbsent(key, mangaId)?.let { previousOwner ->
                disjointSet.union(previousOwner, mangaId)
            }
        }
        manga.metadataEquivalenceKeys(contentType).forEach { key ->
            ownerByKey.putIfAbsent(key, mangaId)?.let { previousOwner ->
                disjointSet.union(previousOwner, mangaId)
            }
        }
        manga.projectionSnapshotKey(contentType)?.let { key ->
            ownerBySnapshot.putIfAbsent(key, mangaId)?.let { previousOwner ->
                disjointSet.union(previousOwner, mangaId)
            }
        }
    }
    return disjointSet.groups()
        .mapNotNull { ids ->
            val source = ids.firstNotNullOfOrNull { mangaById[it]?.source?.trim() } ?: return@mapNotNull null
            EquivalentProjectionGroup(source = source, mangaIds = ids.sorted())
        }
        .sortedBy { it.mangaIds.first() }
}

/** Returns every non-retained identity group when one source contributes several remote works. */
internal fun findConflictingSourceProjectionGroups(
    groups: List<EquivalentProjectionGroup>,
    preferredMangaId: Long?,
    entityNameMatchingMangaIds: Set<Long> = emptySet(),
): List<EquivalentProjectionGroup> {
    return groups
        .groupBy(EquivalentProjectionGroup::source)
        .values
        .filter { sourceGroups -> sourceGroups.size > 1 }
        .flatMap { sourceGroups ->
            val retainedGroup = sourceGroups.firstOrNull { preferredMangaId in it.mangaIds }
                ?: sourceGroups.firstOrNull { group -> group.mangaIds.any(entityNameMatchingMangaIds::contains) }
                ?: sourceGroups.first()
            sourceGroups.filterNot { it === retainedGroup }
        }
}

internal fun buildCrossEntityProjectionMergePlans(
    groups: List<EquivalentProjectionGroup>,
    entityIdByMangaId: Map<Long, Long>,
    mangaById: Map<Long, MangaEntity>,
    requestedEntityIds: Set<Long>? = null,
): List<CrossEntityProjectionMergePlan> {
    return groups.mapNotNull { group ->
        val ownerEntityIds = group.mangaIds.mapNotNull(entityIdByMangaId::get).distinct()
        if (
            ownerEntityIds.size <= 1 ||
            (requestedEntityIds != null && ownerEntityIds.none(requestedEntityIds::contains))
        ) {
            return@mapNotNull null
        }
        val canonicalMangaId = selectCanonicalProjectionMangaId(group.mangaIds, mangaById)
            ?: return@mapNotNull null
        val targetEntityId = entityIdByMangaId[canonicalMangaId] ?: return@mapNotNull null
        CrossEntityProjectionMergePlan(
            group = group,
            canonicalMangaId = canonicalMangaId,
            targetEntityId = targetEntityId,
            sourceEntityIds = ownerEntityIds.filterNot { it == targetEntityId },
        )
    }
}

/**
 * Selects duplicate projections whose current owner also contains another work from the same source.
 *
 * The selected projections must be split before their duplicate entities are merged; otherwise the
 * merge would also pull the owner's distinct same-source work into the duplicate target.
 */
internal fun findDuplicateProjectionIdsWithSameSourceConflict(
    group: EquivalentProjectionGroup,
    entityIdByMangaId: Map<Long, Long>,
    mangaById: Map<Long, MangaEntity>,
): List<Long> {
    val groupMangaIds = group.mangaIds.toSet()
    val mangaIdsByEntity = entityIdByMangaId.entries.groupBy(
        keySelector = Map.Entry<Long, Long>::value,
        valueTransform = Map.Entry<Long, Long>::key,
    )
    return group.mangaIds.filter { mangaId ->
        val entityId = entityIdByMangaId[mangaId] ?: return@filter false
        mangaIdsByEntity[entityId].orEmpty().any { siblingMangaId ->
            siblingMangaId !in groupMangaIds && mangaById[siblingMangaId]?.source?.trim() == group.source
        }
    }
}

/**
 * Selects projections that must leave an owner before a duplicate group can be merged safely.
 *
 * This is used only after the owners fail the compatible content-type check. An owner that also
 * contains projections outside the duplicate group may represent another work or media family, so
 * merging the whole owner would pull those unrelated projections into the duplicate target.
 */
internal fun findDuplicateProjectionIdsToDetach(
    group: EquivalentProjectionGroup,
    entityIdByMangaId: Map<Long, Long>,
): List<Long> {
    val groupMangaIds = group.mangaIds.toSet()
    val mangaIdsByEntity = entityIdByMangaId.entries.groupBy(
        keySelector = Map.Entry<Long, Long>::value,
        valueTransform = Map.Entry<Long, Long>::key,
    )
    return group.mangaIds.filter { mangaId ->
        val entityId = entityIdByMangaId[mangaId] ?: return@filter false
        mangaIdsByEntity[entityId].orEmpty().any { it !in groupMangaIds }
    }
}

internal fun MangaEntity.resolvedProjectionContentType(): String? {
    return contentType?.takeIf(String::isNotBlank)
        ?: ContentSource(source).resolvedContentTypeForSnapshot()?.name
}

internal fun selectCanonicalProjectionMangaId(
    mangaIds: Collection<Long>,
    mangaById: Map<Long, MangaEntity>,
    priorityScore: (Long) -> Long = { 0L },
): Long? {
    return mangaIds.minWithOrNull(
        compareByDescending<Long> { mangaById[it]?.hasRemoteIdentity() == true }
            .thenByDescending(priorityScore)
            .thenBy { it },
    )
}

private fun MangaEntity.equivalenceKeys(contentType: String?): Set<String> {
    val normalizedSource = source.trim()
    return sequenceOf(url, publicUrl)
        .flatMap { remoteLocationAliases(it).asSequence() }
        .mapTo(LinkedHashSet()) { location ->
            "$normalizedSource|${contentType.orEmpty()}|location|$location"
        }
}

private fun MangaEntity.metadataEquivalenceKeys(contentType: String?): Set<String> {
    val normalizedSource = source.trim()
    val titleKey = normalizeStrictTitleKey(title, listOf(normalizedSource))
    if (titleKey.isBlank()) {
        return emptySet()
    }
    return sequenceOf(coverUrl, largeCoverUrl.orEmpty())
        .flatMap { remoteLocationAliases(it).asSequence() }
        .mapTo(LinkedHashSet()) { coverLocation ->
            "$normalizedSource|${contentType.orEmpty()}|title-cover|$titleKey|$coverLocation"
        }
}

private fun MangaEntity.hasRemoteIdentity(): Boolean {
    return url.isNotBlank() || publicUrl.isNotBlank()
}

private fun remoteLocationAliases(value: String): Set<String> {
    val raw = value.trim()
    if (raw.isEmpty()) {
        return emptySet()
    }
    return buildSet {
        add("raw:$raw")
        val uri = runCatching { URI(raw) }.getOrNull() ?: return@buildSet
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            return@buildSet
        }
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        if (path != "/" || query.isNotEmpty() || fragment.isNotEmpty()) {
            add("http-location:$path$query$fragment")
        }
    }
}

private fun MangaEntity.projectionSnapshotKey(contentType: String?): ProjectionSnapshotKey? {
    if (title.isBlank()) {
        return null
    }
    return ProjectionSnapshotKey(
        source = source.trim(),
        contentType = contentType,
        title = title.trim(),
        altTitles = altTitles,
        rating = rating,
        isNsfw = isNsfw,
        contentRating = contentRating,
        coverUrl = coverUrl,
        largeCoverUrl = largeCoverUrl,
        state = state,
        authors = authors,
        description = description,
        sourceData = sourceData,
    )
}

private class ProjectionDisjointSet(mangaIds: Collection<Long>) {
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
