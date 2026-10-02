@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.skepsun.kototoro.history.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class HistoryDao {

    @Transaction
    @Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun findAll(offset: Int, limit: Int): List<HistoryWithContent>

    fun dump(): Flow<HistoryWithContent> = flow {
        val window = 20
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAll(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }

    @Transaction
    @Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
    abstract suspend fun findRecent(limit: Int): List<HistoryWithContent>

    @Transaction
    @Query(
        """
        SELECT history.* FROM history
        INNER JOIN manga ON manga.manga_id = history.manga_id
        WHERE history.deleted_at = 0
            AND manga.content_type IN (:allowedTypes)
        ORDER BY history.updated_at DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun findRecentForSpace(
        allowedTypes: Collection<String>,
        limit: Int,
    ): List<HistoryWithContent>

    @Transaction
    @Query(
        """
        SELECT history.* FROM history
        INNER JOIN manga ON manga.manga_id = history.manga_id
        WHERE history.deleted_at = 0
            AND manga.content_type IN (:allowedTypes)
            AND manga.source IN (:allowedSources)
        ORDER BY history.updated_at DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun findRecentForSpaceAndSources(
        allowedTypes: Collection<String>,
        allowedSources: Collection<String>,
        limit: Int,
    ): List<HistoryWithContent>

    @Query("SELECT manga_id FROM history WHERE deleted_at = 0")
    abstract suspend fun findActiveMangaIds(): List<Long>

    @Query("SELECT COUNT(*) FROM history WHERE deleted_at = 0")
    abstract fun observeCountActive(): Flow<Int>

    @Query("SELECT * FROM history ORDER BY updated_at DESC")
    abstract suspend fun findAllEntriesIncludingDeleted(): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE manga_id = :mangaId AND deleted_at = 0 LIMIT 1")
    abstract suspend fun find(mangaId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE manga_id IN (:mangaIds) AND deleted_at = 0")
    abstract suspend fun findAllByMangaIds(mangaIds: Collection<Long>): List<HistoryEntity>

    @Query("SELECT manga_id, percent, chapters FROM history WHERE manga_id IN (:mangaIds) AND deleted_at = 0")
    abstract suspend fun findProgress(mangaIds: List<Long>): List<HistoryProgressEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: HistoryEntity): Long

    @Query(
        "UPDATE history SET page = :page, chapter_id = :chapterId, scroll = :scroll, percent = :percent, updated_at = :updatedAt, chapters = :chapters, parent_chapter_id = :parentChapterId, deleted_at = 0 WHERE manga_id = :mangaId",
    )
    abstract suspend fun update(
        mangaId: Long,
        page: Int,
        chapterId: Long,
        scroll: Float,
        percent: Float,
        chapters: Int,
        updatedAt: Long,
        parentChapterId: Long?,
    ): Int

    @Query(
        "UPDATE history SET page = :page, chapter_id = :chapterId, scroll = :scroll, percent = :percent, updated_at = :updatedAt, chapters = :chapters, parent_chapter_id = :parentChapterId, deleted_at = :deletedAt WHERE manga_id = :mangaId",
    )
    abstract suspend fun updateRaw(
        mangaId: Long,
        page: Int,
        chapterId: Long,
        scroll: Float,
        percent: Float,
        chapters: Int,
        updatedAt: Long,
        parentChapterId: Long?,
        deletedAt: Long,
    ): Int

    suspend fun upsertSync(entity: HistoryEntity): Boolean {
        val updated = updateRaw(
            mangaId = entity.mangaId,
            page = entity.page,
            chapterId = entity.chapterId,
            scroll = entity.scroll,
            percent = entity.percent,
            chapters = entity.chaptersCount,
            updatedAt = entity.updatedAt,
            parentChapterId = entity.parentChapterId,
            deletedAt = entity.deletedAt,
        )
        return if (updated == 0) {
            insert(entity)
            true
        } else {
            false
        }
    }

    /**
     * Projects an entity onto the "live" update statement above, which always clears
     * `deleted_at` — the semantics of an ordinary history write. Sync restores use
     * [upsertSync] instead because they must preserve tombstones.
     */
    suspend fun update(entity: HistoryEntity): Int = update(
        mangaId = entity.mangaId,
        page = entity.page,
        chapterId = entity.chapterId,
        scroll = entity.scroll,
        percent = entity.percent,
        chapters = entity.chaptersCount,
        updatedAt = entity.updatedAt,
        parentChapterId = entity.parentChapterId,
    )

    @Transaction
    open suspend fun upsert(entity: HistoryEntity): Boolean {
        return if (update(entity) == 0) {
            insert(entity)
            true
        } else {
            false
        }
    }

    @Transaction
    open suspend fun upsert(entities: Iterable<HistoryEntity>) {
        for (entity in entities) {
            if (update(entity) == 0) {
                insert(entity)
            }
        }
    }

    suspend fun delete(mangaId: Long) = setDeletedAt(mangaId, kotlin.time.Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE history SET deleted_at = 0, updated_at = :updatedAt WHERE manga_id = :mangaId")
    protected abstract suspend fun recoverAt(mangaId: Long, updatedAt: Long)

    suspend fun recover(mangaId: Long) = recoverAt(mangaId, kotlin.time.Clock.System.now().toEpochMilliseconds())

    suspend fun deleteAfter(minDate: Long) = setDeletedAtAfter(minDate, kotlin.time.Clock.System.now().toEpochMilliseconds())

    @Query(
        """
        UPDATE history
        SET deleted_at = :deletedAt,
            updated_at = :deletedAt
        WHERE deleted_at = 0
            AND NOT EXISTS (
                SELECT 1
                FROM favourites
                WHERE favourites.manga_id = history.manga_id
                    AND favourites.deleted_at = 0
            )
        """,
    )
    protected abstract suspend fun setDeletedAtNotFavorite(deletedAt: Long)

    suspend fun deleteNotFavorite() = setDeletedAtNotFavorite(kotlin.time.Clock.System.now().toEpochMilliseconds())

    suspend fun clear() = setDeletedAtAfter(0L, kotlin.time.Clock.System.now().toEpochMilliseconds())

    @Query("DELETE FROM history WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
    abstract suspend fun gc(maxDeletionTime: Long)

    @Query("UPDATE history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE manga_id = :mangaId")
    protected abstract suspend fun setDeletedAt(mangaId: Long, deletedAt: Long)

    @Query("UPDATE history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE created_at >= :minDate AND deleted_at = 0")
    protected abstract suspend fun setDeletedAtAfter(minDate: Long, deletedAt: Long)
}
