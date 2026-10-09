package org.skepsun.kototoro.favourites.domain

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.parser.StoredContentIdentityResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryCountEntry
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.toFavouriteCategory
import org.skepsun.kototoro.favourites.domain.model.Cover
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.LocalContentSearchQuery
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.tracker.domain.SourceTrackerEvent
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventEmitter
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
    private val settings: AppSettings,
    private val sourceTrackerEvents: SourceTrackerEventEmitter,
    private val storedContentIdentityResolver: StoredContentIdentityResolver,
) {

    suspend fun getAllContent(): List<Content> {
        val entries = db.getFavouritesDao().findAllActiveEntries()
        if (entries.isEmpty()) return emptyList()
        val mangaIds = entries.map { it.mangaId }.distinct()
        val contentsById = db.getMangaDao().findWithTagsByIds(mangaIds)
            .associate { it.manga.id to it.toContent() }
        return mangaIds.mapNotNull { contentsById[it] }
    }

    suspend fun getLastContent(limit: Int): List<Content> {
        if (limit <= 0) return emptyList()
        val entries = db.getFavouritesDao().findActiveNewest(limit)
        if (entries.isEmpty()) return emptyList()
        val mangaIds = entries.map { it.mangaId }.distinct()
        val contentsById = db.getMangaDao().findWithTagsByIds(mangaIds)
            .associate { it.manga.id to it.toContent() }
        return mangaIds.mapNotNull { contentsById[it] }
    }

    suspend fun search(
        query: String,
        kind: SearchKind,
        limit: Int,
        advanced: AdvancedSearchParams? = null,
    ): List<Content> {
        val searchQuery = LocalContentSearchQuery(query, kind, advanced)
        if (limit <= 0 || !searchQuery.hasCriteria) return emptyList()
        return searchQuery.search(getAllContent(), limit)
    }

    fun observeAll(order: ListSortOrder, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
        return observeFavouriteContents(FavouriteCategory.NO_ID, order, filterOptions, limit)
    }

    fun observeAllContents(
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int,
        spaceId: SpaceId? = null,
    ): Flow<List<Content>> {
        return observeFavouriteContents(FavouriteCategory.NO_ID, order, filterOptions, limit)
    }

    fun observeFeedCategoryIds(): Flow<Map<String, Set<Long>>> {
        return db.invalidationTracker.createFlow(
            TABLE_FAVOURITES,
            TABLE_MANGA,
            emitInitialState = true,
        ).mapLatest {
            buildFavouriteCategoryIdsByFeedKey()
        }.distinctUntilChanged()
    }

    fun observeCategoryCountEntries(): Flow<List<FavouriteCategoryCountEntry>> {
        return db.getFavouritesDao().observeCategoryCountEntries().distinctUntilChanged()
    }

    suspend fun getContent(categoryId: Long): List<Content> {
        return buildFavouriteContents(categoryId = categoryId, order = ListSortOrder.NEWEST)
    }

    suspend fun findSearchCategories(mangaIds: List<Long>): List<FavouriteSearchMatch> {
        val memberships = mangaIds.distinct().chunked(500).flatMap {
            db.getFavouritesDao().findAllActiveByMangaIds(it)
        }.groupBy { it.categoryId }
        return observeCategories().first().mapNotNull { category ->
            val members = memberships[category.id]?.mapTo(HashSet()) { it.mangaId } ?: return@mapNotNull null
            FavouriteSearchMatch(category, mangaIds.distinct().filter { it in members })
        }
    }

    fun observeAll(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int
    ): Flow<List<Content>> {
        return observeFavouriteContents(categoryId, order, filterOptions, limit)
    }

    fun observeAllContents(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int,
        spaceId: SpaceId? = null,
    ): Flow<List<Content>> {
        return observeFavouriteContents(categoryId, order, filterOptions, limit)
    }

    fun observeAll(categoryId: Long, filterOptions: Set<ListFilterOption>, limit: Int): Flow<List<Content>> {
        return observeOrder(categoryId)
            .flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }
    }

    fun observeAllContents(
        categoryId: Long,
        filterOptions: Set<ListFilterOption>,
        limit: Int,
        spaceId: SpaceId? = null,
    ): Flow<List<Content>> {
        return observeOrder(categoryId)
            .flatMapLatest { order -> observeAllContents(categoryId, order, filterOptions, limit, spaceId) }
    }

    fun observeContentCount(): Flow<Int> {
        return db.getFavouritesDao().observeCountActive().distinctUntilChanged()
    }

    fun observeFavouriteBadgeChanges(): Flow<Unit> {
        return db.invalidationTracker.createFlow(
            TABLE_FAVOURITES,
            TABLE_PREFERENCES,
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
            TABLE_FAVOURITES,
            TABLE_FAVOURITE_CATEGORIES,
            TABLE_MANGA,
            TABLE_HISTORY,
            "tracks",
            emitInitialState = true,
        ).mapLatest {
            db.withTransaction {
                val categories = db.getFavouriteCategoriesDao().findAll()
                val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
                for (entity in categories) {
                    val cat = entity.toFavouriteCategory()
                    res[cat] = buildFavouriteCovers(
                        categoryId = cat.id,
                        order = cat.order,
                    )
                }
                res
            }
        }.distinctUntilChanged()
    }

    suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
        return buildFavouriteCovers(
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
            TABLE_FAVOURITES,
            emitInitialState = true,
        ).mapLatest {
            getCategoriesIds(mangaId)
        }.distinctUntilChanged()
    }

    fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
        return db.invalidationTracker.createFlow(
            TABLE_FAVOURITES,
            TABLE_FAVOURITE_CATEGORIES,
            emitInitialState = true,
        ).mapLatest {
            db.getFavouritesDao().findCategories(mangaId).mapNotNullTo(LinkedHashSet()) { categoryId ->
                db.getFavouriteCategoriesDao().find(categoryId.toInt())?.toFavouriteCategory()
            }
        }.distinctUntilChanged()
    }

    fun observeCategoriesByWork(mangaId: Long): Flow<Set<FavouriteCategory>> = observeCategories(mangaId)

    suspend fun getCategory(id: Long): FavouriteCategory {
        return db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()
    }

    suspend fun findCategoryByTitle(title: String): FavouriteCategory? {
        return db.getFavouriteCategoriesDao().findAll()
            .firstOrNull { it.title == title }
            ?.toFavouriteCategory()
    }

    suspend fun isFavorite(mangaId: Long): Boolean {
        return db.getFavouritesDao().countCategories(mangaId) != 0
    }

    suspend fun isFavoriteByWork(mangaId: Long): Boolean = isFavorite(mangaId)

    suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
        return db.getFavouritesDao().findCategories(mangaId).toCollection(LinkedHashSet())
    }

    suspend fun getCategoriesIdsByWork(mangaId: Long): Set<Long> = getCategoriesIds(mangaId)

    suspend fun getCategoriesIds(mangaIds: Collection<Long>): Map<Long, Set<Long>> {
        if (mangaIds.isEmpty()) return emptyMap()
        val distinctIds = mangaIds.distinct()
        val memberships = db.getFavouritesDao().findAllActiveByMangaIds(distinctIds)
        val byMangaId = memberships.groupBy({ it.mangaId }, { it.categoryId })
        return distinctIds.associateWith { mangaId ->
            byMangaId[mangaId]?.toCollection(LinkedHashSet()).orEmpty()
        }
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
        return entity.toFavouriteCategory(id)
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
                db.getFavouritesDao().deleteAll(id)
                db.getFavouriteCategoriesDao().delete(id)
            }
            db.getChaptersDao().gc()
        }
    }

    suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
        db.getFavouriteCategoriesDao().updateOrder(id, order.name)
    }

    suspend fun reorderCategory(categoryId: Long, orderedIds: List<Long>) {
        require(categoryId != FavouriteCategory.NO_ID)
        db.withTransaction {
            val dao = db.getFavouritesDao()
            val entries = dao.findActive(categoryId).sortedWith(compareBy<FavouriteEntity> { it.sortKey }.thenBy { it.mangaId })
            val byId = entries.associateBy { it.mangaId }
            val ids = org.skepsun.kototoro.core.util.mergeManualOrder(entries.map { it.mangaId }, orderedIds)
            val now = maxOf(System.currentTimeMillis(), (entries.maxOfOrNull { it.updatedAt } ?: 0L) + 1)
            dao.upsert(ids.mapIndexed { index, id -> byId.getValue(id).copy(sortKey = index, updatedAt = now) })
            setCategoryOrder(categoryId, ListSortOrder.MANUAL)
        }
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
        if (mangas.isEmpty()) return
        db.withTransaction {
            val currentTime = System.currentTimeMillis()
            val existing = db.getFavouritesDao().findActive(categoryId).associateBy { it.mangaId }
            val addedIds = existing.keys.toMutableSet()
            var nextSortKey = (existing.values.maxOfOrNull { it.sortKey } ?: -1) + 1
            for (manga in mangas) {
                val stored = storedContentIdentityResolver.preserveStoredRemoteIdentity(manga)
                val tags = stored.tags.toEntities()
                db.getTagsDao().upsert(tags)
                db.getMangaDao().upsert(stored.toEntity(), tags)
                if (!addedIds.add(stored.id)) continue
                db.getFavouritesDao().upsert(
                    FavouriteEntity(
                        mangaId = stored.id,
                        categoryId = categoryId,
                        createdAt = currentTime,
                        sortKey = nextSortKey++,
                        deletedAt = 0L,
                        isPinned = false,
                        updatedAt = currentTime,
                    ),
                )
            }
        }
        emitFavoriteAdded(mangas)
    }

    suspend fun addToCategoryAsSeparateWorks(categoryId: Long, mangas: Collection<Content>) {
        addToCategory(categoryId, mangas)
    }

    suspend fun setPinned(mangaIds: Collection<Long>, isPinned: Boolean) {
        if (mangaIds.isEmpty()) return
        db.getFavouritesDao().setPinned(mangaIds.toList(), isPinned)
    }

    suspend fun isPinned(mangaIds: Collection<Long>): Boolean {
        if (mangaIds.isEmpty()) return false
        return db.getFavouritesDao().isPinned(mangaIds.toList()) == true
    }

    suspend fun getPinnedIds(mangaIds: Collection<Long>): Set<Long> {
        if (mangaIds.isEmpty()) return emptySet()
        return db.getFavouritesDao().findPinnedIds(mangaIds.toList()).toSet()
    }

    suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().delete(id)
            }
            db.getChaptersDao().gc()
        }
        emitUnfavoriteRemoved(ids)
        return ReversibleHandle { recoverToFavourites(ids) }
    }

    suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
        db.withTransaction {
            for (id in ids) {
                db.getFavouritesDao().delete(id, categoryId)
            }
            db.getChaptersDao().gc()
        }
        emitUnfavoriteIfLastCategory(ids)
        return ReversibleHandle { recoverToCategory(categoryId, ids) }
    }

    private fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
        return db.getFavouriteCategoriesDao().observe(categoryId)
            .filterNotNull()
            .map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
            .distinctUntilChanged()
    }

    private fun observeFavouriteContents(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption>,
        limit: Int,
    ): Flow<List<Content>> {
        return db.invalidationTracker.createFlow(
            TABLE_FAVOURITES,
            TABLE_FAVOURITE_CATEGORIES,
            TABLE_MANGA,
            TABLE_TAGS,
            TABLE_MANGA_TAGS,
            TABLE_PREFERENCES,
            "tracks",
            "local_index",
            emitInitialState = true,
        ).mapLatest {
            buildFavouriteContents(categoryId, order, filterOptions, limit)
        }.distinctUntilChanged()
    }

    private suspend fun buildFavouriteContents(
        categoryId: Long,
        order: ListSortOrder,
        filterOptions: Set<ListFilterOption> = emptySet(),
        limit: Int = Int.MAX_VALUE,
    ): List<Content> {
        val entries = if (categoryId == FavouriteCategory.NO_ID) {
            db.getFavouritesDao().findAllActiveEntries()
        } else {
            db.getFavouritesDao().findActive(categoryId)
        }
        if (entries.isEmpty()) return emptyList()
        val mangaIds = entries.map { it.mangaId }.distinct()
        val contentsById = db.getMangaDao().findWithTagsByIds(mangaIds)
            .associate { it.manga.id to it.toContent() }
        val pinnedIds = db.getFavouritesDao().findPinnedIds(mangaIds).toSet()

        val comparator = compareByDescending<Content> { it.id in pinnedIds }
            .thenBy { it.title }

        return mangaIds.mapNotNull { contentsById[it] }
            .filter { matchesFavouriteFilters(it, filterOptions) }
            .sortedWith(comparator)
            .take(limit)
    }

    private suspend fun buildFavouriteCovers(
        categoryId: Long,
        order: ListSortOrder,
        limit: Int = Int.MAX_VALUE,
    ): List<Cover> {
        return buildFavouriteContents(
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

    @VisibleForTesting
    internal suspend fun buildFavouriteCategoryIdsByFeedKey(): Map<String, Set<Long>> {
        val entries = db.getFavouritesDao().findAllActiveEntries()
        if (entries.isEmpty()) {
            return emptyMap()
        }
        val mangaDao = db.getMangaDao()
        val mangaIds = entries.map { it.mangaId }.distinct()
        val mangaById = mangaDao.findEntitiesByIds(mangaIds).associateBy(MangaEntity::id)

        val result = LinkedHashMap<String, LinkedHashSet<Long>>()
        for (entry in entries) {
            result.getOrPut("manga:${entry.mangaId}") { linkedSetOf() } += entry.categoryId
            mangaById[entry.mangaId]?.feedLookupKey()?.let { key ->
                result.getOrPut(key) { linkedSetOf() } += entry.categoryId
            }
        }
        return result
    }

    private fun matchesFavouriteFilters(
        content: Content,
        filterOptions: Set<ListFilterOption>,
    ): Boolean {
        return filterOptions.all { option ->
            when (option) {
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

    private fun MangaEntity.feedLookupKey(): String {
        return "$source|$url"
    }

    suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
        return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
            it.toFavouriteCategory()
        }
    }


    private suspend fun recoverToFavourites(mangaIds: Collection<Long>) {
        db.withTransaction {
            for (id in mangaIds) {
                db.getFavouritesDao().recover(id)
            }
        }
    }

    private suspend fun recoverToCategory(categoryId: Long, mangaIds: Collection<Long>) {
        db.withTransaction {
            for (id in mangaIds) {
                db.getFavouritesDao().recover(id, categoryId)
            }
        }
    }

    private fun emitFavoriteAdded(contents: Collection<Content>) {
        for (content in contents) {
            emitSourceTrackerEvent(
                SourceTrackerEvent.Favorite(
                    contentId = content.id,
                    sourceKey = content.source.name,
                    added = true,
                    contentUrl = content.eventUrl(),
                ),
            )
        }
    }

    private suspend fun emitUnfavoriteRemoved(mangaIds: Collection<Long>) {
        for (mangaId in mangaIds) {
            val content = db.getMangaDao().find(mangaId)?.toContent() ?: continue
            emitSourceTrackerEvent(
                SourceTrackerEvent.Unfavorite(
                    contentId = content.id,
                    sourceKey = content.source.name,
                    contentUrl = content.eventUrl(),
                ),
            )
        }
    }

    private suspend fun emitUnfavoriteIfLastCategory(mangaIds: Collection<Long>) {
        for (mangaId in mangaIds) {
            val content = db.getMangaDao().find(mangaId)?.toContent() ?: continue
            if (db.getFavouritesDao().countCategories(mangaId) != 0) {
                continue
            }
            emitSourceTrackerEvent(
                SourceTrackerEvent.Unfavorite(
                    contentId = content.id,
                    sourceKey = content.source.name,
                    contentUrl = content.eventUrl(),
                ),
            )
        }
    }

    private fun emitSourceTrackerEvent(event: SourceTrackerEvent) {
        try {
            sourceTrackerEvents.emit(event)
        } catch (e: Exception) {
            // Local write already succeeded; a broken emitter must not surface to the caller.
        }
    }

    private fun Content.eventUrl(): String? {
        return publicUrl.takeIf { it.isNotBlank() } ?: url.takeIf { it.isNotBlank() }
    }
}
