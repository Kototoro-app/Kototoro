package org.skepsun.kototoro.space.domain

import kotlinx.serialization.Serializable

const val SPACE_ROUTE_PREFERENCES_SCHEMA_VERSION = 1
const val MAIN_LIST_ROUTE_KEY = "main:list"

@Serializable
data class SpaceListPreferences(
    val listMode: String,
    val gridSize: Int,
    val historySortOrder: String? = null,
    val favoritesSortOrder: String? = null,
    val sourceTags: Set<String> = emptySet(),
    /** Display options of each page, keyed by `ListDisplaySection.id`; empty in preferences saved before they existed. */
    val sections: Map<String, SpaceSectionDisplay> = emptyMap(),
)

/** One page's layout, grid size and extra card info switch inside a space. */
@Serializable
data class SpaceSectionDisplay(
    val listMode: String,
    val gridSize: Int,
    val showExtraInfo: Boolean = false,
)

interface SpaceRoutePreferencesRepository {
    suspend fun load(spaceId: SpaceId, routeKey: String): SpaceListPreferences?
    suspend fun save(spaceId: SpaceId, routeKey: String, preferences: SpaceListPreferences)
    suspend fun delete(spaceId: SpaceId, routeKey: String)
}
