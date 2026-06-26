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
abstract class WorkFavouritesDao {

	@Query("SELECT DISTINCT category_id FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at ASC")
	abstract suspend fun findCategoriesIds(entityId: Long): List<Long>

	@Query(
		"""
		SELECT entity_id AS entityId, category_id AS categoryId
		FROM work_favourites
		WHERE entity_id IN (:entityIds)
			AND anchor_manga_id IS NOT NULL
			AND deleted_at = 0
		""",
	)
	abstract suspend fun findCategoryMemberships(entityIds: List<Long>): List<WorkFavouriteCategoryMembership>

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId LIMIT 1")
	abstract suspend fun find(entityId: Long, categoryId: Long): WorkFavouriteEntity?

	@Query("SELECT COUNT(category_id) FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun findCategoriesCount(entityId: Long): Int

	@Query("SELECT COUNT(*) FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun countActive(): Int

	@Query("SELECT COUNT(DISTINCT entity_id) FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun countActiveWorks(): Int

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC")
	abstract suspend fun findActive(): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT 1")
	abstract suspend fun findActiveForEntity(entityId: Long): WorkFavouriteEntity?

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY updated_at DESC")
	abstract suspend fun findActive(categoryId: Long): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
	abstract suspend fun findActiveUpdated(limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY updated_at DESC LIMIT :limit")
	abstract suspend fun findActiveUpdated(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at DESC LIMIT :limit")
	abstract suspend fun findActiveNewest(limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY created_at DESC LIMIT :limit")
	abstract suspend fun findActiveNewest(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at ASC LIMIT :limit")
	abstract suspend fun findActiveOldest(limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY created_at ASC LIMIT :limit")
	abstract suspend fun findActiveOldest(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id = :anchorMangaId AND deleted_at = 0 ORDER BY updated_at DESC")
	abstract suspend fun findActiveByAnchorMangaId(anchorMangaId: Long): List<WorkFavouriteEntity>

	@Query("SELECT MAX(pinned) FROM work_favourites WHERE entity_id IN (:entityIds) AND anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun isPinned(entityIds: List<Long>): Boolean?

	@Query("SELECT DISTINCT entity_id FROM work_favourites WHERE entity_id IN (:entityIds) AND anchor_manga_id IS NOT NULL AND pinned = 1 AND deleted_at = 0")
	abstract suspend fun findPinnedEntityIds(entityIds: List<Long>): List<Long>

	@Query(
		"""
		SELECT DISTINCT wf.entity_id
		FROM work_favourites wf
		INNER JOIN favourite_categories fc ON fc.category_id = wf.category_id
		WHERE wf.deleted_at = 0
			AND wf.anchor_manga_id IS NOT NULL
			AND fc.deleted_at = 0
			AND fc.track = 1
		""",
	)
	abstract suspend fun findTrackedEntityIds(): List<Long>

	@Upsert
	abstract suspend fun upsert(entity: WorkFavouriteEntity)

	@Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE entity_id = :entityId")
	abstract suspend fun setDeletedAt(entityId: Long, deletedAt: Long, updatedAt: Long)

	@Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE entity_id = :entityId AND category_id = :categoryId")
	abstract suspend fun setDeletedAt(entityId: Long, categoryId: Long, deletedAt: Long, updatedAt: Long)

	@Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE category_id = :categoryId AND deleted_at = 0")
	abstract suspend fun setDeletedAtAll(categoryId: Long, deletedAt: Long, updatedAt: Long)

	@Query("DELETE FROM work_favourites")
	abstract suspend fun deleteAll()

	@Query("DELETE FROM work_favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Query("UPDATE work_favourites SET entity_id = :newEntityId WHERE entity_id = :oldEntityId")
	protected abstract suspend fun remapEntityIdRaw(oldEntityId: Long, newEntityId: Long)

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId")
	protected abstract suspend fun findAllForEntity(entityId: Long): List<WorkFavouriteEntity>

	@Query("DELETE FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId")
	protected abstract suspend fun deleteRow(entityId: Long, categoryId: Long)

	/**
	 * Move every row from [oldEntityId] to [newEntityId]. When a target
	 * `(newEntityId, category_id)` row already exists the two are merged via
	 * [mergeRestoredWorkFavourites] instead of letting the bulk UPDATE hit the
	 * `(entity_id, category_id)` primary-key constraint (the restore crash that
	 * surfaced as SQLITE_CONSTRAINT_PRIMARYKEY on work_favourites).
	 */
	@Transaction
	open suspend fun remapEntityId(oldEntityId: Long, newEntityId: Long) {
		if (oldEntityId == newEntityId) return
		val sources = findAllForEntity(oldEntityId)
		if (sources.isEmpty()) return
		val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
		if (targetsByCategory.isEmpty()) {
			remapEntityIdRaw(oldEntityId, newEntityId)
			return
		}
		for (source in sources) {
			val moved = source.copy(entityId = newEntityId)
			val target = targetsByCategory[source.categoryId]
			if (target == null) {
				deleteRow(oldEntityId, source.categoryId)
				upsert(moved)
			} else {
				deleteRow(oldEntityId, source.categoryId)
				upsert(mergeRestoredWorkFavourites(target, moved))
			}
		}
	}

	@Query("UPDATE work_favourites SET pinned = :isPinned WHERE entity_id IN (:entityIds)")
	abstract suspend fun setPinned(entityIds: List<Long>, isPinned: Boolean)

	suspend fun delete(entityId: Long) {
		val currentTime = System.currentTimeMillis()
		setDeletedAt(entityId = entityId, deletedAt = currentTime, updatedAt = currentTime)
	}

	suspend fun delete(entityId: Long, categoryId: Long) {
		val currentTime = System.currentTimeMillis()
		setDeletedAt(entityId = entityId, categoryId = categoryId, deletedAt = currentTime, updatedAt = currentTime)
	}

	suspend fun deleteAll(categoryId: Long) {
		val currentTime = System.currentTimeMillis()
		setDeletedAtAll(categoryId = categoryId, deletedAt = currentTime, updatedAt = currentTime)
	}

	suspend fun recover(entityId: Long) {
		val currentTime = System.currentTimeMillis()
		setDeletedAt(entityId = entityId, deletedAt = 0L, updatedAt = currentTime)
	}

	suspend fun recover(entityId: Long, categoryId: Long) {
		val currentTime = System.currentTimeMillis()
		setDeletedAt(entityId = entityId, categoryId = categoryId, deletedAt = 0L, updatedAt = currentTime)
	}

	@Query("SELECT * FROM work_favourites ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<WorkFavouriteEntity>

	fun dump(): Flow<WorkFavouriteEntity> = flow {
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
