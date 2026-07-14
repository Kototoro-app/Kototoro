package org.skepsun.kototoro.space.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.TABLE_SPACE_SESSION

@Entity(tableName = TABLE_SPACE_SESSION)
data class SpaceSessionEntity(
	@PrimaryKey
	@ColumnInfo(name = "space_id") val spaceId: String,
	@ColumnInfo(name = "selected_top_level") val selectedTopLevel: String,
	@ColumnInfo(name = "resume_kind") val resumeKind: String,
	@ColumnInfo(name = "resume_entity_id") val resumeEntityId: Long?,
	@ColumnInfo(name = "resume_projection_id") val resumeProjectionId: Long?,
	@ColumnInfo(name = "resume_route") val resumeRoute: String?,
	@ColumnInfo(name = "route_schema_version") val routeSchemaVersion: Int,
	@ColumnInfo(name = "last_accessed") val lastAccessed: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
