package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.room.RoomRawQuery
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaQueryBuilder
import org.skepsun.kototoro.list.domain.ListFilterOption

@Dao
abstract class TracksDao : MangaQueryBuilder.ConditionCallback {

    @Query("SELECT * FROM tracks ORDER BY last_check_time ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun findAll(offset: Int, limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY last_check_time DESC")
    abstract fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY last_check_time DESC")
    abstract suspend fun dump(): List<TrackEntity>

    @Query("SELECT manga_id FROM tracks")
    abstract suspend fun findAllIds(): LongArray

    @Query("SELECT * FROM tracks WHERE manga_id = :mangaId LIMIT 1")
    abstract suspend fun find(mangaId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE manga_id IN (:entityIds) ORDER BY last_chapter_date DESC, last_check_time DESC")
    abstract suspend fun findByEntityIds(entityIds: Collection<Long>): List<TrackEntity>

    @Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId LIMIT 1")
    abstract suspend fun findNewChapters(mangaId: Long): Int

    @Query("SELECT manga_id, IFNULL(chapters_new, 0) AS chapters_new FROM tracks WHERE manga_id IN (:mangaIds)")
    abstract suspend fun findNewChapters(mangaIds: List<Long>): List<NewChaptersCountEntry>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract suspend fun getTracksCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE chapters_new > 0")
    abstract fun observeUpdateContentCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*)
        FROM (
            SELECT manga_id
            FROM track_logs
            WHERE unread = 1
            UNION
            SELECT manga_id
            FROM tracks
            WHERE chapters_new > 0
        )
        """,
    )
    abstract fun observeUnreadWorkCount(): Flow<Int>

    @Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId LIMIT 1")
    abstract fun observeNewChapters(mangaId: Long): Flow<Int>

    fun observeUpdatedContent(
        limit: Int,
        filterOptions: Set<ListFilterOption>,
        contentTypes: Collection<String>? = null,
    ): Flow<List<TrackEntity>> = observeContentImpl(
        MangaQueryBuilder("tracks", this)
            .where("chapters_new > 0")
            .filters(filterOptions)
            .let { builder ->
                if (contentTypes.isNullOrEmpty()) {
                    builder
                } else {
                    builder.where(
                        "(SELECT content_type FROM manga WHERE manga.manga_id = tracks.manga_id) IN (${
                            contentTypes.joinToString(",") { "'${it}'" }
                        })",
                    )
                }
            }
            .limit(limit)
            .orderBy("${pinnedSortExpr("tracks.manga_id")} DESC, last_chapter_date DESC")
            .build(),
    )

    @Query("DELETE FROM tracks")
    abstract suspend fun clear()

    @Query("UPDATE tracks SET chapters_new = 0")
    abstract suspend fun clearCounters()

    @Query("UPDATE tracks SET chapters_new = 0 WHERE manga_id = :mangaId")
    abstract suspend fun clearCounter(mangaId: Long)

    @Query(
        """
        INSERT OR IGNORE INTO tracks(
            manga_id,
            last_chapter_id,
            chapters_new,
            last_check_time,
            last_chapter_date,
            last_result,
            last_error
        )
        SELECT manga_id,
            0,
            SUM(
                CASE
                    WHEN chapters LIKE 'New chapters x %' THEN CAST(SUBSTR(chapters, 16) AS INTEGER)
                    WHEN chapters = '' THEN 1
                    ELSE LENGTH(chapters) - LENGTH(REPLACE(chapters, CHAR(10), '')) + 1
                END
            ),
            MAX(created_at),
            MAX(created_at),
            1,
            NULL
        FROM track_logs
        WHERE unread = 1
            AND EXISTS (
                SELECT 1
                FROM manga
                WHERE manga.manga_id = track_logs.manga_id
            )
            AND NOT EXISTS (
                SELECT 1
                FROM tracks
                WHERE tracks.manga_id = track_logs.manga_id
            )
        GROUP BY manga_id
        """,
    )
    abstract suspend fun insertTracksFromUnreadLogs()

    @Query(
        """
        UPDATE tracks
        SET
            chapters_new = MAX(
                chapters_new,
                (
                    SELECT SUM(
                        CASE
                            WHEN track_logs.chapters LIKE 'New chapters x %' THEN CAST(SUBSTR(track_logs.chapters, 16) AS INTEGER)
                            WHEN track_logs.chapters = '' THEN 1
                            ELSE LENGTH(track_logs.chapters) - LENGTH(REPLACE(track_logs.chapters, CHAR(10), '')) + 1
                        END
                    )
                    FROM track_logs
                    WHERE track_logs.manga_id = tracks.manga_id
                        AND track_logs.unread = 1
                )
            ),
            last_check_time = MAX(
                last_check_time,
                IFNULL((
                    SELECT MAX(created_at)
                    FROM track_logs
                    WHERE track_logs.manga_id = tracks.manga_id
                        AND track_logs.unread = 1
                ), 0)
            ),
            last_chapter_date = MAX(
                last_chapter_date,
                IFNULL((
                    SELECT MAX(created_at)
                    FROM track_logs
                    WHERE track_logs.manga_id = tracks.manga_id
                        AND track_logs.unread = 1
                ), 0)
            ),
            last_result = CASE
                WHEN (
                    SELECT COUNT(*)
                    FROM track_logs
                    WHERE track_logs.manga_id = tracks.manga_id
                        AND track_logs.unread = 1
                ) > 0 THEN 1
                ELSE last_result
            END
        WHERE EXISTS (
            SELECT 1
            FROM track_logs
            WHERE track_logs.manga_id = tracks.manga_id
                AND track_logs.unread = 1
        )
        """,
    )
    abstract suspend fun restoreCountersFromUnreadLogs()

    @Query("DELETE FROM tracks WHERE manga_id = :mangaId")
    abstract suspend fun delete(mangaId: Long)

    @Query(
        """
        DELETE FROM tracks
        WHERE manga_id NOT IN (
            SELECT manga_id
            FROM history
            WHERE deleted_at = 0

            UNION

            SELECT f.manga_id
            FROM favourites f
            INNER JOIN favourite_categories fc ON fc.category_id = f.category_id
            WHERE f.deleted_at = 0
                AND fc.deleted_at = 0
                AND fc.track = 1
        )
        """,
    )
    abstract suspend fun gc()

    @Upsert
    abstract suspend fun upsert(entity: TrackEntity)

    @RawQuery(observedEntities = [TrackEntity::class])
    protected abstract fun observeContentImpl(query: RoomRawQuery): Flow<List<TrackEntity>>

    override fun getCondition(option: ListFilterOption): String? = when (option) {
        ListFilterOption.Macro.FAVORITE -> favouriteExistsExpr("tracks.manga_id")
        is ListFilterOption.Favorite -> favouriteExistsExpr("tracks.manga_id", option.categoryId)
        is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags " +
            "WHERE manga_tags.manga_id = tracks.manga_id " +
            "AND tag_id = ${option.tagId})"
        ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga " +
            "WHERE manga.manga_id = tracks.manga_id) = 1"
        else -> null
    }

    private fun favouriteExistsExpr(localMangaIdExpr: String, categoryId: Long? = null): String {
        val categoryFilter = categoryId?.let { " AND f.category_id = $it" }.orEmpty()
        return "EXISTS(SELECT 1 FROM favourites f " +
            "WHERE f.manga_id = $localMangaIdExpr AND f.deleted_at = 0$categoryFilter)"
    }

    private fun pinnedSortExpr(localMangaIdExpr: String): String {
        return "IFNULL((" +
            "SELECT MAX(pinned) FROM favourites f " +
            "WHERE f.manga_id = $localMangaIdExpr AND f.deleted_at = 0" +
            "), 0)"
    }
}
