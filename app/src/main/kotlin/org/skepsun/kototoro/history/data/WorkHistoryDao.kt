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
abstract class WorkHistoryDao {

	@Query(
		"""
		SELECT wh.* FROM work_history wh
		INNER JOIN `entity` e ON e.id = wh.entity_id
		WHERE wh.deleted_at = 0
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wh.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND COALESCE(m.content_type, e.content_type) IN (:allowedTypes)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wh.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND COALESCE(m.content_type, e.content_type) IN (:classifiedTypes)
					AND COALESCE(m.content_type, e.content_type) NOT IN (:allowedTypes)
			)
		ORDER BY wh.updated_at DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findRecentForSpace(
		allowedTypes: Collection<String>,
		classifiedTypes: Collection<String>,
		limit: Int,
	): List<WorkHistoryEntity>

	@Query(
		"""
		SELECT wh.* FROM work_history wh
		INNER JOIN `entity` e ON e.id = wh.entity_id
		WHERE wh.deleted_at = 0
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wh.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND COALESCE(m.content_type, e.content_type) IN (:allowedTypes)
					AND m.source IN (:allowedSources)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wh.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND COALESCE(m.content_type, e.content_type) IN (:classifiedTypes)
					AND COALESCE(m.content_type, e.content_type) NOT IN (:allowedTypes)
			)
		ORDER BY wh.updated_at DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findRecentForSpaceAndSources(
		allowedTypes: Collection<String>,
		classifiedTypes: Collection<String>,
		allowedSources: Collection<String>,
		limit: Int,
	): List<WorkHistoryEntity>

	@Query("SELECT * FROM work_history WHERE entity_id = :entityId LIMIT 1")
	abstract suspend fun find(entityId: Long): WorkHistoryEntity?

	@Query("SELECT * FROM work_history WHERE entity_id IN (:entityIds) AND deleted_at = 0")
	abstract suspend fun findByEntityIds(entityIds: List<Long>): List<WorkHistoryEntity>

	@Query("SELECT * FROM work_history WHERE anchor_manga_id = :anchorMangaId AND deleted_at = 0 LIMIT 1")
	abstract suspend fun findActiveByAnchorMangaId(anchorMangaId: Long): WorkHistoryEntity?

	@Query("SELECT * FROM work_history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT 1")
	abstract suspend fun findLastOrNull(): WorkHistoryEntity?

