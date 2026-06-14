package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_TRACKING_SITE_LINKS

@Entity(
	tableName = TABLE_TRACKING_SITE_LINKS,
	primaryKeys = ["service", "remote_id", "manga_id"],
	indices = [
		Index(value = ["entity_id"]),
		Index(value = ["manga_id"]),
		Index(value = ["service", "entity_id"]),
		Index(value = ["service", "remote_id"]),
	],
)
data class TrackingSiteLinkEntity(
	@ColumnInfo(name = "service") val service: Int,
	@ColumnInfo(name = "remote_id") val remoteId: Long,
	@ColumnInfo(name = "entity_id") val entityId: Long? = null,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "source_name") val sourceName: String?,
	@ColumnInfo(name = "confidence") val confidence: Float,
	@ColumnInfo(name = "is_manual") val isManual: Boolean,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
