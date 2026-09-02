package org.skepsun.kototoro.history.domain.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.history.data.HistoryBindingFacetRow
import org.skepsun.kototoro.history.data.HistoryCardRow
import org.skepsun.kototoro.history.data.HistoryCategoryFacetRow
import org.skepsun.kototoro.history.data.HistoryDownloadedRow
import org.skepsun.kototoro.history.data.HistoryOverrideRow
import org.skepsun.kototoro.history.data.HistoryTagFacetRow
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deep module owning the history read model
 * (history-updates-feed komikku-alignment plan, Phase H2).
 *
 * Same contract as the favourites/updates stores: parameterless observe(),
 * complete self-consistent emissions, no writes, no network, no per-entity
 * lookups. Everything the paging-era `buildHistoryPagingAggregates` resolved
 * per page (bindings, categories, entity content types, tags of the display
 * projections) is folded here once.
 */
@Singleton
class HistoryLibrarySnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    private val sourceGroupManager: SourceGroupManager,
) {

    fun observe(): Flow<HistorySnapshot> {
        val dao = database.getHistoryLibraryReadDao()
        return combine(
            dao.observeHistoryCardBaseRows().distinctUntilChanged(),
            dao.observeHistoryTagFacets().distinctUntilChanged(),
            dao.observeHistoryBindingFacets().distinctUntilChanged(),
            dao.observeHistoryCategoryFacets().distinctUntilChanged(),
            dao.observeHistoryOverrides().distinctUntilChanged(),
            dao.observeHistoryDownloadedRows().distinctUntilChanged(),
        ) { values: Array<*> ->
            @Suppress("UNCHECKED_CAST")
            buildSnapshot(
                baseRows = values[0] as List<HistoryCardRow>,
                tagFacets = values[1] as List<HistoryTagFacetRow>,
                bindingFacets = values[2] as List<HistoryBindingFacetRow>,
                categoryFacets = values[3] as List<HistoryCategoryFacetRow>,
                overrides = values[4] as List<HistoryOverrideRow>,
                downloadedRows = values[5] as List<HistoryDownloadedRow>,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    internal fun buildSnapshot(
        baseRows: List<HistoryCardRow>,
        tagFacets: List<HistoryTagFacetRow>,
        bindingFacets: List<HistoryBindingFacetRow>,
        categoryFacets: List<HistoryCategoryFacetRow>,
        overrides: List<HistoryOverrideRow>,
        downloadedRows: List<HistoryDownloadedRow> = emptyList(),
    ): HistorySnapshot {
        if (baseRows.isEmpty()) {
            return HistorySnapshot.Empty
        }

        val tagsByMangaId = HashMap<Long, LinkedHashMap<String, HistoryCardTag>>(tagFacets.size)
        for (facet in tagFacets) {
            tagsByMangaId.getOrPut(facet.mangaId) { LinkedHashMap() }
                .putIfAbsent(facet.tagKey, HistoryCardTag(facet.tagTitle, facet.tagKey))
        }
        val bindingsByEntityId = HashMap<Long, ArrayList<HistoryBinding>>(bindingFacets.size)
        val localIdsByEntityId = HashMap<Long, LinkedHashSet<Long>>(bindingFacets.size)
        for (facet in bindingFacets) {
            val entityId = facet.entityId
            val bindings = bindingsByEntityId.getOrPut(entityId) { ArrayList(2) }
            val contentType = facet.mangaContentType?.let(::parseContentType)
                ?: facet.entityContentType?.let(::parseContentType)
            bindings += HistoryBinding(
                mangaId = facet.mangaId,
                source = facet.mangaSource,
                contentType = contentType,
            )
            localIdsByEntityId.getOrPut(entityId) { LinkedHashSet() }.add(facet.mangaId)
        }
        val categoryIdsByEntityId = HashMap<Long, LinkedHashSet<Long>>(categoryFacets.size)
        for (facet in categoryFacets) {
            categoryIdsByEntityId.getOrPut(facet.entityId) { LinkedHashSet() }.add(facet.categoryId)
        }
        val overrideByMangaId = HashMap<Long, HistoryOverrideRow>(overrides.size)
        for (override in overrides) {
            overrideByMangaId[override.mangaId] = override
        }
        val downloadedEntities = HashSet<Long>(downloadedRows.size)
        for (row in downloadedRows) {
            downloadedEntities.add(row.entityId)
        }

        val rows = ArrayList<HistoryCardEntry>(baseRows.size)
        for (base in baseRows) {
            val entityId = base.entityId
            val displayId = base.displayMangaId
            // authoritative content type: anchor manga type first, entity type
            // second (the Novel/Video chips depend on this order)
            val contentType = base.anchorContentType?.let(::parseContentType)
                ?: base.entityContentType?.let(::parseContentType)
                ?: ContentType.MANGA
            val localMangaIds = buildList(4) {
                add(base.anchorMangaId)
                addAll(localIdsByEntityId[entityId].orEmpty())
            }.distinct()
            val preferred = base.preferredLocalMangaId?.takeIf { it in localMangaIds }
                ?: localMangaIds.firstOrNull()
            val override = displayId?.let(overrideByMangaId::get)
            rows += HistoryCardEntry(
                uiId = entityId.toUiGroupId(contentType.ordinal),
                entityId = entityId,
                anchorMangaId = base.anchorMangaId,
                preferredLocalMangaId = preferred,
                displayMangaId = displayId,
                updatedAt = base.updatedAt,
                createdAt = base.createdAt,
                percent = base.percent,
                chaptersCount = base.chaptersCount,
                chapterId = base.chapterId,
                newChapters = base.newChapters ?: 0,
                lastChapterDate = base.lastChapterDate,
                isFavourite = base.isFavourite,
                isPinned = base.isPinned,
                isDownloaded = entityId in downloadedEntities,
                categoryIds = categoryIdsByEntityId[entityId].orEmpty(),
                contentType = contentType,
                displayContentTypeOrdinal = contentType.ordinal,
                localMangaIds = localMangaIds,
                bindings = bindingsByEntityId[entityId].orEmpty(),
                title = base.displayTitle.orEmpty(),
                altTitle = base.displayAltTitle,
                coverUrl = base.displayCoverUrl?.takeIf { it.isNotBlank() },
                largeCoverUrl = base.displayLargeCoverUrl?.takeIf { it.isNotBlank() },
                author = base.displayAuthor,
                sourceName = base.displaySource.orEmpty(),
                publicationState = base.displayState?.let(::parseContentState),
                isNsfw = base.displayNsfw == true,
                rating = base.displayRating ?: -1f,
                tags = displayId?.let(tagsByMangaId::get)?.values?.toList().orEmpty(),
                overrideTitle = override?.titleOverride?.takeIf { it.isNotBlank() },
                overrideCoverUrl = override?.coverOverride?.takeIf { it.isNotBlank() },
                metadataTrackingService = base.metadataTrackingService,
                metadataTrackingTitle = base.metadataTrackingTitle?.takeIf { it.isNotBlank() },
                metadataTrackingCoverUrl = base.metadataTrackingCoverUrl?.takeIf { it.isNotBlank() },
                sourceGroupFlags = contentGroupFlag(base.displaySource, base.displayNsfw == true),
                sourceOriginFlags = originGroupFlag(base.displaySource),
            )
        }
        return HistorySnapshot(rows = rows)
    }

    private fun contentGroupFlag(sourceName: String?, nsfw: Boolean): Int {
        val group = sourceGroupManager.getContentGroupByName(sourceName.orEmpty(), nsfw)
        return 1 shl group.ordinal
    }

    private fun originGroupFlag(sourceName: String?): Int {
        val origin = sourceGroupManager.getOriginGroupByName(sourceName.orEmpty())
        return 1 shl origin.ordinal
    }

    private companion object {
        fun parseContentType(name: String): ContentType? = runCatching { ContentType.valueOf(name) }.getOrNull()

        fun parseContentState(name: String): ContentState? = runCatching { ContentState.valueOf(name) }.getOrNull()

        fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())
    }
}
