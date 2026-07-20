package org.skepsun.kototoro.work.domain

import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.parsers.model.Content

interface WorkResolver {

	suspend fun resolveByMangaId(mangaId: Long): WorkIdentity

	suspend fun resolveByEntityId(entityId: Long): WorkIdentity?

	suspend fun resolveBindingsByEntityId(entityId: Long): List<EntityBinding>

	suspend fun resolveManyByMangaIds(mangaIds: Collection<Long>): Map<Long, WorkIdentity>

	suspend fun ensureForProjection(
		content: Content,
		provenance: WorkIdentityProvenance = WorkIdentityProvenance.USER,
	): WorkIdentity

	suspend fun bindProjectionToEntity(
		targetEntityId: Long,
		projection: Content,
	): WorkProjectionBindingResult

	suspend fun selectPreferredProjection(entityId: Long): Long?
}

enum class WorkIdentityProvenance {
	USER,
	IMPORT,
	MIGRATION,
	RESTORE,
}
