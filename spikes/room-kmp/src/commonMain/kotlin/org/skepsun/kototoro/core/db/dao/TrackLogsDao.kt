package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaQueryBuilder
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.tracker.data.TrackLogEntity

@Dao
abstract class TrackLogsDao : MangaQueryBuilder.ConditionCallback {

    @Query("SELECT COUNT(*) FROM track_logs WHERE unread = 1")
    abstract fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM track_logs ORDER BY created_at DESC")
    abstract suspend fun dump(): List<TrackLogEntity>

    @Query(
        """
        SELECT *
        FROM track_logs
        WHERE manga_id = :mangaId
            AND chapters = :chapters
            AND created_at = :createdAt
        LIMIT 1
        """,
    )
    abstract suspend fun findDuplicate(
        mangaId: Long,
        chapters: String,
        createdAt: Long,
    ): TrackLogEntity?

    @Query("DELETE FROM track_logs")
    abstract suspend fun clear()

    @Query(
        """
        DELETE FROM track_logs
        WHERE NOT EXISTS (
            SELECT 1
            FROM manga
            WHERE manga.manga_id = track_logs.manga_id
        )
        """,
    )
    abstract suspend fun deleteOrphans()

    @Query("UPDATE track_logs SET unread = 0 WHERE id = :id")
    abstract suspend fun markAsRead(id: Long)

    @Query("UPDATE track_logs SET unread = 0 WHERE manga_id = :ownerId AND unread = 1")
    abstract suspend fun markUnreadAsReadByOwner(ownerId: Long)

    @Query("SELECT DISTINCT manga_id FROM track_logs WHERE unread = 1 AND manga_id IN (:ownerIds)")
    abstract suspend fun findUnreadOwnerIds(ownerIds: List<Long>): List<Long>

    @Query("SELECT * FROM track_logs WHERE id = :id LIMIT 1")
    abstract suspend fun find(id: Long): TrackLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: TrackLogEntity): Long

    @Query(
        """
        DELETE FROM track_logs
        WHERE NOT EXISTS (
            SELECT 1
            FROM tracks
            WHERE tracks.manga_id = track_logs.manga_id
        )
        """,
    )
    abstract suspend fun gc()

    @Query("DELETE FROM track_logs WHERE id NOT IN (SELECT id FROM track_logs ORDER BY created_at DESC LIMIT :size)")
    abstract suspend fun trim(size: Int)

    @Query("SELECT COUNT(*) FROM track_logs")
    abstract suspend fun count(): Int

    @Query(
        """
        INSERT INTO track_logs(manga_id, chapters, created_at, unread)
        SELECT tracks.manga_id,
            CASE
                WHEN tracks.chapters_new > 1 THEN 'New chapters x ' || tracks.chapters_new
                ELSE 'New chapters'
            END,
            MAX(tracks.last_chapter_date, tracks.last_check_time, 0),
            1
        FROM tracks
        WHERE tracks.chapters_new > 0
            AND NOT EXISTS (
                SELECT 1
                FROM track_logs
                WHERE track_logs.manga_id = tracks.manga_id
            )
        """,
    )
    abstract suspend fun ensureUnreadUpdateLogs()

    override fun getCondition(option: ListFilterOption): String? = when (option) {
        ListFilterOption.Macro.FAVORITE -> favouriteExistsExpr("track_logs.manga_id")
        is ListFilterOption.Favorite -> favouriteExistsExpr("track_logs.manga_id", option.categoryId)
        is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags " +
            "WHERE manga_tags.manga_id = track_logs.manga_id " +
            "AND tag_id = ${option.tagId})"
        ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga " +
            "WHERE manga.manga_id = track_logs.manga_id) = 1"
        else -> null
    }

    private fun favouriteExistsExpr(localMangaIdExpr: String, categoryId: Long? = null): String {
        val categoryFilter = categoryId?.let { " AND f.category_id = $it" }.orEmpty()
        return "EXISTS(SELECT 1 FROM favourites f " +
            "WHERE f.manga_id = $localMangaIdExpr AND f.deleted_at = 0$categoryFilter)"
    }
}
