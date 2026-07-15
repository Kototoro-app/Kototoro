package org.skepsun.kototoro.space.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
abstract class SpaceRoutePreferencesDao {

	@Query(
		"""
		SELECT * FROM space_route_preferences
		WHERE space_id = :spaceId AND route_key = :routeKey
		LIMIT 1
		""",
	)
	abstract suspend fun find(spaceId: String, routeKey: String): SpaceRoutePreferencesEntity?

	@Upsert
	abstract suspend fun upsert(entity: SpaceRoutePreferencesEntity)

	@Query("DELETE FROM space_route_preferences WHERE space_id = :spaceId AND route_key = :routeKey")
	abstract suspend fun delete(spaceId: String, routeKey: String)

	@Query("DELETE FROM space_route_preferences WHERE space_id = :spaceId")
	abstract suspend fun deleteForSpace(spaceId: String)
}
