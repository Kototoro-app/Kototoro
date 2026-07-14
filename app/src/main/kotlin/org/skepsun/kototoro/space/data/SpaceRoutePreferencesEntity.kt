package org.skepsun.kototoro.space.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import org.skepsun.kototoro.core.db.TABLE_SPACE_ROUTE_PREFERENCES

@Entity(
	tableName = TABLE_SPACE_ROUTE_PREFERENCES,
	primaryKeys = ["space_id", "route_key"],
)
data class SpaceRoutePreferencesEntity(
	@ColumnInfo(name = "space_id") val spaceId: String,
	@ColumnInfo(name = "route_key") val routeKey: String,
	@ColumnInfo(name = "payload") val payload: String,
	@ColumnInfo(name = "schema_version") val schemaVersion: Int,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
