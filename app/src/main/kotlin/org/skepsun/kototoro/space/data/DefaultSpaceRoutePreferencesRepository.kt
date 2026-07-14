package org.skepsun.kototoro.space.data

import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SPACE_ROUTE_PREFERENCES_SCHEMA_VERSION
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceListPreferences
import org.skepsun.kototoro.space.domain.SpaceRoutePreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceRoutePreferencesRepository internal constructor(
	private val dao: SpaceRoutePreferencesDao,
	private val json: Json,
) : SpaceRoutePreferencesRepository {

	@Inject
	constructor(database: MangaDatabase, json: Json) : this(database.getSpaceRoutePreferencesDao(), json)

	private val builtInSpaceIds = BuiltInSpaces.contexts.mapTo(HashSet()) { it.id }

	override suspend fun load(spaceId: SpaceId, routeKey: String): SpaceListPreferences? {
		requireValidKey(spaceId, routeKey)
		val entity = dao.find(spaceId.value, routeKey) ?: return null
		if (entity.schemaVersion != SPACE_ROUTE_PREFERENCES_SCHEMA_VERSION) return null
		return runCatching {
			json.decodeFromString(SpaceListPreferences.serializer(), entity.payload)
		}.getOrNull()
	}

	override suspend fun save(
		spaceId: SpaceId,
		routeKey: String,
		preferences: SpaceListPreferences,
	) {
		requireValidKey(spaceId, routeKey)
		dao.upsert(
			SpaceRoutePreferencesEntity(
				spaceId = spaceId.value,
				routeKey = routeKey,
				payload = json.encodeToString(SpaceListPreferences.serializer(), preferences),
				schemaVersion = SPACE_ROUTE_PREFERENCES_SCHEMA_VERSION,
				updatedAt = System.currentTimeMillis(),
			),
		)
	}

	override suspend fun delete(spaceId: SpaceId, routeKey: String) {
		requireValidKey(spaceId, routeKey)
		dao.delete(spaceId.value, routeKey)
	}

	private fun requireValidKey(spaceId: SpaceId, routeKey: String) {
		require(spaceId in builtInSpaceIds) { "Unknown built-in SpaceId: ${spaceId.value}" }
		require(routeKey.isNotBlank()) { "routeKey must not be blank" }
	}
}
