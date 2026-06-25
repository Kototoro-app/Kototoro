package org.skepsun.kototoro.history.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class HistoryDao {

	@Transaction
	@Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
	abstract suspend fun findAll(offset: Int, limit: Int): List<HistoryWithContent>

	@Query("SELECT * FROM history ORDER BY updated_at DESC")
	abstract suspend fun findAllEntriesIncludingDeleted(): List<HistoryEntity>

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

	suspend fun update(entity: HistoryEntity): Int {
		return update(
			mangaId = entity.mangaId,
			page = entity.page,
			chapterId = entity.chapterId,
			scroll = entity.scroll,
			percent = entity.percent,
			chapters = entity.chaptersCount,
			updatedAt = entity.updatedAt,
			parentChapterId = entity.parentChapterId,
		)
	}

	suspend fun clear() = setDeletedAtAfter(0L, System.currentTimeMillis())

	@Query("DELETE FROM history WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Transaction
	open suspend fun upsert(entities: Iterable<HistoryEntity>) {
		for (entity in entities) {
			if (update(entity) == 0) {
				insert(entity)
			}
		}
	}

	@Query("UPDATE history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE created_at >= :minDate AND deleted_at = 0")
	protected abstract suspend fun setDeletedAtAfter(minDate: Long, deletedAt: Long)
}
