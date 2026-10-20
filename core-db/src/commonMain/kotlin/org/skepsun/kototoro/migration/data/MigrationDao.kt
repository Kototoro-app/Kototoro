package org.skepsun.kototoro.migration.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity

@Dao
abstract class MigrationDao {

    @Query(
        """
        SELECT m.manga_id AS id, m.title AS title, m.alt_title AS altTitles, m.source AS source,
            m.content_type AS contentType, m.cover_url AS coverUrl, m.public_url AS publicUrl,
            (SELECT COUNT(*) FROM chapters c WHERE c.manga_id = m.manga_id) AS chaptersCount,
            t.last_result AS trackResult, t.last_check_time AS trackCheckTime, t.last_error AS trackError,
            h.percent AS historyPercent, h.chapters AS historyChaptersCount,
            (SELECT c2.number FROM chapters c2 WHERE c2.manga_id = m.manga_id AND c2.chapter_id = h.chapter_id)
                AS historyChapterNumber
        FROM manga m
        LEFT JOIN tracks t ON t.manga_id = m.manga_id
        LEFT JOIN history h ON h.manga_id = m.manga_id AND h.deleted_at = 0
        WHERE m.manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
        """,
    )
    abstract suspend fun findLibraryRows(): List<LibraryRow>

    /** Tracking-site entries of active favourites: metadata links, then account bindings (always manual). */
    @Query(
        """
        SELECT l.manga_id AS mangaId, l.service AS service, l.remote_id AS remoteId, l.is_manual AS manual
        FROM tracking_site_links l
        WHERE l.remote_id > 0 AND l.manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
        UNION ALL
        SELECT s.manga_id AS mangaId, s.scrobbler AS service, s.target_id AS remoteId, 1 AS manual
        FROM scrobblings s
        WHERE s.target_id > 0 AND s.manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)
        """,
    )
    abstract suspend fun findLibraryTrackingIds(): List<LibraryTrackingId>

    @Query("SELECT content_type FROM manga WHERE manga_id = :mangaId")
    abstract suspend fun findStoredContentType(mangaId: Long): String?

    @Query("SELECT COUNT(*) FROM chapters WHERE manga_id = :mangaId")
    abstract suspend fun countChapters(mangaId: Long): Int

    /** Chapter count remembered by history; covers entries whose chapters were never cached. */
    @Query("SELECT IFNULL(MAX(chapters), 0) FROM history WHERE manga_id = :mangaId")
    abstract suspend fun findHistoryChaptersCount(mangaId: Long): Int

    @Query("SELECT * FROM media_notes WHERE manga_id = :mangaId")
    abstract suspend fun findNotes(mangaId: Long): List<MediaNoteEntity>

    @Insert
    abstract suspend fun insertNotes(notes: List<MediaNoteEntity>)

    @Update
    abstract suspend fun updateNotes(notes: List<MediaNoteEntity>)

    @Query("UPDATE OR IGNORE stats SET manga_id = :newId WHERE manga_id = :oldId")
    abstract suspend fun moveStats(oldId: Long, newId: Long)

    @Query("SELECT * FROM reading_sessions WHERE manga_id = :mangaId")
    abstract suspend fun findSessions(mangaId: Long): List<ReadingRecordEntity>

    @Update
    abstract suspend fun updateSessions(sessions: List<ReadingRecordEntity>)

    @Query("SELECT * FROM reading_jump_points WHERE manga_id = :mangaId")
    abstract suspend fun findJumpPoints(mangaId: Long): List<ReadingJumpPointEntity>

    @Update
    abstract suspend fun updateJumpPoints(points: List<ReadingJumpPointEntity>)
}
