package org.skepsun.kototoro.stats.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_WORK_STATS
import org.skepsun.kototoro.entitygraph.data.EntityRecord

@Entity(
	tableName = TABLE_WORK_STATS,
	primaryKeys = ["entity_id", "started_at"],
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(name = "idx_work_stats_entity", value = ["entity_id"]),
	],
)
data class WorkStatsEntity(
	@ColumnInfo(name = "entity_id") val entityId: Long,
	@ColumnInfo(name = "anchor_manga_id") val anchorMangaId: Long,
	@ColumnInfo(name = "started_at") val startedAt: Long,
	@ColumnInfo(name = "duration") val duration: Long,
	@ColumnInfo(name = "pages") val pages: Int,
)
