package org.skepsun.kototoro.history.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.entitygraph.data.EntityRecord

@Entity(
	tableName = TABLE_WORK_HISTORY,
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(name = "idx_work_history_anchor_manga", value = ["anchor_manga_id"]),
		Index(name = "idx_work_history_updated_at", value = ["updated_at"]),
	],
)
data class WorkHistoryEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "entity_id") val entityId: Long,
	@ColumnInfo(name = "anchor_manga_id") val anchorMangaId: Long,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	@ColumnInfo(name = "scroll") val scroll: Float,
	@ColumnInfo(name = "percent") val percent: Float,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
	@ColumnInfo(name = "chapters") val chaptersCount: Int,
	@ColumnInfo(name = "parent_chapter_id") val parentChapterId: Long? = null,
)
