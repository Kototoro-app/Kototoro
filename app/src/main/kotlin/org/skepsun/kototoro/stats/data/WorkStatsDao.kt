package org.skepsun.kototoro.stats.data

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
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

	@Query("SELECT * FROM work_stats WHERE entity_id = :entityId AND anchor_manga_id = :anchorMangaId ORDER BY started_at")
	protected abstract suspend fun findAllForEntityAndAnchor(entityId: Long, anchorMangaId: Long): List<WorkStatsEntity>

	@Query("SELECT * FROM work_stats WHERE anchor_manga_id = :anchorMangaId ORDER BY started_at")
	abstract suspend fun findAllByAnchorMangaId(anchorMangaId: Long): List<WorkStatsEntity>

	@Query("SELECT IFNULL(SUM(pages),0) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getReadPagesCount(entityId: Long): Int

	@Query("SELECT IFNULL(SUM(duration)/SUM(pages), 0) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getAverageTimePerPage(entityId: Long): Long

	@Query("SELECT IFNULL(SUM(duration)/SUM(pages), 0) FROM work_stats")
	abstract suspend fun getAverageTimePerPage(): Long

	@Query("SELECT COUNT(*) FROM work_stats WHERE entity_id = :entityId")
	abstract suspend fun getRowCount(entityId: Long): Int

	@Query("SELECT DISTINCT anchor_manga_id FROM work_stats")
	abstract suspend fun findAnchorMangaIds(): List<Long>

	@Query(
		"""
		SELECT entity_id AS entityId,
			IFNULL(SUM(pages), 0) AS totalPages,
			IFNULL(SUM(duration) / SUM(pages), 0) AS averageTimePerPage,
			COUNT(*) AS entryCount
		FROM work_stats
		WHERE entity_id IN (:entityIds)
		GROUP BY entity_id
		""",
	)
	abstract suspend fun findSummaries(entityIds: Collection<Long>): List<WorkStatsSummaryRow>

	@Query("DELETE FROM work_stats")
	abstract suspend fun clear()

	@Query("UPDATE work_stats SET entity_id = :newEntityId WHERE entity_id = :oldEntityId")
	protected abstract suspend fun remapEntityIdRaw(oldEntityId: Long, newEntityId: Long)

	@Query("DELETE FROM work_stats WHERE entity_id = :entityId AND started_at = :startedAt")
	protected abstract suspend fun deleteRow(entityId: Long, startedAt: Long)

	@Query(
		"""
		UPDATE work_stats
		SET anchor_manga_id = :newAnchorMangaId
		WHERE entity_id = :entityId
			AND anchor_manga_id = :oldAnchorMangaId
		""",
	)
	abstract suspend fun replaceAnchorMangaId(entityId: Long, oldAnchorMangaId: Long, newAnchorMangaId: Long)

	@Query("DELETE FROM work_stats WHERE entity_id = :entityId AND anchor_manga_id = :anchorMangaId")
	abstract suspend fun deleteByAnchorMangaId(entityId: Long, anchorMangaId: Long): Int

	/**
	 * Move every row from [oldEntityId] to [newEntityId]. Rows that collide on a
	 * target `(newEntityId, started_at)` key are merged via
	 * [mergeRestoredWorkStats] instead of letting the bulk UPDATE hit the
	 * `(entity_id, started_at)` primary-key constraint during restore / remap.
	 */
	@Transaction
	open suspend fun remapEntityId(oldEntityId: Long, newEntityId: Long) {
		if (oldEntityId == newEntityId) return
		val sources = findAll(oldEntityId)
		if (sources.isEmpty()) return
		val targetsByStartedAt = findAll(newEntityId).associateBy { it.startedAt }
		if (targetsByStartedAt.isEmpty()) {
			remapEntityIdRaw(oldEntityId, newEntityId)
			return
		}
		for (source in sources) {
			val moved = source.copy(entityId = newEntityId)
			val target = targetsByStartedAt[source.startedAt]
			deleteRow(oldEntityId, source.startedAt)
			if (target == null) {
				upsert(moved)
			} else {
				upsert(mergeRestoredWorkStats(target, moved))
			}
		}
	}

	@Transaction
	open suspend fun moveAnchorToEntity(oldEntityId: Long, newEntityId: Long, anchorMangaId: Long) {
		if (oldEntityId == newEntityId) return
		val sources = findAllForEntityAndAnchor(oldEntityId, anchorMangaId)
		if (sources.isEmpty()) return
		val targetsByStartedAt = findAll(newEntityId).associateBy { it.startedAt }
		for (source in sources) {
			val moved = source.copy(entityId = newEntityId)
			val target = targetsByStartedAt[source.startedAt]
			deleteRow(oldEntityId, source.startedAt)
			if (target == null) {
				upsert(moved)
			} else {
				upsert(mergeRestoredWorkStats(target, moved))
			}
		}
	}

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
