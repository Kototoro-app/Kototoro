package org.skepsun.kototoro.favourites.domain

import android.util.Log
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.isLocalReadingSource
import org.skepsun.kototoro.entitygraph.domain.normalizeStrictTitleKey
import org.skepsun.kototoro.entitygraph.domain.stripEntityDisambiguationTitleSuffix
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

private const val TAG = "MergeFavoriteEntities"
const val DEFAULT_FUZZY_MERGE_THRESHOLD = 0.9f

data class MergeCandidateOptions(
    val fuzzyEnabled: Boolean = false,
    val fuzzyThreshold: Float = DEFAULT_FUZZY_MERGE_THRESHOLD,
)

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

    suspend fun buildCandidateGroups(
        contents: List<Content>,
        options: MergeCandidateOptions = MergeCandidateOptions(),
    ): List<MergeCandidateGroup> {
        val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(contents.map { it.id })
        val localReadingBindingsByMangaId = entityGraphRepository.findLocalReadingBindingsByMangaIds(contents.map { it.id })
        val localEntityIdsByMangaId = localReadingBindingsByMangaId.mapValues { (_, binding) -> binding.entityId }
        val protectedLocalMangaIds = localReadingBindingsByMangaId
            .filterValues { binding -> binding.isUserProtectedLocalBinding() }
            .keys
        val trackingGroups = buildTrackingBindingGroups(
            contents = contents,
            entityIdsByMangaId = entityIdsByMangaId,
            localEntityIdsByMangaId = localEntityIdsByMangaId,
        )
        val exactGroups = contents
            .filterNot { content -> trackingGroups.any { content.id in it.mangaIds } }
            .groupBy { MergeGroupKey(normalizeTitle(it), it.source.contentType) }
            .mapNotNull { (key, items) ->
                val mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id }
                if (key.normalizedTitle.isBlank() || mangaIds.size < 2) {
                    null
                } else {
                    val mergeState = resolveMergeState(mangaIds, localEntityIdsByMangaId)
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

        val higherPriorityIds = (trackingGroups + exactGroups).flatMapTo(HashSet()) { it.mangaIds }
        val aliasGroups = buildAliasGroups(
            contents = contents,
            entityIdsByMangaId = entityIdsByMangaId,
            localEntityIdsByMangaId = localEntityIdsByMangaId,
            protectedLocalMangaIds = protectedLocalMangaIds,
            higherPriorityIds = higherPriorityIds,
        )
        val fuzzyGroups = buildFuzzyGroups(
            contents = contents,
            localEntityIdsByMangaId = localEntityIdsByMangaId,
            protectedLocalMangaIds = protectedLocalMangaIds,
            higherPriorityIds = higherPriorityIds + aliasGroups.flatMap { it.mangaIds },
            threshold = options.fuzzyThreshold,
            enabled = options.fuzzyEnabled,
        )

        return (trackingGroups + exactGroups + aliasGroups + fuzzyGroups).sortedWith(
            compareByDescending<MergeCandidateGroup> { it.id.contains(":tracking:") }
                .thenByDescending { it.id.contains(":alias:") }
                .thenByDescending { it.isExactMatch }
                .thenByDescending { it.matchScore }
                .thenByDescending { it.mangaIds.size }
                .thenBy { it.title.lowercase() },
        )
    }

    private suspend fun buildTrackingBindingGroups(
        contents: List<Content>,
        entityIdsByMangaId: Map<Long, Long>,
        localEntityIdsByMangaId: Map<Long, Long>,
    ): List<MergeCandidateGroup> {
        if (contents.size < 2) return emptyList()
        val linksByTrackingKey = LinkedHashMap<TrackingGroupKey, MutableList<Content>>()
        contents.forEach { content ->
            database.getTrackingSiteDao()
                .findLinksByManga(content.id)
                .forEach { link ->
                    if (isSuspectTrackingLink(content, link)) {
                        return@forEach
                    }
                    val key = TrackingGroupKey(
                        serviceId = link.service,
                        remoteId = link.remoteId,
                        contentType = content.source.contentType,
                    )
                    linksByTrackingKey.getOrPut(key) { mutableListOf() } += content
                }
        }
        return linksByTrackingKey.entries.mapNotNull { (trackingKey, groupedContents) ->
            val distinctContents = groupedContents.distinctBy { it.id }
            if (distinctContents.size < 2) {
                return@mapNotNull null
            }
            val service = ScrobblerService.entries.firstOrNull { it.id == trackingKey.serviceId } ?: return@mapNotNull null
            val mangaIds = distinctContents.mapTo(LinkedHashSet(distinctContents.size)) { it.id }
            val mergeState = resolveMergeState(mangaIds, localEntityIdsByMangaId)
            val primary = distinctContents.first()
            val trackingTitleKey = trackingTitleKey(trackingKey).ifBlank { normalizeTitle(primary) }
            MergeCandidateGroup(
                id = "${trackingKey.contentType.name}:tracking:${service.id}:${trackingKey.remoteId}",
                title = primary.title,
                normalizedTitle = trackingTitleKey,
                contentType = primary.source.contentType,
                mangaIds = mangaIds,
                items = distinctContents.map { content ->
                    MergeCandidateItem(
                        mangaId = content.id,
                        title = content.title,
                        normalizedTitle = normalizeTitle(content),
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

    private suspend fun trackingTitleKey(key: TrackingGroupKey): String {
        val trackingItem = database.getTrackingSiteDao().findItem(key.serviceId, key.remoteId) ?: return ""
        return buildList {
            add(trackingItem.title)
            addAll(decodeStringList(trackingItem.altTitles))
            trackingItem.primaryTitle?.let(::add)
            trackingItem.secondaryTitle?.let(::add)
        }
            .map(::normalizeTitle)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private suspend fun isSuspectTrackingLink(
        content: Content,
        link: TrackingSiteLinkEntity,
    ): Boolean {
        val trackingItem = database.getTrackingSiteDao().findItem(link.service, link.remoteId) ?: return false
        val trackingNames = buildList {
            add(trackingItem.title)
            addAll(decodeStringList(trackingItem.altTitles))
            trackingItem.primaryTitle?.let(::add)
            trackingItem.secondaryTitle?.let(::add)
        }
        return trackingNames.any { it.isNotBlank() } &&
            trackingNames.none { trackingName -> isCompatibleTrackingTitle(content, trackingName) }
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

    suspend fun mergeManual(contents: List<Content>): MergeEntitiesResult {
        val distinctContents = contents.distinctBy { it.id }
        val contentType = distinctContents.firstOrNull()?.source?.contentType
        if (
            distinctContents.size < 2 ||
            contentType == null ||
            distinctContents.any { it.source.contentType != contentType }
        ) {
            return MergeEntitiesResult(succeeded = 0, failed = 0, skipped = 1)
        }
        val mangaIds = distinctContents.mapTo(LinkedHashSet(distinctContents.size)) { it.id }
        val group = MergeCandidateGroup(
            id = "${contentType.name}:manual:${mangaIds.sorted().joinToString("-")}",
            title = distinctContents.first().title,
            normalizedTitle = normalizeTitle(distinctContents.first()),
            contentType = contentType,
            mangaIds = mangaIds,
            items = distinctContents.map { content ->
                MergeCandidateItem(
                    mangaId = content.id,
                    title = content.title,
                    normalizedTitle = normalizeTitle(content),
                    sourceName = content.source.name,
                    coverUrl = content.coverUrl,
                    score = 1f,
                )
            },
            matchScore = 1f,
            isExactMatch = false,
        )
        return if (mergeOne(group)) {
            MergeEntitiesResult(succeeded = 1, failed = 0, skipped = 0)
        } else {
            MergeEntitiesResult(succeeded = 0, failed = 1, skipped = 0)
        }
    }

    private suspend fun mergeOne(group: MergeCandidateGroup): Boolean {
        val contents = group.items.map { item ->
            database.getMangaDao().find(item.mangaId)?.toContent()
        }
            .filterNotNull()
        if (contents.size < 2) {
            return false
        }
        val protectedBindings = entityGraphRepository.findLocalReadingBindingsByMangaIds(contents.map { it.id })
            .filterValues { binding -> binding.isUserProtectedLocalBinding() }
        val trackingLinksByMangaId = group.items.associate { item ->
            item.mangaId to database.getTrackingSiteDao().findLinksByManga(item.mangaId)
        }
        if (
            protectedBindings.isNotEmpty() &&
            !group.id.contains(":manual:") &&
            !isSafeTrackingMergeGroup(group, contents, trackingLinksByMangaId) &&
            !isSafeExactTitleMergeGroup(group, contents)
        ) {
            Log.i(
                TAG,
                "merge skipped: group=${group.id}, protectedLocalIds=${protectedBindings.keys.joinToString()}",
            )
            return false
        }
        val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(contents.map { it.id })
        val entityIds = entityIdsByMangaId.values.distinct()
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
        val mergedEntityId = when {
            entityIds.size >= 2 -> {
                val targetEntityId = selectTargetEntityId(entityIds)
                val mergedId = entityGraphRepository.mergeEntities(
                    targetEntityId = targetEntityId,
                    sourceEntityIds = entityIds.filterNot { it == targetEntityId },
                )
                if (mergedId != null && entityGraphRepository.attachLocalWorksToEntity(mergedId, contents)) {
                    mergedId
                } else {
                    null
                }
            }

            entityIds.size == 1 -> {
                val targetEntityId = entityIds.first()
                if (entityGraphRepository.attachLocalWorksToEntity(targetEntityId, contents)) {
                    targetEntityId
                } else {
                    null
                }
            }

            else -> entityGraphRepository.mergeLocalWorkEntities(contents)
        } ?: return false
        selectPreferredTrackingSelection(group, trackingLinksByMangaId)?.let { selection ->
            val localMangaIds = entityGraphRepository.getBindings(mergedEntityId)
                .asSequence()
                .filter { it.isLocalReadingSource() }
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
        selectGroupTrackingSelection(group, trackingLinksByMangaId)?.let { selection ->
            return selection
        }
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

    private fun selectGroupTrackingSelection(
        group: MergeCandidateGroup,
        trackingLinksByMangaId: Map<Long, List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>>,
    ): ContentDataRepository.MetadataSourceSelection.Tracking? {
        if (!group.id.contains(":tracking:")) {
            return null
        }
        val keys = group.items.mapNotNull { item ->
            trackingLinksByMangaId[item.mangaId].orEmpty()
                .firstOrNull { link -> group.id == "${group.contentType.name}:tracking:${link.service}:${link.remoteId}" }
                ?.let { link -> link.service to link.remoteId }
        }
        val key = keys.distinct().singleOrNull() ?: return null
        if (keys.size != group.items.size) {
            return null
        }
        val service = ScrobblerService.entries.firstOrNull { it.id == key.first } ?: return null
        return ContentDataRepository.MetadataSourceSelection.Tracking(
            serviceId = service.id,
            remoteId = key.second,
        )
    }

    private suspend fun isSafeTrackingMergeGroup(
        group: MergeCandidateGroup,
        contents: List<Content>,
        trackingLinksByMangaId: Map<Long, List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>>,
    ): Boolean {
        if (!group.id.contains(":tracking:")) {
            return false
        }
        val contentsById = contents.associateBy { it.id }
        var expectedKey: TrackingGroupKey? = null
        group.items.forEach { item ->
            val content = contentsById[item.mangaId] ?: return false
            val key = trackingLinksByMangaId[item.mangaId].orEmpty()
                .firstOrNull { link ->
                    group.id == "${group.contentType.name}:tracking:${link.service}:${link.remoteId}" &&
                        !isSuspectTrackingLink(content, link)
                }
                ?.let { link -> TrackingGroupKey(link.service, link.remoteId, group.contentType) }
                ?: return false
            val previous = expectedKey
            if (previous != null && previous != key) {
                return false
            }
            expectedKey = key
        }
        return expectedKey != null
    }

    private fun isSafeExactTitleMergeGroup(
        group: MergeCandidateGroup,
        contents: List<Content>,
    ): Boolean {
        if (!group.isExactMatch || group.id.contains(":tracking:") || group.id.contains(":alias:")) {
            return false
        }
        if (contents.size < 2 || contents.any { it.source.contentType != group.contentType }) {
            return false
        }
        val titleKeys = contents.mapTo(LinkedHashSet(contents.size)) { normalizeTitle(it) }
        if (titleKeys.size != 1 || titleKeys.first().isBlank() || titleKeys.first() != group.normalizedTitle) {
            return false
        }
        return true
    }

    private fun buildFuzzyGroups(
        contents: List<Content>,
        localEntityIdsByMangaId: Map<Long, Long>,
        protectedLocalMangaIds: Set<Long>,
        higherPriorityIds: Set<Long>,
        threshold: Float,
        enabled: Boolean,
    ): List<MergeCandidateGroup> {
        if (!enabled || contents.size < 2) {
            return emptyList()
        }
        val boundedThreshold = threshold.coerceIn(0.8f, 1f)
        return contents
            .asSequence()
            .filterNot { it.id in protectedLocalMangaIds }
            .filterNot { it.id in higherPriorityIds }
            .groupBy { it.source.contentType }
            .flatMap { (contentType, typedContents) ->
                buildFuzzyGroupsForType(
                    contentType = contentType,
                    contents = typedContents,
                    localEntityIdsByMangaId = localEntityIdsByMangaId,
                    threshold = boundedThreshold,
                )
            }
            .distinctBy { group -> group.mangaIds.sorted().joinToString("-") }
    }

    private fun buildFuzzyGroupsForType(
        contentType: ContentType,
        contents: List<Content>,
        localEntityIdsByMangaId: Map<Long, Long>,
        threshold: Float,
    ): List<MergeCandidateGroup> {
        if (contents.size < 2) {
            return emptyList()
        }
        val keysById = contents.associate { content -> content.id to normalizeTitle(content) }
        val candidates = ArrayList<Set<Long>>()
        for (leftIndex in 0 until contents.lastIndex) {
            val left = contents[leftIndex]
            val leftKey = keysById[left.id].orEmpty()
            if (leftKey.isBlank()) continue
            for (rightIndex in leftIndex + 1 until contents.size) {
                val right = contents[rightIndex]
                val rightKey = keysById[right.id].orEmpty()
                if (rightKey.isBlank()) continue
                val score = titleSimilarity(leftKey, rightKey)
                if (score >= threshold) {
                    candidates += linkedSetOf(left.id, right.id)
                }
            }
        }
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val contentsById = contents.associateBy { it.id }
        val groups = ArrayList<Set<Long>>()
        candidates
            .sortedWith(compareBy<Set<Long>> { it.first() }.thenBy { it.last() })
            .forEach { pair ->
                val mergedIndex = groups.indexOfFirst { existing ->
                    val merged = existing + pair
                    merged.allPairsMeetThreshold(keysById, threshold)
                }
                if (mergedIndex >= 0) {
                    groups[mergedIndex] = groups[mergedIndex] + pair
                } else {
                    groups += pair
                }
            }
        return groups.map { groupIds ->
            val items = groupIds.mapNotNull(contentsById::get)
            val groupScore = minPairSimilarity(items.mapNotNull { keysById[it.id] })
            val mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id }
            val mergeState = resolveMergeState(mangaIds, localEntityIdsByMangaId)
            val title = items.maxByOrNull { keysById[it.id].orEmpty().length }?.title ?: items.first().title
            MergeCandidateGroup(
                id = "${contentType.name}:fuzzy:${(threshold * 100).toInt()}:${mangaIds.sorted().joinToString("-")}",
                title = title,
                normalizedTitle = normalizeTitle(title),
                contentType = contentType,
                mangaIds = mangaIds,
                items = items.map { content ->
                    val key = keysById[content.id].orEmpty()
                    MergeCandidateItem(
                        mangaId = content.id,
                        title = content.title,
                        normalizedTitle = key,
                        sourceName = content.source.name,
                        coverUrl = content.coverUrl,
                        score = maxPairSimilarity(key, keysById, mangaIds),
                    )
                },
                matchScore = groupScore,
                isExactMatch = false,
                resolvedEntityId = mergeState.entityId,
                isAlreadyMerged = mergeState.isAlreadyMerged,
            )
        }
    }

    private fun titleSimilarity(left: String, right: String): Float {
        if (left == right) return 1f
        val maxLength = maxOf(left.length, right.length)
        if (maxLength == 0) return 0f
        return (1f - left.levenshteinDistance(right).toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
    }

    private fun minPairSimilarity(keys: List<String>): Float {
        if (keys.size < 2) return 1f
        var min = 1f
        for (leftIndex in 0 until keys.lastIndex) {
            for (rightIndex in leftIndex + 1 until keys.size) {
                min = minOf(min, titleSimilarity(keys[leftIndex], keys[rightIndex]))
            }
        }
        return min
    }

    private fun maxPairSimilarity(
        key: String,
        keysById: Map<Long, String>,
        mangaIds: Set<Long>,
    ): Float {
        return mangaIds
            .asSequence()
            .mapNotNull(keysById::get)
            .filterNot { it == key }
            .maxOfOrNull { titleSimilarity(key, it) }
            ?: 1f
    }

    private fun Set<Long>.allPairsMeetThreshold(
        keysById: Map<Long, String>,
        threshold: Float,
    ): Boolean {
        val ids = toList()
        for (leftIndex in 0 until ids.lastIndex) {
            val left = keysById[ids[leftIndex]].orEmpty()
            for (rightIndex in leftIndex + 1 until ids.size) {
                val right = keysById[ids[rightIndex]].orEmpty()
                if (left.isBlank() || right.isBlank() || titleSimilarity(left, right) < threshold) {
                    return false
                }
            }
        }
        return true
    }

    private fun normalizeTitle(value: String): String = normalizeStrictTitleKey(value)

    private fun normalizeTitle(value: String, sourceNames: Iterable<String>): String = normalizeStrictTitleKey(
        stripEntityDisambiguationTitleSuffix(value, sourceNames),
    )

    private fun normalizeTitle(content: Content): String = normalizeStrictTitleKey(
        stripEntityDisambiguationTitleSuffix(content.title, listOf(content.source.name)),
    )

    private fun isCompatibleTrackingTitle(content: Content, trackingTitle: String): Boolean {
        val localKey = normalizeTitle(content)
        val trackingKey = normalizeTitle(trackingTitle)
        return localKey.isNotBlank() && localKey == trackingKey
    }

    private suspend fun buildAliasGroups(
        contents: List<Content>,
        entityIdsByMangaId: Map<Long, Long>,
        localEntityIdsByMangaId: Map<Long, Long>,
        protectedLocalMangaIds: Set<Long>,
        higherPriorityIds: Set<Long>,
    ): List<MergeCandidateGroup> {
        if (contents.size < 2) return emptyList()
        val boundEntityIds = entityIdsByMangaId.values.distinct()
        if (boundEntityIds.isEmpty()) {
            return emptyList()
        }
        val entitiesById = entityGraphRepository.getEntitiesByIds(boundEntityIds).associateBy { it.id }
        val contentsByEntityAndType = contents.groupBy { content ->
            EntityContentTypeKey(
                entityId = entityIdsByMangaId[content.id],
                contentType = content.source.contentType,
            )
        }
        return entitiesById.values.flatMap { entity ->
            val sourceNames = contents.mapTo(LinkedHashSet(contents.size)) { it.source.name }
            val aliasKeys = entity.strictNameKeys(sourceNames)
            if (aliasKeys.isEmpty()) {
                return@flatMap emptyList()
            }
            ContentType.entries.mapNotNull { contentType ->
                val entityContents = contentsByEntityAndType[
                    EntityContentTypeKey(entityId = entity.id, contentType = contentType),
                ].orEmpty()
                    .filterNot { content -> content.id in protectedLocalMangaIds }
                    .filter { content -> normalizeTitle(content) in aliasKeys }
                if (entityContents.isEmpty()) {
                    return@mapNotNull null
                }
                val matchedContents = contents.filter { content ->
                    entityIdsByMangaId[content.id] != entity.id &&
                        content.source.contentType == contentType &&
                        content.id !in protectedLocalMangaIds &&
                        normalizeTitle(content) in aliasKeys
                }
                if (matchedContents.isEmpty()) {
                    return@mapNotNull null
                }
                val items = (entityContents + matchedContents).distinctBy { it.id }
                val sourceNames = items.mapTo(LinkedHashSet(items.size)) { it.source.name }
                if (
                    items.size < 2 ||
                    sourceNames.size != items.size ||
                    items.all { it.id in higherPriorityIds }
                ) {
                    return@mapNotNull null
                }
                val mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id }
                val mergeState = resolveMergeState(mangaIds, localEntityIdsByMangaId)
                MergeCandidateGroup(
                    id = "${contentType.name}:alias:${entity.id}:${mangaIds.joinToString("-")}",
                    title = entity.primaryName,
                    normalizedTitle = normalizeTitle(entity.primaryName, sourceNames),
                    contentType = contentType,
                    mangaIds = mangaIds,
                    items = items.map { content ->
                        val normalizedTitle = normalizeTitle(content)
                        MergeCandidateItem(
                            mangaId = content.id,
                            title = content.title,
                            normalizedTitle = normalizedTitle,
                            sourceName = content.source.name,
                            coverUrl = content.coverUrl,
                            score = if (normalizedTitle in aliasKeys) 1f else 0f,
                        )
                    },
                    matchScore = 1f,
                    isExactMatch = true,
                    resolvedEntityId = mergeState.entityId,
                    isAlreadyMerged = mergeState.isAlreadyMerged,
                )
            }
        }.distinctBy { group ->
            "${group.contentType.name}:${group.mangaIds.sorted().joinToString("-")}"
        }
    }

    private fun Entity.strictNameKeys(sourceNames: Iterable<String>): Set<String> {
        return (listOf(primaryName) + aliases)
            .mapTo(LinkedHashSet()) { normalizeTitle(it, sourceNames) }
            .filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    private fun EntityBinding.isUserProtectedLocalBinding(): Boolean {
        return isLocalReadingSource() &&
            (
                stateName == EntityBindingState.MANUAL.name ||
                    createdBy == EntityBindingCreatedBy.USER.name
                )
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

    private data class MergeGroupKey(
        val normalizedTitle: String,
        val contentType: ContentType,
    )

    private data class TrackingGroupKey(
        val serviceId: Int,
        val remoteId: Long,
        val contentType: ContentType,
    )

    private data class EntityContentTypeKey(
        val entityId: Long?,
        val contentType: ContentType,
    )

    private data class MergeResolution(
        val entityId: Long?,
        val isAlreadyMerged: Boolean,
    )
}
