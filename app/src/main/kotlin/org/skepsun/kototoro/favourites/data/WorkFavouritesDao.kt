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

	@Query(
		"""
		SELECT wf.* FROM work_favourites wf
		WHERE wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0
			AND (:categoryId IS NULL OR wf.category_id = :categoryId)
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:allowedTypes)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:classifiedTypes)
					AND m.content_type NOT IN (:allowedTypes)
			)
		ORDER BY
			CASE WHEN :oldestFirst = 1 THEN wf.created_at END ASC,
			CASE WHEN :oldestFirst = 0 THEN wf.created_at END DESC,
			wf.updated_at DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findActiveForSpace(
		categoryId: Long?,
		allowedTypes: Collection<String>,
		classifiedTypes: Collection<String>,
		oldestFirst: Boolean,
		limit: Int,
	): List<WorkFavouriteEntity>

	@Query(
		"""
		SELECT wf.* FROM work_favourites wf
		WHERE wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0
			AND (:categoryId IS NULL OR wf.category_id = :categoryId)
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:allowedTypes)
					AND m.source IN (:allowedSources)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:classifiedTypes)
					AND m.content_type NOT IN (:allowedTypes)
			)
		ORDER BY
			CASE WHEN :oldestFirst = 1 THEN wf.created_at END ASC,
			CASE WHEN :oldestFirst = 0 THEN wf.created_at END DESC,
			wf.updated_at DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findActiveForSpaceAndSources(
		categoryId: Long?,
		allowedTypes: Collection<String>,
		classifiedTypes: Collection<String>,
		allowedSources: Collection<String>,
		oldestFirst: Boolean,
		limit: Int,
	): List<WorkFavouriteEntity>

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

	@Query(
		"""
		SELECT COUNT(*)
		FROM work_favourites wf
		LEFT JOIN `entity` e ON e.id = wf.entity_id
		WHERE e.id IS NULL
		""",
	)
	abstract suspend fun countDanglingEntityRefs(): Int

	@Query(
		"""
		SELECT COUNT(*)
		FROM work_favourites wf
		LEFT JOIN favourite_categories fc
			ON fc.category_id = wf.category_id
			AND fc.deleted_at = 0
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND fc.category_id IS NULL
		""",
	)
	abstract suspend fun countActiveDanglingCategoryRefs(): Int

	@Query("SELECT COUNT(DISTINCT entity_id) FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun countActiveWorks(): Int

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC")
	abstract suspend fun findActive(): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
	abstract suspend fun findActiveWithoutAnchor(limit: Int): List<WorkFavouriteEntity>

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

	@Query("SELECT DISTINCT anchor_manga_id FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
	abstract suspend fun findActiveAnchorMangaIds(): List<Long>

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

	@Query(
		"""
		UPDATE work_favourites
		SET anchor_manga_id = :newAnchorMangaId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id = :oldAnchorMangaId
		""",
	)
	abstract suspend fun replaceAnchorMangaId(
		entityId: Long,
		oldAnchorMangaId: Long,
		newAnchorMangaId: Long,
		updatedAt: Long,
	)

	@Query(
		"""
		UPDATE work_favourites
		SET anchor_manga_id = :anchorMangaId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NULL
			AND deleted_at = 0
		""",
	)
	abstract suspend fun fillMissingAnchorMangaId(
		entityId: Long,
		anchorMangaId: Long,
		updatedAt: Long,
	): Int

	@Query(
		"""
		UPDATE work_favourites
		SET deleted_at = :updatedAt,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NULL
			AND deleted_at = 0
		""",
	)
	abstract suspend fun deactivateActiveWithoutAnchor(
		entityId: Long,
		updatedAt: Long,
	): Int

	@Query("DELETE FROM work_favourites")
	abstract suspend fun deleteAll()

	@Query("DELETE FROM work_favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Query("UPDATE work_favourites SET entity_id = :newEntityId WHERE entity_id = :oldEntityId")
	protected abstract suspend fun remapEntityIdRaw(oldEntityId: Long, newEntityId: Long)

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId")
	protected abstract suspend fun findAllForEntity(entityId: Long): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id = :anchorMangaId")
	protected abstract suspend fun findAllForEntityAndAnchor(entityId: Long, anchorMangaId: Long): List<WorkFavouriteEntity>

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

	@Transaction
	open suspend fun moveAnchorToEntity(oldEntityId: Long, newEntityId: Long, anchorMangaId: Long): Int {
		if (oldEntityId == newEntityId) return 0
		val sources = findAllForEntityAndAnchor(oldEntityId, anchorMangaId)
		if (sources.isEmpty()) return 0
		val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
		for (source in sources) {
			val moved = source.copy(entityId = newEntityId)
			val target = targetsByCategory[source.categoryId]
			deleteRow(oldEntityId, source.categoryId)
			if (target == null) {
				upsert(moved)
			} else {
				upsert(mergeRestoredWorkFavourites(target, moved))
			}
		}
		return sources.size
	}

	@Transaction
	open suspend fun copyActiveCategoriesToEntity(
		oldEntityId: Long,
		newEntityId: Long,
		anchorMangaId: Long,
	) {
		if (oldEntityId == newEntityId) return
		val now = System.currentTimeMillis()
		val sources = findAllForEntity(oldEntityId)
			.filter { it.anchorMangaId != null && it.deletedAt == 0L }
		if (sources.isEmpty()) return
		val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
		for (source in sources) {
			val copied = source.copy(
				entityId = newEntityId,
				anchorMangaId = anchorMangaId,
				updatedAt = now,
			)
			val target = targetsByCategory[source.categoryId]
			if (target == null) {
				upsert(copied)
			} else {
				upsert(mergeRestoredWorkFavourites(target, copied))
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
		recoverAt(entityId = entityId, updatedAt = currentTime)
	}

	suspend fun recover(entityId: Long, categoryId: Long) {
		val currentTime = System.currentTimeMillis()
		recoverAt(entityId = entityId, categoryId = categoryId, updatedAt = currentTime)
	}

	@Query(
		"""
		UPDATE work_favourites
		SET deleted_at = 0, updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NOT NULL
		""",
	)
	protected abstract suspend fun recoverAt(entityId: Long, updatedAt: Long)

	@Query(
		"""
		UPDATE work_favourites
		SET deleted_at = 0, updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND category_id = :categoryId
			AND anchor_manga_id IS NOT NULL
		""",
	)
	protected abstract suspend fun recoverAt(entityId: Long, categoryId: Long, updatedAt: Long)

	@Query(
		"""
		SELECT wf.*
		FROM work_favourites wf
		LEFT JOIN favourite_categories fc
			ON fc.category_id = wf.category_id
			AND fc.deleted_at = 0
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND fc.category_id IS NULL
		ORDER BY wf.updated_at DESC
		""",
	)
	protected abstract suspend fun findActiveWithDanglingCategory(): List<WorkFavouriteEntity>

	@Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId LIMIT 1")
	protected abstract suspend fun findIncludingDeleted(entityId: Long, categoryId: Long): WorkFavouriteEntity?

	@Transaction
	open suspend fun repairActiveDanglingCategoryRefs(targetCategoryId: Long): Int {
		val sources = findActiveWithDanglingCategory()
		for (source in sources) {
			val moved = source.copy(categoryId = targetCategoryId)
			val target = findIncludingDeleted(source.entityId, targetCategoryId)
			deleteRow(source.entityId, source.categoryId)
			if (target == null) {
				upsert(moved)
			} else {
				upsert(mergeRestoredWorkFavourites(target, moved))
			}
		}
		return sources.size
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

	@Query(
		"""
		SELECT wf.*
		FROM work_favourites wf
		INNER JOIN favourite_categories fc ON fc.category_id = wf.category_id
		WHERE fc.deleted_at = 0
		ORDER BY wf.updated_at DESC
		LIMIT :limit OFFSET :offset
		""",
	)
	protected abstract suspend fun findAllWithActiveCategory(offset: Int, limit: Int): List<WorkFavouriteEntity>

	fun dumpWithActiveCategories(): Flow<WorkFavouriteEntity> = flow {
		val window = 10
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
}
