package org.skepsun.kototoro.work.domain

import androidx.paging.PagingSource
import dagger.Reusable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.TABLE_WORK_STATS
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.paging.BatchMappingPagingSource
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.favourites.data.FavouriteLibraryPagingRow
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.stats.data.WorkStatsSummaryRow
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.tracker.data.TrackEntity
import javax.inject.Inject

@Reusable
class WorkAggregateRepository @Inject constructor(
    private val db: MangaDatabase,
    private val workResolver: WorkResolver,
    private val spaceContentPolicy: SpaceContentPolicy,
    private val contentSourcesRepository: ContentSourcesRepository,
) {

    fun createFavouritePagingSource(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        spaceId: SpaceId?,
        groupTab: BrowseGroupTab,
        includeTags: Boolean,
    ): PagingSource<Int, WorkAggregate> {
        val allowedSources = spaceId?.let(spaceContentPolicy::allowedSourceNames)
        val contentTypes = groupTab.allowedContentTypes()?.map(ContentType::name).orEmpty()
        val publicationStates = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.PublicationState>()
            .map { it.state.name }
            .toSet()
        val exactSources = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.Source>()
            .map { it.mangaSource.name }
            .toSet()
        val tagIds = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.Tag>()
            .map(ListFilterOption.Tag::tagId)
            .toSet()
        val nsfwMode = when {
            ListFilterOption.Macro.NSFW in filterOptions -> 1
            filterOptions.any { it is ListFilterOption.Inverted && it.option == ListFilterOption.Macro.NSFW } -> 0
            else -> -1
        }
        val delegate = db.getWorkFavouritesDao().pagingSource(
            categoryId = categoryId,
            orderName = order.name,
            applySpaceFilter = spaceId != null,
            allowedTypes = spaceId?.let(::allowedTypeNames).orEmpty(),
            classifiedTypes = classifiedTypeNames,
            applySourceFilter = allowedSources != null,
            allowedSources = allowedSources.orEmpty(),
            applyContentTypeFilter = contentTypes.isNotEmpty(),
            contentTypes = contentTypes,
            applyPublicationStateFilter = publicationStates.isNotEmpty(),
            publicationStates = publicationStates,
            nsfwMode = nsfwMode,
            requireDownloaded = ListFilterOption.Downloaded in filterOptions,
            requireNewChapters = ListFilterOption.Macro.NEW_CHAPTERS in filterOptions,
            applyExactSourceFilter = exactSources.isNotEmpty(),
            exactSources = exactSources,
            applyTagFilter = tagIds.isNotEmpty(),
            tagIds = tagIds,
        )
        return BatchMappingPagingSource(delegate, diagnosticLabel = "favourites-aggregate") { entries ->
            filterFavouriteAggregates(
                aggregates = buildFavouritePagingAggregates(entries, spaceId, includeTags),
                filterOptions = filterOptions,
            )
        }
    }

    fun observeFavouriteAggregates(
        categoryId: Long = FavouriteCategory.NO_ID,
        order: ListSortOrder = ListSortOrder.UPDATED,
        filterOptions: Set<ListFilterOption> = emptySet(),
        spaceId: SpaceId? = null,
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
    ): Flow<List<WorkAggregate>> {
        return db.invalidationTracker.createFlow(
            TABLE_WORK_FAVOURITES,
            TABLE_FAVOURITE_CATEGORIES,
            TABLE_ENTITY_GRAPH_BINDING,
            TABLE_ENTITY_PREFERENCES,
            TABLE_MANGA,
            TABLE_TAGS,
            TABLE_MANGA_TAGS,
            TABLE_WORK_HISTORY,
            TABLE_WORK_STATS,
            TABLE_PREFERENCES,
            "tracks",
            "local_index",
            emitInitialState = true,
        ).mapLatest {
            findFavouriteAggregates(
                categoryId = categoryId,
                order = order,
                filterOptions = filterOptions,
                spaceId = spaceId,
                groupTab = groupTab,
            )
        }.distinctUntilChanged()
    }

    fun observeFavouriteLibraryAggregates(
        categoryId: Long = FavouriteCategory.NO_ID,
        order: ListSortOrder = ListSortOrder.UPDATED,
        filterOptions: Set<ListFilterOption> = emptySet(),
        spaceId: SpaceId? = null,
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
        includeTags: Boolean = true,
    ): Flow<List<WorkAggregate>> {
        return db.invalidationTracker.createFlow(
            TABLE_WORK_FAVOURITES,
            TABLE_FAVOURITE_CATEGORIES,
            TABLE_ENTITY_GRAPH_BINDING,
            TABLE_ENTITY_PREFERENCES,
            TABLE_MANGA,
            TABLE_TAGS,
            TABLE_MANGA_TAGS,
            TABLE_WORK_HISTORY,
            TABLE_PREFERENCES,
            "tracks",
            "local_index",
            emitInitialState = true,
        ).mapLatest {
            if (canUseFavouriteLibraryProjection(filterOptions, spaceId, groupTab)) {
                findFavouriteLibraryAggregates(categoryId, order, filterOptions, includeTags)
            } else {
                findFavouriteAggregates(
                    categoryId = categoryId,
                    order = order,
                    filterOptions = filterOptions,
                    spaceId = spaceId,
                    groupTab = groupTab,
                )
            }
        }.distinctUntilChanged()
    }

    fun createHistoryPagingSource(
        order: ListSortOrder,
        spaceId: SpaceId?,
        groupTab: BrowseGroupTab? = null,
    ): PagingSource<Int, WorkAggregate> {
        val allowedSources = spaceId?.let(spaceContentPolicy::allowedSourceNames)
        // When not bound to a space, push the selected type chip down into SQL so
        // switching to Novel/Video doesn't page through the whole history table.
        val tabAllowedTypes = if (spaceId == null) {
            groupTab?.allowedContentTypes()?.map(ContentType::name)
        } else {
            null
        }
        val delegate = db.getWorkHistoryDao().pagingSource(
            orderName = order.name,
            applySpaceFilter = spaceId != null,
            allowedTypes = spaceId?.let(::allowedTypeNames).orEmpty(),
            classifiedTypes = classifiedTypeNames,
            applySourceFilter = allowedSources != null,
            allowedSources = allowedSources.orEmpty(),
            applyTabFilter = tabAllowedTypes != null,
            tabAllowedTypes = tabAllowedTypes.orEmpty(),
        )
        return BatchMappingPagingSource(delegate, diagnosticLabel = "history-aggregate") { histories ->
            buildHistoryAggregates(histories, spaceId)
        }
    }

    suspend fun findFavouriteAggregates(
        categoryId: Long = FavouriteCategory.NO_ID,
        order: ListSortOrder = ListSortOrder.UPDATED,
        limit: Int = Int.MAX_VALUE,
        spaceId: SpaceId? = null,
    ): List<WorkAggregate> {
        return findFavouriteAggregates(
            categoryId = categoryId,
            order = order,
            filterOptions = emptySet(),
            limit = limit,
            spaceId = spaceId,
        )
    }

    suspend fun findFavouriteContents(
        categoryId: Long = FavouriteCategory.NO_ID,
        order: ListSortOrder = ListSortOrder.UPDATED,
        filterOptions: Set<ListFilterOption> = emptySet(),
        limit: Int = Int.MAX_VALUE,
        spaceId: SpaceId? = null,
    ): List<Content> {
        return findFavouriteAggregates(
            categoryId = categoryId,
            order = order,
            filterOptions = filterOptions,
            limit = limit,
            spaceId = spaceId,
        ).mapNotNull { it.displayProjection }
    }

    suspend fun findAggregateByMangaId(mangaId: Long): WorkAggregate? {
        val identity = workResolver.resolveByMangaId(mangaId)
        val entityId = identity.entityId ?: return null
        val projectionSet = resolveProjectionSet(
            entityIds = listOf(entityId),
            anchorIds = listOf(mangaId),
        )
        val resolvedIdentity = projectionSet.identitiesByEntityId[entityId] ?: identity
        val displayProjection = resolveDisplayProjection(
            identity = resolvedIdentity,
            anchorId = mangaId,
            cachedProjectionsById = projectionSet.projectionsById,
        )
        return WorkAggregate(
            identity = resolvedIdentity,
            displayProjection = displayProjection,
            projections = projectionSet.projectionsFor(resolvedIdentity, mangaId),
            categories = findCategoriesByEntityId(listOf(entityId))[entityId].orEmpty(),
            history = db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L },
            favourite = db.getWorkFavouritesDao().findActiveForEntity(entityId),
            stats = findStatsByEntityId(listOf(entityId))[entityId],
            tracking = findTrackingByEntityId(listOf(entityId))[entityId],
        )
    }

    suspend fun findAggregatesByEntityIds(entityIds: Collection<Long>): Map<Long, WorkAggregate> {
        val distinctEntityIds = entityIds.distinct()
        if (distinctEntityIds.isEmpty()) {
            return emptyMap()
        }
        val projectionSet = resolveProjectionSet(
            entityIds = distinctEntityIds,
            anchorIds = emptyList(),
        )
        val categoriesByEntityId = findCategoriesByEntityId(distinctEntityIds)
        val historyByEntityId = findHistoryByEntityId(distinctEntityIds)
        val statsByEntityId = findStatsByEntityId(distinctEntityIds)
        val trackingByEntityId = findTrackingByEntityId(distinctEntityIds)
        return distinctEntityIds.mapNotNull { entityId ->
            val identity = projectionSet.identitiesByEntityId[entityId] ?: return@mapNotNull null
            val displayProjection = resolveDisplayProjection(
                identity = identity,
                anchorId = historyByEntityId[entityId]?.anchorMangaId ?: trackingByEntityId[entityId]?.anchorMangaId,
                cachedProjectionsById = projectionSet.projectionsById,
            )
            entityId to WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = projectionSet.projectionsFor(identity),
                categories = categoriesByEntityId[entityId].orEmpty(),
                history = historyByEntityId[entityId],
                stats = statsByEntityId[entityId],
                tracking = trackingByEntityId[entityId],
            )
        }.toMap()
    }

    suspend fun buildTrackingAggregates(tracks: List<TrackEntity>): List<WorkAggregate> {
        if (tracks.isEmpty()) {
            return emptyList()
        }
        val entityIds = tracks.mapNotNull(TrackEntity::entityId).distinct()
        if (entityIds.isEmpty()) {
            return emptyList()
        }
        val anchorIds = tracks.map(TrackEntity::mangaId)
        val projectionSet = resolveProjectionSet(
            entityIds = entityIds,
            anchorIds = anchorIds,
        )
        val categoriesByEntityId = findCategoriesByEntityId(entityIds)
        val statsByEntityId = findStatsByEntityId(entityIds)
        val trackingByEntityId = findTrackingByEntityId(entityIds)
        return entityIds.mapNotNull { entityId ->
            val identity = projectionSet.identitiesByEntityId[entityId] ?: return@mapNotNull null
            val tracking = trackingByEntityId[entityId] ?: return@mapNotNull null
            val displayProjection = resolveDisplayProjection(
                identity = identity,
                anchorId = tracking.anchorMangaId,
                cachedProjectionsById = projectionSet.projectionsById,
            ) ?: return@mapNotNull null
            WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = projectionSet.projectionsFor(identity),
                categories = categoriesByEntityId[entityId].orEmpty(),
                history = db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L },
                favourite = db.getWorkFavouritesDao().findActiveForEntity(entityId),
                stats = statsByEntityId[entityId],
                tracking = tracking,
            )
        }.sortedWith(
            compareByDescending<WorkAggregate> { it.tracking?.lastChapterDate ?: 0L }
                .thenByDescending { it.tracking?.newChapters ?: 0 },
        )
    }

    suspend fun findRecentHistoryAggregates(
        limit: Int = Int.MAX_VALUE,
        spaceId: SpaceId? = null,
        allowedSourceNames: Set<String>? = spaceId?.let(spaceContentPolicy::allowedSourceNames),
    ): List<WorkAggregate> {
        if (limit <= 0) {
            return emptyList()
        }
        val histories = findRecentHistoryEntries(limit, spaceId, allowedSourceNames)
        return buildHistoryAggregates(histories, spaceId)
    }

    /**
     * Recent history restricted to the given content types (the equivalent of a
     * content-type chip, without a space binding). Used by the home rail so its
     * type filter is applied in SQL instead of over a fixed in-memory window.
     */
    suspend fun findRecentHistoryAggregatesByTypes(
        limit: Int,
        allowedTypes: Set<ContentType>,
    ): List<WorkAggregate> {
        if (limit <= 0 || allowedTypes.isEmpty()) {
            return emptyList()
        }
        val histories = db.getWorkHistoryDao().findRecentForSpace(
            allowedTypes = allowedTypes.map(ContentType::name),
            classifiedTypes = classifiedTypeNames,
            limit = limit,
        )
        return buildHistoryAggregates(histories, spaceId = null, allowedContentTypes = allowedTypes)
    }

    suspend fun findHistoryAggregates(
        limit: Int = Int.MAX_VALUE,
        spaceId: SpaceId? = null,
    ): List<WorkAggregate> {
        if (limit <= 0) {
            return emptyList()
        }
        val histories = findRecentHistoryEntries(limit, spaceId)
        return buildHistoryAggregates(histories, spaceId)
    }

    suspend fun buildHistoryAggregates(
        histories: List<WorkHistoryEntity>,
        spaceId: SpaceId?,
        allowedContentTypes: Set<ContentType>? = null,
    ): List<WorkAggregate> {
        if (histories.isEmpty()) {
            return emptyList()
        }
        val entityIds = histories.map(WorkHistoryEntity::entityId)
        val projectionSet = resolveProjectionSet(
            entityIds = entityIds,
            anchorIds = histories.map(WorkHistoryEntity::anchorMangaId),
        )
        val categoriesByEntityId = findCategoriesByEntityId(entityIds)
        val statsByEntityId = findStatsByEntityId(entityIds)
        val trackingByEntityId = findTrackingByEntityId(entityIds)
        val allowedTypes = allowedContentTypes ?: spaceId?.let(spaceContentPolicy::allowedTypes)
        return histories.mapNotNull { history ->
            val identity = projectionSet.identitiesByEntityId[history.entityId] ?: return@mapNotNull null
            val displayProjection = resolveDisplayProjection(
                identity = identity,
                anchorId = history.anchorMangaId,
                cachedProjectionsById = projectionSet.projectionsById,
                persistedContentTypesById = projectionSet.contentTypesById,
                fallbackContentType = projectionSet.contentTypesByEntityId[history.entityId],
                allowedContentTypes = allowedTypes,
            )
                ?: return@mapNotNull null
            WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = listOf(displayProjection),
                categories = categoriesByEntityId[history.entityId].orEmpty(),
                history = history,
                stats = statsByEntityId[history.entityId],
                tracking = trackingByEntityId[history.entityId],
                contentType = projectionSet.contentTypesById[history.anchorMangaId]
                    ?: projectionSet.contentTypesByEntityId[history.entityId],
            )
        }
    }

    private suspend fun findRecentHistoryEntries(limit: Int, spaceId: SpaceId?): List<WorkHistoryEntity> {
        return findRecentHistoryEntries(
            limit = limit,
            spaceId = spaceId,
            allowedSourceNames = spaceId?.let(spaceContentPolicy::allowedSourceNames),
        )
    }

    private suspend fun findRecentHistoryEntries(
        limit: Int,
        spaceId: SpaceId?,
        allowedSourceNames: Set<String>?,
    ): List<WorkHistoryEntity> {
        if (spaceId != null) {
            return if (allowedSourceNames == null) {
                db.getWorkHistoryDao().findRecentForSpace(
                    allowedTypes = allowedTypeNames(spaceId),
                    classifiedTypes = classifiedTypeNames,
                    limit = limit,
                )
            } else {
                db.getWorkHistoryDao().findRecentForSpaceAndSources(
                    allowedTypes = allowedTypeNames(spaceId),
                    classifiedTypes = classifiedTypeNames,
                    allowedSources = allowedSourceNames,
                    limit = limit,
                )
            }
        }
        return if (limit == Int.MAX_VALUE) {
            db.getWorkHistoryDao().findAll(offset = 0, limit = Int.MAX_VALUE)
                .filter { it.deletedAt == 0L }
        } else {
            db.getWorkHistoryDao().findRecent(limit)
        }
    }

    private fun canUseFavouriteLibraryProjection(
        filterOptions: Set<ListFilterOption>,
        spaceId: SpaceId?,
        groupTab: BrowseGroupTab,
    ): Boolean {
        return spaceId == null &&
            groupTab == BrowseGroupTab.All &&
            filterOptions.all { it == ListFilterOption.SFW }
    }

    private suspend fun findFavouriteLibraryAggregates(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        includeTags: Boolean,
    ): List<WorkAggregate> = coroutineScope {
        val representatives = db.getWorkFavouritesDao().findLibraryRepresentatives(categoryId)
        if (representatives.isEmpty()) {
            return@coroutineScope emptyList()
        }
        val entries = representatives.map { representative -> representative.favourite }
        val entityIds = entries.map(WorkFavouriteEntity::entityId).distinct()
        val bindingsDeferred = async {
            entityIds.chunked(LIBRARY_QUERY_CHUNK_SIZE)
                .flatMap { chunk -> db.getEntityGraphDao().findActiveLocalBindingsByEntities(chunk) }
                .groupBy { binding -> binding.entityId }
        }
        val categoriesDeferred = async { findCategoriesByEntityId(entityIds) }
        val historyDeferred = async { findHistoryByEntityId(entityIds) }
        val trackingDeferred = async { findTrackingByEntityId(entityIds) }

        val preferredMangaIdsByEntityId = representatives.associate { representative ->
            representative.favourite.entityId to representative.preferredLocalMangaId
        }
        val projectionIds = buildSet {
            entries.mapNotNullTo(this, WorkFavouriteEntity::anchorMangaId)
            preferredMangaIdsByEntityId.values.mapNotNullTo(this) { preferredMangaId -> preferredMangaId }
        }
        val contentsById = if (includeTags) {
            db.getMangaDao().findWithTagsByIds(projectionIds)
                .associate { row -> row.manga.id to row.toContent() }
        } else {
            db.getMangaDao().findEntitiesByIds(projectionIds)
                .associate { manga -> manga.id to manga.toContent(emptySet(), null) }
        }
        val bindingsByEntityId = bindingsDeferred.await()
        val categoriesByEntityId = categoriesDeferred.await()
        val historyByEntityId = historyDeferred.await()
        val trackingByEntityId = trackingDeferred.await()

        val aggregates = entries.mapNotNull { entry ->
            val storedPreferredMangaId = preferredMangaIdsByEntityId[entry.entityId]
            val displayProjection = storedPreferredMangaId?.let(contentsById::get)
                ?: entry.anchorMangaId?.let(contentsById::get)
                ?: return@mapNotNull null
            val preferredMangaId = storedPreferredMangaId?.takeIf(contentsById::containsKey)
                ?: displayProjection.id
            val localMangaIds = buildSet {
                add(preferredMangaId)
                entry.anchorMangaId?.let(::add)
                bindingsByEntityId[entry.entityId].orEmpty().mapNotNullTo(this) { binding ->
                    binding.externalId.toLongOrNull()
                }
            }
            val identity = WorkIdentity(
                entityId = entry.entityId,
                requestedMangaId = displayProjection.id,
                preferredMangaId = preferredMangaId,
                localMangaIds = localMangaIds,
                migrationState = WorkMigrationState.VALID,
            )
            WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = listOf(displayProjection),
                categories = categoriesByEntityId[entry.entityId].orEmpty(),
                favourite = entry,
                history = historyByEntityId[entry.entityId],
                tracking = trackingByEntityId[entry.entityId],
                contentType = displayProjection.source.contentType,
            )
        }
        filterFavouriteAggregates(aggregates, filterOptions)
            .sortedWith(favouriteAggregateComparator(order))
            .distinctBy { aggregate -> aggregate.identity.entityId }
    }

    suspend fun findFavouriteAggregates(
        categoryId: Long = FavouriteCategory.NO_ID,
        order: ListSortOrder = ListSortOrder.UPDATED,
        filterOptions: Set<ListFilterOption> = emptySet(),
        limit: Int = Int.MAX_VALUE,
        spaceId: SpaceId? = null,
        groupTab: BrowseGroupTab = BrowseGroupTab.All,
    ): List<WorkAggregate> {
        if (limit <= 0) {
            return emptyList()
        }
        val entries = findFavouriteEntries(categoryId, order, filterOptions, limit, spaceId, groupTab)
        if (entries.isEmpty()) {
            return emptyList()
        }
        val aggregates = buildFavouriteAggregates(entries, spaceId)
        return filterFavouriteAggregates(aggregates, filterOptions)
            .sortedWith(favouriteAggregateComparator(order))
            .distinctBy { it.identity.entityId ?: it.displayProjection?.id }
            .take(limit)
    }

    private suspend fun filterFavouriteAggregates(
        aggregates: List<WorkAggregate>,
        filterOptions: Set<ListFilterOption>,
    ): List<WorkAggregate> {
        val downloadedIds = if (ListFilterOption.Downloaded in filterOptions) {
            db.getLocalContentIndexDao().findExistingIds(
                aggregates.mapNotNull { it.displayProjection?.id }.distinct(),
            ).toSet()
        } else {
            emptySet()
        }
        val brokenProjectionSourceNames = if (ListFilterOption.Macro.BROKEN_PROJECTION in filterOptions) {
            aggregates.asSequence()
                .flatMap { aggregate ->
                    aggregate.projections
                        .ifEmpty { listOfNotNull(aggregate.displayProjection) }
                        .asSequence()
                }
                .map { it.source.name }
                .distinct()
                .filterNot(contentSourcesRepository::isSourceAvailable)
                .toSet()
        } else {
            emptySet()
        }
        val readingStatuses = if (filterOptions.any { it is ListFilterOption.ReadingStatus }) {
            findEffectiveReadingStatuses(aggregates)
        } else {
            emptyMap()
        }
        return aggregates
            .filter { aggregate ->
                val content = aggregate.displayProjection ?: return@filter false
                matchesFavouriteFilters(
                    aggregate = aggregate,
                    content = content,
                    filterOptions = filterOptions,
                    downloadedIds = downloadedIds,
                    brokenProjectionSourceNames = brokenProjectionSourceNames,
                    readingStatus = aggregate.identity.entityId?.let(readingStatuses::get),
                )
            }
            .distinctBy { it.identity.entityId ?: it.displayProjection?.id }
    }

    private suspend fun findEffectiveReadingStatuses(
        aggregates: List<WorkAggregate>,
    ): Map<Long, ScrobblingStatus> {
        val entityIds = aggregates.mapNotNull { it.identity.entityId }.distinct()
        val entityStatuses = db.getEntityGraphDao().findEntityPrefsByIds(entityIds)
            .mapNotNull { prefs -> prefs.readingStatus.toScrobblingStatusOrNull()?.let { prefs.entityId to it } }
            .toMap()
        val fallbackMangaIds = aggregates.asSequence()
            .filter { aggregate -> aggregate.identity.entityId !in entityStatuses }
            .mapNotNull { it.displayProjection?.id }
            .distinct()
            .toList()
        val legacyStatuses = if (fallbackMangaIds.isEmpty()) {
            emptyMap()
        } else {
            db.getPreferencesDao().findByIds(fallbackMangaIds)
                .mapNotNull { prefs -> prefs.readingStatus.toScrobblingStatusOrNull()?.let { prefs.mangaId to it } }
                .toMap()
        }
        return aggregates.mapNotNull { aggregate ->
            val entityId = aggregate.identity.entityId ?: return@mapNotNull null
            val explicitStatus = entityStatuses[entityId]
                ?: aggregate.displayProjection?.id?.let(legacyStatuses::get)
            entityId to aggregate.resolveReadingStatus(explicitStatus)
        }.toMap()
    }

    private fun String?.toScrobblingStatusOrNull(): ScrobblingStatus? =
        this?.let { value -> runCatching { ScrobblingStatus.valueOf(value) }.getOrNull() }

    private suspend fun buildFavouritePagingAggregates(
        rows: List<FavouriteLibraryPagingRow>,
        spaceId: SpaceId?,
        includeTags: Boolean,
    ): List<WorkAggregate> = coroutineScope {
        if (rows.isEmpty()) {
            return@coroutineScope emptyList()
        }
        val entityIds = rows.map { row -> row.favourite.entityId }.distinct()
        val bindingsDeferred = async {
            db.getEntityGraphDao().findActiveLocalBindingsByEntities(entityIds)
                .groupBy { binding -> binding.entityId }
        }
        val categoriesDeferred = async { findCategoriesByEntityId(entityIds) }
        val bindingsByEntityId = bindingsDeferred.await()
        val projectionIds = buildSet {
            rows.forEach { row ->
                row.favourite.anchorMangaId?.let(::add)
                row.preferredLocalMangaId?.let(::add)
            }
            bindingsByEntityId.values.flatten().mapNotNullTo(this) { binding ->
                binding.externalId.toLongOrNull()
            }
        }
        val embeddedMangaById = rows.mapNotNull(FavouriteLibraryPagingRow::displayManga)
            .associateBy { manga -> manga.id }
        val projectionsById: Map<Long, Content>
        val contentTypesById: Map<Long, ContentType?>
        if (includeTags) {
            val projectionRows = db.getMangaDao().findWithTagsByIds(projectionIds)
            projectionsById = projectionRows.associate { row -> row.manga.id to row.toContent() }
            contentTypesById = projectionRows.associate { row ->
                row.manga.id to row.manga.contentType?.let(::parseContentType)
            }
        } else {
            val remainingIds = projectionIds - embeddedMangaById.keys
            val mangaById = embeddedMangaById + db.getMangaDao().findEntitiesByIds(remainingIds)
                .associateBy { manga -> manga.id }
            projectionsById = mangaById.mapValues { (_, manga) -> manga.toContent(emptySet(), null) }
            contentTypesById = mangaById.mapValues { (_, manga) -> manga.contentType?.let(::parseContentType) }
        }
        val categoriesByEntityId = categoriesDeferred.await()
        val allowedTypes = spaceId?.let(spaceContentPolicy::allowedTypes)

        rows.mapNotNull { row ->
            val entry = row.favourite
            val localMangaIds = buildSet {
                entry.anchorMangaId?.let(::add)
                bindingsByEntityId[entry.entityId].orEmpty().mapNotNullTo(this) { binding ->
                    binding.externalId.toLongOrNull()
                }
            }
            val preferredMangaId = row.preferredLocalMangaId?.takeIf { it in localMangaIds }
                ?: localMangaIds.firstOrNull()
            val identity = WorkIdentity(
                entityId = entry.entityId,
                requestedMangaId = row.displayManga?.id ?: entry.anchorMangaId,
                preferredMangaId = preferredMangaId,
                localMangaIds = localMangaIds,
                migrationState = WorkMigrationState.VALID,
            )
            val displayProjection = resolveDisplayProjection(
                identity = identity,
                anchorId = entry.anchorMangaId,
                cachedProjectionsById = projectionsById,
                persistedContentTypesById = contentTypesById,
                allowedContentTypes = allowedTypes,
            ) ?: return@mapNotNull null
            val projections = buildList {
                preferredMangaId?.let(::add)
                entry.anchorMangaId?.let(::add)
                addAll(localMangaIds)
            }.distinct()
                .filter { id -> allowedTypes == null || contentTypesById[id] in allowedTypes }
                .mapNotNull(projectionsById::get)
                .distinctBy { content ->
                    ProjectionIdentityKeys.contentCompactKey(
                        source = content.source.name,
                        id = content.id,
                        url = content.url,
                        publicUrl = content.publicUrl,
                    )
                }
            WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = projections,
                categories = categoriesByEntityId[entry.entityId].orEmpty(),
                favourite = entry,
                history = row.history,
                tracking = row.toTrackingSummary(),
                contentType = contentTypesById[displayProjection.id],
            )
        }
    }

    private fun FavouriteLibraryPagingRow.toTrackingSummary(): WorkTrackingSummary? {
        return WorkTrackingSummary(
            anchorMangaId = trackingAnchorMangaId ?: return null,
            lastChapterId = trackingLastChapterId ?: return null,
            newChapters = trackingNewChapters ?: 0,
            lastCheckTime = trackingLastCheckTime ?: 0L,
            lastChapterDate = trackingLastChapterDate ?: 0L,
        )
    }

    private suspend fun buildFavouriteAggregates(
        entries: List<WorkFavouriteEntity>,
        spaceId: SpaceId?,
    ): List<WorkAggregate> = coroutineScope {
        val entityIds = entries.map(WorkFavouriteEntity::entityId)
        val projectionSetDeferred = async {
            resolveProjectionSet(
                entityIds = entityIds,
                anchorIds = entries.mapNotNull(WorkFavouriteEntity::anchorMangaId),
            )
        }
        val categoriesDeferred = async { findCategoriesByEntityId(entityIds) }
        val historyDeferred = async { findHistoryByEntityId(entityIds) }
        val trackingDeferred = async { findTrackingByEntityId(entityIds) }
        val projectionSet = projectionSetDeferred.await()
        val categoriesByEntityId = categoriesDeferred.await()
        val historyByEntityId = historyDeferred.await()
        val trackingByEntityId = trackingDeferred.await()
        val allowedTypes = spaceId?.let(spaceContentPolicy::allowedTypes)

        entries.mapNotNull { entry: WorkFavouriteEntity ->
            val identity = projectionSet.identitiesByEntityId[entry.entityId] ?: return@mapNotNull null
            val displayProjection = resolveDisplayProjection(
                identity = identity,
                anchorId = entry.anchorMangaId,
                cachedProjectionsById = projectionSet.projectionsById,
                persistedContentTypesById = projectionSet.contentTypesById,
                fallbackContentType = projectionSet.contentTypesByEntityId[entry.entityId],
                allowedContentTypes = allowedTypes,
            )
                ?: return@mapNotNull null
            WorkAggregate(
                identity = identity,
                displayProjection = displayProjection,
                projections = projectionSet.projectionsFor(identity, entry.anchorMangaId, allowedTypes),
                categories = categoriesByEntityId[entry.entityId].orEmpty(),
                favourite = entry,
                history = historyByEntityId[entry.entityId],
                tracking = trackingByEntityId[entry.entityId],
            )
        }
    }

    private suspend fun findCategoriesByEntityId(entityIds: Collection<Long>): Map<Long, Set<FavouriteCategory>> {
        if (entityIds.isEmpty()) {
            return emptyMap()
        }
        val memberships = db.getWorkFavouritesDao()
            .findCategoryMemberships(entityIds.distinct())
        if (memberships.isEmpty()) {
            return emptyMap()
        }
        val categoriesById = findCategoriesById(memberships.map { it.categoryId })
        return memberships
            .groupBy { it.entityId }
            .mapValues { (_, entries) ->
                entries.mapNotNullTo(LinkedHashSet()) { categoriesById[it.categoryId] }
            }
    }

    private suspend fun findCategoriesById(categoryIds: Collection<Long>): Map<Long, FavouriteCategory> {
        if (categoryIds.isEmpty()) {
            return emptyMap()
        }
        return db.getFavouriteCategoriesDao()
            .findByIds(categoryIds.distinct())
            .associate { it.categoryId.toLong() to it.toFavouriteCategory() }
    }

    private suspend fun findStatsByEntityId(entityIds: Collection<Long>): Map<Long, WorkStatsSummary> {
        if (entityIds.isEmpty()) {
            return emptyMap()
        }
        return db.getWorkStatsDao()
            .findSummaries(entityIds.distinct())
            .associate { row -> row.entityId to row.toWorkStatsSummary() }
    }

    private suspend fun findHistoryByEntityId(entityIds: Collection<Long>): Map<Long, WorkHistoryEntity> {
        if (entityIds.isEmpty()) {
            return emptyMap()
        }
        return db.getWorkHistoryDao()
            .findByEntityIds(entityIds.distinct())
            .associateBy(WorkHistoryEntity::entityId)
    }

    private suspend fun findTrackingByEntityId(entityIds: Collection<Long>): Map<Long, WorkTrackingSummary> {
        if (entityIds.isEmpty()) {
            return emptyMap()
        }
        return db.getTracksDao()
            .findByEntityIds(entityIds.distinct())
            .groupBy { track -> track.entityId }
            .mapNotNull { (entityId, tracks) ->
                entityId?.let { it to tracks.toWorkTrackingSummary() }
            }
            .toMap()
    }

    private fun WorkStatsSummaryRow.toWorkStatsSummary(): WorkStatsSummary {
        return WorkStatsSummary(
            totalPages = totalPages,
            averageTimePerPage = averageTimePerPage,
            entryCount = entryCount,
        )
    }

    private fun Collection<TrackEntity>.toWorkTrackingSummary(): WorkTrackingSummary {
        val representative = maxWithOrNull(
            compareBy<TrackEntity>(
                TrackEntity::lastChapterDate,
                TrackEntity::lastCheckTime,
                TrackEntity::newChapters,
            ),
        ) ?: error("Cannot build tracking summary from an empty collection")
        return WorkTrackingSummary(
            anchorMangaId = representative.mangaId,
            lastChapterId = representative.lastChapterId,
            newChapters = sumOf(TrackEntity::newChapters),
            lastCheckTime = maxOf(TrackEntity::lastCheckTime),
            lastChapterDate = maxOf(TrackEntity::lastChapterDate),
        )
    }

    private suspend fun findFavouriteEntries(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int,
        spaceId: SpaceId?,
        groupTab: BrowseGroupTab,
    ): List<WorkFavouriteEntity> {
        if (
            limit == Int.MAX_VALUE &&
            spaceId == null &&
            filterOptions.all { it == ListFilterOption.SFW } &&
            groupTab == BrowseGroupTab.All
        ) {
            return db.getWorkFavouritesDao().findListRepresentatives(categoryId)
        }
        if (limit != Int.MAX_VALUE && filterOptions.isEmpty() && groupTab == BrowseGroupTab.All && spaceId != null) {
            val queryLimit = when {
                categoryId == FavouriteCategory.NO_ID ->
                    (limit * UNCATEGORIZED_FAVOURITE_LIMIT_MULTIPLIER).coerceAtLeast(limit)
                else -> limit
            }
            val allowedSources = spaceContentPolicy.allowedSourceNames(spaceId)
            return if (allowedSources == null) {
                db.getWorkFavouritesDao().findActiveForSpace(
                    categoryId = categoryId.takeUnless { it == FavouriteCategory.NO_ID },
                    allowedTypes = allowedTypeNames(spaceId),
                    classifiedTypes = classifiedTypeNames,
                    oldestFirst = order == ListSortOrder.OLDEST,
                    limit = queryLimit,
                )
            } else {
                db.getWorkFavouritesDao().findActiveForSpaceAndSources(
                    categoryId = categoryId.takeUnless { it == FavouriteCategory.NO_ID },
                    allowedTypes = allowedTypeNames(spaceId),
                    classifiedTypes = classifiedTypeNames,
                    allowedSources = allowedSources,
                    oldestFirst = order == ListSortOrder.OLDEST,
                    limit = queryLimit,
                )
            }
        }
        val canLimitByWorkState = filterOptions.isEmpty() &&
            groupTab == BrowseGroupTab.All &&
            limit != Int.MAX_VALUE
        if (canLimitByWorkState) {
            val queryLimit = if (categoryId == FavouriteCategory.NO_ID) {
                (limit * UNCATEGORIZED_FAVOURITE_LIMIT_MULTIPLIER).coerceAtLeast(limit)
            } else {
                limit
            }
            return when (order) {
                ListSortOrder.NEWEST -> if (categoryId == FavouriteCategory.NO_ID) {
                    db.getWorkFavouritesDao().findActiveNewest(queryLimit)
                } else {
                    db.getWorkFavouritesDao().findActiveNewest(categoryId, queryLimit)
                }
                ListSortOrder.OLDEST -> if (categoryId == FavouriteCategory.NO_ID) {
                    db.getWorkFavouritesDao().findActiveOldest(queryLimit)
                } else {
                    db.getWorkFavouritesDao().findActiveOldest(categoryId, queryLimit)
                }
                else -> findAllFavouriteEntries(categoryId)
            }
        }
        val allowedSources = spaceId?.let(spaceContentPolicy::allowedSourceNames)
        val contentTypes = groupTab.allowedContentTypes()?.map(ContentType::name).orEmpty()
        val publicationStates = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.PublicationState>()
            .map { it.state.name }
            .toSet()
        val exactSources = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.Source>()
            .map { it.mangaSource.name }
            .toSet()
        val tagIds = filterOptions.asSequence()
            .filterIsInstance<ListFilterOption.Tag>()
            .map(ListFilterOption.Tag::tagId)
            .toSet()
        val nsfwMode = when {
            ListFilterOption.Macro.NSFW in filterOptions -> 1
            filterOptions.any { it is ListFilterOption.Inverted && it.option == ListFilterOption.Macro.NSFW } -> 0
            else -> -1
        }
        return db.getWorkFavouritesDao().findList(
            categoryId = categoryId,
            orderName = order.name,
            applySpaceFilter = spaceId != null,
            allowedTypes = spaceId?.let(::allowedTypeNames).orEmpty(),
            classifiedTypes = classifiedTypeNames,
            applySourceFilter = allowedSources != null,
            allowedSources = allowedSources.orEmpty(),
            applyContentTypeFilter = contentTypes.isNotEmpty(),
            contentTypes = contentTypes,
            applyPublicationStateFilter = publicationStates.isNotEmpty(),
            publicationStates = publicationStates,
            nsfwMode = nsfwMode,
            requireDownloaded = ListFilterOption.Downloaded in filterOptions,
            requireNewChapters = ListFilterOption.Macro.NEW_CHAPTERS in filterOptions,
            applyExactSourceFilter = exactSources.isNotEmpty(),
            exactSources = exactSources,
            applyTagFilter = tagIds.isNotEmpty(),
            tagIds = tagIds,
        )
    }

    private suspend fun findAllFavouriteEntries(categoryId: Long): List<WorkFavouriteEntity> {
        return if (categoryId == FavouriteCategory.NO_ID) {
            db.getWorkFavouritesDao().findActive()
        } else {
            db.getWorkFavouritesDao().findActive(categoryId)
        }
    }

    private suspend fun resolveProjectionSet(
        entityIds: Collection<Long>,
        anchorIds: Collection<Long>,
    ): WorkProjectionSet = coroutineScope {
        val identitiesByEntityId = workResolver.resolveManyByEntityIds(entityIds)
        val projectionIds = LinkedHashSet<Long>()
        projectionIds += anchorIds
        identitiesByEntityId.values.filterNotNull().forEach { identity ->
            identity.preferredMangaId?.let(projectionIds::add)
            projectionIds += identity.localMangaIds
        }
        val projectionRowsDeferred = async { db.getMangaDao().findWithTagsByIds(projectionIds) }
        val contentTypesByEntityIdDeferred = async {
            entityIds.distinct().takeIf { it.isNotEmpty() }
                ?.let { ids -> db.getEntityGraphDao().findEntitiesByIds(ids) }
                .orEmpty()
                .associate { entity -> entity.id to entity.contentType?.let(::parseContentType) }
        }
        val projectionRows = projectionRowsDeferred.await()
        val contentTypesByEntityId = contentTypesByEntityIdDeferred.await()
        val projectionsById = projectionRows.associate { it.manga.id to it.toContent() }
        WorkProjectionSet(
            identitiesByEntityId = identitiesByEntityId,
            projectionsById = projectionsById,
            contentTypesById = projectionRows.associate { row ->
                row.manga.id to row.manga.contentType?.let(::parseContentType)
            },
            contentTypesByEntityId = contentTypesByEntityId,
        )
    }

    private suspend fun resolveDisplayProjection(
        identity: WorkIdentity,
        anchorId: Long?,
        cachedProjectionsById: Map<Long, Content>,
        persistedContentTypesById: Map<Long, ContentType?> = emptyMap(),
        fallbackContentType: ContentType? = null,
        allowedContentTypes: Set<ContentType>? = null,
    ): Content? {
        val anchorProjection = anchorId?.let { mangaId ->
            cachedProjectionsById[mangaId] ?: db.getMangaDao().find(mangaId)?.toContent()
        }?.takeIf {
            allowedContentTypes == null ||
                (persistedContentTypesById[anchorId] ?: fallbackContentType) in allowedContentTypes
        }
        val candidateIds = buildList {
            identity.preferredMangaId?.let(::add)
            anchorId?.let(::add)
            identity.localMangaIds.forEach(::add)
        }.distinct()
        for (mangaId in candidateIds) {
            val candidate = cachedProjectionsById[mangaId] ?: db.getMangaDao().find(mangaId)?.toContent()
            val contentType = persistedContentTypesById[mangaId] ?: fallbackContentType
            if (candidate != null && (allowedContentTypes == null || contentType in allowedContentTypes)) {
                return candidate.takeUnless { it.isStaleLocalMangaProjectionFor(anchorProjection) } ?: anchorProjection
            }
        }
        return null
    }

    private fun Content.isStaleLocalMangaProjectionFor(anchorProjection: Content?): Boolean {
        return source == LocalMangaSource &&
            anchorProjection != null &&
            anchorProjection.source != LocalMangaSource &&
            anchorProjection.source.getContentType() != source.getContentType()
    }

    private data class WorkProjectionSet(
        val identitiesByEntityId: Map<Long, WorkIdentity?>,
        val projectionsById: Map<Long, Content>,
        val contentTypesById: Map<Long, ContentType?>,
        val contentTypesByEntityId: Map<Long, ContentType?>,
    ) {
        fun projectionsFor(
            identity: WorkIdentity,
            anchorId: Long? = null,
            allowedContentTypes: Set<ContentType>? = null,
        ): List<Content> {
            val projectionIds = buildList {
                identity.preferredMangaId?.let(::add)
                anchorId?.let(::add)
                addAll(identity.localMangaIds)
            }.distinct()
            val fallbackContentType = identity.entityId?.let(contentTypesByEntityId::get)
            return projectionIds
                .filter { id ->
                    allowedContentTypes == null ||
                        (contentTypesById[id] ?: fallbackContentType) in allowedContentTypes
                }
                .mapNotNull(projectionsById::get)
                .distinctBy { content ->
                    ProjectionIdentityKeys.contentCompactKey(
                        source = content.source.name,
                        id = content.id,
                        url = content.url,
                        publicUrl = content.publicUrl,
                    )
                }
        }
    }

    private fun parseContentType(name: String): ContentType? {
        return runCatching { ContentType.valueOf(name) }.getOrNull()
    }

    private fun matchesFavouriteFilters(
        aggregate: WorkAggregate,
        content: Content,
        filterOptions: Set<ListFilterOption>,
        downloadedIds: Set<Long>,
        brokenProjectionSourceNames: Set<String>,
        readingStatus: ScrobblingStatus?,
    ): Boolean {
        if (!aggregate.matchesTagAndSourceFilters(filterOptions)) {
            return false
        }
        if (!content.matchesPublicationStateFilters(filterOptions)) {
            return false
        }
        val hasReadingStatusFilter = filterOptions.any { it is ListFilterOption.ReadingStatus }
        if (hasReadingStatusFilter &&
            (readingStatus == null || !readingStatus.matchesReadingStatusFilters(filterOptions))
        ) {
            return false
        }
        return filterOptions.all { option ->
            when (option) {
                ListFilterOption.Downloaded -> content.id in downloadedIds
                is ListFilterOption.Macro -> when (option) {
                    ListFilterOption.Macro.NSFW -> content.isNsfw()
                    else -> aggregate.matchesFavouriteMacroFilter(option, brokenProjectionSourceNames)
                }
                is ListFilterOption.Inverted -> when (option.option) {
                    ListFilterOption.Macro.NSFW -> !content.isNsfw()
                    else -> true
                }
                is ListFilterOption.Tag,
                is ListFilterOption.Source,
                -> true
                is ListFilterOption.PublicationState -> true
                is ListFilterOption.ReadingStatus -> true
                else -> true
            }
        }
    }

    private fun favouriteAggregateComparator(order: ListSortOrder): Comparator<WorkAggregate> {
        val byPinned = compareByDescending<WorkAggregate> { it.favourite?.isPinned == true }
        val byTitle = compareBy<WorkAggregate> { it.displayProjection?.title.orEmpty() }
        return byPinned.then(
            when (order) {
                ListSortOrder.RATING -> compareByDescending { it.displayProjection?.rating ?: -1f }
                ListSortOrder.NEWEST -> compareByDescending { it.favourite?.createdAt ?: 0L }
                ListSortOrder.OLDEST -> compareBy { it.favourite?.createdAt ?: 0L }
                ListSortOrder.PROGRESS -> compareByDescending { it.history?.percent ?: 0f }
                ListSortOrder.UNREAD -> compareBy { it.history?.percent ?: 0f }
                ListSortOrder.LAST_READ -> compareByDescending { it.history?.updatedAt ?: 0L }
                ListSortOrder.LONG_AGO_READ -> compareBy { it.history?.updatedAt ?: 0L }
                ListSortOrder.NEW_CHAPTERS -> compareByDescending<WorkAggregate> {
                    it.tracking?.newChapters ?: 0
                }.thenByDescending { it.tracking?.lastChapterDate ?: 0L }
                ListSortOrder.UPDATED -> compareByDescending { it.tracking?.lastChapterDate ?: 0L }
                ListSortOrder.ALPHABETIC -> byTitle
                ListSortOrder.ALPHABETIC_REVERSE -> byTitle.reversed()
                else -> compareByDescending { it.favourite?.updatedAt ?: 0L }
            },
        )
    }

    private fun allowedTypeNames(spaceId: SpaceId): Set<String> {
        return spaceContentPolicy.allowedTypes(spaceId).mapTo(LinkedHashSet()) { it.name }
    }

    private val classifiedTypeNames: Set<String>
        get() = BuiltInSpaces.contexts
            .flatMapTo(LinkedHashSet()) { context -> context.allowedContentTypes.map { it.name } }

    private companion object {
        private const val LIBRARY_QUERY_CHUNK_SIZE = 500
        private const val UNCATEGORIZED_FAVOURITE_LIMIT_MULTIPLIER = 4
    }
}
