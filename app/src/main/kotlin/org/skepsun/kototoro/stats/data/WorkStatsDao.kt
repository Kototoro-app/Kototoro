package org.skepsun.kototoro.stats.data

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Dao
abstract class WorkStatsDao {

	@Query("SELECT * FROM work_stats WHERE entity_id = :entityId ORDER BY started_at")
	abstract suspend fun findAll(entityId: Long): List<WorkStatsEntity>

	@Query("SELECT IFNULL(SUM(pages),0) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getReadPagesCount(entityId: Long): Int

	@Query("SELECT IFNULL(SUM(duration)/SUM(pages), 0) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getAverageTimePerPage(entityId: Long): Long

	@Query("SELECT COUNT(*) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getRowCount(entityId: Long): Int

	@Query("DELETE FROM work_stats")
	abstract suspend fun clear()

	suspend fun getDurationStats(
		fromDate: Long,
		isNsfw: Boolean?,
		favouriteCategories: Set<Long>,
	): Map<MangaEntity, Long> {
		val groupedConditions = ArrayList<String>()
		groupedConditions.add("ws.started_at >= $fromDate")
		groupedConditions.add("EXISTS(SELECT 1 FROM work_history wh WHERE wh.entity_id = ws.entity_id AND wh.deleted_at = 0)")
		if (favouriteCategories.isNotEmpty()) {
			val ids = favouriteCategories.joinToString(",")
			groupedConditions.add(
				"EXISTS(SELECT 1 FROM work_favourites wf WHERE wf.entity_id = ws.entity_id AND wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0 AND wf.category_id IN ($ids))",
			)
		}
		val outerConditions = ArrayList<String>()
		if (isNsfw != null) {
			val flag = if (isNsfw) 1 else 0
			outerConditions.add("manga.nsfw = $flag")
		}
		val groupedWhere = groupedConditions.joinToString(separator = " AND ")
		val outerWhere = outerConditions.takeIf { it.isNotEmpty() }
			?.joinToString(prefix = "WHERE ", separator = " AND ")
			.orEmpty()
		return getDurationStatsImpl(
			SimpleSQLiteQuery(
				"""
				SELECT manga.*, grouped.d AS d
				FROM (
					SELECT
						ws.entity_id AS entity_id,
						COALESCE(
							(
								SELECT m.manga_id
								FROM entity_preferences ep2
								INNER JOIN manga m ON m.manga_id = ep2.preferred_local_manga_id
								WHERE ep2.entity_id = ws.entity_id
								LIMIT 1
							),
							MIN(ws.anchor_manga_id)
						) AS representative_manga_id,
						SUM(ws.duration) AS d
					FROM work_stats ws
					WHERE $groupedWhere
					GROUP BY ws.entity_id
				) grouped
				LEFT JOIN manga ON manga.manga_id = grouped.representative_manga_id
				$outerWhere
				ORDER BY grouped.d DESC
				""".trimIndent(),
			),
		)
	}

	@Upsert
	abstract suspend fun upsert(entity: WorkStatsEntity)

	@Query("SELECT * FROM work_stats ORDER BY started_at LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<WorkStatsEntity>

	fun dumpEnabled(): Flow<WorkStatsEntity> = flow {
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

	@RawQuery
	protected abstract suspend fun getDurationStatsImpl(
		query: SupportSQLiteQuery,
	): Map<@MapColumn("manga") MangaEntity, @MapColumn("d") Long>
}
