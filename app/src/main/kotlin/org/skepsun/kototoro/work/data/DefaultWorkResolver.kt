package org.skepsun.kototoro.work.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import org.skepsun.kototoro.work.domain.WorkMigrationState
import org.skepsun.kototoro.work.domain.WorkProjectionBindingAction
import org.skepsun.kototoro.work.domain.WorkProjectionBindingConflict
import org.skepsun.kototoro.work.domain.WorkProjectionBindingResult
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.work.domain.isWorkContentTypeCompatibleWith
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_WORK_RESOLVER_QUERY_PARAMS = 500

@Singleton
class DefaultWorkResolver @Inject constructor(
	private val db: MangaDatabase,
	private val entityGraphRepository: EntityGraphRepository,
) : WorkResolver {

	override suspend fun resolveByMangaId(mangaId: Long): WorkIdentity = withContext(Dispatchers.IO) {
		resolveManyByMangaIds(listOf(mangaId))[mangaId] ?: reviewIdentity(requestedMangaId = mangaId)
	}

	override suspend fun resolveByEntityId(entityId: Long): WorkIdentity? = withContext(Dispatchers.IO) {
		val dao = db.getEntityGraphDao()
		val entity = dao.findEntity(entityId)
		if (entity?.type != EntityType.WORK.name) {
			return@withContext null
		}
		buildIdentity(
			entityId = entityId,
			requestedMangaId = null,
			bindings = dao.findActiveLocalBindingsByEntity(entityId),
			prefs = dao.findEntityPrefs(entityId),
		)
	}

	override suspend fun resolveBindingsByEntityId(entityId: Long) = withContext(Dispatchers.IO) {
		if (resolveByEntityId(entityId) == null) {
			emptyList()
		} else {
			entityGraphRepository.getBindings(entityId)
		}
	}

	override suspend fun resolveManyByMangaIds(mangaIds: Collection<Long>): Map<Long, WorkIdentity> = withContext(Dispatchers.IO) {
		val distinctMangaIds = mangaIds.distinct()
		if (distinctMangaIds.isEmpty()) {
			return@withContext emptyMap()
		}
		val dao = db.getEntityGraphDao()
		val bindingsByMangaId = LinkedHashMap<Long, EntityBindingRecord>()
		distinctMangaIds.map(Long::toString).chunked(MAX_WORK_RESOLVER_QUERY_PARAMS).forEach { chunk ->
			dao.findActiveBindingsBySources(
				sources = listOf("local_manga", "0"),
				externalIds = chunk,
			).forEach { binding ->
				val localMangaId = binding.externalId.toLongOrNull() ?: return@forEach
				val existing = bindingsByMangaId[localMangaId]
				if (existing == null || (existing.source != "local_manga" && binding.source == "local_manga")) {
					bindingsByMangaId[localMangaId] = binding
				}
			}
		}
		val bindingsByEntityId = bindingsByMangaId.values
			.map { it.entityId }
			.distinct()
			.chunked(MAX_WORK_RESOLVER_QUERY_PARAMS)
			.flatMap { dao.findActiveLocalBindingsByEntities(it) }
			.groupBy { it.entityId }
		val prefsByEntityId = bindingsByEntityId.keys
			.chunked(MAX_WORK_RESOLVER_QUERY_PARAMS)
			.flatMap { dao.findEntityPrefsByIds(it) }
			.associateBy { it.entityId }

		distinctMangaIds.associateWithTo(LinkedHashMap()) { mangaId ->
			val binding = bindingsByMangaId[mangaId]
			if (binding == null) {
				reviewIdentity(requestedMangaId = mangaId)
			} else {
				buildIdentity(
					entityId = binding.entityId,
					requestedMangaId = mangaId,
					bindings = bindingsByEntityId[binding.entityId].orEmpty(),
					prefs = prefsByEntityId[binding.entityId],
				)
			}
		}
	}

	override suspend fun ensureForProjection(
		content: Content,
		provenance: WorkIdentityProvenance,
	): WorkIdentity = withContext(Dispatchers.IO) {
		val entity = entityGraphRepository.ensureLocalWorkEntity(
			content = content,
			createdBy = provenance.toBindingCreatedBy(),
		)
		resolveByEntityId(entity.id) ?: WorkIdentity(
			entityId = entity.id,
			requestedMangaId = content.id,
			preferredMangaId = content.id,
			localMangaIds = setOf(content.id),
			migrationState = WorkMigrationState.VALID,
		)
	}

	override suspend fun bindProjectionToEntity(
		targetEntityId: Long,
		projection: Content,
	): WorkProjectionBindingResult = withContext(Dispatchers.IO) {
		val targetEntity = entityGraphRepository.getEntity(targetEntityId)
		if (targetEntity?.type != EntityType.WORK) {
			return@withContext bindingConflict(
				reason = WorkProjectionBindingConflict.TARGET_ENTITY_MISSING,
				targetEntityId = targetEntityId,
				projectionId = projection.id,
			)
		}
		if (!targetEntity.contentType.isWorkContentTypeCompatibleWith(projection.source.contentType)) {
			return@withContext bindingConflict(
				reason = WorkProjectionBindingConflict.TARGET_CONTENT_TYPE_CONFLICT,
				targetEntityId = targetEntityId,
				projectionId = projection.id,
			)
		}

		val currentIdentity = resolveByMangaId(projection.id)
		val sourceEntityId = currentIdentity.entityId
		val action = when {
			sourceEntityId == null -> {
				val attached = entityGraphRepository.attachLocalWorkProjectionToEntity(
					entityId = targetEntityId,
					content = projection,
					selectAsPreferred = true,
				)
				if (!attached) {
					return@withContext bindingConflict(
						reason = WorkProjectionBindingConflict.BINDING_FAILED,
						targetEntityId = targetEntityId,
						projectionId = projection.id,
					)
				}
				WorkProjectionBindingAction.ATTACHED
			}

			sourceEntityId == targetEntityId -> {
				if (!entityGraphRepository.selectPreferredLocalWorkProjection(targetEntityId, projection.id)) {
					return@withContext bindingConflict(
						reason = WorkProjectionBindingConflict.OWNER_CHANGED,
						targetEntityId = targetEntityId,
						sourceEntityId = sourceEntityId,
						projectionId = projection.id,
					)
				}
				WorkProjectionBindingAction.REUSED
			}

			else -> {
				val sourceIdentity = resolveByEntityId(sourceEntityId)
				if (sourceIdentity == null || projection.id !in sourceIdentity.localMangaIds) {
					return@withContext bindingConflict(
						reason = WorkProjectionBindingConflict.SOURCE_IDENTITY_INVALID,
						targetEntityId = targetEntityId,
						sourceEntityId = sourceEntityId,
						projectionId = projection.id,
					)
				}
				if (sourceIdentity.localMangaIds.size == 1) {
					val mergedEntityId = entityGraphRepository.mergeEntities(
						targetEntityId = targetEntityId,
						sourceEntityIds = listOf(sourceEntityId),
						preferredLocalMangaId = projection.id,
						allowCompatibleContentTypes = true,
					)
					if (mergedEntityId != targetEntityId) {
						return@withContext bindingConflict(
							reason = WorkProjectionBindingConflict.BINDING_FAILED,
							targetEntityId = targetEntityId,
							sourceEntityId = sourceEntityId,
							projectionId = projection.id,
						)
					}
					WorkProjectionBindingAction.MERGED_SINGLE_PROJECTION_WORK
				} else {
					val moved = entityGraphRepository.moveLocalWorkProjectionToEntity(
						localMangaId = projection.id,
						targetEntityId = targetEntityId,
						expectedSourceEntityId = sourceEntityId,
						selectAsPreferred = true,
					)
					if (!moved.isSuccess) {
						return@withContext bindingConflict(
							reason = moved.failure.toBindingConflict(),
							targetEntityId = targetEntityId,
							sourceEntityId = sourceEntityId,
							projectionId = projection.id,
						)
					}
					WorkProjectionBindingAction.MOVED_PROJECTION
				}
			}
		}

		val authoritativeIdentity = resolveByMangaId(projection.id)
		if (authoritativeIdentity.entityId != targetEntityId || projection.id !in authoritativeIdentity.localMangaIds) {
			return@withContext bindingConflict(
				reason = WorkProjectionBindingConflict.OWNER_CHANGED,
				targetEntityId = targetEntityId,
				sourceEntityId = authoritativeIdentity.entityId,
				projectionId = projection.id,
			)
		}
		WorkProjectionBindingResult.Success(
			entityId = targetEntityId,
			projection = projection,
			action = action,
		)
	}

	override suspend fun selectPreferredProjection(entityId: Long): Long? = withContext(Dispatchers.IO) {
		val dao = db.getEntityGraphDao()
		val prefs = dao.findEntityPrefs(entityId)
		val localMangaIds = dao.findActiveLocalBindingsByEntity(entityId)
			.localMangaIds()
		selectPreferredProjection(localMangaIds, prefs)
	}

	private fun buildIdentity(
		entityId: Long,
		requestedMangaId: Long?,
		bindings: List<EntityBindingRecord>,
		prefs: EntityPrefsRecord?,
	): WorkIdentity {
		val localMangaIds = bindings.localMangaIds()
		val preferredMangaId = selectPreferredProjection(localMangaIds, prefs)
		return WorkIdentity(
			entityId = entityId,
			requestedMangaId = requestedMangaId,
			preferredMangaId = preferredMangaId,
			localMangaIds = localMangaIds,
			migrationState = WorkMigrationState.VALID,
		)
	}

	private fun reviewIdentity(requestedMangaId: Long): WorkIdentity = WorkIdentity(
		entityId = null,
		requestedMangaId = requestedMangaId,
		preferredMangaId = null,
		localMangaIds = emptySet(),
		migrationState = WorkMigrationState.NEEDS_REVIEW,
	)

	private fun selectPreferredProjection(
		localMangaIds: Set<Long>,
		prefs: EntityPrefsRecord?,
	): Long? {
		return prefs?.preferredLocalMangaId?.takeIf { it in localMangaIds }
			?: localMangaIds.firstOrNull()
	}

	private fun List<EntityBindingRecord>.localMangaIds(): Set<Long> {
		return asSequence()
			.filter { it.source == "local_manga" || it.source == "0" }
			.mapNotNull { it.externalId.toLongOrNull() }
			.toCollection(LinkedHashSet())
	}

	private fun bindingConflict(
		reason: WorkProjectionBindingConflict,
		targetEntityId: Long,
		sourceEntityId: Long? = null,
		projectionId: Long? = null,
	): WorkProjectionBindingResult.Conflict {
		return WorkProjectionBindingResult.Conflict(
			reason = reason,
			targetEntityId = targetEntityId,
			sourceEntityId = sourceEntityId,
			projectionId = projectionId,
		)
	}

	private fun EntityGraphRepository.MoveLocalWorkProjectionFailure?.toBindingConflict(): WorkProjectionBindingConflict {
		return when (this) {
			EntityGraphRepository.MoveLocalWorkProjectionFailure.CONTENT_TYPE_CONFLICT ->
				WorkProjectionBindingConflict.TARGET_CONTENT_TYPE_CONFLICT
			EntityGraphRepository.MoveLocalWorkProjectionFailure.OWNER_CHANGED ->
				WorkProjectionBindingConflict.OWNER_CHANGED
			EntityGraphRepository.MoveLocalWorkProjectionFailure.NO_ACTIVE_LOCAL_BINDING,
			EntityGraphRepository.MoveLocalWorkProjectionFailure.SOURCE_ENTITY_MISSING,
			EntityGraphRepository.MoveLocalWorkProjectionFailure.LOCAL_CONTENT_MISSING,
				-> WorkProjectionBindingConflict.SOURCE_IDENTITY_INVALID
			else -> WorkProjectionBindingConflict.BINDING_FAILED
		}
	}

	private fun WorkIdentityProvenance.toBindingCreatedBy(): EntityBindingCreatedBy = when (this) {
		WorkIdentityProvenance.USER -> EntityBindingCreatedBy.USER
		WorkIdentityProvenance.IMPORT -> EntityBindingCreatedBy.SYNC
		WorkIdentityProvenance.MIGRATION -> EntityBindingCreatedBy.MIGRATION
		WorkIdentityProvenance.RESTORE -> EntityBindingCreatedBy.SYNC
	}
}
