package org.skepsun.kototoro.work.domain

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
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
) {

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

	private suspend fun buildHistoryAggregates(
		histories: List<WorkHistoryEntity>,
		spaceId: SpaceId?,
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
		val allowedTypes = spaceId?.let(spaceContentPolicy::allowedTypes)
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

	suspend fun findFavouriteAggregates(
		categoryId: Long = FavouriteCategory.NO_ID,
		order: ListSortOrder = ListSortOrder.UPDATED,
		filterOptions: Set<ListFilterOption> = emptySet(),
		limit: Int = Int.MAX_VALUE,
		spaceId: SpaceId? = null,
	): List<WorkAggregate> {
		if (limit <= 0) {
			return emptyList()
		}
		val entries = findFavouriteEntries(categoryId, order, filterOptions, limit, spaceId)
		if (entries.isEmpty()) {
			return emptyList()
		}
		val aggregates = buildFavouriteAggregates(entries, spaceId)
		val downloadedIds = if (ListFilterOption.Downloaded in filterOptions) {
			db.getLocalContentIndexDao().findExistingIds(
				aggregates.mapNotNull { it.displayProjection?.id }.distinct(),
			).toSet()
		} else {
			emptySet()
		}
		return aggregates
			.filter { aggregate ->
				val content = aggregate.displayProjection ?: return@filter false
				matchesFavouriteFilters(content, filterOptions, downloadedIds)
			}
			.sortedWith(favouriteAggregateComparator(order))
			.distinctBy { it.identity.entityId ?: it.displayProjection?.id }
			.take(limit)
	}

	private suspend fun buildFavouriteAggregates(
		entries: List<WorkFavouriteEntity>,
		spaceId: SpaceId?,
	): List<WorkAggregate> {
		val projectionSet = resolveProjectionSet(
			entityIds = entries.map(WorkFavouriteEntity::entityId),
			anchorIds = entries.mapNotNull(WorkFavouriteEntity::anchorMangaId),
		)
		val entityIds = entries.map(WorkFavouriteEntity::entityId)
		val categoriesById = findCategoriesById(entries.map { it.categoryId })
		val historyByEntityId = findHistoryByEntityId(entityIds)
		val statsByEntityId = findStatsByEntityId(entityIds)
		val trackingByEntityId = findTrackingByEntityId(entityIds)
		val allowedTypes = spaceId?.let(spaceContentPolicy::allowedTypes)

		return entries.mapNotNull { entry: WorkFavouriteEntity ->
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
			val categories: Set<FavouriteCategory> = categoriesById[entry.categoryId]?.let { setOf(it) } ?: emptySet()
			WorkAggregate(
				identity = identity,
				displayProjection = displayProjection,
				projections = projectionSet.projectionsFor(identity, entry.anchorMangaId, allowedTypes),
				categories = categories,
				favourite = entry,
				history = historyByEntityId[entry.entityId],
				stats = statsByEntityId[entry.entityId],
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
	): List<WorkFavouriteEntity> {
		if (spaceId != null) {
			val queryLimit = when {
				filterOptions.isNotEmpty() -> Int.MAX_VALUE
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
		val canLimitByWorkState = filterOptions.isEmpty() && limit != Int.MAX_VALUE
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
		return findAllFavouriteEntries(categoryId)
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
	): WorkProjectionSet {
		val identitiesByEntityId = entityIds
			.distinct()
			.associateWith { entityId -> workResolver.resolveByEntityId(entityId) }
		val projectionIds = LinkedHashSet<Long>()
		projectionIds += anchorIds
		identitiesByEntityId.values.filterNotNull().forEach { identity ->
			identity.preferredMangaId?.let(projectionIds::add)
			projectionIds += identity.localMangaIds
		}
		val projectionRows = db.getMangaDao().findWithTagsByIds(projectionIds)
		val projectionsById = projectionRows.associate { it.manga.id to it.toContent() }
		val contentTypesByEntityId = entityIds.distinct().takeIf { it.isNotEmpty() }
			?.let { ids -> db.getEntityGraphDao().findEntitiesByIds(ids) }
			.orEmpty()
			.associate { entity -> entity.id to entity.contentType?.let(::parseContentType) }
		return WorkProjectionSet(
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
		content: Content,
		filterOptions: Set<ListFilterOption>,
		downloadedIds: Set<Long>,
	): Boolean {
		return filterOptions.all { option ->
			when (option) {
				ListFilterOption.Downloaded -> content.id in downloadedIds
				ListFilterOption.Macro.NSFW -> content.isNsfw()
				is ListFilterOption.Inverted -> when (option.option) {
					ListFilterOption.Macro.NSFW -> !content.isNsfw()
					else -> true
				}
				is ListFilterOption.Tag -> content.tags.any { tag -> tag.title == option.tag.title && tag.key == option.tag.key }
				is ListFilterOption.Source -> content.source.name == option.mangaSource.name
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
		private const val UNCATEGORIZED_FAVOURITE_LIMIT_MULTIPLIER = 4
	}
}
