@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.skepsun.kototoro.favourites.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class FavouritesDao {

    @Transaction
    @Query(
        """
        SELECT f.* FROM favourites f
        INNER JOIN favourite_categories fc ON fc.category_id = f.category_id
        WHERE fc.deleted_at = 0 AND f.deleted_at = 0
        ORDER BY f.updated_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun findAllWithActiveCategory(offset: Int, limit: Int): List<FavouriteContent>

    fun dump(): Flow<FavouriteContent> = flow {
        val window = 20
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAllWithActiveCategory(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }

    @Query("SELECT * FROM favourites ORDER BY created_at DESC")
    abstract suspend fun findAllEntriesIncludingDeleted(): List<FavouriteEntity>

    @Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC")
    abstract suspend fun findAllActiveEntries(): List<FavouriteEntity>

    @Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC LIMIT :limit")
    abstract suspend fun findActiveNewest(limit: Int): List<FavouriteEntity>

    @Query("SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0")
    abstract suspend fun findActive(categoryId: Long): List<FavouriteEntity>

    @Query("SELECT * FROM favourites WHERE manga_id IN (:mangaIds) AND deleted_at = 0")
    abstract suspend fun findAllActiveByMangaIds(mangaIds: Collection<Long>): List<FavouriteEntity>

    @Query("SELECT COUNT(*) FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
    abstract suspend fun countCategories(mangaId: Long): Int

    @Query("SELECT COUNT(*) FROM favourites WHERE deleted_at = 0")
    abstract fun observeCountActive(): Flow<Int>

    @Query("SELECT * FROM favourites WHERE manga_id = :mangaId AND category_id = :categoryId LIMIT 1")
    abstract suspend fun find(mangaId: Long, categoryId: Long): FavouriteEntity?

    @Query("SELECT * FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
    abstract suspend fun findActiveByMangaId(mangaId: Long): List<FavouriteEntity>

    @Query("SELECT * FROM favourites WHERE manga_id = :mangaId")
    abstract suspend fun findByMangaId(mangaId: Long): List<FavouriteEntity>

    @Query("SELECT category_id FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
    abstract suspend fun findCategories(mangaId: Long): List<Long>

    @Query("SELECT category_id FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
    abstract fun observeCategories(mangaId: Long): Flow<List<Long>>

    @Query(
        """
        SELECT
            f.manga_id AS manga_id,
            f.category_id AS category_id,
            m.source AS source,
            m.nsfw AS nsfw
        FROM favourites f
        INNER JOIN manga m ON m.manga_id = f.manga_id
        WHERE f.deleted_at = 0
        """,
    )
    abstract fun observeCategoryCountEntries(): Flow<List<FavouriteCategoryCountEntry>>

    @Upsert
    abstract suspend fun upsert(entity: FavouriteEntity)

    @Upsert
    abstract suspend fun upsert(entities: List<FavouriteEntity>)

    suspend fun delete(mangaId: Long) {
        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        setDeletedAt(mangaId = mangaId, deletedAt = currentTime)
        setUpdatedAt(mangaId = mangaId, updatedAt = currentTime)
    }

    suspend fun delete(mangaId: Long, categoryId: Long) {
        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        setDeletedAt(categoryId = categoryId, mangaId = mangaId, deletedAt = currentTime)
        setUpdatedAt(categoryId = categoryId, mangaId = mangaId, updatedAt = currentTime)
    }

    suspend fun deleteAll(categoryId: Long) {
        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        setDeletedAtAll(categoryId = categoryId, deletedAt = currentTime)
        setUpdatedAtAll(categoryId = categoryId, updatedAt = currentTime)
    }

    suspend fun recover(mangaId: Long) {
        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        setDeletedAt(mangaId = mangaId, deletedAt = 0L)
        setUpdatedAt(mangaId = mangaId, updatedAt = currentTime)
    }

    suspend fun recover(categoryId: Long, mangaId: Long) {
        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        setDeletedAt(categoryId = categoryId, mangaId = mangaId, deletedAt = 0L)
        setUpdatedAt(categoryId = categoryId, mangaId = mangaId, updatedAt = currentTime)
    }

    @Query("DELETE FROM favourites")
    abstract suspend fun clear()

    @Query("DELETE FROM favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
    abstract suspend fun gc(maxDeletionTime: Long)

    @Query("UPDATE favourites SET pinned = :isPinned WHERE manga_id IN (:mangaIds)")
    abstract suspend fun setPinned(mangaIds: List<Long>, isPinned: Boolean)

    @Query("SELECT MAX(pinned) FROM favourites WHERE manga_id IN (:mangaIds)")
    abstract suspend fun isPinned(mangaIds: List<Long>): Boolean?

    @Query("SELECT DISTINCT manga_id FROM favourites WHERE manga_id IN (:mangaIds) AND pinned = 1 AND deleted_at = 0")
    abstract suspend fun findPinnedIds(mangaIds: List<Long>): List<Long>

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId")
    protected abstract suspend fun setDeletedAt(mangaId: Long, deletedAt: Long)

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND category_id = :categoryId")
    protected abstract suspend fun setDeletedAt(categoryId: Long, mangaId: Long, deletedAt: Long)

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE category_id = :categoryId AND deleted_at = 0")
    protected abstract suspend fun setDeletedAtAll(categoryId: Long, deletedAt: Long)

    @Query("UPDATE favourites SET updated_at = :updatedAt WHERE manga_id = :mangaId")
    protected abstract suspend fun setUpdatedAt(mangaId: Long, updatedAt: Long)

    @Query("UPDATE favourites SET updated_at = :updatedAt WHERE manga_id = :mangaId AND category_id = :categoryId")
    protected abstract suspend fun setUpdatedAt(categoryId: Long, mangaId: Long, updatedAt: Long)

    @Query("UPDATE favourites SET updated_at = :updatedAt WHERE category_id = :categoryId AND deleted_at = 0")
    protected abstract suspend fun setUpdatedAtAll(categoryId: Long, updatedAt: Long)
}
