package org.skepsun.kototoro.scrobbling.common.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
	tableName = "scrobblings",
	primaryKeys = ["scrobbler", "id", "owner_id", "media_type"],
	indices = [
		Index(value = ["owner_id"]),
		Index(value = ["entity_id"]),
		Index(value = ["scrobbler", "entity_id"]),
		Index(value = ["scrobbler", "entity_id", "target_id", "media_type"]),
	],
)
data class ScrobblingEntity(
	@ColumnInfo(name = "scrobbler") val scrobbler: Int,
	@ColumnInfo(name = "id") val id: Int,
	@ColumnInfo(name = "owner_id") val ownerId: Long = 0L,
	@ColumnInfo(name = "entity_id") val entityId: Long? = null,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "target_id") val targetId: Long,
	@ColumnInfo(name = "status") val status: String?,
	@ColumnInfo(name = "chapter") val chapter: Int,
	@ColumnInfo(name = "comment") val comment: String?,
	@ColumnInfo(name = "rating") val rating: Float,
	@ColumnInfo(name = "media_type") val mediaType: String = "",
	@ColumnInfo(name = "remote_title") val remoteTitle: String? = null,
	@ColumnInfo(name = "remote_cover_url") val remoteCoverUrl: String? = null,
	@ColumnInfo(name = "remote_url") val remoteUrl: String? = null,
)

fun resolveScrobblingOwnerId(entityId: Long?, mangaId: Long): Long {
	return entityId ?: mangaId.takeIf { it != 0L }?.let { -it } ?: 0L
}
