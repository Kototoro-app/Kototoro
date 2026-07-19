package org.skepsun.kototoro.favourites.domain

import android.util.Log
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryCountEntry
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.stabilizeActiveWorkFavouriteAnchor
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.favourites.domain.model.Cover
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.space.domain.SpaceId
import javax.inject.Inject

private const val TAG = "FavouritesRepository"

internal fun selectLegacyFavouriteMangaIds(
	entries: Collection<FavouriteEntity>,
	availableMangaIds: Set<Long>,
): List<Long> {
	return entries.asSequence()
		.filter { it.deletedAt == 0L }
		.map(FavouriteEntity::mangaId)
		.distinct()
		.filter(availableMangaIds::contains)
		.toList()
}

@Reusable
class FavouritesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
	private val entityGraphRepository: EntityGraphRepository,
	private val workAggregateRepository: WorkAggregateRepository,
	private val settings: AppSettings,
) {

	private data class WorkFavouriteNormalizationKey(
		val entityId: Long,
		val categoryId: Long,
	)

	suspend fun getAllContent(): List<Content> {
		return workAggregateRepository.findFavouriteAggregates(
			categoryId = FavouriteCategory.NO_ID,
			order = ListSortOrder.NEWEST,
		).mapNotNull { it.displayProjection }
	}

	suspend fun getLastContent(limit: Int): List<Content> {
		return workAggregateRepository.findFavouriteAggregates(
			categoryId = FavouriteCategory.NO_ID,
			order = ListSortOrder.NEWEST,
			limit = limit,
		).mapNotNull { it.displayProjection }
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Content> {
		if (limit <= 0) {
			return emptyList()
		}
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty()) {
			return emptyList()
		}
		val comparator = compareBy<Content> { it.title.levenshteinDistance(normalizedQuery) }
			.thenBy { it.title }
		return getAllContent()
			.asSequence()
			.filter { content -> content.matchesFavouriteSearch(normalizedQuery, kind) }
			.let { sequence ->
				when (kind) {
					SearchKind.SIMPLE,
					SearchKind.TITLE,
					SearchKind.ADVANCED -> sequence.sortedWith(comparator)
					SearchKind.AUTHOR,
					SearchKind.TAG -> sequence
				}
			}
			.take(limit)
			.toList()
	}

	private fun Content.matchesFavouriteSearch(query: String, kind: SearchKind): Boolean {
		val normalizedQuery = query.lowercase()
		fun String?.containsQuery() = this?.lowercase()?.contains(normalizedQuery) == true
		fun Iterable<String>.anyContainsQuery() = any { it.lowercase().contains(normalizedQuery) }
		return when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE,
			SearchKind.ADVANCED -> {
				title.containsQuery() ||
					altTitles.anyContainsQuery()
			}
			SearchKind.AUTHOR -> authors.anyContainsQuery()
			SearchKind.TAG -> tags.any { it.title.containsQuery() }
		}
	}

	fun observeAll(order: ListSortOrder, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
		return observeWorkFavouriteContents(FavouriteCategory.NO_ID, order, filterOptions, limit)
	}

	fun observeAllProjectionContents(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		spaceId: SpaceId? = null,
	): Flow<List<Content>> {
		return observeWorkFavouriteProjectionContents(FavouriteCategory.NO_ID, order, filterOptions, limit, spaceId)
	}

	fun observeFeedCategoryIds(): Flow<Map<String, Set<Long>>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_ENTITY_PREFERENCES,
			TABLE_MANGA,
			emitInitialState = true,
		).mapLatest {
			buildWorkFavouriteCategoryIdsByFeedKey()
		}.distinctUntilChanged()
	}

	fun observeCategoryCountEntries(): Flow<List<org.skepsun.kototoro.favourites.data.FavouriteCategoryCountEntry>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			TABLE_ENTITY_PREFERENCES,
			TABLE_MANGA,
			emitInitialState = true,
		).mapLatest {
			buildWorkFavouriteCategoryCountEntries()
		}.distinctUntilChanged()
	}

	suspend fun getContent(categoryId: Long): List<Content> {
		return buildWorkFavouriteContents(categoryId = categoryId, order = ListSortOrder.NEWEST)
	}

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Content>> {
		return observeWorkFavouriteContents(categoryId, order, filterOptions, limit)
	}

	fun observeAllProjectionContents(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		spaceId: SpaceId? = null,
	): Flow<List<Content>> {
		return observeWorkFavouriteProjectionContents(categoryId, order, filterOptions, limit, spaceId)
	}

	fun observeAll(categoryId: Long, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
		return observeOrder(categoryId)
			.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }
	}

	fun observeAllProjectionContents(
		categoryId: Long,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		spaceId: SpaceId? = null,
	): Flow<List<Content>> {
		return observeOrder(categoryId)
			.flatMapLatest { order -> observeAllProjectionContents(categoryId, order, filterOptions, limit, spaceId) }
	}

	fun observeContentCount(): Flow<Int> {
		return db.invalidationTracker.createFlow(TABLE_WORK_FAVOURITES, emitInitialState = true)
			.mapLatest { db.getWorkFavouritesDao().countActiveWorks() }
			.distinctUntilChanged()
	}

	fun observeFavouriteBadgeChanges(): Flow<Unit> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_ENTITY_GRAPH_BINDING,
			TABLE_ENTITY_PREFERENCES,
			emitInitialState = false,
		).map { Unit }
	}

	fun observeCategories(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAll().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	fun observeCategoriesForLibrary(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAllVisible().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	fun observeCategoriesWithCovers(): Flow<Map<FavouriteCategory, List<Cover>>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			TABLE_ENTITY_PREFERENCES,
			TABLE_MANGA,
			TABLE_WORK_HISTORY,
			"tracks",
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val categories = db.getFavouriteCategoriesDao().findAll()
				val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
				for (entity in categories) {
					val cat = entity.toFavouriteCategory()
					res[cat] = buildWorkFavouriteCovers(
						categoryId = cat.id,
						order = cat.order,
					)
				}
				res
			}
		}.distinctUntilChanged()
	}

	suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
		return buildWorkFavouriteCovers(
			categoryId = FavouriteCategory.NO_ID,
			order = order,
			limit = limit,
		)
	}

	fun observeCategory(id: Long): Flow<FavouriteCategory?> {
		return db.getFavouriteCategoriesDao().observe(id)
			.map { it?.toFavouriteCategory() }
	}

	fun observeCategoriesIds(mangaId: Long): Flow<Set<Long>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_ENTITY_GRAPH_BINDING,
			emitInitialState = true,
		).mapLatest {
			findWorkCategoryIds(mangaId)
		}.distinctUntilChanged()
	}

	fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return observeCategoriesByWork(mangaId)
	}

	fun observeCategoriesByWork(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			TABLE_ENTITY_GRAPH_BINDING,
			TABLE_ENTITY_PREFERENCES,
			emitInitialState = true,
		).mapLatest {
			findWorkCategoryIds(mangaId).mapNotNullTo(LinkedHashSet()) { categoryId ->
					db.getFavouriteCategoriesDao().find(categoryId.toInt())?.toFavouriteCategory()
				}
		}.distinctUntilChanged()
	}

	suspend fun getCategory(id: Long): FavouriteCategory {
		return db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()
	}

	suspend fun findCategoryByTitle(title: String): FavouriteCategory? {
		return db.getFavouriteCategoriesDao().findAll()
			.firstOrNull { it.title == title }
			?.toFavouriteCategory()
	}

	suspend fun isFavorite(mangaId: Long): Boolean {
		return isFavoriteByWork(mangaId)
	}

	suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
		return findWorkCategoryIds(mangaId)
	}

	suspend fun getCategoriesIds(mangaIds: Collection<Long>): Map<Long, Set<Long>> {
		if (mangaIds.isEmpty()) return emptyMap()
		val identitiesByMangaId = workResolver.resolveManyByMangaIds(mangaIds)
		val entityIdsByMangaId = identitiesByMangaId
			.mapValues { (_, identity) -> identity.entityId }
			.filterValues { it != null }
			.mapValues { (_, entityId) -> requireNotNull(entityId) }
		if (entityIdsByMangaId.isEmpty()) {
			return mangaIds.associateWith { emptySet() }
		}
		val categoryIdsByEntityId = db.getWorkFavouritesDao()
			.findCategoryMemberships(entityIdsByMangaId.values.distinct())
			.groupBy(
				keySelector = { it.entityId },
				valueTransform = { it.categoryId },
			)
			.mapValues { (_, categoryIds) -> categoryIds.toCollection(LinkedHashSet()) }
		return mangaIds.associateWith { mangaId ->
			entityIdsByMangaId[mangaId]?.let { entityId -> categoryIdsByEntityId[entityId] }.orEmpty()
		}
	}

	suspend fun isFavoriteByWork(mangaId: Long): Boolean {
		val entityId = resolveFavouriteEntityId(mangaId)
		if (entityId != null) {
			return db.getWorkFavouritesDao().findCategoriesCount(entityId) != 0
		}
		return false
	}

	suspend fun getCategoriesIdsByWork(mangaId: Long): Set<Long> {
		return findWorkCategoryIds(mangaId)
	}

	suspend fun findPopularSources(categoryId: Long, limit: Int): List<ContentSource> {
		return buildWorkFavouriteProjectionContents(
			categoryId = categoryId,
			order = ListSortOrder.NEWEST,
		)
			.groupingBy { it.source.name }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(limit)
			.map { it.key }
			.toContentSources()
	}

	suspend fun findPopularTags(categoryId: Long, limit: Int): List<ContentTag> {
		return buildWorkFavouriteProjectionContents(
			categoryId = categoryId,
			order = ListSortOrder.NEWEST,
		)
			.flatMap { it.tags }
			.groupingBy { it }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(limit)
			.map { it.key }
	}

	suspend fun createCategory(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	): FavouriteCategory {
		val entity = FavouriteCategoryEntity(
			title = title,
			createdAt = System.currentTimeMillis(),
			sortKey = db.getFavouriteCategoriesDao().getNextSortKey(),
			categoryId = 0,
			order = sortOrder.name,
			track = isTrackerEnabled,
			deletedAt = 0L,
			isVisibleInLibrary = isVisibleOnShelf,
		)
		val id = db.getFavouriteCategoriesDao().insert(entity)
		val category = entity.toFavouriteCategory(id)
		return category
	}

	suspend fun updateCategory(
		id: Long,
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		db.getFavouriteCategoriesDao().update(id, title, sortOrder.name, isTrackerEnabled, isVisibleOnShelf)
	}

	suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
		db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
	}

	suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
		db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
	}

	suspend fun removeCategories(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getWorkFavouritesDao().deleteAll(id)
				db.getFavouriteCategoriesDao().delete(id)
			}
			db.getChaptersDao().gc()
		}
	}

	suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
		db.getFavouriteCategoriesDao().updateOrder(id, order.name)
	}

	suspend fun reorderCategories(orderedIds: List<Long>) {
		val dao = db.getFavouriteCategoriesDao()
		db.withTransaction {
			for ((i, id) in orderedIds.withIndex()) {
				dao.updateSortKey(id, i)
			}
		}
	}

	suspend fun addToCategory(categoryId: Long, mangas: Collection<Content>) {
		val anchorContents = resolveWorkAnchorContents(mangas)
		val entityIdsByMangaId = anchorContents.associate { manga ->
			manga.id to requireNotNull(
				workResolver.ensureForProjection(
					content = manga,
					provenance = WorkIdentityProvenance.USER,
				).entityId,
			)
		}
		addResolvedAnchorsToCategory(categoryId, anchorContents, entityIdsByMangaId)
	}

	suspend fun addToCategoryAsSeparateWorks(categoryId: Long, mangas: Collection<Content>) {
		val anchorContents = resolveWorkAnchorContents(mangas)
		val entityIdsByMangaId = anchorContents.associate { manga ->
			manga.id to entityGraphRepository.ensureIndependentLocalWorkEntity(manga).id
		}
		addResolvedAnchorsToCategory(categoryId, anchorContents, entityIdsByMangaId)
	}

	private suspend fun addResolvedAnchorsToCategory(
		categoryId: Long,
		anchorContents: Collection<Content>,
		entityIdsByMangaId: Map<Long, Long>,
	) {
		db.withTransaction {
			val currentTime = System.currentTimeMillis()
			for (manga in anchorContents) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				entityIdsByMangaId[manga.id]?.let { entityId ->
					db.getWorkFavouritesDao().upsert(
						WorkFavouriteEntity(
							entityId = entityId,
							categoryId = categoryId,
							anchorMangaId = manga.id,
							createdAt = currentTime,
							sortKey = 0,
							deletedAt = 0L,
							isPinned = false,
							updatedAt = currentTime,
						),
					)
				}
			}
		}
	}

	suspend fun setPinned(mangaIds: Collection<Long>, isPinned: Boolean) {
		if (mangaIds.isEmpty()) return
		db.withTransaction {
			val entityIds = mangaIds.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
			if (entityIds.isNotEmpty()) {
				db.getWorkFavouritesDao().setPinned(entityIds.toList(), isPinned)
			}
		}
	}

	suspend fun isPinned(mangaIds: Collection<Long>): Boolean {
		if (mangaIds.isEmpty()) return false
		val entityIds = mangaIds.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		return entityIds.isNotEmpty() && db.getWorkFavouritesDao().isPinned(entityIds.toList()) == true
	}

	suspend fun getPinnedIds(mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val entityIdsByMangaId = resolveEntityIdsByMangaIds(mangaIds)
		val pinnedEntityIds = db.getWorkFavouritesDao().findPinnedEntityIds(entityIdsByMangaId.values.distinct())
		if (pinnedEntityIds.isEmpty()) {
			return emptySet()
		}
		return mangaIds.filterTo(LinkedHashSet()) { mangaId ->
			entityIdsByMangaId[mangaId] in pinnedEntityIds
		}
	}

	suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
		val resolvedEntityIds = ids.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		db.withTransaction {
			for (entityId in resolvedEntityIds) {
				db.getWorkFavouritesDao().delete(entityId)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToFavourites(resolvedEntityIds) }
	}

	suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
		val resolvedEntityIds = ids.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		db.withTransaction {
			for (entityId in resolvedEntityIds) {
				db.getWorkFavouritesDao().delete(entityId, categoryId)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToCategory(categoryId, resolvedEntityIds) }
	}

	private fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
		return db.getFavouriteCategoriesDao().observe(categoryId)
			.filterNotNull()
			.map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
			.distinctUntilChanged()
	}

	private fun observeWorkFavouriteContents(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	): Flow<List<Content>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			TABLE_ENTITY_GRAPH_BINDING,
			TABLE_ENTITY_PREFERENCES,
			TABLE_MANGA,
			TABLE_TAGS,
			TABLE_MANGA_TAGS,
			TABLE_WORK_HISTORY,
			"tracks",
			"local_index",
			emitInitialState = true,
		).mapLatest {
			buildWorkFavouriteContents(categoryId, order, filterOptions, limit)
		}.distinctUntilChanged()
	}

	private fun observeWorkFavouriteProjectionContents(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		spaceId: SpaceId?,
	): Flow<List<Content>> {
		return db.invalidationTracker.createFlow(
			TABLE_WORK_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			TABLE_ENTITY_GRAPH_BINDING,
			TABLE_ENTITY_PREFERENCES,
			TABLE_MANGA,
			TABLE_TAGS,
			TABLE_MANGA_TAGS,
			TABLE_WORK_HISTORY,
			"tracks",
			"local_index",
			emitInitialState = true,
		).mapLatest {
			buildWorkFavouriteProjectionContents(categoryId, order, filterOptions, limit, spaceId)
		}.distinctUntilChanged()
	}

	private suspend fun buildWorkFavouriteContents(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption> = emptySet(),
		limit: Int = Int.MAX_VALUE,
	): List<Content> {
		return workAggregateRepository.findFavouriteContents(
			categoryId = categoryId,
			order = order,
			filterOptions = filterOptions,
			limit = limit,
		)
	}

	private suspend fun buildWorkFavouriteProjectionContents(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption> = emptySet(),
		limit: Int = Int.MAX_VALUE,
		spaceId: SpaceId? = null,
	): List<Content> {
		return workAggregateRepository.findFavouriteAggregates(
			categoryId = categoryId,
			order = order,
			filterOptions = filterOptions,
			limit = limit,
			spaceId = spaceId,
		).flatMap { aggregate ->
			aggregate.projections
				.ifEmpty { listOfNotNull(aggregate.displayProjection) }
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

	private suspend fun buildWorkFavouriteCovers(
		categoryId: Long,
		order: ListSortOrder,
		limit: Int = Int.MAX_VALUE,
	): List<Cover> {
		return workAggregateRepository.findFavouriteContents(
			categoryId = categoryId,
			order = order,
			limit = limit,
		).map { content ->
				Cover(
					mangaId = content.id,
					url = content.coverUrl,
					source = content.source.name,
				)
			}
	}

	private suspend fun buildWorkFavouriteCategoryCountEntries(): List<FavouriteCategoryCountEntry> {
		return db.getWorkFavouritesDao().findActive()
			.mapNotNull { entry ->
				val content = resolveWorkFavouriteContent(entry) ?: return@mapNotNull null
				FavouriteCategoryCountEntry(
					mangaId = content.id,
					categoryId = entry.categoryId,
					source = content.source.name,
					isNsfw = content.isNsfw(),
				)
			}
	}

	private suspend fun buildWorkFavouriteCategoryIdsByFeedKey(): Map<String, Set<Long>> {
		val result = LinkedHashMap<String, LinkedHashSet<Long>>()
		for (entry in db.getWorkFavouritesDao().findActive()) {
			val content = resolveWorkFavouriteContent(entry) ?: continue
			result.getOrPut(content.feedLookupKey()) { linkedSetOf() } += entry.categoryId
		}
		return result
	}

	private suspend fun resolveWorkFavouriteContent(entry: WorkFavouriteEntity): Content? {
		entry.anchorMangaId?.let { anchorId ->
			db.getMangaDao().find(anchorId)?.toContent()?.let { return it }
		}
		val identity = workResolver.resolveByEntityId(entry.entityId)
		val candidateIds = buildList {
			identity?.preferredMangaId?.let(::add)
			identity?.localMangaIds.orEmpty().forEach(::add)
		}.distinct()
		for (mangaId in candidateIds) {
			db.getMangaDao().find(mangaId)?.toContent()?.let { return it }
		}
		return null
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

	private fun Content.feedLookupKey(): String {
		return "${source.name}|$url"
	}

	suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
		return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
			it.toFavouriteCategory()
		}
	}

	/**
	 * Ensures every legacy favourite projection is represented in EntityGraph before
	 * the work-favourite rows are read by the migration worker.
	 */
	suspend fun ensureLegacyFavouriteProjectionsForMigration() {
		val legacyEntries = db.getFavouritesDao().findAllActiveEntries()
		if (legacyEntries.isEmpty()) {
			return
		}
		val mangaIds = legacyEntries.map(FavouriteEntity::mangaId).distinct()
		val mangaById = db.getMangaDao()
			.findEntitiesByIds(mangaIds)
			.associateBy { it.id }
		val missingMangaIds = mangaIds.filterNot(mangaById::containsKey)
		if (missingMangaIds.isNotEmpty()) {
			Log.w(TAG, "Legacy favourite projections are missing from manga: $missingMangaIds")
		}
		selectLegacyFavouriteMangaIds(legacyEntries, mangaById.keys).forEach { mangaId ->
			workResolver.ensureForProjection(
				content = mangaById.getValue(mangaId).toContent(tags = emptySet(), chapters = null),
				provenance = WorkIdentityProvenance.MIGRATION,
			)
		}
	}

	suspend fun normalizeWorkFavouritesIfNeeded() {
		if (!settings.requiresWorkMigrationNormalization) {
			return
		}
		normalizeWorkFavourites(includeDeletedLegacyRows = true)
	}

	suspend fun normalizeWorkFavouritesForSync(): Boolean {
		if (settings.requiresWorkMigrationNormalization) {
			normalizeWorkFavourites(includeDeletedLegacyRows = false)
		}
		return true
	}

	private suspend fun normalizeWorkFavourites(includeDeletedLegacyRows: Boolean) {
		val localFavourites = if (includeDeletedLegacyRows) {
			db.getFavouritesDao().findAllEntriesIncludingDeleted()
		} else {
			db.getFavouritesDao().findAllActiveEntries()
		}
		if (localFavourites.isEmpty()) {
			return
		}
		val mangaById = db.getMangaDao()
			.findEntitiesByIds(localFavourites.map { it.mangaId }.distinct())
			.associateBy { it.id }
		val contentById = mangaById.mapValues { (_, manga) -> manga.toContent(tags = emptySet(), chapters = null) }
		val activeMangaIds = localFavourites
			.asSequence()
			.filter { it.deletedAt == 0L }
			.map(FavouriteEntity::mangaId)
			.toSet()
		val ensuredEntityIds = ensureMigrationWorkEntities(
			contentById.filterKeys(activeMangaIds::contains).values,
		)
		val existingEntityIds = resolveEntityIdsByMangaIds(localFavourites.map { it.mangaId })
		val entityIdsByMangaId = existingEntityIds + ensuredEntityIds
		val unresolvedActiveMangaIds = localFavourites
			.asSequence()
			.filter { it.deletedAt == 0L }
			.map(FavouriteEntity::mangaId)
			.distinct()
			.filterNot(entityIdsByMangaId::containsKey)
			.toList()
		if (unresolvedActiveMangaIds.isNotEmpty()) {
			Log.w(TAG, "Keeping legacy favourites with unresolved projections: $unresolvedActiveMangaIds")
		}
		val normalized = LinkedHashMap<WorkFavouriteNormalizationKey, WorkFavouriteEntity>()
		for (favourite in localFavourites) {
			val entityId = entityIdsByMangaId[favourite.mangaId] ?: continue
			val key = WorkFavouriteNormalizationKey(
				entityId = entityId,
				categoryId = favourite.categoryId,
			)
			val candidate = WorkFavouriteEntity(
				entityId = entityId,
				categoryId = favourite.categoryId,
				anchorMangaId = favourite.mangaId,
				sortKey = favourite.sortKey,
				isPinned = favourite.isPinned,
				createdAt = favourite.createdAt,
				deletedAt = favourite.deletedAt,
				updatedAt = favourite.updatedAt,
			)
			val existing = normalized[key]
			normalized[key] = if (existing == null) {
				candidate
			} else {
				mergeNormalizedWorkFavourite(existing, candidate)
			}
		}
		if (normalized.isEmpty() && unresolvedActiveMangaIds.isEmpty()) {
			db.withTransaction { db.getFavouritesDao().clear() }
			return
		}
		if (normalized.isEmpty()) {
			return
		}
		db.withTransaction {
			val workFavouritesDao = db.getWorkFavouritesDao()
			normalized.values.forEach { candidate ->
				val local = workFavouritesDao.find(candidate.entityId, candidate.categoryId)
				workFavouritesDao.upsert(
					if (local == null) {
						candidate
					} else {
						mergeNormalizedWorkFavourite(local, candidate)
					},
				)
			}
			if (unresolvedActiveMangaIds.isEmpty()) {
				db.getFavouritesDao().clear()
			}
		}
	}

	private suspend fun resolveEntityIdsByMangaIds(mangaIds: Collection<Long>): Map<Long, Long> {
		return workResolver.resolveManyByMangaIds(mangaIds)
			.mapValues { (_, identity) -> identity.entityId }
			.filterValues { it != null }
			.mapValues { (_, entityId) -> requireNotNull(entityId) }
	}

	private suspend fun ensureMigrationWorkEntities(contents: Collection<Content>): Map<Long, Long> {
		return contents.associate { content ->
			content.id to requireNotNull(
				workResolver.ensureForProjection(
					content = content,
					provenance = WorkIdentityProvenance.MIGRATION,
				).entityId,
			)
		}
	}

	private fun mergeNormalizedWorkFavourite(
		existing: WorkFavouriteEntity,
		candidate: WorkFavouriteEntity,
	): WorkFavouriteEntity {
		val merged = when {
			candidate.updatedAt > existing.updatedAt -> candidate.copy(
				createdAt = minOf(existing.createdAt, candidate.createdAt),
				isPinned = existing.isPinned || candidate.isPinned,
			)

			candidate.updatedAt == existing.updatedAt -> existing.copy(
				sortKey = maxOf(existing.sortKey, candidate.sortKey),
				isPinned = existing.isPinned || candidate.isPinned,
				createdAt = minOf(existing.createdAt, candidate.createdAt),
				deletedAt = minOf(existing.deletedAt, candidate.deletedAt),
			)

			else -> existing.copy(
				isPinned = existing.isPinned || candidate.isPinned,
				createdAt = minOf(existing.createdAt, candidate.createdAt),
			)
		}
		return stabilizeActiveWorkFavouriteAnchor(merged, existing, candidate)
	}

	private suspend fun recoverToFavourites(entityIds: Collection<Long>) {
		db.withTransaction {
			for (entityId in entityIds) {
				db.getWorkFavouritesDao().recover(entityId)
			}
		}
	}

	private suspend fun recoverToCategory(categoryId: Long, entityIds: Collection<Long>) {
		db.withTransaction {
			for (entityId in entityIds) {
				db.getWorkFavouritesDao().recover(entityId, categoryId)
			}
		}
	}

	private suspend fun findWorkCategoryIds(mangaId: Long): Set<Long> {
		val entityId = resolveFavouriteEntityId(mangaId) ?: return emptySet()
		return db.getWorkFavouritesDao().findCategoriesIds(entityId).toCollection(LinkedHashSet())
	}

	private suspend fun resolveFavouriteEntityId(mangaId: Long): Long? {
		return workResolver.resolveByMangaId(mangaId).entityId
	}

	private suspend fun resolveWorkAnchorContents(mangas: Collection<Content>): List<Content> {
		if (mangas.isEmpty()) return emptyList()
		val contentsById = mangas.associateBy { it.id }
		val identitiesByMangaId = workResolver.resolveManyByMangaIds(contentsById.keys)
		val localContents = LinkedHashMap<Long, Content>()
		val fallbackContents = LinkedHashMap<Long, Content>()
		mangas.forEach { content ->
			val identity = identitiesByMangaId[content.id]
			if (identity?.entityId == null) {
				fallbackContents.putIfAbsent(content.id, content)
				return@forEach
			}
			val preferredId = identity.preferredMangaId
			if (preferredId == null || preferredId == content.id) {
				localContents.putIfAbsent(content.id, content)
			} else {
				val preferred = contentsById[preferredId] ?: db.getMangaDao().find(preferredId)?.toContent()
				if (preferred != null) {
					localContents.putIfAbsent(preferred.id, preferred)
				} else {
					localContents.putIfAbsent(content.id, content)
				}
			}
		}
		return (localContents.values + fallbackContents.values.filterNot { it.id in localContents }).distinctBy { it.id }
	}
}