	@Query("SELECT * FROM work_history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
	abstract suspend fun findRecent(limit: Int): List<WorkHistoryEntity>

	@Query("SELECT anchor_manga_id FROM work_history WHERE deleted_at = 0")
	abstract suspend fun findActiveAnchorMangaIds(): List<Long>

	@Query("SELECT COUNT(*) FROM work_history WHERE deleted_at = 0")
	abstract suspend fun countActive(): Int

	@Query(
		"""
		SELECT COUNT(*)
		FROM work_history wh
		LEFT JOIN `entity` e ON e.id = wh.entity_id
		WHERE e.id IS NULL
		""",
	)
	abstract suspend fun countDanglingEntityRefs(): Int

	@Query("SELECT COUNT(*) FROM work_history WHERE deleted_at = 0")
	abstract fun observeCountActive(): Flow<Int>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insert(entity: WorkHistoryEntity): Long

	@Query(
		"""
		UPDATE work_history
		SET anchor_manga_id = :anchorMangaId,
			page = :page,
			chapter_id = :chapterId,
			scroll = :scroll,
			percent = :percent,
			updated_at = :updatedAt,
			chapters = :chapters,
			parent_chapter_id = :parentChapterId,
			deleted_at = :deletedAt
		WHERE entity_id = :entityId
		""",
	)
	protected abstract suspend fun update(
		entityId: Long,
		anchorMangaId: Long,
		page: Int,
		chapterId: Long,
		scroll: Float,
		percent: Float,
		chapters: Int,
		updatedAt: Long,
		parentChapterId: Long?,
		deletedAt: Long,
	): Int

	suspend fun update(entity: WorkHistoryEntity): Int {
		return update(
			entityId = entity.entityId,
			anchorMangaId = entity.anchorMangaId,
			page = entity.page,
			chapterId = entity.chapterId,
			scroll = entity.scroll,
			percent = entity.percent,
			chapters = entity.chaptersCount,
			updatedAt = entity.updatedAt,
			parentChapterId = entity.parentChapterId,
			deletedAt = entity.deletedAt,
		)
	}

	@Transaction
	open suspend fun upsert(entity: WorkHistoryEntity): Boolean {
		return if (update(entity) == 0) {
			insert(entity)
			true
		} else {
			false
		}
	}

	@Query("UPDATE work_history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE entity_id = :entityId")
	abstract suspend fun setDeletedAt(entityId: Long, deletedAt: Long)

	suspend fun delete(entityId: Long) = setDeletedAt(entityId, System.currentTimeMillis())

	@Query(
		"""
		UPDATE work_history
		SET anchor_manga_id = :newAnchorMangaId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id = :oldAnchorMangaId
			AND deleted_at = 0
		""",
	)
	abstract suspend fun replaceActiveAnchorMangaId(
		entityId: Long,
		oldAnchorMangaId: Long,
		newAnchorMangaId: Long,
		updatedAt: Long,
	)

	@Query(
		"""
		UPDATE work_history
		SET deleted_at = :deletedAt,
			updated_at = :deletedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id = :anchorMangaId
			AND deleted_at = 0
		""",
	)
	abstract suspend fun deleteActiveByAnchor(entityId: Long, anchorMangaId: Long, deletedAt: Long)

	@Query("UPDATE work_history SET entity_id = :newEntityId WHERE entity_id = :oldEntityId")
	protected abstract suspend fun remapEntityIdRaw(oldEntityId: Long, newEntityId: Long)

	@Query("DELETE FROM work_history WHERE entity_id = :entityId")
	protected abstract suspend fun deleteRow(entityId: Long)

	/**
	 * Move the row from [oldEntityId] to [newEntityId]. When a row already
	 * exists at [newEntityId] the two are merged via [mergeRestoredWorkHistory]
	 * instead of letting the bulk UPDATE hit the `entity_id` primary-key
	 * constraint during restore / entity remap.
	 */
	@Transaction
	open suspend fun remapEntityId(oldEntityId: Long, newEntityId: Long) {
		if (oldEntityId == newEntityId) return
		val source = find(oldEntityId) ?: return
		val moved = source.copy(entityId = newEntityId)
		val target = find(newEntityId)
		if (target == null) {
			remapEntityIdRaw(oldEntityId, newEntityId)
			return
		}
		deleteRow(oldEntityId)
		upsert(mergeRestoredWorkHistory(target, moved))
	}

	@Transaction
	open suspend fun moveAnchorToEntity(oldEntityId: Long, newEntityId: Long, anchorMangaId: Long) {
		if (oldEntityId == newEntityId) return
		val source = find(oldEntityId)?.takeIf { it.anchorMangaId == anchorMangaId } ?: return
		val moved = source.copy(entityId = newEntityId)
		val target = find(newEntityId)
		deleteRow(oldEntityId)
		if (target == null) {
			upsert(moved)
		} else {
			upsert(mergeRestoredWorkHistory(target, moved))
		}
	}

	@Query("UPDATE work_history SET deleted_at = 0, updated_at = :updatedAt WHERE entity_id = :entityId")
	abstract suspend fun recoverAt(entityId: Long, updatedAt: Long)

	suspend fun recover(entityId: Long) = recoverAt(entityId, System.currentTimeMillis())

	@Query("UPDATE work_history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE created_at >= :minDate AND deleted_at = 0")
	abstract suspend fun setDeletedAtAfter(minDate: Long, deletedAt: Long)

	suspend fun deleteAfter(minDate: Long) = setDeletedAtAfter(minDate, System.currentTimeMillis())

	@Query("UPDATE work_history SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE deleted_at = 0")
	abstract suspend fun setDeletedAtAll(deletedAt: Long)

	suspend fun clear() = setDeletedAtAll(System.currentTimeMillis())

	@Query("DELETE FROM work_history WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Query("SELECT * FROM work_history ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
	abstract suspend fun findAll(offset: Int, limit: Int): List<WorkHistoryEntity>

	fun dump(): Flow<WorkHistoryEntity> = flow {
		val window = 10
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
}
