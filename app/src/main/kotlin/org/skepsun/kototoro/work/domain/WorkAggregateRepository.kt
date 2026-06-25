package org.skepsun.kototoro.work.domain

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.stats.data.WorkStatsSummaryRow
import org.skepsun.kototoro.tracker.data.TrackEntity
import javax.inject.Inject

@Reusable
class WorkAggregateRepository @Inject constructor(
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) {

	suspend fun findFavouriteAggregates(
		categoryId: Long = FavouriteCategory.NO_ID,
		order: ListSortOrder = ListSortOrder.UPDATED,
		limit: Int = Int.MAX_VALUE,
	): List<WorkAggregate> {
		return findFavouriteAggregates(
			categoryId = categoryId,
			order = order,
			filterOptions = emptySet(),
			limit = limit,
		)
	}

	suspend fun findFavouriteContents(
		categoryId: Long = FavouriteCategory.NO_ID,
		order: ListSortOrder = ListSortOrder.UPDATED,
		filterOptions: Set<ListFilterOption> = emptySet(),
		limit: Int = Int.MAX_VALUE,
	): List<Content> {
		return findFavouriteAggregates(
			categoryId = categoryId,
			order = order,
			filterOptions = filterOptions,
			limit = limit,
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
			projections = projectionSet.projectionsById.values.toList(),
			categories = findCategoriesByEntityId(listOf(entityId))[entityId].orEmpty(),
			history = db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L },
			favourite = db.getWorkFavouritesDao().findActiveForEntity(entityId),
			stats = findStatsByEntityId(listOf(entityId))[entityId],
			tracking = findTrackingByEntityId(listOf(entityId))[entityId],
		)
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

	suspend fun findRecentHistoryAggregates(limit: Int = Int.MAX_VALUE): List<WorkAggregate> {
		if (limit <= 0) {
			return emptyList()
		}
		val histories = findRecentHistoryEntries(limit)
		return buildHistoryAggregates(histories)
	}

	suspend fun findHistoryAggregates(limit: Int = Int.MAX_VALUE): List<WorkAggregate> {
		if (limit <= 0) {
			return emptyList()
		}
		val histories = findRecentHistoryEntries(limit)
		return buildHistoryAggregates(histories)
	}

	private suspend fun buildHistoryAggregates(histories: List<WorkHistoryEntity>): List<WorkAggregate> {
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
		return histories.mapNotNull { history ->
			val identity = projectionSet.identitiesByEntityId[history.entityId] ?: return@mapNotNull null
			val displayProjection = resolveDisplayProjection(
				identity = identity,
				anchorId = history.anchorMangaId,
				cachedProjectionsById = projectionSet.projectionsById,
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

	private suspend fun findRecentHistoryEntries(limit: Int): List<WorkHistoryEntity> {
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
	): List<WorkAggregate> {
		if (limit <= 0) {
			return emptyList()
		}
		val entries = findFavouriteEntries(categoryId, order, filterOptions, limit)
		if (entries.isEmpty()) {
			return emptyList()
		}
		val aggregates = buildFavouriteAggregates(entries)
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

	private suspend fun buildFavouriteAggregates(entries: List<WorkFavouriteEntity>): List<WorkAggregate> {
		val projectionSet = resolveProjectionSet(
			entityIds = entries.map(WorkFavouriteEntity::entityId),
			anchorIds = entries.mapNotNull(WorkFavouriteEntity::anchorMangaId),
		)
		val entityIds = entries.map(WorkFavouriteEntity::entityId)
		val categoriesById = findCategoriesById(entries.map { it.categoryId })
		val statsByEntityId = findStatsByEntityId(entityIds)
		val trackingByEntityId = findTrackingByEntityId(entityIds)

		return entries.mapNotNull { entry: WorkFavouriteEntity ->
			val identity = projectionSet.identitiesByEntityId[entry.entityId] ?: return@mapNotNull null
			val displayProjection = resolveDisplayProjection(
				identity = identity,
				anchorId = entry.anchorMangaId,
				cachedProjectionsById = projectionSet.projectionsById,
			)
				?: return@mapNotNull null
			val categories: Set<FavouriteCategory> = categoriesById[entry.categoryId]?.let { setOf(it) } ?: emptySet()
			WorkAggregate(
				identity = identity,
				displayProjection = displayProjection,
				projections = listOf(displayProjection),
				categories = categories,
				favourite = entry,
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
	): List<WorkFavouriteEntity> {
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
				ListSortOrder.UPDATED -> if (categoryId == FavouriteCategory.NO_ID) {
					db.getWorkFavouritesDao().findActiveUpdated(queryLimit)
				} else {
					db.getWorkFavouritesDao().findActiveUpdated(categoryId, queryLimit)
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
		val projectionsById = db.getMangaDao()
			.findWithTagsByIds(projectionIds)
			.associate { it.manga.id to it.toContent() }
		return WorkProjectionSet(
			identitiesByEntityId = identitiesByEntityId,
			projectionsById = projectionsById,
		)
	}

	private suspend fun resolveDisplayProjection(
		identity: WorkIdentity,
		anchorId: Long?,
		cachedProjectionsById: Map<Long, Content>,
	): Content? {
		val candidateIds = buildList {
			identity.preferredMangaId?.let(::add)
			anchorId?.let(::add)
			identity.localMangaIds.forEach(::add)
		}.distinct()
		for (mangaId in candidateIds) {
			cachedProjectionsById[mangaId]?.let { return it }
			db.getMangaDao().find(mangaId)?.toContent()?.let { return it }
		}
		return null
	}

	private data class WorkProjectionSet(
		val identitiesByEntityId: Map<Long, WorkIdentity?>,
		val projectionsById: Map<Long, Content>,
	) {
		fun projectionsFor(identity: WorkIdentity): List<Content> {
			val projectionIds = buildList {
				identity.preferredMangaId?.let(::add)
				addAll(identity.localMangaIds)
			}.distinct()
			return projectionIds.mapNotNull(projectionsById::get)
		}
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
				ListSortOrder.ALPHABETIC -> byTitle
				ListSortOrder.ALPHABETIC_REVERSE -> byTitle.reversed()
				else -> compareByDescending { it.favourite?.updatedAt ?: 0L }
			},
		)
	}

	private companion object {
		private const val UNCATEGORIZED_FAVOURITE_LIMIT_MULTIPLIER = 4
	}
}
