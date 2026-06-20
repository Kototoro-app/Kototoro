package org.skepsun.kototoro.favourites.domain

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
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContentList
import org.skepsun.kototoro.core.db.entity.toContentTagsList
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.favourites.data.toContentList
import org.skepsun.kototoro.favourites.domain.model.Cover
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.search.domain.SearchKind
import javax.inject.Inject

@Reusable
class FavouritesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val localObserver: LocalFavoritesObserver,
	private val entityGraphRepository: EntityGraphRepository,
	private val settings: AppSettings,
) {

	private data class WorkFavouriteNormalizationKey(
		val entityId: Long,
		val categoryId: Long,
	)

	suspend fun getAllContent(): List<Content> {
		val entities = db.getFavouritesDao().findAll()
		return resolveWorkAnchorContents(entities.toContentList())
	}

	suspend fun getLastContent(limit: Int): List<Content> {
		val entities = db.getFavouritesDao().findLast(limit)
		return resolveWorkAnchorContents(entities.toContentList())
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Content> {
		val dao = db.getFavouritesDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
			SearchKind.ADVANCED -> dao.searchByTitle(q, limit)
		}
		return resolveWorkAnchorContents(entities.toContentList())
	}

	fun observeAll(order: ListSortOrder, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(order, filterOptions, limit)
			.map { it.toContentList() }
	}

	fun observeAllRawFavorites(): Flow<List<org.skepsun.kototoro.favourites.data.FavouriteContent>> {
		return db.getFavouritesDao().observeAll(ListSortOrder.NEWEST, emptySet(), Int.MAX_VALUE)
	}

	fun observeCategoryCountEntries(): Flow<List<org.skepsun.kototoro.favourites.data.FavouriteCategoryCountEntry>> {
		return db.getFavouritesDao().observeCategoryCountEntries()
	}

	suspend fun getContent(categoryId: Long): List<Content> {
		val entities = db.getFavouritesDao().findAll(categoryId)
		return resolveWorkAnchorContents(entities.toContentList())
	}

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Content>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(categoryId, order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit)
			.map { it.toContentList() }
	}

	fun observeAll(categoryId: Long, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
		return observeOrder(categoryId)
			.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }
	}

	fun observeContentCount(): Flow<Int> {
		return db.getFavouritesDao().observeContentCount()
			.distinctUntilChanged()
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
			TABLE_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val categories = db.getFavouriteCategoriesDao().findAll()
				val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
				for (entity in categories) {
					val cat = entity.toFavouriteCategory()
					res[cat] = db.getFavouritesDao().findCovers(
						categoryId = cat.id,
						order = cat.order,
					)
				}
				res
			}
		}.distinctUntilChanged()
	}

	suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
		return db.getFavouritesDao().findCovers(order, limit)
	}

	fun observeCategory(id: Long): Flow<FavouriteCategory?> {
		return db.getFavouriteCategoriesDao().observe(id)
			.map { it?.toFavouriteCategory() }
	}

	fun observeCategoriesIds(mangaId: Long): Flow<Set<Long>> {
		return db.getFavouritesDao().observeIds(mangaId).map { it.toSet() }
	}

	fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return db.getFavouritesDao().observeCategories(mangaId).map {
			it.mapTo(LinkedHashSet(it.size)) { x -> x.toFavouriteCategory() }
		}
	}

	fun observeCategoriesByWork(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return db.invalidationTracker.createFlow(
			TABLE_FAVOURITES,
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
		return db.getFavouritesDao().findCategoriesCount(mangaId) != 0
	}

	suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
		return db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
	}

	suspend fun getCategoriesIds(mangaIds: Collection<Long>): Map<Long, Set<Long>> {
		if (mangaIds.isEmpty()) return emptyMap()
		return db.getFavouritesDao().findCategoryMemberships(mangaIds.toList())
			.groupBy(
				keySelector = { it.mangaId },
				valueTransform = { it.categoryId },
			)
			.mapValues { (_, categoryIds) -> categoryIds.toCollection(LinkedHashSet()) }
			.let { grouped -> mangaIds.associateWith { grouped[it].orEmpty() } }
	}

	suspend fun isFavoriteByWork(mangaId: Long): Boolean {
		val entityId = resolveFavouriteEntityId(mangaId)
		if (entityId != null) {
			return db.getWorkFavouritesDao().findCategoriesCount(entityId) != 0
		}
		return resolveFavouriteAnchorIds(mangaId).any { anchorId ->
			db.getFavouritesDao().findCategoriesCount(anchorId) != 0
		}
	}

	suspend fun getCategoriesIdsByWork(mangaId: Long): Set<Long> {
		return findWorkCategoryIds(mangaId)
	}

	suspend fun findPopularSources(categoryId: Long, limit: Int): List<ContentSource> {
		return db.getFavouritesDao().run {
			if (categoryId == FavouriteCategory.NO_ID) {
				findPopularSources(limit)
			} else {
				findPopularSources(categoryId, limit)
			}
		}.toContentSources()
	}

	suspend fun findPopularTags(categoryId: Long, limit: Int): List<ContentTag> {
		val daoCategoryId = if (categoryId == FavouriteCategory.NO_ID) 0L else categoryId
		return db.getFavouritesDao().findPopularTags(daoCategoryId, limit).toContentTagsList()
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
				db.getFavouritesDao().deleteAll(id)
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
		db.withTransaction {
			val currentTime = System.currentTimeMillis()
			for (manga in resolveWorkAnchorContents(mangas)) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				val entity = FavouriteEntity(
					mangaId = manga.id,
					categoryId = categoryId,
					createdAt = currentTime,
					sortKey = 0,
					deletedAt = 0L,
					isPinned = false,
					updatedAt = currentTime,
				)
				db.getFavouritesDao().insert(entity)
				resolveFavouriteEntityId(manga.id)?.let { entityId ->
					db.getWorkFavouritesDao().upsert(
						WorkFavouriteEntity(
							entityId = entityId,
							categoryId = categoryId,
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
			db.getFavouritesDao().setPinned(mangaIds.toList(), isPinned)
			val entityIds = mangaIds.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
			if (entityIds.isNotEmpty()) {
				db.getWorkFavouritesDao().setPinned(entityIds.toList(), isPinned)
			}
		}
	}

	suspend fun isPinned(mangaIds: Collection<Long>): Boolean {
		if (mangaIds.isEmpty()) return false
		val entityIds = mangaIds.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		if (entityIds.isNotEmpty()) {
			return db.getWorkFavouritesDao().isPinned(entityIds.toList()) ?: false
		}
		return db.getFavouritesDao().isPinned(mangaIds.toList()) ?: false
	}

	suspend fun getPinnedIds(mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(mangaIds)
		val pinnedEntityIds = db.getWorkFavouritesDao().findPinnedEntityIds(entityIdsByMangaId.values.distinct())
		if (pinnedEntityIds.isNotEmpty()) {
			return mangaIds.filterTo(LinkedHashSet()) { mangaId ->
				entityIdsByMangaId[mangaId] in pinnedEntityIds
			}
		}
		return db.getFavouritesDao().findPinnedIds(mangaIds.toList()).toSet()
	}

	suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
		val resolvedIds = ids.flatMapTo(LinkedHashSet()) { resolveFavouriteAnchorIds(it) }
		val resolvedEntityIds = ids.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		db.withTransaction {
			for (entityId in resolvedEntityIds) {
				db.getWorkFavouritesDao().delete(entityId)
			}
			for (id in resolvedIds) {
				db.getFavouritesDao().delete(mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToFavourites(resolvedIds, resolvedEntityIds) }
	}

	suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
		val resolvedIds = ids.flatMapTo(LinkedHashSet()) { resolveFavouriteAnchorIds(it) }
		val resolvedEntityIds = ids.mapNotNullTo(LinkedHashSet()) { resolveFavouriteEntityId(it) }
		db.withTransaction {
			for (entityId in resolvedEntityIds) {
				db.getWorkFavouritesDao().delete(entityId, categoryId)
			}
			for (id in resolvedIds) {
				db.getFavouritesDao().delete(categoryId = categoryId, mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToCategory(categoryId, resolvedIds, resolvedEntityIds) }
	}

	private fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
		return db.getFavouriteCategoriesDao().observe(categoryId)
			.filterNotNull()
			.map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
			.distinctUntilChanged()
	}

	suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
		return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
			it.toFavouriteCategory()
		}
	}

	suspend fun normalizeWorkFavouritesIfNeeded() {
		if (!settings.requiresWorkMigrationNormalization && !hasWorkFavouriteDrift()) {
			return
		}
		normalizeWorkFavourites()
		settings.requiresWorkMigrationNormalization = false
	}

	suspend fun normalizeWorkFavouritesForSync() {
		normalizeWorkFavourites()
	}

	private suspend fun hasWorkFavouriteDrift(): Boolean {
		val localActiveCount = db.getFavouritesDao().countActive()
		return db.getWorkFavouritesDao().countActive() != localActiveCount
	}

	private suspend fun normalizeWorkFavourites() {
		val localFavourites = db.getFavouritesDao().findAllEntriesIncludingDeleted()
		if (localFavourites.isEmpty()) {
			return
		}
		val mangaById = db.getMangaDao()
			.findEntitiesByIds(localFavourites.map { it.mangaId }.distinct())
			.associateBy { it.id }
		val contentById = mangaById.mapValues { (_, manga) -> manga.toContent(tags = emptySet(), chapters = null) }
		val ensuredEntityIds = entityGraphRepository.ensureLocalWorkEntities(contentById.values)
		val existingEntityIds = entityGraphRepository.findEntityIdsByAnyMangaIds(localFavourites.map { it.mangaId })
		val entityIdsByMangaId = existingEntityIds + ensuredEntityIds
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
		}
	}

	private fun mergeNormalizedWorkFavourite(
		existing: WorkFavouriteEntity,
		candidate: WorkFavouriteEntity,
	): WorkFavouriteEntity {
		return when {
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
	}

	private suspend fun recoverToFavourites(ids: Collection<Long>, entityIds: Collection<Long>) {
		db.withTransaction {
			for (entityId in entityIds) {
				db.getWorkFavouritesDao().recover(entityId)
			}
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id)
			}
		}
	}

	private suspend fun recoverToCategory(categoryId: Long, ids: Collection<Long>, entityIds: Collection<Long>) {
		db.withTransaction {
			for (entityId in entityIds) {
				db.getWorkFavouritesDao().recover(entityId, categoryId)
			}
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id, categoryId = categoryId)
			}
		}
	}

	private suspend fun findWorkCategoryIds(mangaId: Long): Set<Long> {
		val categoryIds = LinkedHashSet<Long>()
		resolveFavouriteEntityId(mangaId)?.let { entityId ->
			categoryIds += db.getWorkFavouritesDao().findCategoriesIds(entityId)
		}
		resolveFavouriteAnchorIds(mangaId).forEach { anchorId ->
			categoryIds += db.getFavouritesDao().findCategoriesIds(anchorId)
		}
		return categoryIds
	}

	private suspend fun resolveFavouriteEntityId(mangaId: Long): Long? {
		return entityGraphRepository.findEntityIdsByAnyMangaIds(setOf(mangaId))[mangaId]
	}

	private suspend fun resolveFavouriteAnchorIds(mangaId: Long): Set<Long> {
		val entityId = entityGraphRepository.findEntityIdsByAnyMangaIds(setOf(mangaId))[mangaId] ?: return setOf(mangaId)
		val preferredLocalId = db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
		val localIds = entityGraphRepository.getBindings(entityId)
			.asSequence()
			.mapNotNull { binding ->
				when (binding.source) {
					"local_manga", "0" -> binding.externalId.toLongOrNull()
					else -> null
				}
			}
			.toCollection(LinkedHashSet())
		preferredLocalId?.let(localIds::add)
		return if (localIds.isEmpty()) setOf(mangaId) else localIds
	}

	private suspend fun resolveWorkAnchorContents(mangas: Collection<Content>): List<Content> {
		if (mangas.isEmpty()) return emptyList()
		val contentsById = mangas.associateBy { it.id }
		val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(contentsById.keys)
		val preferredLocalIdsByEntity = db.getEntityGraphDao()
			.findEntityPrefsByIds(entityIdsByMangaId.values.distinct())
			.associate { it.entityId to it.preferredLocalMangaId }
		val localContents = LinkedHashMap<Long, Content>()
		val fallbackContents = LinkedHashMap<Long, Content>()
		mangas.forEach { content ->
			val entityId = entityIdsByMangaId[content.id]
			if (entityId == null) {
				fallbackContents.putIfAbsent(content.id, content)
				return@forEach
			}
			val preferredId = preferredLocalIdsByEntity[entityId]
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
