package org.skepsun.kototoro.space.data

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.space.domain.MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK
import org.skepsun.kototoro.space.domain.SPACE_ROUTE_SCHEMA_VERSION
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionRepository
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceSessionRepository internal constructor(
	private val dao: SpaceSessionDao,
	private val codec: SpaceRouteCodec,
	private val catalogRepository: SpaceCatalogRepository,
) : SpaceSessionRepository {

	@Inject
	constructor(database: MangaDatabase, codec: SpaceRouteCodec, catalogRepository: SpaceCatalogRepository) :
		this(database.getSpaceSessionDao(), codec, catalogRepository)

	override suspend fun load(spaceId: SpaceId): SpaceSessionSnapshot? {
		requireKnownSpace(spaceId)
		val session = dao.findSession(spaceId.value) ?: return null
		val stacks = dao.findNavigationEntries(spaceId.value)
			.groupBy(SpaceNavigationEntryEntity::stackKey)
			.mapValues { (_, entries) -> entries.decodeValidPrefix() }
			.filterValues { it.isNotEmpty() }
		val resumeRoute = if (session.resumeKind == SpaceRouteCodec.KIND_NONE) {
			null
		} else {
			codec.decode(
				kind = session.resumeKind,
				payload = session.resumeRoute,
				schemaVersion = session.routeSchemaVersion,
			)
		}
		return SpaceSessionSnapshot(
			spaceId = spaceId,
			selectedTopLevel = session.selectedTopLevel,
			resumeRoute = resumeRoute,
			stacks = stacks,
			lastAccessed = session.lastAccessed,
			updatedAt = session.updatedAt,
		)
	}

	override suspend fun save(snapshot: SpaceSessionSnapshot) {
		requireKnownSpace(snapshot.spaceId)
		val encodedResume = snapshot.resumeRoute?.let(codec::encode)
		val session = SpaceSessionEntity(
			spaceId = snapshot.spaceId.value,
			selectedTopLevel = snapshot.selectedTopLevel,
			resumeKind = encodedResume?.kind ?: SpaceRouteCodec.KIND_NONE,
			resumeEntityId = (snapshot.resumeRoute as? SpaceRouteSnapshot.WorkDetails)?.entityId,
			resumeProjectionId = (snapshot.resumeRoute as? SpaceRouteSnapshot.WorkDetails)?.requestedProjectionId,
			resumeRoute = encodedResume?.payload,
			routeSchemaVersion = SPACE_ROUTE_SCHEMA_VERSION,
			lastAccessed = snapshot.lastAccessed,
			updatedAt = snapshot.updatedAt,
		)
		val entries = snapshot.stacks.flatMap { (stackKey, routes) ->
			routes.limitForStorage().mapIndexed { position, route ->
				val encoded = codec.encode(route)
				SpaceNavigationEntryEntity(
					spaceId = snapshot.spaceId.value,
					stackKey = stackKey,
					position = position,
					routeKind = encoded.kind,
					routePayload = encoded.payload,
					routeSchemaVersion = SPACE_ROUTE_SCHEMA_VERSION,
					updatedAt = snapshot.updatedAt,
				)
			}
		}
		dao.replaceSnapshot(session, entries)
	}

	override suspend fun delete(spaceId: SpaceId) {
		requireKnownSpace(spaceId)
		dao.deleteSnapshot(spaceId.value)
	}

	private fun requireKnownSpace(spaceId: SpaceId) {
		require(catalogRepository.find(spaceId) != null) { "Unknown SpaceId: ${spaceId.value}" }
	}

	private fun List<SpaceNavigationEntryEntity>.decodeValidPrefix(): List<SpaceRouteSnapshot> {
		val result = ArrayList<SpaceRouteSnapshot>(size)
		for (entry in sortedBy(SpaceNavigationEntryEntity::position)) {
			val route = codec.decode(entry.routeKind, entry.routePayload, entry.routeSchemaVersion) ?: break
			result += route
		}
		return result
	}
}

internal fun List<SpaceRouteSnapshot>.limitForStorage(): List<SpaceRouteSnapshot> {
	if (size <= MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK) return this
	return listOf(first()) + takeLast(MAX_SPACE_NAVIGATION_ENTRIES_PER_STACK - 1)
}
