package org.skepsun.kototoro.entitygraph.data

import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.work.domain.WorkResolver

suspend fun EntityGraphDao.findWorkEntityIdByLocalMangaId(mangaId: Long): Long? {
	return findActiveBinding("local_manga", mangaId.toString())?.entityId
		?: findActiveBinding("0", mangaId.toString())?.entityId
}

// The input mangaId is a local projection anchor. Once a work/entity exists, this resolves
// the owning work id; otherwise callers must keep using projection-local semantics.
suspend fun MangaDatabase.resolveWorkEntityIdByMangaId(mangaId: Long): Long? {
	return getEntityGraphDao().findWorkEntityIdByLocalMangaId(mangaId)
}

// Prefer work-owned tracking links whenever any candidate projection already belongs to a work.
// Only fall back to raw manga ids for not-yet-bound legacy records.
suspend fun MangaDatabase.findTrackingLinksByWorkOrMangaCandidates(
	service: Int? = null,
	mangaIds: List<Long>,
	workResolver: WorkResolver,
): List<TrackingSiteLinkEntity> {
	if (mangaIds.isEmpty()) {
		return emptyList()
	}
	val distinctIds = mangaIds.distinct()
	val entityIds = workResolver.resolveManyByMangaIds(distinctIds).values
		.mapNotNull { it.entityId }
		.distinct()
	val trackingDao = getTrackingSiteDao()
	return when {
		entityIds.isNotEmpty() && service != null -> trackingDao.findLinksByEntityIds(service, entityIds)
		entityIds.isNotEmpty() -> trackingDao.findLinksByEntityIds(entityIds)
		service != null -> trackingDao.findLinksByMangaIds(service, distinctIds)
		else -> trackingDao.findLinksByMangaIds(distinctIds)
	}
}

suspend fun MangaDatabase.findTrackingLinksByLegacyWorkOrMangaCandidates(
	service: Int? = null,
	mangaIds: List<Long>,
): List<TrackingSiteLinkEntity> {
	if (mangaIds.isEmpty()) {
		return emptyList()
	}
	val distinctIds = mangaIds.distinct()
	val entityIds = distinctIds.mapNotNull { resolveWorkEntityIdByMangaId(it) }.distinct()
	val trackingDao = getTrackingSiteDao()
	return when {
		entityIds.isNotEmpty() && service != null -> trackingDao.findLinksByEntityIds(service, entityIds)
		entityIds.isNotEmpty() -> trackingDao.findLinksByEntityIds(entityIds)
		service != null -> trackingDao.findLinksByMangaIds(service, distinctIds)
		else -> trackingDao.findLinksByMangaIds(distinctIds)
	}
}

// Deletion follows the same owner rule as reads: work/entity first, projection fallback second.
suspend fun MangaDatabase.deleteTrackingLinksByWorkOrMangaCandidates(
	service: Int,
	mangaIds: List<Long>,
	workResolver: WorkResolver,
) {
	if (mangaIds.isEmpty()) {
		return
	}
	val distinctIds = mangaIds.distinct()
	val entityIds = workResolver.resolveManyByMangaIds(distinctIds).values
		.mapNotNull { it.entityId }
		.distinct()
	val trackingDao = getTrackingSiteDao()
	if (entityIds.isNotEmpty()) {
		trackingDao.deleteLinksByEntityIds(service, entityIds)
	} else {
		trackingDao.deleteLinksByMangaIds(service, distinctIds)
	}
}

fun TrackingSiteDao.observeLinksByWorkOrMangaCandidates(
	entityId: Long?,
	mangaIds: List<Long>,
) = when {
	entityId != null -> observeLinksByEntityIds(listOf(entityId))
	mangaIds.isEmpty() -> flowOf(emptyList())
	else -> observeLinksByMangaIds(mangaIds.distinct())
}

suspend fun TrackingSiteDao.findLinksByWorkOrMangaCandidates(
	service: Int,
	entityId: Long?,
	mangaIds: List<Long>,
): List<TrackingSiteLinkEntity> {
	if (entityId == null && mangaIds.isEmpty()) {
		return emptyList()
	}
	return entityId?.let { findLinksByEntity(service, it) }
		?: findLinksByMangaIds(service, mangaIds.distinct())
}

suspend fun MangaDatabase.attachEntityOwnership(
	link: TrackingSiteLinkEntity,
	workResolver: WorkResolver,
): TrackingSiteLinkEntity {
	if (link.entityId != null || link.mangaId == 0L) {
		return link
	}
	val entityId = workResolver.resolveByMangaId(link.mangaId).entityId
	return if (link.entityId == entityId) {
		link
	} else {
		link.copy(entityId = entityId)
	}
}
