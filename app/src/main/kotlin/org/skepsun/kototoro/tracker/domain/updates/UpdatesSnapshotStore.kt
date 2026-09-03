package org.skepsun.kototoro.tracker.domain.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.tracker.data.TrackedEntityCategoryFacetRow
import org.skepsun.kototoro.tracker.data.TrackedOverrideRow
import org.skepsun.kototoro.tracker.data.TrackedTagFacetRow
import org.skepsun.kototoro.tracker.data.UpdateTrackRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deep module owning the updates read model
 * (history-updates-feed komikku-alignment plan, Phase U2).
 *
 * Same contract as [org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshotStore]:
 * parameterless observe(), complete self-consistent emissions, no writes, no
 * network, no per-entity lookups. The whole list is grouped per entity here —
 * the legacy per-page aggregation (which could split an entity across a page
 * boundary and accumulated grouped-id maps in @Volatile fields) collapses into
 * this one assembly.
 */
@Singleton
class UpdatesSnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    private val sourceGroupManager: SourceGroupManager,
) {

    fun observe(): Flow<UpdatesSnapshot> {
        val dao = database.getTrackerReadDao()
        return combine(
            dao.observeUpdateTrackRows().distinctUntilChanged(),
            dao.observeTrackedTagFacets(includeFeedLogs = false).distinctUntilChanged(),
            dao.observeTrackedOverrides(includeFeedLogs = false).distinctUntilChanged(),
            dao.observeTrackedEntityCategoryFacets().distinctUntilChanged(),
        ) { values: Array<*> ->
            @Suppress("UNCHECKED_CAST")
            buildSnapshot(
                trackRows = values[0] as List<UpdateTrackRow>,
                tagFacets = values[1] as List<TrackedTagFacetRow>,
                overrides = values[2] as List<TrackedOverrideRow>,
                categoryFacets = values[3] as List<TrackedEntityCategoryFacetRow>,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    internal fun buildSnapshot(
        trackRows: List<UpdateTrackRow>,
        tagFacets: List<TrackedTagFacetRow>,
        overrides: List<TrackedOverrideRow>,
        categoryFacets: List<TrackedEntityCategoryFacetRow>,
    ): UpdatesSnapshot {
        if (trackRows.isEmpty()) {
            return UpdatesSnapshot.Empty
        }

        val tagsByMangaId = HashMap<Long, LinkedHashMap<Long, String>>(tagFacets.size)
        for (facet in tagFacets) {
            tagsByMangaId.getOrPut(facet.mangaId) { LinkedHashMap() }
                .putIfAbsent(facet.tagId, facet.tagTitle)
        }
        val overrideByMangaId = HashMap<Long, TrackedOverrideRow>(overrides.size)
        for (override in overrides) {
            overrideByMangaId[override.mangaId] = override
        }
        val categoryIdsByEntityId = HashMap<Long, LinkedHashSet<Long>>(categoryFacets.size)
        for (facet in categoryFacets) {
            categoryIdsByEntityId.getOrPut(facet.entityId) { LinkedHashSet() }.add(facet.categoryId)
        }

        // seeds in track order (the DAO's stable order is the tie-break source)
        val seeds = ArrayList<TrackRowSeed>(trackRows.size)
        for (track in trackRows) {
            val displayId = track.displayMangaId
            val displayContentType = track.displayContentType?.let(::parseContentType)
            seeds += TrackRowSeed(
                ownerId = track.ownerId,
                mangaId = track.mangaId,
                entityId = track.entityId,
                preferredLocalMangaId = track.preferredLocalMangaId,
                newChapters = track.newChapters,
                lastChapterDate = track.lastChapterDate,
                lastCheckTime = track.lastCheckTime,
                isPinned = track.entityPinned,
                displayMangaId = displayId,
                title = track.displayTitle.orEmpty(),
                altTitle = track.displayAltTitle,
                coverUrl = track.displayCoverUrl?.takeIf { it.isNotBlank() },
                author = track.displayAuthor,
                sourceName = track.displaySource.orEmpty(),
                contentType = displayContentType,
                displayContentTypeOrdinal = displayContentType?.ordinal ?: ContentType.MANGA.ordinal,
                publicationState = track.displayState?.let(::parseContentState),
                isNsfw = track.displayNsfw == true,
                rating = track.displayRating ?: -1f,
                tags = displayId?.let(tagsByMangaId::get)
                    ?.map { (tagId, title) -> UpdateCardTag(tagId, title) }
                    .orEmpty(),
                overrideTitle = displayId?.let(overrideByMangaId::get)?.titleOverride?.takeIf { it.isNotBlank() },
                overrideCoverUrl = displayId?.let(overrideByMangaId::get)?.coverOverride?.takeIf { it.isNotBlank() },
                metadataTrackingService = track.metadataTrackingService,
                metadataTrackingTitle = track.metadataTrackingTitle?.takeIf { it.isNotBlank() },
                metadataTrackingCoverUrl = track.metadataTrackingCoverUrl?.takeIf { it.isNotBlank() },
                sourceGroupFlags = contentGroupFlag(track.displaySource, track.displayNsfw == true),
                sourceOriginFlags = originGroupFlag(track.displaySource),
            )
        }

        // ---- group per entity, mirroring groupTrackingByEntity
        // display content type per entity: the first track whose display source
        // is not a TRACKING_* stub wins; entities with only stubs keep that type
        val displayTypeOrdinalByEntity = HashMap<Long, Int>()
        for (seed in seeds) {
            val entityId = seed.entityId ?: continue
            if (seed.sourceName.startsWith("TRACKING_")) {
                displayTypeOrdinalByEntity.putIfAbsent(entityId, seed.displayContentTypeOrdinal)
            } else {
                displayTypeOrdinalByEntity[entityId] = seed.displayContentTypeOrdinal
            }
        }

        val grouped = LinkedHashMap<GroupKey, MutableList<TrackRowSeed>>(seeds.size)
        for (seed in seeds) {
            val entityId = seed.entityId
            val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get)
                ?: seed.displayContentTypeOrdinal
            val key = GroupKey(
                uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: seed.mangaId,
                contentTypeOrdinal = contentTypeOrdinal,
            )
            grouped.getOrPut(key) { ArrayList(1) }.add(seed)
        }

        val groups = ArrayList<UpdateGroupRow>(grouped.size)
        for ((key, items) in grouped) {
            groups += items.toUpdateGroupRow(key, categoryIdsByEntityId)
        }
        return UpdatesSnapshot(groups = groups)
    }

    /**
     * The representative: the preferred projection when one of the tracks is
     * it, else the freshest track (lastChapterDate, lastCheck, newChapters).
     */
    private fun List<TrackRowSeed>.toUpdateGroupRow(
        key: GroupKey,
        categoryIdsByEntityId: Map<Long, Set<Long>>,
    ): UpdateGroupRow {
        val entityId = firstNotNullOfOrNull(TrackRowSeed::entityId)
        val preferredLocalMangaId = firstNotNullOfOrNull(TrackRowSeed::preferredLocalMangaId)
        val representative = firstOrNull { it.mangaId == preferredLocalMangaId }
            ?: maxWithOrNull(
                compareBy(
                    TrackRowSeed::lastChapterDate,
                    TrackRowSeed::lastCheckTime,
                    TrackRowSeed::newChapters,
                ),
            )
            ?: first()
        return UpdateGroupRow(
            uiId = key.uiId,
            entityId = entityId,
            preferredLocalMangaId = preferredLocalMangaId ?: representative.mangaId,
            mangaIds = map(TrackRowSeed::mangaId),
            totalNewChapters = sumOf(TrackRowSeed::newChapters),
            lastChapterDate = mapNotNull(TrackRowSeed::lastChapterDate).maxOrNull(),
            isPinned = representative.isPinned,
            categoryIds = entityId?.let(categoryIdsByEntityId::get).orEmpty(),
            displayMangaId = representative.displayMangaId,
            title = representative.title,
            altTitle = representative.altTitle,
            coverUrl = representative.coverUrl,
            author = representative.author,
            sourceName = representative.sourceName,
            contentType = representative.contentType,
            publicationState = representative.publicationState,
            isNsfw = representative.isNsfw,
            rating = representative.rating,
            tags = representative.tags,
            overrideTitle = representative.overrideTitle,
            overrideCoverUrl = representative.overrideCoverUrl,
            metadataTrackingService = representative.metadataTrackingService,
            metadataTrackingTitle = representative.metadataTrackingTitle,
            metadataTrackingCoverUrl = representative.metadataTrackingCoverUrl,
            sourceGroupFlags = representative.sourceGroupFlags,
            sourceOriginFlags = representative.sourceOriginFlags,
            displayContentTypeOrdinal = key.contentTypeOrdinal,
        )
    }

    private fun contentGroupFlag(sourceName: String?, nsfw: Boolean): Int {
        val group = sourceGroupManager.getContentGroupByName(sourceName.orEmpty(), nsfw)
        return 1 shl group.ordinal
    }

    private fun originGroupFlag(sourceName: String?): Int {
        val origin = sourceGroupManager.getOriginGroupByName(sourceName.orEmpty())
        return 1 shl origin.ordinal
    }

    private data class GroupKey(
        val uiId: Long,
        val contentTypeOrdinal: Int,
    )

    private companion object {
        fun parseContentType(name: String): ContentType? = runCatching { ContentType.valueOf(name) }.getOrNull()

        fun parseContentState(name: String): ContentState? = runCatching { ContentState.valueOf(name) }.getOrNull()

        fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

    }
}
