package org.skepsun.kototoro.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.entity.MangaEntity

const val TRACK_LOG_RETAINED_SIZE = 120

@Entity(
	tableName = "track_logs",
	indices = [
		Index(value = ["entity_id"]),
	],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
class TrackLogEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "owner_id", index = true) val ownerId: Long,
	@ColumnInfo(name = "manga_id", index = true) val mangaId: Long,
	@ColumnInfo(name = "entity_id") val entityId: Long? = null,
	@ColumnInfo(name = "chapters") val chapters: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "unread") val isUnread: Boolean,
)
