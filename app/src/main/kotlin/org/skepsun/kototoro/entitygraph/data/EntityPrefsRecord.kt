package org.skepsun.kototoro.entitygraph.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES

@Serializable
@Entity(
	tableName = TABLE_ENTITY_PREFERENCES,
	foreignKeys = [
		ForeignKey(
			entity = EntityRecord::class,
			parentColumns = ["id"],
			childColumns = ["entity_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class EntityPrefsRecord(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "entity_id")
	val entityId: Long,
	@ColumnInfo(name = "preferred_local_manga_id")
	val preferredLocalMangaId: Long?,
	@ColumnInfo(name = "title_override")
	val titleOverride: String?,
	@ColumnInfo(name = "cover_override")
	val coverUrlOverride: String?,
	@ColumnInfo(name = "content_rating_override")
	val contentRatingOverride: String?,
	@ColumnInfo(name = "reading_status")
	val readingStatus: String?,
	@ColumnInfo(name = "metadata_source_kind")
	val metadataSourceKind: String?,
	@ColumnInfo(name = "metadata_binding_source")
	val metadataBindingSource: String?,
	@ColumnInfo(name = "metadata_binding_external_id")
	val metadataBindingExternalId: String?,
	@ColumnInfo(name = "metadata_source_service")
	val metadataSourceService: Int?,
	@ColumnInfo(name = "metadata_source_remote_id")
	val metadataSourceRemoteId: Long?,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long,
)
