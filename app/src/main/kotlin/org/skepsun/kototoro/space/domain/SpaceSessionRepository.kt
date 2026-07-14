package org.skepsun.kototoro.space.domain

import kotlinx.serialization.Serializable

const val SPACE_ROUTE_SCHEMA_VERSION = 1
const val MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK = 20

@Serializable
sealed interface SpaceRouteSnapshot {

	@Serializable
	data class TopLevel(val key: String) : SpaceRouteSnapshot

	@Serializable
	data class WorkDetails(
		val entityId: Long,
		val requestedProjectionId: Long?,
	) : SpaceRouteSnapshot

	@Serializable
	data class ContentList(val sourceName: String) : SpaceRouteSnapshot
}

data class SpaceSessionSnapshot(
	val spaceId: SpaceId,
	val selectedTopLevel: String,
	val resumeRoute: SpaceRouteSnapshot?,
	val stacks: Map<String, List<SpaceRouteSnapshot>>,
	val lastAccessed: Long,
	val updatedAt: Long,
)

interface SpaceSessionRepository {

	suspend fun load(spaceId: SpaceId): SpaceSessionSnapshot?

	suspend fun save(snapshot: SpaceSessionSnapshot)

	suspend fun delete(spaceId: SpaceId)
}
