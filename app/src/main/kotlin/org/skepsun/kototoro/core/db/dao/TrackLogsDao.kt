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

	@Query("DELETE FROM track_logs")
	abstract suspend fun clear()

	@Query("UPDATE track_logs SET unread = 0 WHERE id = :id")
	abstract suspend fun markAsRead(id: Long)

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
		val representativeLocalMangaIdExpr = representativeLocalMangaIdExpr(localMangaIdExpr)
		val categoryFilter = categoryId?.let { " AND wf.category_id = $it" }.orEmpty()
		val legacyCategoryFilter = categoryId?.let { " AND favourites.category_id = $it" }.orEmpty()
		return "(" +
			"EXISTS(SELECT 1 FROM work_favourites wf " +
			"WHERE wf.entity_id = $entityIdExpr AND wf.deleted_at = 0$categoryFilter)" +
			" OR " +
			"EXISTS(SELECT 1 FROM favourites " +
			"WHERE favourites.manga_id = $representativeLocalMangaIdExpr AND favourites.deleted_at = 0$legacyCategoryFilter)" +
			")"
	}

	private fun pinnedSortExpr(localMangaIdExpr: String): String {
		val entityIdExpr = entityIdExpr(localMangaIdExpr)
		val representativeLocalMangaIdExpr = representativeLocalMangaIdExpr(localMangaIdExpr)
		return "IFNULL((" +
			"SELECT MAX(pinned) FROM work_favourites wf " +
			"WHERE wf.entity_id = $entityIdExpr AND wf.deleted_at = 0" +
			"), IFNULL((" +
			"SELECT MAX(pinned) FROM favourites " +
			"WHERE favourites.manga_id = $representativeLocalMangaIdExpr AND favourites.deleted_at = 0" +
			"), 0))"
	}

	private fun representativeLocalMangaIdExpr(localMangaIdExpr: String): String =
		"COALESCE((" +
			"SELECT m.manga_id FROM entity_preferences ep " +
			"INNER JOIN manga m ON m.manga_id = ep.preferred_local_manga_id " +
			"WHERE ep.entity_id = ${entityIdExpr(localMangaIdExpr)} LIMIT 1" +
			"), $localMangaIdExpr)"
}
