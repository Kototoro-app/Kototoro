package org.skepsun.kototoro.space.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import org.skepsun.kototoro.core.db.TABLE_SPACE_NAVIGATION_ENTRY

@Entity(
	tableName = TABLE_SPACE_NAVIGATION_ENTRY,
	primaryKeys = ["space_id", "stack_key", "position"],
)
data class SpaceNavigationEntryEntity(
	@ColumnInfo(name = "space_id") val spaceId: String,
	@ColumnInfo(name = "stack_key") val stackKey: String,
	@ColumnInfo(name = "position") val position: Int,
	@ColumnInfo(name = "route_kind") val routeKind: String,
	@ColumnInfo(name = "route_payload") val routePayload: String?,
	@ColumnInfo(name = "route_schema_version") val routeSchemaVersion: Int,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
