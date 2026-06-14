package org.skepsun.kototoro.readingrecord.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ReadingChapterAggregateEntity(
	@androidx.room.ColumnInfo(name = "chapter_id") val chapterId: Long,
	@androidx.room.ColumnInfo(name = "sessions_count") val sessionsCount: Int,
	@androidx.room.ColumnInfo(name = "duration") val duration: Long,
	@androidx.room.ColumnInfo(name = "last_read_at") val lastReadAt: Long,
)

@Dao
abstract class ReadingRecordDao {

	@Query("SELECT * FROM reading_sessions WHERE manga_id = :mangaId ORDER BY end_at DESC, start_at DESC")
	abstract fun observeSessions(mangaId: Long): Flow<List<ReadingRecordEntity>>

	@Query("SELECT * FROM reading_sessions WHERE manga_id IN (:mangaIds) ORDER BY end_at DESC, start_at DESC")
	abstract fun observeSessions(mangaIds: List<Long>): Flow<List<ReadingRecordEntity>>

	@Query("SELECT * FROM reading_sessions WHERE manga_id = :mangaId ORDER BY end_at DESC, start_at DESC")
	abstract suspend fun findSessions(mangaId: Long): List<ReadingRecordEntity>

	@Query("SELECT * FROM reading_sessions WHERE manga_id IN (:mangaIds) ORDER BY end_at DESC, start_at DESC")
	abstract suspend fun findSessions(mangaIds: List<Long>): List<ReadingRecordEntity>

	@Query(
		"""
		SELECT end_chapter_id AS chapter_id,
			COUNT(*) AS sessions_count,
			IFNULL(SUM(end_at - start_at), 0) AS duration,
			MAX(end_at) AS last_read_at
		FROM reading_sessions
		WHERE manga_id = :mangaId
			AND end_at >= start_at
		GROUP BY end_chapter_id
		ORDER BY last_read_at DESC
		""",
	)
	abstract fun observeChapterAggregates(mangaId: Long): Flow<List<ReadingChapterAggregateEntity>>

	@Query(
		"""
		SELECT end_chapter_id AS chapter_id,
			COUNT(*) AS sessions_count,
			IFNULL(SUM(end_at - start_at), 0) AS duration,
			MAX(end_at) AS last_read_at
		FROM reading_sessions
		WHERE manga_id IN (:mangaIds)
			AND end_at >= start_at
		GROUP BY end_chapter_id
		ORDER BY last_read_at DESC
		""",
	)
	abstract fun observeChapterAggregates(mangaIds: List<Long>): Flow<List<ReadingChapterAggregateEntity>>

	@Query("SELECT IFNULL(SUM(end_at - start_at), 0) FROM reading_sessions WHERE manga_id = :mangaId AND end_at >= start_at")
	abstract fun observeTotalDuration(mangaId: Long): Flow<Long>

	@Query("SELECT IFNULL(SUM(end_at - start_at), 0) FROM reading_sessions WHERE manga_id IN (:mangaIds) AND end_at >= start_at")
	abstract fun observeTotalDuration(mangaIds: List<Long>): Flow<Long>

	@Query("SELECT COUNT(DISTINCT start_at / 86400000) FROM reading_sessions WHERE manga_id = :mangaId")
	abstract fun observeReadingDays(mangaId: Long): Flow<Int>

	@Query("SELECT COUNT(DISTINCT start_at / 86400000) FROM reading_sessions WHERE manga_id IN (:mangaIds)")
	abstract fun observeReadingDays(mangaIds: List<Long>): Flow<Int>

	@Query("SELECT MAX(end_at) FROM reading_sessions WHERE manga_id = :mangaId")
	abstract fun observeLastReadAt(mangaId: Long): Flow<Long?>

	@Query("SELECT MAX(end_at) FROM reading_sessions WHERE manga_id IN (:mangaIds)")
	abstract fun observeLastReadAt(mangaIds: List<Long>): Flow<Long?>

	@Query("SELECT * FROM reading_jump_points WHERE manga_id = :mangaId ORDER BY created_at DESC LIMIT :limit")
	abstract fun observeJumpPoints(mangaId: Long, limit: Int): Flow<List<ReadingJumpPointEntity>>

	@Query("SELECT * FROM reading_jump_points WHERE manga_id IN (:mangaIds) ORDER BY created_at DESC LIMIT :limit")
	abstract fun observeJumpPoints(mangaIds: List<Long>, limit: Int): Flow<List<ReadingJumpPointEntity>>

	@Query("SELECT * FROM reading_jump_points WHERE manga_id = :mangaId ORDER BY created_at DESC LIMIT :limit")
	abstract suspend fun findJumpPoints(mangaId: Long, limit: Int): List<ReadingJumpPointEntity>

	@Query("SELECT * FROM reading_jump_points WHERE manga_id IN (:mangaIds) ORDER BY created_at DESC LIMIT :limit")
	abstract suspend fun findJumpPoints(mangaIds: List<Long>, limit: Int): List<ReadingJumpPointEntity>

	@Insert
	abstract suspend fun insertSession(entity: ReadingRecordEntity): Long

	@Insert
	abstract suspend fun insertJumpPoint(entity: ReadingJumpPointEntity): Long

	@Query("DELETE FROM reading_sessions WHERE id = :id")
	abstract suspend fun deleteSession(id: Long)

	@Query("DELETE FROM reading_jump_points WHERE id = :id")
	abstract suspend fun deleteJumpPoint(id: Long)

	@Query("DELETE FROM reading_sessions WHERE manga_id = :mangaId")
	abstract suspend fun clearSessions(mangaId: Long)

	@Query("DELETE FROM reading_sessions WHERE manga_id IN (:mangaIds)")
	abstract suspend fun clearSessions(mangaIds: List<Long>)

	@Query("DELETE FROM reading_jump_points WHERE manga_id = :mangaId")
	abstract suspend fun clearJumpPoints(mangaId: Long)

	@Query("DELETE FROM reading_jump_points WHERE manga_id IN (:mangaIds)")
	abstract suspend fun clearJumpPoints(mangaIds: List<Long>)
}
