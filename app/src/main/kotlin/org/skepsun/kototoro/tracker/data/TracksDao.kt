package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
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

	@Query("SELECT * FROM tracks WHERE owner_id = :ownerId LIMIT 1")
	abstract suspend fun findByOwnerId(ownerId: Long): TrackEntity?

	@Query("SELECT * FROM tracks WHERE entity_id IN (:entityIds) ORDER BY last_chapter_date DESC, last_check_time DESC")
	abstract suspend fun findByEntityIds(entityIds: Collection<Long>): List<TrackEntity>

	@Query("SELECT IFNULL(chapters_new,0) FROM tracks WHERE manga_id = :mangaId LIMIT 1")
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
			SELECT entity_id
			FROM track_logs
			WHERE entity_id IS NOT NULL
				AND unread = 1
				AND created_at > :lastOpenTime
			UNION
			SELECT entity_id
			FROM tracks
			WHERE entity_id IS NOT NULL
				AND chapters_new > 0
				AND last_check_time > :lastOpenTime
		)
		""",
	)
	abstract fun observeUnreadWorkCount(lastOpenTime: Long): Flow<Int>

	@Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId LIMIT 1")
	abstract fun observeNewChapters(mangaId: Long): Flow<Int>

	fun observeUpdatedContent(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<TrackEntity>> = observeContentImpl(
		MangaQueryBuilder("tracks", this)
			.where("chapters_new > 0")
			.filters(filterOptions)
			.limit(limit)
			.orderBy("${pinnedSortExpr("tracks.manga_id")} DESC, last_chapter_date DESC")
			.build(),
	)

	fun observeAllTracks(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<TrackEntity>> = observeContentImpl(
		MangaQueryBuilder("tracks", this)
			.filters(filterOptions)
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
			owner_id,
			manga_id,
			entity_id,
			last_chapter_id,
			chapters_new,
			last_check_time,
			last_chapter_date,
			last_result,
			last_error
		)
		SELECT owner_id,
			manga_id,
			entity_id,
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
				WHERE tracks.owner_id = track_logs.owner_id
			)
		GROUP BY owner_id, manga_id, entity_id
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
					WHERE track_logs.owner_id = tracks.owner_id
						AND track_logs.unread = 1
				)
			),
			last_check_time = MAX(
				last_check_time,
				IFNULL((
					SELECT MAX(created_at)
					FROM track_logs
					WHERE track_logs.owner_id = tracks.owner_id
						AND track_logs.unread = 1
				), 0)
			),
			last_chapter_date = MAX(
				last_chapter_date,
				IFNULL((
					SELECT MAX(created_at)
					FROM track_logs
					WHERE track_logs.owner_id = tracks.owner_id
						AND track_logs.unread = 1
				), 0)
			),
			last_result = CASE
				WHEN (
					SELECT COUNT(*)
					FROM track_logs
					WHERE track_logs.owner_id = tracks.owner_id
						AND track_logs.unread = 1
				) > 0 THEN 1
				ELSE last_result
			END
		WHERE EXISTS (
			SELECT 1
			FROM track_logs
			WHERE track_logs.owner_id = tracks.owner_id
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
		WHERE owner_id NOT IN (
			SELECT entity_id
			FROM work_history
			WHERE deleted_at = 0

			UNION

			SELECT wf.entity_id
			FROM work_favourites wf
			INNER JOIN favourite_categories fc ON fc.category_id = wf.category_id
			WHERE wf.deleted_at = 0
				AND wf.anchor_manga_id IS NOT NULL
				AND fc.deleted_at = 0
				AND fc.track = 1
		)
		""",
	)
	abstract suspend fun gc()

	@Upsert
	abstract suspend fun upsert(entity: TrackEntity)

	@RawQuery(observedEntities = [TrackEntity::class])
	protected abstract fun observeContentImpl(query: SupportSQLiteQuery): Flow<List<TrackEntity>>

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.FAVORITE -> favouriteExistsExpr("tracks.manga_id")
		is ListFilterOption.Favorite -> favouriteExistsExpr("tracks.manga_id", option.category.id)
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags " +
			"WHERE manga_tags.manga_id = ${representativeLocalMangaIdExpr("tracks.manga_id")} " +
			"AND tag_id = ${option.tagId})"
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga " +
			"WHERE manga.manga_id = ${representativeLocalMangaIdExpr("tracks.manga_id")}) = 1"
		else -> null
	}

	private fun entityIdExpr(localMangaIdExpr: String): String =
		"COALESCE(tracks.entity_id, (" +
			"SELECT entity_id FROM entity_binding " +
			"WHERE source IN ('local_manga', '0') " +
			"AND external_id = CAST($localMangaIdExpr AS TEXT) " +
			"AND state IN ('MANUAL', 'CONFIRMED', 'LEGACY') " +
			"LIMIT 1" +
		"))"

	private fun representativeLocalMangaIdExpr(localMangaIdExpr: String): String =
		"COALESCE((" +
			"SELECT m.manga_id FROM entity_preferences ep " +
			"INNER JOIN manga m ON m.manga_id = ep.preferred_local_manga_id " +
			"WHERE ep.entity_id = ${entityIdExpr(localMangaIdExpr)} LIMIT 1" +
			"), $localMangaIdExpr)"

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
}
