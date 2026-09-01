package org.skepsun.kototoro.favourites.domain.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.favourites.data.FavouriteCardBaseRow
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deep module owning the favourites read model
 * (favourites-komikku-alignment plan, section 4).
 *
 * Interface contract:
 * - [observe] takes no parameters: category / quick filter / sort / space / list mode
 *   are all derived in memory from the emitted snapshot;
 * - every emission is a complete, self-consistent snapshot (see
 *   [FavouriteLibrarySnapshot] invariants) built off the DAO on a background
 *   dispatcher;
 * - upstream changes that do not alter the snapshot do not re-emit
 *   ([distinctUntilChanged] on the assembled value);
 * - broken rows (dangling preferred/anchor, missing bindings) survive as rows instead
 *   of failing the flow;
 * - nothing here writes (the read path never touches `entity_preferences`) and no
 *   network is performed;
 * - Room entities, `WorkAggregate` and the entity graph stay inside.
 */
@Singleton
class FavouriteLibrarySnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    private val sourceGroupManager: SourceGroupManager,
) {

    fun observe(): Flow<FavouriteLibrarySnapshot> {
        val dao = database.getFavouriteLibraryReadDao()
        return combine(
            dao.observeFavouriteCardBaseRows().distinctUntilChanged(),
            dao.observeFavouriteMembershipRows().distinctUntilChanged(),
            dao.observeFavouriteProjectionFacets().distinctUntilChanged(),
            dao.observeFavouriteTagFacets().distinctUntilChanged(),
            dao.observeDownloadedFavouriteRows().distinctUntilChanged(),
            dao.observeFavouriteLegacyOverrides().distinctUntilChanged(),
        ) { values: Array<*> ->
            @Suppress("UNCHECKED_CAST")
            buildSnapshot(
                baseRows = values[0] as List<FavouriteCardBaseRow>,
                membershipRows = values[1] as List<org.skepsun.kototoro.favourites.data.FavouriteMembershipRow>,
                projectionFacets = values[2] as List<org.skepsun.kototoro.favourites.data.FavouriteProjectionFacetRow>,
                tagFacets = values[3] as List<org.skepsun.kototoro.favourites.data.FavouriteTagFacetRow>,
                downloadedRows = values[4] as List<org.skepsun.kototoro.favourites.data.FavouriteDownloadedRow>,
                legacyOverrides = values[5] as List<org.skepsun.kototoro.favourites.data.FavouriteLegacyOverrideRow>,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    internal fun buildSnapshot(
        baseRows: List<FavouriteCardBaseRow>,
        membershipRows: List<org.skepsun.kototoro.favourites.data.FavouriteMembershipRow>,
        projectionFacets: List<org.skepsun.kototoro.favourites.data.FavouriteProjectionFacetRow>,
        tagFacets: List<org.skepsun.kototoro.favourites.data.FavouriteTagFacetRow>,
        downloadedRows: List<org.skepsun.kototoro.favourites.data.FavouriteDownloadedRow>,
        legacyOverrides: List<org.skepsun.kototoro.favourites.data.FavouriteLegacyOverrideRow>,
    ): FavouriteLibrarySnapshot {
        if (baseRows.isEmpty()) {
            return FavouriteLibrarySnapshot.Empty
        }

        // ---- projection facets: binding-based projection set per entity
        val projectionIdsByEntity = HashMap<Long, LinkedHashSet<Long>>(baseRows.size)
        val projectionSourcesByEntity = HashMap<Long, LinkedHashSet<String>>(baseRows.size)
        val projectionSourcePresence = HashMap<String, MutableSet<Long>>(64)
        for (facet in projectionFacets) {
            projectionIdsByEntity.getOrPut(facet.entityId) { LinkedHashSet() }.add(facet.mangaId)
            projectionSourcesByEntity.getOrPut(facet.entityId) { LinkedHashSet() }.add(facet.source)
            projectionSourcePresence.getOrPut(facet.source) { LinkedHashSet() }.add(facet.entityId)
        }

        // ---- tag facets: identity set + limited display list per entity
        val tagIdsByEntity = HashMap<Long, LinkedHashSet<Long>>(baseRows.size)
        val displayTagsByEntity = HashMap<Long, LinkedHashMap<Long, String>>(baseRows.size)
        val tagEntityPresence = HashMap<Long, MutableSet<Long>>(256)
        val facetTagsById = HashMap<Long, FavouriteFacetTag>(256)
        for (facet in tagFacets) {
            tagIdsByEntity.getOrPut(facet.entityId) { LinkedHashSet() }.add(facet.tagId)
            displayTagsByEntity.getOrPut(facet.entityId) { LinkedHashMap() }.putIfAbsent(facet.tagId, facet.tagTitle)
            facetTagsById.putIfAbsent(
                facet.tagId,
                FavouriteFacetTag(
                    tagId = facet.tagId,
                    title = facet.tagTitle,
                    key = facet.tagKey,
                    source = facet.tagSource,
                ),
            )
            tagEntityPresence.getOrPut(facet.tagId) { LinkedHashSet() }.add(facet.entityId)
        }

        // ---- downloaded entities
        val downloadedEntities = HashSet<Long>(downloadedRows.size)
        for (row in downloadedRows) {
            downloadedEntities.add(row.entityId)
        }

        // ---- legacy overrides keyed by manga id (fallback when entity prefs lack one)
        val legacyOverrideByMangaId = HashMap<Long, org.skepsun.kototoro.favourites.data.FavouriteLegacyOverrideRow>(
            legacyOverrides.size,
        )
        for (override in legacyOverrides) {
            legacyOverrideByMangaId[override.mangaId] = override
        }

        // ---- assemble rows
        val rowsByEntityId = HashMap<Long, FavouriteCardRow>(baseRows.size)
        var brokenCount = 0
        for (base in baseRows) {
            val projectionIds = projectionIdsByEntity[base.entityId].orEmpty()
            val legacyOverride = base.displayMangaId?.let(legacyOverrideByMangaId::get)
            val broken = base.displayMangaId == null || projectionIds.isEmpty()
            if (broken) brokenCount++
            rowsByEntityId[base.entityId] = FavouriteCardRow(
                entityId = base.entityId,
                displayMangaId = base.displayMangaId,
                localMangaIds = projectionIds,
                title = base.displayTitle.orEmpty(),
                altTitle = base.displayAltTitle,
                coverUrl = base.displayCoverUrl?.takeIf { it.isNotBlank() },
                author = base.displayAuthor,
                sourceName = base.displaySource.orEmpty(),
                sourceGroupFlags = sourceGroupFlags(base),
                sourceOriginFlags = sourceOriginFlags(base),
                contentType = base.displayContentType?.let(::parseContentType)
                    ?: base.entityContentType?.let(::parseContentType),
                publicationState = base.displayState?.let(::parseContentState),
                isNsfw = base.displayNsfw == true,
                rating = base.displayRating ?: -1f,
                readingStatus = base.readingStatus?.let(::parseReadingStatus)
                    ?: resolveReadingStatus(base.historyPercent),
                newChapters = base.trackingNewChapters ?: 0,
                lastChapterDate = base.trackingLastChapterDate ?: 0L,
                progressPercent = base.historyPercent,
                progressTotalChapters = base.historyChapters,
                lastReadAt = base.historyUpdatedAt,
                projectionCount = projectionIds.size,
                projectionSourceNames = projectionSourcesByEntity[base.entityId].orEmpty(),
                tagIds = tagIdsByEntity[base.entityId].orEmpty(),
                displayTags = displayTagsByEntity[base.entityId].orEmpty()
                    .map { (tagId, title) -> FavouriteCardTag(tagId, title) },
                isDownloaded = base.entityId in downloadedEntities,
                hasBrokenProjection = broken,
                overrideTitle = base.titleOverride?.takeIf { it.isNotBlank() }
                    ?: legacyOverride?.titleOverride?.takeIf { it.isNotBlank() },
                overrideCoverUrl = base.coverOverride?.takeIf { it.isNotBlank() }
                    ?: legacyOverride?.coverOverride?.takeIf { it.isNotBlank() },
                metadataTrackingService = base.metadataTrackingService,
                metadataTrackingTitle = base.metadataTrackingTitle?.takeIf { it.isNotBlank() },
                metadataTrackingCoverUrl = base.metadataTrackingCoverUrl?.takeIf { it.isNotBlank() },
                isPinned = base.representativePinned,
                createdAt = base.representativeCreatedAt,
                updatedAt = base.representativeUpdatedAt,
            )
        }

        // ---- memberships grouped per category, only for known rows
        val membershipsByCategory = HashMap<Long, ArrayList<FavouriteMembership>>()
        val knownEntities = rowsByEntityId.keys
        for (membership in membershipRows) {
            if (membership.entityId !in knownEntities) continue
            membershipsByCategory.getOrPut(membership.categoryId) { ArrayList() }.add(
                FavouriteMembership(
                    entityId = membership.entityId,
                    categoryId = membership.categoryId,
                    isPinned = membership.isPinned,
                    sortKey = membership.sortKey,
                    createdAt = membership.createdAt,
                    updatedAt = membership.updatedAt,
                ),
            )
        }

        // ---- quick filter metadata from facets (ordered by entity count desc, then title)
        val tags = tagEntityPresence.entries
            .mapNotNull { (tagId, entities) ->
                val tag = facetTagsById[tagId] ?: return@mapNotNull null
                tag to entities.size
            }
            .sortedWith(compareByDescending<Pair<FavouriteFacetTag, Int>> { it.second }.thenBy { it.first.title })
        val sources = projectionSourcePresence.entries
            .map { it.key to it.value.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        val snapshot = FavouriteLibrarySnapshot(
            rowsByEntityId = rowsByEntityId,
            allEntityIds = rowsByEntityId.keys.sorted(),
            membershipsByCategory = membershipsByCategory,
            quickFilterMetadata = FavouriteQuickFilterMetadata(
                tags = tags.map { it.first },
                tagEntityCounts = tags.associate { it.first.tagId to it.second },
                sources = sources.map { it.first },
                sourceEntityCounts = sources.associate { it.first to it.second },
            ),
        )
        if (brokenCount > 0) {
            Log.d(TAG, "snapshot rows=${rowsByEntityId.size} broken=$brokenCount")
        }
        return snapshot
    }

    private fun sourceGroupFlags(base: FavouriteCardBaseRow): Int {
        val group = sourceGroupManager.getContentGroupByName(base.displaySource.orEmpty(), base.displayNsfw == true)
        return groupFlag(group)
    }

    private fun sourceOriginFlags(base: FavouriteCardBaseRow): Int {
        val origin = sourceGroupManager.getOriginGroupByName(base.displaySource.orEmpty())
        return originFlag(origin)
    }

    private fun resolveReadingStatus(percent: Float?): ScrobblingStatus = when {
        percent != null && ReadingProgressIsCompleted(percent) -> ScrobblingStatus.COMPLETED
        percent != null -> ScrobblingStatus.READING
        else -> ScrobblingStatus.PLANNED
    }

    private companion object {
        const val TAG = "FavouriteLibrary"

        fun parseContentType(name: String): ContentType? = runCatching { ContentType.valueOf(name) }.getOrNull()

        fun parseContentState(name: String): ContentState? = runCatching { ContentState.valueOf(name) }.getOrNull()

        fun parseReadingStatus(name: String): ScrobblingStatus? =
            runCatching { ScrobblingStatus.valueOf(name) }.getOrNull()

        /** `ReadingProgress.isCompleted` without pulling the UI-layer class in. */
        fun ReadingProgressIsCompleted(percent: Float): Boolean = percent >= 0.999f

        /** Stable bit per [ContentGroup] so filters compare ints instead of enum sets. */
        fun groupFlag(group: ContentGroup): Int = 1 shl group.ordinal

        /** Stable bit per [OriginGroup]. */
        fun originFlag(origin: OriginGroup): Int = 1 shl origin.ordinal
    }
}

