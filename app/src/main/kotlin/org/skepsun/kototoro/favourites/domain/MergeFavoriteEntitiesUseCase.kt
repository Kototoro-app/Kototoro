package org.skepsun.kototoro.favourites.domain

import android.util.Log
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.normalizeEntityName
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

private const val TAG = "MergeFavoriteEntities"

data class MergeCandidateGroup(
    val id: String,
    val title: String,
    val normalizedTitle: String,
    val contentType: ContentType,
    val mangaIds: Set<Long>,
    val items: List<MergeCandidateItem>,
    val matchScore: Float,
    val isExactMatch: Boolean,
    val resolvedEntityId: Long? = null,
    val isAlreadyMerged: Boolean = false,
)

data class MergeCandidateItem(
    val mangaId: Long,
    val title: String,
    val normalizedTitle: String,
    val sourceName: String,
    val displaySourceName: String = sourceName,
    val coverUrl: String?,
    val score: Float,
)

data class MergeEntitiesResult(
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

class MergeFavoriteEntitiesUseCase @Inject constructor(
    private val database: MangaDatabase,
    private val entityGraphRepository: EntityGraphRepository,
    private val contentDataRepository: ContentDataRepository,
) {

    suspend fun buildCandidateGroups(contents: List<Content>): List<MergeCandidateGroup> {
        val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(contents.map { it.id })
        val trackingGroups = buildTrackingBindingGroups(
            contents = contents,
            entityIdsByMangaId = entityIdsByMangaId,
        )
        val exactGroups = contents
            .filterNot { content -> trackingGroups.any { content.id in it.mangaIds } }
            .groupBy { MergeGroupKey(normalizeTitle(it.title), it.source.contentType) }
            .mapNotNull { (key, items) ->
                val mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id }
                if (key.normalizedTitle.isBlank() || mangaIds.size < 2) {
                    null
                } else {
                    val mergeState = resolveMergeState(mangaIds, entityIdsByMangaId)
                    MergeCandidateGroup(
                        id = "${key.contentType.name}:${key.normalizedTitle}",
                        title = items.first().title,
                        normalizedTitle = key.normalizedTitle,
                        contentType = key.contentType,
                        mangaIds = mangaIds,
                        items = items.map {
                            MergeCandidateItem(
                                mangaId = it.id,
                                title = it.title,
                                normalizedTitle = key.normalizedTitle,
                                sourceName = it.source.name,
                                coverUrl = it.coverUrl,
                                score = 1f,
                            )
                        },
                        matchScore = 1f,
                        isExactMatch = true,
                        resolvedEntityId = mergeState.entityId,
                        isAlreadyMerged = mergeState.isAlreadyMerged,
                    )
                }
            }

        val consumedIds = (trackingGroups + exactGroups).flatMapTo(HashSet()) { it.mangaIds }
        val fuzzyGroups = buildFuzzyGroups(
            contents = contents.filterNot { it.id in consumedIds },
            entityIdsByMangaId = entityIdsByMangaId,
        )

        return (trackingGroups + exactGroups + fuzzyGroups).sortedWith(
            compareByDescending<MergeCandidateGroup> { it.id.contains(":tracking:") }
                .thenByDescending { it.isExactMatch }
                .thenByDescending { it.matchScore }
                .thenByDescending { it.mangaIds.size }
                .thenBy { it.title.lowercase() },
        )
    }

    private suspend fun buildTrackingBindingGroups(
        contents: List<Content>,
        entityIdsByMangaId: Map<Long, Long>,
    ): List<MergeCandidateGroup> {
        if (contents.size < 2) return emptyList()
        val contentById = contents.associateBy { it.id }
        val linksByTrackingKey = LinkedHashMap<Pair<Int, Long>, MutableList<Content>>()
        contents.forEach { content ->
            database.getTrackingSiteDao()
                .findLinksByManga(content.id)
                .forEach { link ->
                    linksByTrackingKey.getOrPut(link.service to link.remoteId) { mutableListOf() } += content
                }
        }
        return linksByTrackingKey.entries.mapNotNull { (trackingKey, groupedContents) ->
            val distinctContents = groupedContents.distinctBy { it.id }
            if (distinctContents.size < 2) {
                return@mapNotNull null
            }
            val service = ScrobblerService.entries.firstOrNull { it.id == trackingKey.first } ?: return@mapNotNull null
            val mangaIds = distinctContents.mapTo(LinkedHashSet(distinctContents.size)) { it.id }
            val mergeState = resolveMergeState(mangaIds, entityIdsByMangaId)
            val primary = distinctContents.first()
            MergeCandidateGroup(
                id = "${primary.source.contentType.name}:tracking:${service.id}:${trackingKey.second}",
                title = primary.title,
                normalizedTitle = normalizeTitle(primary.title),
                contentType = primary.source.contentType,
                mangaIds = mangaIds,
                items = distinctContents.map { content ->
                    MergeCandidateItem(
                        mangaId = content.id,
                        title = content.title,
                        normalizedTitle = normalizeTitle(content.title),
                        sourceName = content.source.name,
                        coverUrl = content.coverUrl,
                        score = 1f,
                    )
                },
                matchScore = 1f,
                isExactMatch = true,
                resolvedEntityId = mergeState.entityId,
                isAlreadyMerged = mergeState.isAlreadyMerged,
            )
        }
    }

    suspend fun merge(groups: List<MergeCandidateGroup>): MergeEntitiesResult {
        var succeeded = 0
        var failed = 0
        var skipped = 0
        for (group in groups) {
            val merged = runCatching {
                mergeOne(group)
            }.getOrDefault(false)
            when {
                merged -> succeeded++
                group.mangaIds.size < 2 -> skipped++
                else -> failed++
            }
        }
        return MergeEntitiesResult(
            succeeded = succeeded,
            failed = failed,
            skipped = skipped,
        )
    }

    private suspend fun mergeOne(group: MergeCandidateGroup): Boolean {
        val contents = group.items.map { item ->
            database.getMangaDao().find(item.mangaId)?.toContent()
        }
            .filterNotNull()
        if (contents.size < 2) {
            return false
        }
        val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(contents.map { it.id })
        val entityIds = entityIdsByMangaId.values.distinct()
        val trackingLinksByMangaId = group.items.associate { item ->
            item.mangaId to database.getTrackingSiteDao().findLinksByManga(item.mangaId)
        }
        Log.d(
            TAG,
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                buildMergeAuditLog(
                    group = group,
                    entityIdsByMangaId = entityIdsByMangaId,
                    trackingLinksByMangaId = trackingLinksByMangaId,
                )
            } else {
                "merge audit: group=${group.title}, items=${group.items.size}"
            },
        )
        val shouldAvoidLocalProjectionMerge = contents.all { content ->
            entityIdsByMangaId[content.id] != null || trackingLinksByMangaId[content.id].orEmpty().isNotEmpty()
        }
        val mergedEntityId = when {
            entityIds.size >= 2 -> {
                val targetEntityId = selectTargetEntityId(entityIds)
                entityGraphRepository.mergeEntities(
                    targetEntityId = targetEntityId,
                    sourceEntityIds = entityIds.filterNot { it == targetEntityId },
                )
            }

            shouldAvoidLocalProjectionMerge && entityIds.size == 1 -> entityIds.first()
            else -> entityGraphRepository.mergeLocalWorkEntities(contents)
        } ?: return false
        selectPreferredTrackingSelection(group, trackingLinksByMangaId)?.let { selection ->
            val localMangaIds = entityGraphRepository.getBindings(mergedEntityId)
                .asSequence()
                .filter { it.source == "local_manga" || it.source == "0" }
                .mapNotNull { it.externalId.toLongOrNull() }
                .distinct()
                .toList()
            contentDataRepository.setEntityMetadataSourceSelection(
                entityId = mergedEntityId,
                selection = selection,
                mirrorLocalMangaIds = localMangaIds,
            )
        }
        return true
    }

    private fun buildMergeAuditLog(
        group: MergeCandidateGroup,
        entityIdsByMangaId: Map<Long, Long>,
        trackingLinksByMangaId: Map<Long, List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>>,
    ): String {
        val serviceById = ScrobblerService.entries.associateBy { it.id }
        return buildString {
            append("merge audit: group=")
            append(group.title)
            append(", groupId=")
            append(group.id)
            append(", items=")
            append(group.items.size)
            group.items.forEach { item ->
                append(" | mangaId=")
                append(item.mangaId)
                append(", source=")
                append(item.sourceName)
                append(", mappedEntity=")
                append(entityIdsByMangaId[item.mangaId])
                append(", trackingLinks=")
                val links = trackingLinksByMangaId[item.mangaId].orEmpty()
                if (links.isEmpty()) {
                    append("[]")
                } else {
                    append(
                        links.joinToString(
                            prefix = "[",
                            postfix = "]",
                        ) { link ->
                            val serviceName = serviceById[link.service]?.name ?: link.service.toString()
                            "$serviceName:${link.remoteId}@${link.sourceName ?: "?"}"
                        },
                    )
                }
            }
        }
    }

    private suspend fun selectTargetEntityId(entityIds: List<Long>): Long {
        val entities = entityGraphRepository.getEntitiesByIds(entityIds)
        return entities.maxWithOrNull(
            compareBy<org.skepsun.kototoro.entitygraph.domain.Entity> { it.accessCount }
                .thenBy { it.lastAccessed }
                .thenByDescending { it.id },
        )?.id ?: entityIds.first()
    }

    private fun selectPreferredTrackingSelection(
        group: MergeCandidateGroup,
        trackingLinksByMangaId: Map<Long, List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>>,
    ): ContentDataRepository.MetadataSourceSelection.Tracking? {
        val serviceCounts = LinkedHashMap<Int, Int>()
        val remoteCounts = LinkedHashMap<Pair<Int, Long>, Int>()
        group.items.forEach { item ->
            trackingLinksByMangaId[item.mangaId].orEmpty().forEach { link ->
                serviceCounts[link.service] = (serviceCounts[link.service] ?: 0) + 1
                val remoteKey = link.service to link.remoteId
                remoteCounts[remoteKey] = (remoteCounts[remoteKey] ?: 0) + 1
            }
        }
        if (serviceCounts.isEmpty()) {
            return null
        }
        val targetServiceId = serviceCounts.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }
                    .thenBy { it.key },
            )
            .first()
            .key
        val targetRemote = remoteCounts.entries
            .asSequence()
            .filter { it.key.first == targetServiceId }
            .sortedWith(
                compareByDescending<Map.Entry<Pair<Int, Long>, Int>> { it.value }
                    .thenBy { it.key.second },
            )
            .firstOrNull()
            ?.key
            ?: return null
        val service = ScrobblerService.entries.firstOrNull { it.id == targetRemote.first } ?: return null
        return ContentDataRepository.MetadataSourceSelection.Tracking(
            serviceId = service.id,
            remoteId = targetRemote.second,
        )
    }

    private fun normalizeTitle(value: String): String = normalizeEntityName(value)

    private fun buildFuzzyGroups(
        contents: List<Content>,
        entityIdsByMangaId: Map<Long, Long>,
    ): List<MergeCandidateGroup> {
        if (contents.size < 2) return emptyList()
        val remaining = contents
            .map { CandidateSeed(content = it, normalizedTitle = normalizeTitle(it.title)) }
            .filter { it.normalizedTitle.isNotBlank() }
            .toMutableList()
        val result = mutableListOf<MergeCandidateGroup>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val matches = mutableListOf(seed)
            val iterator = remaining.iterator()
            var minScore = 1f
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.content.source.contentType != seed.content.source.contentType) {
                    continue
                }
                val score = similarity(seed.normalizedTitle, candidate.normalizedTitle)
                if (score >= FUZZY_MATCH_THRESHOLD) {
                    matches += candidate
                    minScore = minOf(minScore, score)
                    iterator.remove()
                }
            }
            if (matches.size >= 2) {
                val mangaIds = matches.mapTo(LinkedHashSet(matches.size)) { it.content.id }
                val mergeState = resolveMergeState(mangaIds, entityIdsByMangaId)
                result += MergeCandidateGroup(
                    id = "${seed.content.source.contentType.name}:fuzzy:${matches.joinToString("-") { it.content.id.toString() }}",
                    title = seed.content.title,
                    normalizedTitle = seed.normalizedTitle,
                    contentType = seed.content.source.contentType,
                    mangaIds = mangaIds,
                    items = matches.map {
                        MergeCandidateItem(
                            mangaId = it.content.id,
                            title = it.content.title,
                            normalizedTitle = it.normalizedTitle,
                            sourceName = it.content.source.name,
                            coverUrl = it.content.coverUrl,
                            score = if (it == seed) 1f else similarity(seed.normalizedTitle, it.normalizedTitle),
                        )
                    },
                    matchScore = minScore.coerceAtLeast(FUZZY_MATCH_THRESHOLD),
                    isExactMatch = false,
                    resolvedEntityId = mergeState.entityId,
                    isAlreadyMerged = mergeState.isAlreadyMerged,
                )
            }
        }
        return result
    }

    private fun resolveMergeState(
        mangaIds: Set<Long>,
        entityIdsByMangaId: Map<Long, Long>,
    ): MergeResolution {
        if (mangaIds.isEmpty()) {
            return MergeResolution(entityId = null, isAlreadyMerged = false)
        }
        val boundIds = mangaIds.mapNotNull { entityIdsByMangaId[it] }
        val entityIds = boundIds.toCollection(LinkedHashSet())
        return if (boundIds.size == mangaIds.size && entityIds.size == 1) {
            MergeResolution(entityId = entityIds.first(), isAlreadyMerged = true)
        } else {
            MergeResolution(entityId = entityIds.singleOrNull(), isAlreadyMerged = false)
        }
    }

    private fun similarity(left: String, right: String): Float {
        if (left == right) return 1f
        val maxLength = maxOf(left.length, right.length).coerceAtLeast(1)
        val distance = levenshtein(left, right)
        return (1f - distance.toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[right.length]
    }

    private data class MergeGroupKey(
        val normalizedTitle: String,
        val contentType: ContentType,
    )

    private data class CandidateSeed(
        val content: Content,
        val normalizedTitle: String,
    )

    private data class MergeResolution(
        val entityId: Long?,
        val isAlreadyMerged: Boolean,
    )

    private companion object {
        const val FUZZY_MATCH_THRESHOLD = 0.82f
    }
}
