package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaQueryBuilder
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.tracker.data.TrackLogEntity

@Dao
abstract class TrackLogsDao : MangaQueryBuilder.ConditionCallback {

	fun observeAll(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<TrackLogEntity>> = observeAllImpl(
		MangaQueryBuilder("track_logs", this)
			.filters(filterOptions)
			.limit(limit)
			.orderBy("${pinnedSortExpr("track_logs.manga_id")} DESC, created_at DESC")
			.build(),
	)

	@Query("SELECT COUNT(*) FROM track_logs WHERE unread = 1")
	abstract fun observeUnreadCount(): Flow<Int>

	@Query("SELECT * FROM track_logs ORDER BY created_at DESC")
	abstract suspend fun dump(): List<TrackLogEntity>

	@Query(
		"""
		SELECT *
		FROM track_logs
		WHERE owner_id = :ownerId
			AND manga_id = :mangaId
			AND IFNULL(entity_id, 0) = IFNULL(:entityId, 0)
			AND chapters = :chapters
			AND created_at = :createdAt
		LIMIT 1
		""",
	)
	abstract suspend fun findDuplicate(
		ownerId: Long,
		mangaId: Long,
		entityId: Long?,
		chapters: String,
		createdAt: Long,
	): TrackLogEntity?

	@Query("DELETE FROM track_logs")
	abstract suspend fun clear()

	@Query(
		"""
		UPDATE track_logs
		SET entity_id = (
			SELECT entity_id
			FROM entity_binding
			WHERE source IN ('local_manga', '0')
				AND external_id = CAST(track_logs.manga_id AS TEXT)
				AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			ORDER BY CASE WHEN source = 'local_manga' THEN 0 ELSE 1 END
			LIMIT 1
		),
		owner_id = (
			SELECT entity_id
			FROM entity_binding
			WHERE source IN ('local_manga', '0')
				AND external_id = CAST(track_logs.manga_id AS TEXT)
				AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			ORDER BY CASE WHEN source = 'local_manga' THEN 0 ELSE 1 END
			LIMIT 1
		)
		WHERE EXISTS (
			SELECT 1
			FROM entity_binding
			WHERE source IN ('local_manga', '0')
				AND external_id = CAST(track_logs.manga_id AS TEXT)
				AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		)
		""",
	)
	abstract suspend fun repairWorkIdentities()

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

	@Query("UPDATE track_logs SET unread = 0 WHERE owner_id = :ownerId AND unread = 1")
	abstract suspend fun markUnreadAsReadByOwner(ownerId: Long)

	@Query("SELECT DISTINCT owner_id FROM track_logs WHERE unread = 1 AND owner_id IN (:ownerIds)")
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
			WHERE tracks.owner_id = track_logs.owner_id
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
		INSERT INTO track_logs(owner_id, manga_id, entity_id, chapters, created_at, unread)
		SELECT tracks.owner_id,
			tracks.manga_id,
			tracks.entity_id,
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
				WHERE track_logs.owner_id = tracks.owner_id
			)
		""",
	)
	abstract suspend fun ensureUnreadUpdateLogs()

	@RawQuery(observedEntities = [TrackLogEntity::class])
	protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<TrackLogEntity>>

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.FAVORITE -> favouriteExistsExpr("track_logs.manga_id")
		is ListFilterOption.Favorite -> favouriteExistsExpr("track_logs.manga_id", option.category.id)
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags " +
			"WHERE manga_tags.manga_id = ${representativeLocalMangaIdExpr("track_logs.manga_id")} " +
			"AND tag_id = ${option.tagId})"
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga " +
			"WHERE manga.manga_id = ${representativeLocalMangaIdExpr("track_logs.manga_id")}) = 1"
		else -> null
	}

	private fun entityIdExpr(localMangaIdExpr: String): String =
		"COALESCE(track_logs.entity_id, (" +
			"SELECT entity_id FROM entity_binding " +
			"WHERE source IN ('local_manga', '0') " +
			"AND external_id = CAST($localMangaIdExpr AS TEXT) " +
			"AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY') " +
			"LIMIT 1" +
		"))"

	private fun favouriteExistsExpr(localMangaIdExpr: String, categoryId: Long? = null): String {
		val entityIdExpr = entityIdExpr(localMangaIdExpr)
		val categoryFilter = categoryId?.let { " AND wf.category_id = $it" }.orEmpty()
		return "EXISTS(SELECT 1 FROM work_favourites wf " +
			"WHERE wf.entity_id = $entityIdExpr AND wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0$categoryFilter)"
	}

	private fun pinnedSortExpr(localMangaIdExpr: String): String {
		val entityIdExpr = entityIdExpr(localMangaIdExpr)
		return "IFNULL((" +
			"SELECT MAX(pinned) FROM work_favourites wf " +
			"WHERE wf.entity_id = $entityIdExpr AND wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0" +
			"), 0)"
	}

	private fun representativeLocalMangaIdExpr(localMangaIdExpr: String): String =
		"COALESCE((" +
			"SELECT m.manga_id FROM entity_preferences ep " +
			"INNER JOIN manga m ON m.manga_id = ep.preferred_local_manga_id " +
			"WHERE ep.entity_id = ${entityIdExpr(localMangaIdExpr)} LIMIT 1" +
			"), $localMangaIdExpr)"
}
