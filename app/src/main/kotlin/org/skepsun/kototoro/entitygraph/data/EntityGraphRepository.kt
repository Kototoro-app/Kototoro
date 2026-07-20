package org.skepsun.kototoro.entitygraph.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingMatcher
import org.skepsun.kototoro.entitygraph.domain.EntityBindingStrength
import org.skepsun.kototoro.entitygraph.domain.EntityRelationOrigin
import org.skepsun.kototoro.entitygraph.domain.EntityRelationState
import org.skepsun.kototoro.entitygraph.domain.EntityGraphRepairIssue
import org.skepsun.kototoro.entitygraph.domain.EntityGraphRepairIssueKind
import org.skepsun.kototoro.entitygraph.domain.EntityGraphRepairReport
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.Relation
import org.skepsun.kototoro.entitygraph.domain.RelationType
import org.skepsun.kototoro.entitygraph.domain.TrackingCharacterDto
import org.skepsun.kototoro.entitygraph.domain.TrackingPersonDto
import org.skepsun.kototoro.entitygraph.domain.TrackingStaffDto
import org.skepsun.kototoro.entitygraph.domain.TrackingWorkDto
import org.skepsun.kototoro.entitygraph.domain.normalizeStrictTitleKey
import org.skepsun.kototoro.entitygraph.domain.stripEntityDisambiguationTitleSuffix
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.toTrackingServiceOrNull
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.mergeRestoredWorkFavourites
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.history.data.mergeRestoredWorkHistory
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.stats.data.mergeRestoredWorkStats
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import org.skepsun.kototoro.tracking.malsync.data.MALSyncMappingRepository
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.work.data.WorkMigrationLedgerEntity
import org.skepsun.kototoro.work.domain.isWorkContentTypeCompatibleWith
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTITY_SCAN_LIMIT = 120
private const val RELATION_WEIGHT_DEFAULT = 1f
private const val STALE_ENTITY_DAYS = 30L
private const val STALE_ENTITY_ACCESS_THRESHOLD = 2
private const val MAX_BINDING_QUERY_PARAMS = 500
private const val MAX_ENTITY_ALIASES = 50
private const val TAG = "EntityGraphRepository"
private const val MAX_REPAIR_DIAGNOSTIC_LOGS = 80
private const val LOCAL_MANGA_BINDING_SOURCE = "local_manga"
internal const val WORK_PROJECTION_IDENTITY_ACTION_TABLE = "work_projection_identity_action"
internal const val WORK_PROJECTION_IDENTITY_ACTION_VERSION = 1
internal const val WORK_PROJECTION_IDENTITY_STATUS_ACTIVE = "ACTIVE"
internal const val WORK_PROJECTION_IDENTITY_STATUS_MERGED_BACK = "MERGED_BACK"
internal const val WORK_PROJECTION_IDENTITY_ACTION_SPLIT = "SPLIT"
internal const val WORK_PROJECTION_IDENTITY_ACTION_DETACH = "DETACH"
internal const val WORK_PROJECTION_IDENTITY_ACTION_MOVE = "MOVE"

@Singleton
class EntityGraphRepository @Inject constructor(
	private val db: MangaDatabase,
	private val bindingMatcher: EntityBindingMatcher,
	private val animeOfflineRepository: AnimeOfflineRepository,
	private val malsyncMappingRepository: MALSyncMappingRepository,
	private val settings: AppSettings,
) {

	suspend fun ingestWorkFromTracking(
		source: String,
		workDto: TrackingWorkDto,
	): Entity = withContext(Dispatchers.Default) {
		db.withTransaction {
			val now = System.currentTimeMillis()
			val work = resolveOrCreateEntity(
				type = EntityType.WORK,
				primaryName = workDto.primaryName,
				aliases = workDto.aliases,
				source = source,
				externalId = workDto.externalId,
				contentType = workDto.contentType,
				now = now,
			)
			val relationSource = RelationSourceKey(
				source = source,
				externalId = workDto.externalId,
			)

			workDto.characters.forEach { character ->
				val characterEntity = resolveOrCreateCharacter(
					source = source,
					workEntity = work,
					character = character,
					now = now,
					relationSource = relationSource,
				)
				insertRelationIfAbsent(
					fromEntityId = work.id,
					toEntityId = characterEntity.id,
					type = RelationType.HAS_CHARACTER,
					now = now,
					relationSource = relationSource,
				)
				character.voiceActors.forEach { actor ->
					val actorEntity = resolveOrCreatePerson(
						source = source,
						person = actor,
						now = now,
					)
					insertRelationIfAbsent(
						fromEntityId = characterEntity.id,
						toEntityId = actorEntity.id,
						type = RelationType.VOICED_BY,
						now = now,
						relationSource = relationSource,
					)
				}
			}

			workDto.staff.forEach { staff ->
				val personEntity = resolveOrCreateStaff(
					source = source,
					staff = staff,
					now = now,
				)
				insertRelationIfAbsent(
					fromEntityId = work.id,
					toEntityId = personEntity.id,
					type = RelationType.CREATED_BY,
					now = now,
					relationSource = relationSource,
				)
			}

			work
		}
	}

	suspend fun findEntityByBinding(
		source: String,
		externalId: String,
	): Entity? = withContext(Dispatchers.Default) {
		val dao = db.getEntityGraphDao()
		val binding = findBindingBySourceKey(source, externalId) ?: return@withContext null
		dao.touchEntity(binding.entityId, System.currentTimeMillis())
		dao.findEntity(binding.entityId)?.toModel()
	}

	fun observeEntity(entityId: Long): Flow<Entity?> {
		return db.getEntityGraphDao().observeEntity(entityId).map { it?.toModel() }
	}

	suspend fun getEntity(entityId: Long): Entity? = withContext(Dispatchers.Default) {
		val entity = db.getEntityGraphDao().findEntity(entityId)?.toModel()
		if (entity != null) {
			db.getEntityGraphDao().touchEntity(entityId, System.currentTimeMillis())
		}
		entity
	}

	suspend fun getEntitiesByIds(entityIds: Collection<Long>): List<Entity> = withContext(Dispatchers.Default) {
		if (entityIds.isEmpty()) {
			return@withContext emptyList()
		}
		db.getEntityGraphDao().findEntitiesByIds(entityIds.distinct()).map { it.toModel() }
	}

	suspend fun getBindings(entityId: Long): List<EntityBinding> = withContext(Dispatchers.Default) {
		db.getEntityGraphDao().findActiveBindingsByEntity(entityId).map { it.toModel() }
	}

	suspend fun findLocalReadingBinding(localMangaId: Long): EntityBinding? = withContext(Dispatchers.Default) {
		findEntityByLocalMangaId(localMangaId)?.toModel()
	}

	suspend fun attachLocalReadingBinding(
		entityId: Long,
		localMangaId: Long,
		confidence: Float = 1f,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.USER,
	): Boolean = withContext(Dispatchers.Default) {
		if (entityId <= 0L || localMangaId == 0L) {
			return@withContext false
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			dao.findEntity(entityId) ?: return@withTransaction false
			val externalId = localMangaId.toString()
			if (
				dao.findBinding("local_manga", externalId) != null ||
				dao.findBinding("0", externalId) != null
			) {
				return@withTransaction false
			}
			dao.upsertBindingForSource(
				entityId = entityId,
				source = "local_manga",
				externalId = externalId,
				confidence = confidence,
				createdBy = createdBy,
			)
			true
		}
	}

	suspend fun removeLocalReadingBinding(localMangaId: Long): Unit = withContext(Dispatchers.Default) {
		if (localMangaId == 0L) {
			return@withContext
		}
		val dao = db.getEntityGraphDao()
		Log.i(TAG, "removeLocalReadingBinding: mangaId=$localMangaId, before: local_manga=${dao.findBinding("local_manga", localMangaId.toString())?.let { "entityId=${it.entityId} state=${it.state}" }}, 0=${dao.findBinding("0", localMangaId.toString())?.let { "entityId=${it.entityId} state=${it.state}" }}")
		deleteLocalReadingBinding(dao, localMangaId.toString())
		Log.i(TAG, "removeLocalReadingBinding: mangaId=$localMangaId, after: local_manga=${dao.findBinding("local_manga", localMangaId.toString())?.let { "entityId=${it.entityId}" }}, 0=${dao.findBinding("0", localMangaId.toString())?.let { "entityId=${it.entityId}" }}")
	}

	suspend fun detachLocalWorkProjection(localMangaId: Long): Boolean = withContext(Dispatchers.Default) {
		if (localMangaId == 0L) {
			return@withContext false
		}
		val content = db.getMangaDao().find(localMangaId)?.toContent()
		val split = splitLocalWorkProjectionInTransaction(localMangaId, content)
		val newEntityId = split.newEntityId ?: return@withContext false
		recordProjectionIdentityAction(
			localMangaId = localMangaId,
			oldEntityId = split.oldEntityId,
			newEntityId = newEntityId,
			action = WORK_PROJECTION_IDENTITY_ACTION_DETACH,
			status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
		)
		db.getWorkFavouritesDao().delete(newEntityId)
		db.getWorkHistoryDao().delete(newEntityId)
		true
	}

	suspend fun splitLocalWorkProjection(content: Content): Long? = withContext(Dispatchers.Default) {
		if (content.id == 0L) {
			return@withContext null
		}
		splitLocalWorkProjectionInTransaction(content)?.also { newEntityId ->
			val ledger = db.getWorkMigrationLedgerDao().findLatest(
				legacyTable = WORK_PROJECTION_IDENTITY_ACTION_TABLE,
				legacyKey = content.id.toString(),
			)
			if (ledger == null || ledger.targetEntityId != newEntityId) {
				recordProjectionIdentityAction(
					localMangaId = content.id,
					oldEntityId = null,
					newEntityId = newEntityId,
					action = WORK_PROJECTION_IDENTITY_ACTION_SPLIT,
					status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
				)
			}
		}
	}

	suspend fun splitLocalWorkProjection(localMangaId: Long): Long? = withContext(Dispatchers.Default) {
		splitLocalWorkProjectionWithDiagnostics(localMangaId).newEntityId
	}

	suspend fun ensureIndependentLocalWorkEntity(content: Content): Entity = withContext(Dispatchers.Default) {
		require(content.id != 0L) { "Cannot create an independent Work entity for a transient projection" }
		splitLocalWorkProjectionInTransaction(content)?.let { newEntityId ->
			return@withContext requireNotNull(db.getEntityGraphDao().findEntity(newEntityId)).toModel()
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			deleteLocalProjectionBindings(dao, content)
			createDetachedLocalWorkEntity(
				content = content,
				now = now,
			)
		}
	}

	suspend fun splitLocalWorkProjectionWithDiagnostics(
		localMangaId: Long,
	): SplitLocalWorkProjectionResult = withContext(Dispatchers.Default) {
		if (localMangaId == 0L) {
			return@withContext SplitLocalWorkProjectionResult.failed(
				localMangaId = localMangaId,
				reason = SplitLocalWorkProjectionFailure.INVALID_LOCAL_ID,
			)
		}
		val content = db.getMangaDao().find(localMangaId)?.toContent()
		splitLocalWorkProjectionInTransaction(localMangaId, content)
	}

	private suspend fun splitLocalWorkProjectionInTransaction(content: Content): Long? {
		return db.withTransaction {
			val dao = db.getEntityGraphDao()
			val existing = findEntityByLocalMangaId(content.id) ?: return@withTransaction null
			val existingEntity = dao.findEntity(existing.entityId) ?: return@withTransaction null
			val now = System.currentTimeMillis()
			deleteLocalProjectionBindings(dao, content)
			val entity = createDetachedLocalWorkEntity(
				content = content,
				now = now,
			)
			resetDetachedLocalWorkPrefs(
				dao = dao,
				entityId = entity.id,
				localMangaId = content.id,
				now = now,
			)
			moveDetachedLocalWorkState(
				oldEntityId = existingEntity.id,
				newEntityId = entity.id,
				localMangaId = content.id,
			)
			recordProjectionIdentityActionInTransaction(
				localMangaId = content.id,
				oldEntityId = existingEntity.id,
				newEntityId = entity.id,
				action = WORK_PROJECTION_IDENTITY_ACTION_SPLIT,
				status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
				now = now,
			)
			reconcileSourceEntityWorkStateAfterProjectionSplit(
				dao = dao,
				entityId = existingEntity.id,
				detachedLocalMangaId = content.id,
				now = now,
			)
			updateEntityAfterLocalProjectionSplit(
				dao = dao,
				entity = existingEntity,
				namesToRemove = content.localProjectionNameKeys(),
				now = now,
			)
			entity.id
		}
	}

	private suspend fun splitLocalWorkProjectionInTransaction(
		localMangaId: Long,
		content: Content?,
	): SplitLocalWorkProjectionResult {
		return db.withTransaction {
			val dao = db.getEntityGraphDao()
			val existingBinding = findEntityByLocalMangaId(localMangaId)
				?: return@withTransaction SplitLocalWorkProjectionResult.failed(
					localMangaId = localMangaId,
					reason = SplitLocalWorkProjectionFailure.NO_ACTIVE_LOCAL_BINDING,
					hadLocalContent = content != null,
				)
			val existingEntity = dao.findEntity(existingBinding.entityId)
				?: return@withTransaction SplitLocalWorkProjectionResult.failed(
					localMangaId = localMangaId,
					reason = SplitLocalWorkProjectionFailure.BOUND_ENTITY_MISSING,
					oldEntityId = existingBinding.entityId,
					oldSource = existingBinding.source,
					hadLocalContent = content != null,
				)
			val oldEntityBindings = dao.findBindingsByEntity(existingEntity.id)
				.filter { it.isActiveBinding() }
			val oldEntityLocalMangaIds = oldEntityBindings
				.filter { it.isLocalReadingSource() }
				.mapNotNull { it.externalId.toLongOrNull() }
			Log.i(TAG, "splitLocalWork: entityId=${existingEntity.id} name=${existingEntity.primaryName} " +
				"nameHash=${existingEntity.nameHash} type=${existingEntity.type} " +
				"bindings=${oldEntityBindings.size} localMangaIds=$oldEntityLocalMangaIds " +
				"splittingMangaId=$localMangaId content=${content?.let { "${it.title}(${it.id})" }}")
			val now = System.currentTimeMillis()
			if (content != null) {
				deleteLocalProjectionBindings(dao, content)
			} else {
				deleteLocalProjectionBindings(dao, localMangaId)
			}
			val entity = if (content != null) {
				createDetachedLocalWorkEntity(
					content = content,
					now = now,
				)
			} else {
				createDetachedLocalWorkEntity(
					localMangaId = localMangaId,
					previousEntity = existingEntity,
					now = now,
				)
			}
			val newEntity = dao.findEntity(entity.id)
			Log.i(TAG, "splitLocalWork: newEntityId=${entity.id} " +
				"name=${newEntity?.primaryName} nameHash=${newEntity?.nameHash} " +
				"aliases=${newEntity?.let { decodeStringList(it.aliases) }} " +
				"oldEntityId=${existingEntity.id}")
			resetDetachedLocalWorkPrefs(
				dao = dao,
				entityId = entity.id,
				localMangaId = localMangaId,
				now = now,
			)
			moveDetachedLocalWorkState(
				oldEntityId = existingEntity.id,
				newEntityId = entity.id,
				localMangaId = localMangaId,
			)
			recordProjectionIdentityActionInTransaction(
				localMangaId = localMangaId,
				oldEntityId = existingEntity.id,
				newEntityId = entity.id,
				action = WORK_PROJECTION_IDENTITY_ACTION_SPLIT,
				status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
				now = now,
			)
			reconcileSourceEntityWorkStateAfterProjectionSplit(
				dao = dao,
				entityId = existingEntity.id,
				detachedLocalMangaId = localMangaId,
				now = now,
			)
			updateEntityAfterLocalProjectionSplit(
				dao = dao,
				entity = existingEntity,
				namesToRemove = content?.localProjectionNameKeys().orEmpty(),
				now = now,
			)
			SplitLocalWorkProjectionResult(
				localMangaId = localMangaId,
				oldEntityId = existingEntity.id,
				newEntityId = entity.id,
				oldSource = existingBinding.source,
				hadLocalContent = content != null,
			)
		}
	}

	suspend fun mergeDetachedProjectionBack(
		localMangaId: Long,
		targetEntityId: Long,
	): Boolean = withContext(Dispatchers.Default) {
		if (localMangaId == 0L || targetEntityId == 0L) {
			return@withContext false
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val content = db.getMangaDao().find(localMangaId)?.toContent() ?: return@withTransaction false
			val sourceEntityId = findEntityByLocalMangaId(localMangaId)?.entityId
			if (sourceEntityId == targetEntityId) {
				return@withTransaction true
			}
			dao.findEntity(targetEntityId) ?: return@withTransaction false
			val now = System.currentTimeMillis()
			deleteLocalProjectionBindings(dao, content)
			dao.attachLocalWorkBindingForMerge(
				entityId = targetEntityId,
				externalId = localMangaId.toString(),
				now = now,
			)
			dao.attachProjectionBindingWithoutSyncIdRewrite(
				entityId = targetEntityId,
				content = content,
				now = now,
			)
			if (sourceEntityId != null) {
				db.getWorkFavouritesDao().moveAnchorToEntity(
					oldEntityId = sourceEntityId,
					newEntityId = targetEntityId,
					anchorMangaId = localMangaId,
				)
				db.getWorkHistoryDao().moveAnchorToEntity(
					oldEntityId = sourceEntityId,
					newEntityId = targetEntityId,
					anchorMangaId = localMangaId,
				)
				db.getWorkStatsDao().moveAnchorToEntity(
					oldEntityId = sourceEntityId,
					newEntityId = targetEntityId,
					anchorMangaId = localMangaId,
				)
				reconcileSourceEntityWorkStateAfterProjectionSplit(
					dao = dao,
					entityId = sourceEntityId,
					detachedLocalMangaId = localMangaId,
					now = now,
				)
			}
			dao.touchEntity(targetEntityId, now)
			if (sourceEntityId != null) {
				recordProjectionIdentityActionInTransaction(
					localMangaId = localMangaId,
					oldEntityId = targetEntityId,
					newEntityId = sourceEntityId,
					action = WORK_PROJECTION_IDENTITY_ACTION_DETACH,
					status = WORK_PROJECTION_IDENTITY_STATUS_MERGED_BACK,
					now = now,
				)
				val remainingLocalBindings = dao.findActiveLocalBindingsByEntity(sourceEntityId)
				val hasState = db.getWorkFavouritesDao().findActiveForEntity(sourceEntityId) != null ||
					db.getWorkHistoryDao().find(sourceEntityId)?.deletedAt == 0L ||
					db.getWorkStatsDao().getRowCount(sourceEntityId) > 0
				if (remainingLocalBindings.isEmpty() && !hasState) {
					dao.deleteEntitiesByIds(listOf(sourceEntityId))
				}
			}
			true
		}
	}

	suspend fun moveLocalWorkProjectionToEntity(
		localMangaId: Long,
		targetEntityId: Long,
		expectedSourceEntityId: Long,
		selectAsPreferred: Boolean = false,
	): MoveLocalWorkProjectionResult = withContext(Dispatchers.Default) {
		if (localMangaId == 0L || targetEntityId == 0L || expectedSourceEntityId == 0L) {
			return@withContext MoveLocalWorkProjectionResult.failed(
				localMangaId = localMangaId,
				sourceEntityId = expectedSourceEntityId,
				targetEntityId = targetEntityId,
				reason = MoveLocalWorkProjectionFailure.INVALID_ARGUMENT,
			)
		}
		try {
			db.withTransaction {
				val dao = db.getEntityGraphDao()
				val content = db.getMangaDao().find(localMangaId)?.toContent()
					?: return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.LOCAL_CONTENT_MISSING,
					)
				val existingBinding = findEntityByLocalMangaId(localMangaId)
					?: return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.NO_ACTIVE_LOCAL_BINDING,
					)
				if (existingBinding.entityId == targetEntityId) {
					if (selectAsPreferred) {
						setPreferredLocalProjectionInTransaction(
							dao = dao,
							entityId = targetEntityId,
							localMangaId = localMangaId,
							now = System.currentTimeMillis(),
						)
					}
					return@withTransaction MoveLocalWorkProjectionResult(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
					)
				}
				if (existingBinding.entityId != expectedSourceEntityId) {
					return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = existingBinding.entityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.OWNER_CHANGED,
					)
				}
				val sourceEntity = dao.findEntity(expectedSourceEntityId)
					?: return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.SOURCE_ENTITY_MISSING,
					)
				val targetEntity = dao.findEntity(targetEntityId)
					?: return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.TARGET_ENTITY_MISSING,
					)
				val typedTarget = targetEntity.withInferredContentType(
					dao = dao,
					fallback = content.source.contentType,
				)
				if (!typedTarget.acceptsCompatibleWorkContentType(content.source.contentType)) {
					return@withTransaction MoveLocalWorkProjectionResult.failed(
						localMangaId = localMangaId,
						sourceEntityId = expectedSourceEntityId,
						targetEntityId = targetEntityId,
						reason = MoveLocalWorkProjectionFailure.CONTENT_TYPE_CONFLICT,
					)
				}

				val now = System.currentTimeMillis()
				deleteLocalProjectionBindings(dao, content)
				dao.attachLocalWorkBindingForMerge(
					entityId = targetEntityId,
					externalId = localMangaId.toString(),
					now = now,
					confidence = existingBinding.confidence,
				)
				dao.attachProjectionBindingWithoutSyncIdRewrite(
					entityId = targetEntityId,
					content = content,
					now = now,
					confidence = existingBinding.confidence,
				)
				val movedOwner = findEntityByLocalMangaId(localMangaId)?.entityId
				if (movedOwner != targetEntityId) {
					throw MoveLocalWorkProjectionTransactionException(
						MoveLocalWorkProjectionFailure.REBIND_FAILED,
					)
				}

				reconcileSourceEntityWorkStateAfterProjectionSplit(
					dao = dao,
					entityId = expectedSourceEntityId,
					detachedLocalMangaId = localMangaId,
					now = now,
				)
				updateEntityAfterLocalProjectionSplit(
					dao = dao,
					entity = sourceEntity,
					namesToRemove = content.localProjectionNameKeys(),
					now = now,
				)
				if (typedTarget != targetEntity) {
					dao.updateEntity(typedTarget.copy(lastAccessed = now))
				}
				if (selectAsPreferred) {
					setPreferredLocalProjectionInTransaction(
						dao = dao,
						entityId = targetEntityId,
						localMangaId = localMangaId,
						now = now,
					)
				}
				dao.touchEntity(targetEntityId, now)
				recordProjectionIdentityActionInTransaction(
					localMangaId = localMangaId,
					oldEntityId = expectedSourceEntityId,
					newEntityId = targetEntityId,
					action = WORK_PROJECTION_IDENTITY_ACTION_MOVE,
					status = WORK_PROJECTION_IDENTITY_STATUS_ACTIVE,
					now = now,
				)
				MoveLocalWorkProjectionResult(
					localMangaId = localMangaId,
					sourceEntityId = expectedSourceEntityId,
					targetEntityId = targetEntityId,
				)
			}
		} catch (error: MoveLocalWorkProjectionTransactionException) {
			MoveLocalWorkProjectionResult.failed(
				localMangaId = localMangaId,
				sourceEntityId = expectedSourceEntityId,
				targetEntityId = targetEntityId,
				reason = error.reason,
			)
		}
	}

	suspend fun attachLocalWorkProjectionToEntity(
		entityId: Long,
		content: Content,
		confidence: Float = 1f,
		selectAsPreferred: Boolean = false,
	): Boolean = withContext(Dispatchers.Default) {
		if (entityId == 0L || content.id == 0L) {
			return@withContext false
		}
		try {
			db.withTransaction {
				val dao = db.getEntityGraphDao()
				val existingOwner = findEntityByLocalMangaId(content.id)?.entityId
				if (existingOwner != null) {
					if (existingOwner != entityId) {
						return@withTransaction false
					}
					if (selectAsPreferred) {
						setPreferredLocalProjectionInTransaction(
							dao = dao,
							entityId = entityId,
							localMangaId = content.id,
							now = System.currentTimeMillis(),
						)
					}
					return@withTransaction true
				}
				val targetEntity = dao.findEntity(entityId) ?: return@withTransaction false
				val typedTarget = targetEntity.withInferredContentType(
					dao = dao,
					fallback = content.source.contentType,
				)
				if (!typedTarget.acceptsCompatibleWorkContentType(content.source.contentType)) {
					return@withTransaction false
				}
				val now = System.currentTimeMillis()
				dao.attachLocalWorkBindingForMerge(
					entityId = entityId,
					externalId = content.id.toString(),
					now = now,
					confidence = confidence,
				)
				dao.attachProjectionBindingWithoutSyncIdRewrite(
					entityId = entityId,
					content = content,
					now = now,
					confidence = confidence,
				)
				if (typedTarget != targetEntity) {
					dao.updateEntity(typedTarget.copy(lastAccessed = now))
				}
				if (findEntityByLocalMangaId(content.id)?.entityId != entityId) {
					throw MoveLocalWorkProjectionTransactionException(
						MoveLocalWorkProjectionFailure.REBIND_FAILED,
					)
				}
				if (selectAsPreferred) {
					setPreferredLocalProjectionInTransaction(
						dao = dao,
						entityId = entityId,
						localMangaId = content.id,
						now = now,
					)
				}
				dao.touchEntity(entityId, now)
				true
			}
		} catch (_: MoveLocalWorkProjectionTransactionException) {
			false
		}
	}

	suspend fun selectPreferredLocalWorkProjection(
		entityId: Long,
		localMangaId: Long,
	): Boolean = withContext(Dispatchers.Default) {
		if (entityId == 0L || localMangaId == 0L) {
			return@withContext false
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val owner = findEntityByLocalMangaId(localMangaId)?.entityId
			if (owner != entityId) {
				return@withTransaction false
			}
			val now = System.currentTimeMillis()
			setPreferredLocalProjectionInTransaction(
				dao = dao,
				entityId = entityId,
				localMangaId = localMangaId,
				now = now,
			)
			dao.touchEntity(entityId, now)
			true
		}
	}

	suspend fun attachEntityTrackingBinding(
		entityId: Long,
		service: ScrobblerService,
		remoteId: Long,
		confidence: Float,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.MATCHER,
	): Boolean = withContext(Dispatchers.Default) {
		if (entityId <= 0L || remoteId <= 0L) {
			return@withContext false
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			dao.findEntity(entityId) ?: return@withTransaction false
			dao.upsertBindingForSource(
				entityId = entityId,
				source = service.id.toString(),
				externalId = remoteId.toString(),
				confidence = confidence,
				createdBy = createdBy,
			)
			true
		}
	}

	suspend fun findEntityIdsByLocalMangaIds(localMangaIds: Collection<Long>): Map<Long, Long> = withContext(Dispatchers.Default) {
		if (localMangaIds.isEmpty()) {
			return@withContext emptyMap()
		}
		val ids = localMangaIds.distinct()
		buildMap {
			ids.map(Long::toString).chunked(MAX_BINDING_QUERY_PARAMS).forEach { chunk ->
				db.getEntityGraphDao().findActiveBindingsBySources(
					sources = listOf("local_manga", "0"),
					externalIds = chunk,
				).forEach { binding ->
					binding.externalId.toLongOrNull()?.let { localMangaId ->
						put(localMangaId, binding.entityId)
					}
				}
			}
		}
	}

	suspend fun findLocalReadingBindingsByMangaIds(
		localMangaIds: Collection<Long>,
	): Map<Long, EntityBinding> = withContext(Dispatchers.Default) {
		if (localMangaIds.isEmpty()) {
			return@withContext emptyMap()
		}
		buildMap {
			localMangaIds.distinct().map(Long::toString).chunked(MAX_BINDING_QUERY_PARAMS).forEach { chunk ->
				db.getEntityGraphDao().findActiveBindingsBySources(
					sources = listOf("local_manga", "0"),
					externalIds = chunk,
				).forEach { binding ->
					binding.externalId.toLongOrNull()?.let { localMangaId ->
						put(localMangaId, binding.toModel())
					}
				}
			}
		}
	}

	suspend fun findEntityIdsByAnyMangaIds(mangaIds: Collection<Long>): Map<Long, Long> = withContext(Dispatchers.Default) {
		if (mangaIds.isEmpty()) {
			return@withContext emptyMap()
		}
		val dao = db.getEntityGraphDao()
		val ids = mangaIds.distinct()
		// Owner resolution must come from confirmed local reading bindings only.
		// tracking_site_links is cache/audit data and must not backfill entity ownership.
		// When both local_manga and legacy "0" bindings exist for the same manga,
		// prefer local_manga (the canonical source).
		buildMap<Long, Long> {
			val bestSource = mutableMapOf<Long, String>()
			ids.map(Long::toString).chunked(MAX_BINDING_QUERY_PARAMS).forEach { chunk ->
				dao.findActiveBindingsBySources(
					sources = listOf("local_manga", "0"),
					externalIds = chunk,
				).forEach { binding ->
					binding.externalId.toLongOrNull()?.let { localMangaId ->
						val currentSource = bestSource[localMangaId]
						if (currentSource == null || (currentSource != "local_manga" && binding.source == "local_manga")) {
							put(localMangaId, binding.entityId)
							bestSource[localMangaId] = binding.source
						}
					}
				}
			}
		}
	}

	suspend fun ensureLocalWorkEntities(
		contents: Collection<Content>,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.USER,
	): Map<Long, Long> = withContext(Dispatchers.Default) {
		if (contents.isEmpty()) {
			return@withContext emptyMap()
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			val distinctContents = contents.distinctBy { it.id }
			val existingBindings = LinkedHashMap<Long, EntityBindingRecord>(distinctContents.size)
			distinctContents.map { it.id.toString() }.chunked(MAX_BINDING_QUERY_PARAMS).forEach { chunk ->
				dao.findActiveBindingsBySources(
					sources = listOf("local_manga", "0"),
					externalIds = chunk,
				).forEach { binding ->
					binding.externalId.toLongOrNull()?.let { localMangaId ->
						existingBindings.putIfAbsent(localMangaId, binding)
					}
				}
			}
			val existingEntityIds = existingBindings.values.map { it.entityId }.distinct()
			val entityRecords = if (existingEntityIds.isEmpty()) {
				LinkedHashMap()
			} else {
				dao.findEntitiesByIds(existingEntityIds).associateByTo(LinkedHashMap()) { it.id }
			}
			val redirectedEntityIds = LinkedHashMap<Long, Long>()
			buildMap(distinctContents.size) {
				for (content in distinctContents) {
					val existingBinding = existingBindings[content.id]
					if (existingBinding != null) {
						val entityId = redirectedEntityIds[existingBinding.entityId] ?: existingBinding.entityId
						val record = entityRecords[entityId] ?: dao.findEntity(entityId)
						if (record != null) {
							val typedRecord = record.withInferredContentType(
								dao = dao,
								fallback = content.source.contentType,
							)
							val merged = mergeEntityRecord(
								record = typedRecord,
								primaryName = content.title,
								aliases = content.altTitles.toList(),
								now = now,
							)
							if (!typedRecord.acceptsContentType(content.source.contentType)) {
								val detached = createDetachedLocalWorkEntity(
									content = content,
									now = now,
								)
								entityRecords[detached.id] = detached.toRecord()
								put(content.id, detached.id)
								continue
							}
							if (merged != typedRecord || typedRecord != record) {
								val resolved = updateEntityResolvingNameHashConflict(
									dao = dao,
									original = record,
									merged = merged,
									primaryName = content.title,
									aliases = content.altTitles.toList(),
									now = now,
								)
								if (resolved.id != record.id) {
									redirectedEntityIds[record.id] = resolved.id
									entityRecords.remove(record.id)
								}
								entityRecords[resolved.id] = resolved
							}
						}
						val resolvedEntityId = redirectedEntityIds[existingBinding.entityId] ?: existingBinding.entityId
						dao.upsertBindingForSource(
							entityId = resolvedEntityId,
							source = "local_manga",
							externalId = content.id.toString(),
							confidence = existingBinding.confidence,
							createdBy = createdBy,
						)
						put(content.id, resolvedEntityId)
					} else {
						val entity = createEntity(
							type = EntityType.WORK,
							primaryName = content.title,
							aliases = content.altTitles.toList(),
							source = "local_manga",
							externalId = content.id.toString(),
							confidence = 1f,
							contentType = content.source.contentType,
							now = now,
							createdBy = createdBy,
						)
						entityRecords[entity.id] = entity.toRecord()
						put(content.id, entity.id)
					}
				}
			}
		}
	}

	suspend fun mergeLocalWorkEntities(contents: Collection<Content>): Long? = withContext(Dispatchers.Default) {
		val distinctContents = contents.distinctBy { it.id }
		if (distinctContents.size < 2) {
			return@withContext null
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			if (distinctContents.map { it.source.contentType }.distinct().size > 1) {
				return@withTransaction null
			}
			val ensuredIds = ensureLocalWorkEntities(distinctContents)
			val entityIds = distinctContents.mapNotNullTo(LinkedHashSet()) { ensuredIds[it.id] }
			if (entityIds.isEmpty()) {
				return@withTransaction null
			}
			val records = dao.findEntitiesByIds(entityIds.toList()).associateBy { it.id }
			val entityTypes = entityIds.mapNotNull { records[it]?.contentType }.toSet()
			if (entityTypes.size > 1 || (entityTypes.isNotEmpty() && entityIds.any { records[it]?.contentType == null })) {
				return@withTransaction null
			}
			val targetEntityId = entityIds
				.mapNotNull { records[it] }
				.maxWithOrNull(
					compareBy<EntityRecord> { it.accessCount }
						.thenBy { it.lastAccessed }
						.thenByDescending { it.id },
				)
				?.id
				?: entityIds.first()
			var mergedRecord = requireNotNull(records[targetEntityId])
			distinctContents.forEach { content ->
				mergedRecord = mergeEntityRecord(
					record = mergedRecord,
					primaryName = content.title,
					aliases = content.altTitles.toList(),
					now = now,
				)
			}
			entityIds.filterNot { it == targetEntityId }
				.mapNotNull { records[it] }
				.forEach { record ->
					mergedRecord = mergeEntityRecord(
						record = mergedRecord,
						primaryName = record.primaryName,
						aliases = decodeStringList(record.aliases),
						now = now,
					)
				}
			dao.updateEntity(mergedRecord)
			// Remap bindings and relations from source entities to target
			remapBindingsAndRelations(
				dao = dao,
				targetEntityId = targetEntityId,
				sourceEntityIds = entityIds.filterNot { it == targetEntityId },
			)
			// Re-bind all contents to the target entity
			distinctContents.forEach { content ->
				dao.attachLocalWorkBindingForMerge(
					entityId = targetEntityId,
					externalId = content.id.toString(),
					now = now,
				)
				dao.attachProjectionBindingWithoutSyncIdRewrite(
					entityId = targetEntityId,
					content = content,
					now = now,
				)
			}
			dao.deleteEntitiesByIds(entityIds.filterNot { it == targetEntityId })
			dao.touchEntity(targetEntityId, now)
			targetEntityId
		}
	}

	suspend fun mergeEntities(
		targetEntityId: Long,
		sourceEntityIds: Collection<Long>,
		preferredLocalMangaId: Long? = null,
		allowCompatibleContentTypes: Boolean = false,
	): Long? = withContext(Dispatchers.Default) {
		val distinctSourceIds = sourceEntityIds
			.asSequence()
			.filter { it != targetEntityId }
			.distinct()
			.toMutableList()
		if (distinctSourceIds.isEmpty()) {
			return@withContext targetEntityId
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			val allIds = (distinctSourceIds + targetEntityId).distinct()
			val records = dao.findEntitiesByIds(allIds).associateBy { it.id }.toMutableMap()
			var mergedRecord = records[targetEntityId] ?: return@withTransaction null
			if (preferredLocalMangaId != null) {
				val preferredOwner = findEntityByLocalMangaId(preferredLocalMangaId)?.entityId
				if (preferredOwner !in allIds) {
					return@withTransaction null
				}
			}
			if (!records.values.canMergeWorkContentTypes(allowCompatibleContentTypes)) {
				Log.w(TAG, "mergeEntities: refusing content-type conflict for entityIds=$allIds")
				return@withTransaction null
			}
			distinctSourceIds.mapNotNull { records[it] }.forEach { record ->
				mergedRecord = mergeEntityRecord(
					record = mergedRecord,
					primaryName = record.primaryName,
					aliases = decodeStringList(record.aliases),
					now = now,
				)
			}
			// Resolve name_hash conflicts: if the merged record's (type, name_hash) collides with
			// another entity not in the merge set, absorb it into the merge.
			val absorbedIds = mutableSetOf<Long>()
			var absorbAttempts = 0
			while (absorbAttempts < 5) {
				val conflicting = dao.findEntityByTypeAndNameHashAndContentType(
					mergedRecord.type,
					mergedRecord.nameHash,
					mergedRecord.contentType,
				)
				if (conflicting == null || conflicting.id == targetEntityId) {
					break
				}
				if (conflicting.id in distinctSourceIds || conflicting.id in absorbedIds) {
					break
				}
				Log.w(TAG, "mergeEntities: absorbing conflicting entity ${conflicting.id} (type=${conflicting.type}, nameHash=${conflicting.nameHash})")
				// Remap bindings/relations from conflicting entity to target, then delete it
				remapWorkOwnedState(
					sourceEntityId = conflicting.id,
					targetEntityId = targetEntityId,
				)
				remapBindingsAndRelations(dao, targetEntityId, listOf(conflicting.id))
				dao.deleteEntitiesByIds(listOf(conflicting.id))
				absorbedIds.add(conflicting.id)
				// Merge the absorbed entity's names into the target record (for aliasing)
				mergedRecord = mergeEntityRecord(
					record = mergedRecord,
					primaryName = conflicting.primaryName,
					aliases = decodeStringList(conflicting.aliases),
					now = now,
				)
				absorbAttempts++
			}
			if (absorbAttempts >= 5) {
				Log.e(TAG, "mergeEntities: too many name_hash conflicts, giving up")
				return@withTransaction null
			}
			dao.updateEntity(mergedRecord)
			distinctSourceIds.forEach { sourceEntityId ->
				remapWorkOwnedState(
					sourceEntityId = sourceEntityId,
					targetEntityId = targetEntityId,
				)
			}
			// Remap bindings and relations from source entities to target
			remapBindingsAndRelations(
				dao = dao,
				targetEntityId = targetEntityId,
				sourceEntityIds = distinctSourceIds,
			)
			// FK constraints (CASCADE) handle deletions automatically on source entities
			dao.deleteEntitiesByIds(distinctSourceIds)
			if (preferredLocalMangaId != null) {
				setPreferredLocalProjectionInTransaction(
					dao = dao,
					entityId = targetEntityId,
					localMangaId = preferredLocalMangaId,
					now = now,
				)
			}
			dao.touchEntity(targetEntityId, now)
			targetEntityId
		}
	}

	suspend fun attachLocalWorksToEntity(
		entityId: Long,
		contents: Collection<Content>,
	): Boolean = withContext(Dispatchers.Default) {
		val distinctContents = contents.distinctBy { it.id }
		if (distinctContents.isEmpty()) {
			return@withContext false
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			var record = dao.findEntity(entityId) ?: return@withTransaction false
			record = record.withInferredContentType(dao, distinctContents.first().source.contentType)
			if (!distinctContents.map { it.source.contentType }.all { record.acceptsContentType(it) }) {
				Log.w(TAG, "attachLocalWorksToEntity: refusing content-type conflict for entityId=$entityId")
				return@withTransaction false
			}
			distinctContents.forEach { content ->
				record = mergeEntityRecord(
					record = record,
					primaryName = content.title,
					aliases = content.altTitles.toList(),
					now = now,
				)
				dao.attachLocalWorkBindingForMerge(
					entityId = entityId,
					externalId = content.id.toString(),
					now = now,
				)
				dao.attachProjectionBindingWithoutSyncIdRewrite(
					entityId = entityId,
					content = content,
					now = now,
				)
			}
			val resolved = updateEntityResolvingNameHashConflict(
				dao = dao,
				original = requireNotNull(dao.findEntity(entityId)) {
					"Entity disappeared during attachLocalWorksToEntity: $entityId"
				},
				merged = record,
				primaryName = record.primaryName,
				aliases = decodeStringList(record.aliases),
				now = now,
			)
			dao.touchEntity(resolved.id, now)
			true
		}
	}

	suspend fun ensureLocalWorkEntity(
		content: Content,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.USER,
	): Entity = withContext(Dispatchers.Default) {
		db.withTransaction {
			val now = System.currentTimeMillis()
			val projectionKey = ProjectionIdentityKeys.bindingKey(content.url, content.publicUrl)
			val existing = findEntityByLocalMangaId(content.id)
				?: projectionKey?.let { key ->
					findBindingBySourceKey(content.source.name, key)
			}
			if (existing != null) {
				val dao = db.getEntityGraphDao()
				val record = dao.findEntity(existing.entityId)
				if (record != null) {
					val typedRecord = record.withInferredContentType(
						dao = dao,
						fallback = content.source.contentType,
					)
					if (!typedRecord.acceptsContentType(content.source.contentType)) {
						return@withTransaction createDetachedLocalWorkEntity(content, now)
					}
					val merged = mergeEntityRecord(
						record = typedRecord,
						primaryName = content.title,
						aliases = content.altTitles.toList(),
						now = now,
					)
					val resolvedEntityId = updateEntityResolvingNameHashConflict(
						dao = dao,
						original = record,
						merged = merged,
						primaryName = content.title,
						aliases = content.altTitles.toList(),
						now = now,
					).id
					dao.upsertBindingForSource(
						entityId = resolvedEntityId,
						source = "local_manga",
						externalId = content.id.toString(),
						confidence = existing.confidence,
						createdBy = createdBy,
					)
					if (projectionKey != null) {
						dao.upsertBindingForSource(
							entityId = resolvedEntityId,
							source = content.source.name,
							externalId = projectionKey,
							confidence = existing.confidence,
							createdBy = createdBy,
							sourceKind = EntityBindingSourceKind.READING_SOURCE,
						)
					}
					dao.touchEntity(resolvedEntityId, now)
					return@withTransaction requireNotNull(dao.findEntity(resolvedEntityId)).toModel()
				}
			}
			val entity = if (projectionKey != null) {
				resolveOrCreateEntity(
					type = EntityType.WORK,
					primaryName = content.title,
					aliases = content.altTitles.toList(),
					source = content.source.name,
					externalId = projectionKey,
					contentType = content.source.contentType,
					now = now,
					createdBy = createdBy,
				)
			} else {
				resolveOrCreateEntity(
					type = EntityType.WORK,
					primaryName = content.title,
					aliases = content.altTitles.toList(),
					source = "local_manga",
					externalId = content.id.toString(),
					contentType = content.source.contentType,
					now = now,
					createdBy = createdBy,
				)
			}
			if (projectionKey != null) {
				db.getEntityGraphDao().upsertBindingForSource(
					entityId = entity.id,
					source = content.source.name,
					externalId = projectionKey,
					confidence = 1f,
					createdBy = createdBy,
					sourceKind = EntityBindingSourceKind.READING_SOURCE,
				)
			}
			db.getEntityGraphDao().upsertBindingForSource(
				entityId = entity.id,
				source = "local_manga",
				externalId = content.id.toString(),
				confidence = 1f,
				createdBy = createdBy,
			)
			entity
		}
	}

	suspend fun getRelations(entityId: Long): List<Relation> = withContext(Dispatchers.Default) {
		db.getEntityGraphDao().findVisibleRelationsForEntity(entityId).map { it.toModel() }
	}

	suspend fun getRelationsForTrackingSource(
		entityId: Long,
		service: ScrobblerService,
		remoteId: Long,
	): List<Relation> = withContext(Dispatchers.Default) {
		val sourceKeys = listOf(service.id.toString(), service.name.lowercase()).distinct()
		sourceKeys
			.flatMap { source ->
				db.getEntityGraphDao().findRelationsForEntityAndSource(
					entityId = entityId,
					source = source,
					externalId = remoteId.toString(),
				)
			}
			.distinctBy(RelationRecord::id)
			.map { it.toModel() }
	}

	suspend fun tryBindEntities(
		entityA: Entity,
		entityB: Entity,
	): Float = withContext(Dispatchers.Default) {
		bindingMatcher.tryBindEntities(entityA, entityB)
	}

	suspend fun addManualRelation(
		fromEntityId: Long,
		toEntityId: Long,
		type: RelationType,
	): Boolean = withContext(Dispatchers.Default) {
		if (fromEntityId <= 0L || toEntityId <= 0L || fromEntityId == toEntityId) {
			return@withContext false
		}
		db.withTransaction {
			val now = System.currentTimeMillis()
			val fromEntity = db.getEntityGraphDao().findEntity(fromEntityId) ?: return@withTransaction false
			val toEntity = db.getEntityGraphDao().findEntity(toEntityId) ?: return@withTransaction false
			db.getEntityGraphDao().insertRelation(
				RelationRecord(
					fromEntityId = fromEntity.id,
					toEntityId = toEntity.id,
					type = type.name,
					weight = RELATION_WEIGHT_DEFAULT,
					createdAt = now,
					origin = EntityRelationOrigin.MANUAL.name,
					state = EntityRelationState.ACTIVE.name,
					updatedAt = now,
				),
			) != -1L
		}
	}

	suspend fun deleteTrackingBinding(
		service: ScrobblerService,
		remoteId: Long,
	): Unit = withContext(Dispatchers.Default) {
		val dao = db.getEntityGraphDao()
		listOf(service.id.toString(), service.name.lowercase()).distinct().forEach { source ->
			dao.deleteBindingBySource(source, remoteId.toString())
		}
	}

	suspend fun hideRelation(relationId: Long): Unit = withContext(Dispatchers.Default) {
		updateRelationState(relationId, EntityRelationState.HIDDEN)
	}

	suspend fun rejectRelation(relationId: Long): Unit = withContext(Dispatchers.Default) {
		updateRelationState(relationId, EntityRelationState.REJECTED)
	}

	suspend fun hideStaleLegacyRelations(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		val relationIds = report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.STALE_LEGACY_RELATION }
			.mapNotNull { it.relationId }
			.distinct()
			.toList()
		if (relationIds.isEmpty()) {
			return@withContext 0
		}
		db.withTransaction {
			val now = System.currentTimeMillis()
			relationIds.forEach { relationId ->
				db.getEntityGraphDao().updateRelationState(
					relationId = relationId,
					state = EntityRelationState.HIDDEN.name,
					updatedAt = now,
				)
			}
			relationIds.size
		}
	}

	suspend fun rejectSuspectTrackingBindings(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		val issues = report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.SUSPECT_TRACKING_BINDING }
			.filter { !it.source.isNullOrBlank() && !it.externalId.isNullOrBlank() }
			.distinctBy { "${it.entityId}:${it.source}:${it.externalId}" }
			.toList()
		if (issues.isEmpty()) {
			return@withContext 0
		}
		db.withTransaction {
			val now = System.currentTimeMillis()
			val dao = db.getEntityGraphDao()
			val trackingDao = db.getTrackingSiteDao()
			var repaired = 0
			issues.forEach { issue ->
				val service = issue.source?.toTrackingServiceOrNull() ?: return@forEach
				val remoteId = issue.externalId?.toLongOrNull() ?: return@forEach
				listOf(service.id.toString(), service.name.lowercase()).distinct().forEach { source ->
					dao.updateBindingState(
						source = source,
						externalId = remoteId.toString(),
						state = EntityBindingState.REJECTED.name,
						updatedAt = now,
					)
				}
				dao.findActiveBindingsByEntity(issue.entityId)
					.asSequence()
					.filter { it.isLocalReadingSource() }
					.mapNotNull { it.externalId.toLongOrNull() }
					.let { localMangaIds ->
						issue.localMangaId?.let(::listOf) ?: localMangaIds.toList()
					}
					.forEach { localMangaId ->
						trackingDao.deleteLink(
							service = service.id,
							remoteId = remoteId,
							mangaId = localMangaId,
						)
						clearMangaMetadataSourceIfSuspect(
							localMangaId = localMangaId,
							serviceId = service.id,
							remoteId = remoteId,
						)
					}
				clearEntityMetadataSourceIfSuspect(
					dao = dao,
					entityId = issue.entityId,
					serviceId = service.id,
					remoteId = remoteId,
					now = now,
				)
				repaired++
			}
			repaired
		}
	}

	suspend fun repairSuspectMetadataSourceSelections(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		val issues = report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.SUSPECT_METADATA_SOURCE }
			.toList()
		if (issues.isEmpty()) {
			return@withContext 0
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			var repaired = 0
			val entityPrefsById = issues
				.asSequence()
				.map { it.entityId }
				.distinct()
				.associateWith { entityId -> dao.findEntityPrefs(entityId) }
			issues
				.asSequence()
				.map { it.entityId }
				.distinct()
				.forEach { entityId ->
				val prefs = dao.findEntityPrefs(entityId) ?: return@forEach
				val entityBindings = dao.findActiveBindingsByEntity(entityId)
				val localContents = entityBindings.localContents()
				if (localContents.isEmpty()) {
					return@forEach
				}
				val entity = dao.findEntity(entityId) ?: return@forEach
				val currentService = prefs.metadataSourceService
				val currentRemoteId = prefs.metadataSourceRemoteId
				if (currentService == null || currentRemoteId == null) {
					return@forEach
				}
				val currentNames = trackingNames(currentService, currentRemoteId)
				if (
					currentNames.any { it.isNotBlank() } &&
					currentNames.isCompatibleWithAny(entity, localContents)
				) {
					return@forEach
				}
				val replacement = findCompatibleTrackingSelection(
					entityBindings = entityBindings,
					localContents = localContents,
					entity = entity,
					excluded = TrackingSelection(currentService, currentRemoteId),
				)
				applyMetadataSelection(
					dao = dao,
					entityId = entityId,
					selection = replacement,
					now = now,
				)
				repaired++
			}
			issues
				.asSequence()
				.mapNotNull { issue ->
					val localMangaId = issue.localMangaId ?: return@mapNotNull null
					val entityPrefs = entityPrefsById[issue.entityId]
					if (!entityPrefs?.metadataSourceKind.isNullOrEmpty()) {
						return@mapNotNull null
					}
					val serviceId = issue.source?.toIntOrNull() ?: return@mapNotNull null
					val remoteId = issue.externalId?.toLongOrNull() ?: return@mapNotNull null
					Triple(localMangaId, serviceId, remoteId)
				}
				.distinct()
				.forEach { (localMangaId, serviceId, remoteId) ->
					clearMangaMetadataSourceIfSuspect(
						localMangaId = localMangaId,
						serviceId = serviceId,
						remoteId = remoteId,
					)
					repaired++
				}
			repaired
		}
	}

	suspend fun pruneRedundantProjectionMetadataSelections(): Int = withContext(Dispatchers.Default) {
		db.withTransaction {
			val entityDao = db.getEntityGraphDao()
			val prefsDao = db.getPreferencesDao()
			val bindingsByEntity = entityDao.dumpBindings()
				.filter { it.isActiveBinding() }
				.filter { it.isLocalReadingSource() }
				.groupBy { it.entityId }
			var repaired = 0
			bindingsByEntity.forEach { (entityId, bindings) ->
				val entityPrefs = entityDao.findEntityPrefs(entityId) ?: return@forEach
				val entityKind = entityPrefs.metadataSourceKind ?: return@forEach
				bindings
					.mapNotNull { it.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = prefsDao.find(mangaId) ?: return@forEach
						if (!localPrefs.hasMatchingMetadataSelection(entityPrefs)) {
							return@forEach
						}
						prefsDao.upsert(
							localPrefs.copy(
								metadataSourceKind = null,
								metadataSourceService = null,
								metadataSourceRemoteId = null,
							),
						)
						repaired++
					}
			}
			repaired
		}
	}

	suspend fun pruneRedundantProjectionOverrides(): Int = withContext(Dispatchers.Default) {
		db.withTransaction {
			val entityDao = db.getEntityGraphDao()
			val prefsDao = db.getPreferencesDao()
			val bindingsByEntity = entityDao.dumpBindings()
				.filter { it.isActiveBinding() }
				.filter { it.isLocalReadingSource() }
				.groupBy { it.entityId }
			var repaired = 0
			bindingsByEntity.forEach { (entityId, bindings) ->
				val entityPrefs = entityDao.findEntityPrefs(entityId) ?: return@forEach
				val entityOverrideExists = entityPrefs.hasAnyOverride()
				if (!entityOverrideExists) {
					return@forEach
				}
				bindings
					.mapNotNull { it.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = prefsDao.find(mangaId) ?: return@forEach
						if (!localPrefs.hasMatchingOverride(entityPrefs)) {
							return@forEach
						}
						prefsDao.upsert(
							localPrefs.copy(
								titleOverride = null,
								coverUrlOverride = null,
								contentRatingOverride = null,
							),
						)
						repaired++
					}
			}
			repaired
		}
	}

	suspend fun pruneRedundantProjectionReadingStatuses(): Int = withContext(Dispatchers.Default) {
		db.withTransaction {
			val entityDao = db.getEntityGraphDao()
			val prefsDao = db.getPreferencesDao()
			val bindingsByEntity = entityDao.dumpBindings()
				.filter { it.isActiveBinding() }
				.filter { it.isLocalReadingSource() }
				.groupBy { it.entityId }
			var repaired = 0
			bindingsByEntity.forEach { (entityId, bindings) ->
				val entityPrefs = entityDao.findEntityPrefs(entityId) ?: return@forEach
				val entityReadingStatus = entityPrefs.readingStatus ?: return@forEach
				bindings
					.mapNotNull { it.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = prefsDao.find(mangaId) ?: return@forEach
						if (localPrefs.readingStatus != entityReadingStatus) {
							return@forEach
						}
						prefsDao.upsert(localPrefs.copy(readingStatus = null))
						repaired++
					}
			}
			repaired
		}
	}

	suspend fun pruneStaleTrackingCacheLinks(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		val issues = report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.STALE_TRACKING_CACHE_LINK }
			.filter { !it.source.isNullOrBlank() && !it.externalId.isNullOrBlank() }
			.distinctBy { "${it.entityId}:${it.source}:${it.externalId}:${it.localMangaId}" }
			.toList()
		if (issues.isEmpty()) {
			return@withContext 0
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val trackingDao = db.getTrackingSiteDao()
			val now = System.currentTimeMillis()
			var repaired = 0
			issues.forEach { issue ->
				val localMangaId = issue.localMangaId ?: return@forEach
				val serviceId = issue.source?.toIntOrNull() ?: return@forEach
				val remoteId = issue.externalId?.toLongOrNull() ?: return@forEach
				trackingDao.deleteLink(
					service = serviceId,
					remoteId = remoteId,
					mangaId = localMangaId,
				)
				clearMangaMetadataSourceIfSuspect(
					localMangaId = localMangaId,
					serviceId = serviceId,
					remoteId = remoteId,
				)
				clearEntityMetadataSourceIfSuspect(
					dao = dao,
					entityId = issue.entityId,
					serviceId = serviceId,
					remoteId = remoteId,
					now = now,
				)
				repaired++
			}
			repaired
		}
	}

	suspend fun repairDanglingWorkProjectionAnchors(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		val issues = report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.DANGLING_WORK_PROJECTION_ANCHOR }
			.toList()
		if (issues.isEmpty()) {
			return@withContext 0
		}
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val now = System.currentTimeMillis()
			var repaired = 0
			issues
				.asSequence()
				.filter { it.localMangaId == null }
				.map(EntityGraphRepairIssue::entityId)
				.filter { it > 0L }
				.distinct()
				.forEach { entityId ->
					val fallbackLocalMangaId = findWorkStateAnchorCandidate(
						dao = dao,
						entityId = entityId,
					)
					repaired += if (fallbackLocalMangaId != null) {
						db.getWorkFavouritesDao().fillMissingAnchorMangaId(
							entityId = entityId,
							anchorMangaId = fallbackLocalMangaId,
							updatedAt = now,
						)
					} else {
						db.getWorkFavouritesDao().deactivateActiveWithoutAnchor(
							entityId = entityId,
							updatedAt = now,
						)
					}
				}
			issues
				.asSequence()
				.filter { it.localMangaId != null }
				.distinctBy { "${it.entityId}:${it.localMangaId}" }
				.forEach { issue ->
				val danglingAnchorId = issue.localMangaId ?: return@forEach
				val fallbackLocalMangaId = findFallbackLocalProjectionId(
					dao = dao,
					entityId = issue.entityId,
					detachedLocalMangaId = danglingAnchorId,
				)
				if (fallbackLocalMangaId != null) {
					reconcileDetachedLocalProjectionAnchors(
						entityId = issue.entityId,
						detachedLocalMangaId = danglingAnchorId,
						fallbackLocalMangaId = fallbackLocalMangaId,
						now = now,
					)
					repaired++
				} else {
					repaired += pruneUnexportableWorkStateByAnchor(
						entityId = issue.entityId,
						danglingAnchorId = danglingAnchorId,
						now = now,
					)
				}
				}
			repaired
		}
	}

	suspend fun repairWorkEntitiesMissingSyncId(): Int = withContext(Dispatchers.Default) {
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val entityIds = dao.findWorkEntityIdsMissingSyncId()
			entityIds.forEach { entityId ->
				dao.updateEntitySyncId(entityId, java.util.UUID.randomUUID().toString())
			}
			entityIds.size
		}
	}

	suspend fun repairMixedWorkContentTypeEntities(): Int = withContext(Dispatchers.Default) {
		val report = inspectRepairIssues()
		report.issues
			.asSequence()
			.filter { it.kind == EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES }
			.map { it.entityId }
			.distinct()
			.sumOf { entityId -> repairMixedWorkContentTypeEntity(entityId) }
	}

	suspend fun repairMixedWorkContentTypeEntity(
		entityId: Long,
		preferredLocalMangaId: Long? = null,
	): Int = withContext(Dispatchers.Default) {
		val dao = db.getEntityGraphDao()
		val entity = dao.findEntity(entityId) ?: return@withContext 0
		if (entity.type != EntityType.WORK.name) {
			return@withContext 0
		}
		val projections = dao.findActiveLocalBindingsByEntity(entityId)
			.mapNotNull { binding ->
				val localMangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
				val manga = db.getMangaDao().find(localMangaId)?.manga ?: return@mapNotNull null
				val contentType = manga.contentType
					?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
					?: ContentSource(manga.source).resolvedContentTypeForSnapshot()
				Triple(localMangaId, contentType, manga.title)
			}
		val grouped = projections.filter { it.second != null }.groupBy { it.second }
		if (grouped.size <= 1) {
			return@withContext 0
		}
		val preferredType = projections
			.firstOrNull { it.first == preferredLocalMangaId }
			?.second
			?: entity.contentType?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
			?: grouped.entries
			.maxWithOrNull(compareBy<Map.Entry<ContentType?, List<Triple<Long, ContentType?, String>>>> { it.value.size }
				.thenBy { it.key?.name.orEmpty() })
			?.key
			?: return@withContext 0
		val repaired = projections
			.asSequence()
			.filter { it.second != null && it.second != preferredType }
			.map { it.first }
			.distinct()
			.count { localMangaId -> splitLocalWorkProjection(localMangaId) != null }
		if (entity.contentType != preferredType.name) {
			updateEntityResolvingNameHashConflict(
				dao = dao,
				original = entity,
				merged = entity.copy(contentType = preferredType.name),
				primaryName = entity.primaryName,
				aliases = decodeStringList(entity.aliases),
				now = System.currentTimeMillis(),
			)
		}
		repaired
	}

	private suspend fun updateRelationState(
		relationId: Long,
		state: EntityRelationState,
	) {
		if (relationId <= 0L) {
			return
		}
		db.getEntityGraphDao().updateRelationState(
			relationId = relationId,
			state = state.name,
			updatedAt = System.currentTimeMillis(),
		)
	}

	suspend fun pruneStaleEntities(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.Default) {
		db.withTransaction {
			val cutoff = now - STALE_ENTITY_DAYS * 24L * 60L * 60L * 1000L
			val entityIds = db.getEntityGraphDao().findEntityIdsForPrune(
				cutoffMillis = cutoff,
				accessCountThreshold = STALE_ENTITY_ACCESS_THRESHOLD,
			)
			if (entityIds.isEmpty()) {
				return@withTransaction 0
			}
			// FK constraints (CASCADE) now handle bindings and relations automatically.
			db.getEntityGraphDao().deleteEntitiesByIds(entityIds)
			entityIds.size
		}
	}

	suspend fun inspectRepairIssues(limit: Int = Int.MAX_VALUE): EntityGraphRepairReport = withContext(Dispatchers.Default) {
		val dao = db.getEntityGraphDao()
		val bindings = dao.dumpBindings()
		val activeBindings = bindings.filter { it.isActiveBinding() }
		val activeBindingsByEntity = activeBindings.groupBy { it.entityId }
		val issues = ArrayList<EntityGraphRepairIssue>()
		val entitiesById = dao.dumpEntities().associateBy { it.id }
		val trackingDiagnostics = EntityRepairDiagnosticCollector()
		dao.findWorkEntityIdsMissingSyncId()
			.forEach { entityId ->
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.WORK_ENTITY_MISSING_SYNC_ID,
					entityId = entityId,
				)
			}
		val workAnchorIds = (
				db.getWorkHistoryDao().findActiveAnchorMangaIds() +
					db.getWorkFavouritesDao().findActiveAnchorMangaIds() +
					db.getWorkStatsDao().findAnchorMangaIds()
				).filterTo(LinkedHashSet<Long>()) { it > 0L }
			val existingLocalMangaIds = db.getMangaDao().findEntitiesByIds(workAnchorIds)
				.mapTo(LinkedHashSet<Long>()) { it.id }

		suspend fun addDanglingAnchorIssues(anchorIds: Iterable<Long>) {
			anchorIds
				.asSequence()
				.filter { it > 0L && it !in existingLocalMangaIds }
				.distinct()
				.forEach { anchorId ->
					val entityIds = buildList {
						db.getWorkHistoryDao().findActiveByAnchorMangaId(anchorId)?.let { add(it.entityId) }
						db.getWorkFavouritesDao().findActiveByAnchorMangaId(anchorId).forEach { add(it.entityId) }
						db.getWorkStatsDao().findAllByAnchorMangaId(anchorId).forEach { add(it.entityId) }
					}.distinct()
					if (entityIds.isEmpty()) {
						issues += EntityGraphRepairIssue(
							kind = EntityGraphRepairIssueKind.DANGLING_WORK_PROJECTION_ANCHOR,
							entityId = 0L,
							source = "local_manga",
							externalId = anchorId.toString(),
							localMangaId = anchorId,
						)
					} else {
						entityIds.forEach { entityId ->
							issues += EntityGraphRepairIssue(
								kind = EntityGraphRepairIssueKind.DANGLING_WORK_PROJECTION_ANCHOR,
								entityId = entityId,
								source = "local_manga",
								externalId = anchorId.toString(),
								localMangaId = anchorId,
							)
						}
					}
				}
		}

		addDanglingAnchorIssues(workAnchorIds)
		db.getWorkFavouritesDao().findActiveWithoutAnchor(limit)
			.forEach { favourite ->
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.DANGLING_WORK_PROJECTION_ANCHOR,
					entityId = favourite.entityId,
					source = "work_favourites",
					externalId = favourite.categoryId.toString(),
				)
			}

		dao.dumpPrefs().forEach { prefs ->
			val entityBindings = activeBindingsByEntity[prefs.entityId].orEmpty()
			val preferredLocalId = prefs.preferredLocalMangaId
			if (
				preferredLocalId != null &&
				entityBindings.none { it.isLocalReadingSource() && it.externalId.toLongOrNull() == preferredLocalId }
			) {
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.ORPHAN_PREFERRED_LOCAL,
					entityId = prefs.entityId,
					source = "local_manga",
					externalId = preferredLocalId.toString(),
				)
			}

			val metadataService = prefs.metadataSourceService
			val metadataRemoteId = prefs.metadataSourceRemoteId
			if (metadataService != null && metadataRemoteId != null) {
				val hasMetadataBinding = entityBindings.any { binding ->
					binding.externalId == metadataRemoteId.toString() &&
						binding.source.toTrackingServiceOrNull()?.id == metadataService
				}
				if (!hasMetadataBinding) {
					issues += EntityGraphRepairIssue(
						kind = EntityGraphRepairIssueKind.ORPHAN_METADATA_SOURCE,
						entityId = prefs.entityId,
						source = metadataService.toString(),
						externalId = metadataRemoteId.toString(),
					)
				}
				val localContents = entityBindings.localContents()
				val selectedTrackingNames = trackingNames(metadataService, metadataRemoteId)
				if (
					localContents.isNotEmpty() &&
					selectedTrackingNames.any { it.isNotBlank() } &&
					!selectedTrackingNames.isCompatibleWithAny(entitiesById[prefs.entityId], localContents)
				) {
					val replacement = findCompatibleTrackingSelection(
						entityBindings = entityBindings,
						localContents = localContents,
						entity = entitiesById[prefs.entityId],
						excluded = TrackingSelection(metadataService, metadataRemoteId),
					)
					issues += EntityGraphRepairIssue(
						kind = EntityGraphRepairIssueKind.SUSPECT_METADATA_SOURCE,
						entityId = prefs.entityId,
						source = (replacement?.serviceId ?: metadataService).toString(),
						externalId = (replacement?.remoteId ?: metadataRemoteId).toString(),
						localMangaId = localContents.firstOrNull { content ->
							selectedTrackingNames.none { trackingName ->
								isCompatibleTrackingTitle(content, trackingName)
							}
						}?.id,
					)
					trackingDiagnostics.record(
						branch = "entity_metadata_source",
						entity = entitiesById[prefs.entityId],
						localContent = localContents.firstOrNull { content ->
							selectedTrackingNames.none { trackingName ->
								isCompatibleTrackingTitle(content, trackingName)
							}
						} ?: localContents.firstOrNull(),
						serviceId = metadataService,
						remoteId = metadataRemoteId,
						trackingNames = selectedTrackingNames,
					)
				}
			}

			if (prefs.metadataSourceKind != null) {
				entityBindings
					.asSequence()
					.filter { it.isLocalReadingSource() }
					.mapNotNull { binding -> binding.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = db.getPreferencesDao().find(mangaId) ?: return@forEach
						if (!localPrefs.hasMatchingMetadataSelection(prefs)) {
							return@forEach
						}
						issues += EntityGraphRepairIssue(
							kind = EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_METADATA_SELECTION,
							entityId = prefs.entityId,
							source = "local_manga",
							externalId = mangaId.toString(),
							localMangaId = mangaId,
						)
					}
			}

			if (prefs.hasAnyOverride()) {
				entityBindings
					.asSequence()
					.filter { it.isLocalReadingSource() }
					.mapNotNull { binding -> binding.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = db.getPreferencesDao().find(mangaId) ?: return@forEach
						if (!localPrefs.hasMatchingOverride(prefs)) {
							return@forEach
						}
						issues += EntityGraphRepairIssue(
							kind = EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_OVERRIDE,
							entityId = prefs.entityId,
							source = "local_manga",
							externalId = mangaId.toString(),
							localMangaId = mangaId,
						)
					}
			}

			if (!prefs.readingStatus.isNullOrEmpty()) {
				entityBindings
					.asSequence()
					.filter { it.isLocalReadingSource() }
					.mapNotNull { binding -> binding.externalId.toLongOrNull() }
					.distinct()
					.forEach { mangaId ->
						val localPrefs = db.getPreferencesDao().find(mangaId) ?: return@forEach
						if (localPrefs.readingStatus != prefs.readingStatus) {
							return@forEach
						}
						issues += EntityGraphRepairIssue(
							kind = EntityGraphRepairIssueKind.REDUNDANT_PROJECTION_READING_STATUS,
							entityId = prefs.entityId,
							source = "local_manga",
							externalId = mangaId.toString(),
							localMangaId = mangaId,
						)
					}
			}
		}

		activeBindings
			.filter { it.isLocalReadingSource() }
			.groupBy { it.externalId }
			.filterValues { rows -> rows.mapTo(mutableSetOf()) { it.entityId }.size > 1 }
			.forEach { (externalId, rows) ->
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.CONFLICTING_READING_BINDING,
					entityId = rows.first().entityId,
					source = "local_manga",
					externalId = externalId,
					count = rows.mapTo(mutableSetOf()) { it.entityId }.size,
				)
			}

		activeBindingsByEntity.forEach { (entityId, entityBindings) ->
			val localBindings = entityBindings.filter { it.isLocalReadingSource() }
			val hasTrackingBinding = entityBindings.any { it.source.toTrackingServiceOrNull() != null }
			if (localBindings.isEmpty()) {
				return@forEach
			}
			val entity = entitiesById[entityId] ?: return@forEach
			val localProjectionTypes = localBindings.mapNotNull { binding ->
				val localMangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
				val manga = db.getMangaDao().find(localMangaId)?.manga ?: return@mapNotNull null
				val contentType = manga.contentType
					?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
					?: ContentSource(manga.source).resolvedContentTypeForSnapshot()
				Triple(binding, localMangaId, contentType)
			}
			val distinctContentTypes = localProjectionTypes.mapNotNull { it.third }.distinct()
			if (entity.type == EntityType.WORK.name && distinctContentTypes.size > 1) {
				localProjectionTypes.forEach { (binding, localMangaId, _) ->
					issues += EntityGraphRepairIssue(
						kind = EntityGraphRepairIssueKind.MIXED_WORK_CONTENT_TYPES,
						entityId = entityId,
						source = binding.source,
						externalId = binding.externalId,
						localMangaId = localMangaId,
						count = distinctContentTypes.size,
					)
				}
			}
			val strictEntityNameKeys = entity.strictRepairNameKeys()
			if (strictEntityNameKeys.isEmpty()) {
				return@forEach
			}
			localBindings.forEach { binding ->
				val localMangaId = binding.externalId.toLongOrNull()
				val content = localMangaId?.let { db.getMangaDao().find(it)?.toContent() }
				if (content != null && content.localStrictTitleKeys().none { it in strictEntityNameKeys }) {
					Log.d(
						TAG,
						"repair suspectMismerged: entityId=$entityId name=${entity.primaryName} " +
							"aliases=${decodeStringList(entity.aliases)} strictKeys=$strictEntityNameKeys " +
							"localMangaId=$localMangaId localTitle=${content.title} hasTracking=$hasTrackingBinding",
					)
					issues += EntityGraphRepairIssue(
						kind = EntityGraphRepairIssueKind.SUSPECT_MISMERGED_LOCAL_WORK,
						entityId = entityId,
						source = binding.source,
						externalId = binding.externalId,
						count = localBindings.size,
					)
				}
			}
		}

		activeBindingsByEntity.forEach { (entityId, entityBindings) ->
			val entityPrefs = dao.findEntityPrefs(entityId)
			val hasEntityMetadataSelection = !entityPrefs?.metadataSourceKind.isNullOrEmpty()
			val localContents = entityBindings.mapNotNull { binding ->
				if (!binding.isLocalReadingSource()) {
					return@mapNotNull null
				}
				val localMangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
				db.getMangaDao().find(localMangaId)?.toContent()
			}
			if (localContents.isEmpty()) {
				return@forEach
			}
			val localContentsById = localContents.associateBy { it.id }
			entityBindings.forEach { binding ->
				val service = binding.source.toTrackingServiceOrNull() ?: return@forEach
				val remoteId = binding.externalId.toLongOrNull() ?: return@forEach
				val trackingNames = trackingNames(service.id, remoteId)
				if (trackingNames.none { it.isNotBlank() }) {
					return@forEach
				}
				if (trackingNames.isCompatibleWithAnyLocalContent(localContents)) {
					return@forEach
				}
				val mismatchedContent = localContents.firstOrNull { content ->
					!trackingNames.isCompatibleWithLocalContent(content)
				} ?: localContents.firstOrNull() ?: return@forEach
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.SUSPECT_TRACKING_BINDING,
					entityId = entityId,
					source = service.id.toString(),
					externalId = remoteId.toString(),
					localMangaId = mismatchedContent.id,
				)
				trackingDiagnostics.record(
					branch = "entity_tracking_binding",
					entity = entitiesById[entityId],
					localContent = mismatchedContent,
					serviceId = service.id,
					remoteId = remoteId,
					trackingNames = trackingNames,
				)
			}
			localContents.forEach { content ->
				if (hasEntityMetadataSelection) {
					return@forEach
				}
				db.getPreferencesDao().find(content.id)
					?.takeIf { prefs ->
						prefs.metadataSourceKind == "tracking" &&
							prefs.metadataSourceService != null &&
							prefs.metadataSourceRemoteId != null
					}
					?.let { prefs ->
						if (
							entityBindings.any { binding ->
								binding.source.toTrackingServiceOrNull()?.id == prefs.metadataSourceService &&
									binding.externalId == prefs.metadataSourceRemoteId.toString()
							}
						) {
							return@let
						}
						val trackingNames = trackingNames(
							serviceId = checkNotNull(prefs.metadataSourceService),
							remoteId = checkNotNull(prefs.metadataSourceRemoteId),
						)
						if (
							trackingNames.any { it.isNotBlank() } &&
							!trackingNames.isCompatibleWithLocalContent(content)
						) {
							issues += EntityGraphRepairIssue(
								kind = EntityGraphRepairIssueKind.SUSPECT_METADATA_SOURCE,
								entityId = entityId,
								source = prefs.metadataSourceService.toString(),
								externalId = prefs.metadataSourceRemoteId.toString(),
								localMangaId = content.id,
							)
							trackingDiagnostics.record(
								branch = "manga_metadata_source",
								entity = entitiesById[entityId],
								localContent = content,
								serviceId = checkNotNull(prefs.metadataSourceService),
								remoteId = checkNotNull(prefs.metadataSourceRemoteId),
								trackingNames = trackingNames,
							)
						}
					}
				db.findTrackingLinksByLegacyWorkOrMangaCandidates(
					mangaIds = resolveTrackingCandidateMangaIds(content.id),
				)
					.distinctBy { "${it.service}:${it.remoteId}:${it.mangaId}" }
					.forEach { link ->
					if (entityBindings.any { binding ->
							binding.source.toTrackingServiceOrNull()?.id == link.service &&
								binding.externalId == link.remoteId.toString()
						}
					) {
						return@forEach
					}
					val trackingNames = trackingNames(link.service, link.remoteId)
					if (
						trackingNames.any { it.isNotBlank() } &&
						!trackingNames.isCompatibleWithLocalContent(localContentsById[link.mangaId] ?: content)
					) {
						issues += EntityGraphRepairIssue(
							kind = EntityGraphRepairIssueKind.STALE_TRACKING_CACHE_LINK,
							entityId = entityId,
							source = link.service.toString(),
							externalId = link.remoteId.toString(),
							localMangaId = link.mangaId,
						)
						trackingDiagnostics.record(
							branch = "tracking_site_link",
							entity = entitiesById[entityId],
							localContent = localContentsById[link.mangaId] ?: content,
							serviceId = link.service,
							remoteId = link.remoteId,
							trackingNames = trackingNames,
						)
					}
					}
			}
		}

		val entitiesWithTrackingBindings = activeBindings
			.filter { it.source.toTrackingServiceOrNull() != null }
			.mapTo(mutableSetOf()) { it.entityId }
		dao.dumpRelations()
			.asSequence()
			.filter {
				it.state == EntityRelationState.LEGACY.name ||
					(
						it.origin == EntityRelationOrigin.LEGACY.name &&
							it.state == EntityRelationState.ACTIVE.name
						)
			}
			.filter {
				it.fromEntityId in entitiesWithTrackingBindings ||
					it.toEntityId in entitiesWithTrackingBindings
			}
			.forEach { relation ->
				issues += EntityGraphRepairIssue(
					kind = EntityGraphRepairIssueKind.STALE_LEGACY_RELATION,
					entityId = relation.fromEntityId,
					relationId = relation.id,
				)
			}

		trackingDiagnostics.logIfNeeded(
			totalIssues = issues.count {
				it.kind == EntityGraphRepairIssueKind.SUSPECT_TRACKING_BINDING ||
					it.kind == EntityGraphRepairIssueKind.SUSPECT_METADATA_SOURCE
			},
		)

		EntityGraphRepairReport(
			if (limit == Int.MAX_VALUE) {
				issues
			} else {
				issues.take(limit.coerceAtLeast(1))
			},
		)
	}

	private fun normalizeRepairName(value: String): String = normalizeStrictTitleKey(value)

	private fun normalizeRepairName(content: Content): String = normalizeStrictTitleKey(content.title, listOf(content.source.name))

	private fun EntityRecord.strictRepairNameKeys(): Set<String> {
		return (listOf(primaryName) + decodeStringList(aliases))
			.mapTo(LinkedHashSet()) { normalizeRepairName(it) }
			.filterTo(LinkedHashSet()) { it.isNotBlank() }
	}

	private fun isCompatibleTrackingTitle(content: Content, trackingTitle: String): Boolean {
		val trackingKey = normalizeStrictTitleKey(trackingTitle)
		return trackingKey.isNotBlank() && trackingKey in content.localStrictTitleKeys()
	}

	private fun List<String>.isCompatibleWithAnyLocalContent(localContents: List<Content>): Boolean {
		return localContents.any { content -> isCompatibleWithLocalContent(content) }
	}

	private fun List<String>.isCompatibleWithLocalContent(content: Content): Boolean {
		val trackingKeys = strictTitleKeys()
		if (trackingKeys.isEmpty()) {
			return false
		}
		return content.localStrictTitleKeys().any { it in trackingKeys }
	}

	private fun Content.localStrictTitleKeys(): Set<String> {
		return buildList {
			add(normalizeRepairName(this@localStrictTitleKeys))
			addAll(altTitles)
		}.strictTitleKeys()
	}

	private suspend fun List<EntityBindingRecord>.localContents(): List<Content> {
		return mapNotNull { binding ->
			if (!binding.isLocalReadingSource()) {
				return@mapNotNull null
			}
			val localMangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
			db.getMangaDao().find(localMangaId)?.toContent()
		}
	}

	private suspend fun clearMangaMetadataSourceIfSuspect(
		localMangaId: Long,
		serviceId: Int,
		remoteId: Long,
	) {
		val prefsDao = db.getPreferencesDao()
		val prefs = prefsDao.find(localMangaId) ?: return
		if (
			prefs.metadataSourceKind != "tracking" ||
			prefs.metadataSourceService != serviceId ||
			prefs.metadataSourceRemoteId != remoteId
		) {
			return
		}
		val content = db.getMangaDao().find(localMangaId)?.toContent()
		val names = trackingNames(serviceId, remoteId)
		if (content != null && names.any { it.isNotBlank() } && names.isCompatibleWithLocalContent(content)) {
			return
		}
		prefsDao.upsert(
			prefs.copy(
				metadataSourceKind = null,
				metadataSourceService = null,
				metadataSourceRemoteId = null,
			),
		)
	}

	private suspend fun clearEntityMetadataSourceIfSuspect(
		dao: EntityGraphDao,
		entityId: Long,
		serviceId: Int,
		remoteId: Long,
		now: Long,
	) {
		val prefs = dao.findEntityPrefs(entityId) ?: return
		if (
			prefs.metadataSourceKind != "tracking" ||
			prefs.metadataSourceService != serviceId ||
			prefs.metadataSourceRemoteId != remoteId
		) {
			return
		}
		val localContents = dao.findActiveBindingsByEntity(entityId).localContents()
		val names = trackingNames(serviceId, remoteId)
		if (
			localContents.isNotEmpty() &&
			names.any { it.isNotBlank() } &&
			names.isCompatibleWithAny(dao.findEntity(entityId), localContents)
		) {
			return
		}
		dao.updateEntityMetadataSourceSelection(
			entityId = entityId,
			metadataSourceKind = "base",
			metadataBindingSource = null,
			metadataBindingExternalId = null,
			metadataSourceService = null,
			metadataSourceRemoteId = null,
			updatedAt = now,
		)
	}

	private fun MangaPrefsEntity.hasMatchingMetadataSelection(
		entityPrefs: EntityPrefsRecord,
	): Boolean {
		if (metadataSourceKind != entityPrefs.metadataSourceKind) {
			return false
		}
		return when (metadataSourceKind) {
			"base" -> true
			"tracking" -> metadataSourceService == entityPrefs.metadataSourceService &&
				metadataSourceRemoteId == entityPrefs.metadataSourceRemoteId
			else -> false
		}
	}

	private fun MangaPrefsEntity.hasMatchingOverride(
		entityPrefs: EntityPrefsRecord,
	): Boolean {
		return titleOverride == entityPrefs.titleOverride &&
			coverUrlOverride == entityPrefs.coverUrlOverride &&
			contentRatingOverride == entityPrefs.contentRatingOverride
	}

	private fun EntityPrefsRecord.hasAnyOverride(): Boolean {
		return !titleOverride.isNullOrEmpty() ||
			!coverUrlOverride.isNullOrEmpty() ||
			!contentRatingOverride.isNullOrEmpty()
	}

	private suspend fun trackingNames(serviceId: Int, remoteId: Long): List<String> {
		val trackingItem = db.getTrackingSiteDao().findItem(serviceId, remoteId) ?: return emptyList()
		return buildList {
			add(trackingItem.title)
			addAll(decodeStringList(trackingItem.altTitles))
			trackingItem.primaryTitle?.let(::add)
			trackingItem.secondaryTitle?.let(::add)
		}
	}

	private suspend fun findCompatibleTrackingSelection(
		entityBindings: List<EntityBindingRecord>,
		localContents: List<Content>,
		entity: EntityRecord?,
		excluded: TrackingSelection?,
	): TrackingSelection? {
		val candidates = entityBindings.mapNotNullTo(LinkedHashSet()) { binding ->
			val service = binding.source.toTrackingServiceOrNull() ?: return@mapNotNullTo null
			val remoteId = binding.externalId.toLongOrNull() ?: return@mapNotNullTo null
			TrackingSelection(service.id, remoteId)
		}
		candidates.forEach { selection ->
			if (selection == excluded) {
				return@forEach
			}
			val names = trackingNames(selection.serviceId, selection.remoteId)
			if (names.any { it.isNotBlank() } && names.isCompatibleWithAny(entity, localContents)) {
				return selection
			}
		}
		return null
	}

	private fun List<String>.isCompatibleWithAny(
		entity: EntityRecord?,
		localContents: List<Content>,
	): Boolean {
		val allowedKeys = buildStrictTrackingAnchorKeys(entity, localContents)
		return allowedKeys.isNotEmpty() && strictTitleKeys().any { it in allowedKeys }
	}

	private fun buildStrictTrackingAnchorKeys(
		entity: EntityRecord?,
		localContents: List<Content>,
	): Set<String> {
		return buildList {
			entity?.let {
				add(it.primaryName)
				addAll(decodeStringList(it.aliases))
			}
			localContents.forEach { content ->
				addAll(content.localStrictTitleKeys())
			}
		}.strictTitleKeys()
	}

	private fun Iterable<String>.strictTitleKeys(): Set<String> {
		return mapTo(LinkedHashSet()) { normalizeStrictTitleKey(it) }
			.filterTo(LinkedHashSet()) { it.isNotBlank() }
	}

	private suspend fun applyMetadataSelection(
		dao: EntityGraphDao,
		entityId: Long,
		selection: TrackingSelection?,
		now: Long,
	) {
		db.withTransaction {
			val transactionDao = db.getEntityGraphDao()
			transactionDao.findEntity(entityId) ?: return@withTransaction
			transactionDao.insertEntityPrefsIgnore(newEntityPrefs(entityId, now))
			transactionDao.updateEntityMetadataSourceSelection(
				entityId = entityId,
				metadataSourceKind = if (selection == null) "base" else "tracking",
				metadataBindingSource = selection?.serviceId?.toString(),
				metadataBindingExternalId = selection?.remoteId?.toString(),
				metadataSourceService = selection?.serviceId,
				metadataSourceRemoteId = selection?.remoteId,
				updatedAt = now,
			)
		}
	}

	private fun newEntityPrefs(entityId: Long, now: Long) = EntityPrefsRecord(
		entityId = entityId,
		preferredLocalMangaId = null,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		readingStatus = null,
		metadataSourceKind = null,
		metadataBindingSource = null,
		metadataBindingExternalId = null,
		metadataSourceService = null,
		metadataSourceRemoteId = null,
		updatedAt = now,
	)

	private data class TrackingSelection(
		val serviceId: Int,
		val remoteId: Long,
	)

	private suspend fun resolveOrCreateCharacter(
		source: String,
		workEntity: Entity,
		character: TrackingCharacterDto,
		now: Long,
		relationSource: RelationSourceKey?,
	): Entity {
		val entity = resolveOrCreateEntity(
			type = EntityType.CHARACTER,
			primaryName = character.primaryName,
			aliases = character.aliases,
			source = source,
			externalId = character.externalId,
			now = now,
		)
		insertRelationIfAbsent(
			fromEntityId = entity.id,
			toEntityId = workEntity.id,
			type = RelationType.BELONGS_TO,
			now = now,
			relationSource = relationSource,
		)
		return entity
	}

	private suspend fun findEntityByLocalMangaId(
		localMangaId: Long,
	): EntityBindingRecord? {
		val dao = db.getEntityGraphDao()
		return dao.findActiveBinding("local_manga", localMangaId.toString())
			?: dao.findActiveBinding("0", localMangaId.toString())
	}

	private suspend fun resolveTrackingCandidateMangaIds(localMangaId: Long): List<Long> {
		val binding = findEntityByLocalMangaId(localMangaId)
			?: return listOf(localMangaId)
		val dao = db.getEntityGraphDao()
		val preferredLocalMangaId = dao.findEntityPrefs(binding.entityId)?.preferredLocalMangaId
		val localMangaIds = dao.findActiveBindingsByEntity(binding.entityId)
			.asSequence()
			.filter { it.source == "local_manga" || it.source == "0" }
			.mapNotNull { it.externalId.toLongOrNull() }
			.toList()
		return buildList {
			add(localMangaId)
			preferredLocalMangaId?.let(::add)
			addAll(localMangaIds)
		}.distinct()
	}

	private suspend fun resolveOrCreatePerson(
		source: String,
		person: TrackingPersonDto,
		now: Long,
	): Entity {
		return resolveOrCreateEntity(
			type = EntityType.PERSON,
			primaryName = person.primaryName,
			aliases = person.aliases,
			source = source,
			externalId = person.externalId,
			now = now,
		)
	}

	private suspend fun resolveOrCreateStaff(
		source: String,
		staff: TrackingStaffDto,
		now: Long,
	): Entity {
		return resolveOrCreateEntity(
			type = EntityType.PERSON,
			primaryName = staff.primaryName,
			aliases = staff.aliases,
			source = source,
			externalId = staff.externalId,
			now = now,
		)
	}

	private suspend fun resolveOrCreateEntity(
		type: EntityType,
		primaryName: String,
		aliases: List<String>,
		source: String?,
		externalId: String?,
		contentType: ContentType? = null,
		now: Long,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
	): Entity {
		val dao = db.getEntityGraphDao()
		if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
			val existingBinding = findBindingBySourceKey(source, externalId)
			if (existingBinding != null) {
				dao.findEntity(existingBinding.entityId)?.let { record ->
					if (!record.acceptsContentType(contentType)) {
						return@let
					}
					val merged = mergeEntityRecord(
						record = record,
						primaryName = primaryName,
						aliases = aliases,
						now = now,
					)
					dao.updateEntity(merged)
					dao.touchEntity(merged.id, now)
					dao.upsertBindingForSource(
						entityId = merged.id,
						source = source,
						externalId = externalId,
						confidence = 1f,
						createdBy = createdBy,
					)
					return dao.findEntity(merged.id)?.toModel() ?: merged.toModel()
				}
			}
		}

		val animeOfflineCandidate = resolveAnimeOfflineCandidate(source, externalId, contentType, now)
		if (animeOfflineCandidate != null) {
			return mergeIntoResolvedEntity(
				entity = animeOfflineCandidate,
				primaryName = primaryName,
				aliases = aliases,
				source = source,
				externalId = externalId,
				confidence = 0.99f,
				contentType = contentType,
				now = now,
				createdBy = createdBy,
			)
		}
		val malsyncCandidate = resolveMalSyncCandidate(
			source = source,
			externalId = externalId,
			contentType = contentType,
			now = now,
		)
		if (malsyncCandidate != null) {
			return mergeIntoResolvedEntity(
				entity = malsyncCandidate,
				primaryName = primaryName,
				aliases = aliases,
				source = source,
				externalId = externalId,
				confidence = 0.98f,
				contentType = contentType,
				now = now,
				createdBy = createdBy,
			)
		}
		val candidate = pickCandidate(
			type = type,
			primaryName = primaryName,
			aliases = aliases,
			contentType = contentType,
			now = now,
		)
		if (candidate != null) {
			when (candidate.strength) {
				EntityBindingStrength.AUTO_BIND -> {
					return mergeIntoResolvedEntity(
						entity = candidate.entity,
						primaryName = primaryName,
						aliases = aliases,
						source = source,
						externalId = externalId,
						contentType = contentType,
						confidence = candidate.confidence,
						now = now,
						createdBy = createdBy,
					)
				}

				EntityBindingStrength.WEAK_BIND -> {
					val created = createEntity(
						type = type,
						primaryName = primaryName,
						aliases = aliases,
						source = source,
						externalId = externalId,
						contentType = contentType,
						confidence = 1f,
						now = now,
						createdBy = createdBy,
					)
					insertRelationIfAbsent(
						fromEntityId = created.id,
						toEntityId = candidate.entity.id,
						type = RelationType.RELATED_TO,
						now = now,
						weight = candidate.confidence,
					)
					return created
				}

				EntityBindingStrength.IGNORE -> Unit
			}
		}

		return createEntity(
			type = type,
			primaryName = primaryName,
			aliases = aliases,
			source = source,
			externalId = externalId,
			contentType = contentType,
			confidence = 1f,
			now = now,
			createdBy = createdBy,
		)
	}

	private suspend fun resolveAnimeOfflineCandidate(
		source: String?,
		externalId: String?,
		contentType: ContentType?,
		now: Long,
	): Entity? {
		val service = source.toScrobblerServiceOrNull() ?: return null
		val remoteId = externalId?.toLongOrNull() ?: return null
		val mappings = animeOfflineRepository.resolveMappings(service, remoteId)
		return resolveMappedCandidate(
			now = now,
			mappings = mappings.map { it.service to it.remoteId },
			contentType = contentType,
		)
	}

	private suspend fun resolveMalSyncCandidate(
		source: String?,
		externalId: String?,
		contentType: ContentType?,
		now: Long,
	): Entity? {
		val service = source.toScrobblerServiceOrNull() ?: return null
		val remoteId = externalId?.toLongOrNull() ?: return null
		val kind = contentType.toMalSyncKindOrNull() ?: return null
		val mappings = malsyncMappingRepository.resolve(service, remoteId, kind)
		return resolveMappedCandidate(
			now = now,
			mappings = mappings.map { it.service to it.remoteId },
			contentType = contentType,
		)
	}

	private suspend fun resolveMappedCandidate(
		now: Long,
		mappings: List<Pair<ScrobblerService, Long>>,
		contentType: ContentType?,
	): Entity? {
		if (mappings.isEmpty()) {
			return null
		}
		val dao = db.getEntityGraphDao()
		for ((service, remoteId) in mappings) {
			val binding = findBindingBySourceKey(service.id.toString(), remoteId.toString()) ?: continue
			dao.touchEntity(binding.entityId, now)
			return dao.findEntity(binding.entityId)
				?.takeIf { it.toModel().canAutoBindContentType(contentType) }
				?.toModel()
		}
		return null
	}

	private suspend fun mergeIntoResolvedEntity(
		entity: Entity,
		primaryName: String,
		aliases: List<String>,
		source: String?,
		externalId: String?,
		confidence: Float,
		contentType: ContentType? = null,
		now: Long,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
	): Entity {
		val dao = db.getEntityGraphDao()
		val merged = mergeEntityRecord(
			record = entity.toRecord().withContentType(contentType),
			primaryName = primaryName,
			aliases = aliases,
			now = now,
		)
		dao.updateEntity(merged)
		dao.touchEntity(merged.id, now)
		if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
			dao.upsertBindingForSource(
				entityId = entity.id,
				source = source,
				externalId = externalId,
				confidence = confidence,
				createdBy = createdBy,
			)
		}
		return dao.findEntity(entity.id)?.toModel() ?: entity
	}

	private suspend fun findBindingBySourceKey(
		source: String,
		externalId: String,
	): EntityBindingRecord? {
		val dao = db.getEntityGraphDao()
		for (candidateSource in source.bindingSourceKeys()) {
			dao.findActiveBinding(candidateSource, externalId)?.let { return it }
		}
		return null
	}

	private suspend fun EntityGraphDao.upsertBindingForSource(
		entityId: Long,
		source: String,
		externalId: String,
		confidence: Float,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
		sourceKind: EntityBindingSourceKind? = null,
	) {
		val existing = findBinding(source, externalId)
		if (existing?.state in AUTO_BIND_OVERWRITE_BLOCKING_STATES) {
			return
		}
		val bindings = findActiveBindingsByEntity(entityId)
		upsertBinding(
			EntityBindingRecord(
				entityId = entityId,
				source = source,
				externalId = externalId,
				confidence = confidence,
				isPrimary = bindings.isEmpty(),
				sourceKind = sourceKind?.name ?: source.toEntityBindingSourceKind().name,
				state = if (createdBy == EntityBindingCreatedBy.USER) {
					EntityBindingState.MANUAL.name
				} else {
					EntityBindingState.CONFIRMED.name
				},
				createdBy = createdBy.name,
				updatedAt = System.currentTimeMillis(),
			),
		)
		reconcileProjectionSyncId(entityId)
	}

	private suspend fun EntityGraphDao.reconcileProjectionSyncId(entityId: Long) {
		val entity = findEntity(entityId) ?: return
		val projectionBindings = findActiveBindingsByEntity(entityId)
			.filter { it.isAuthoritativeProjectionBinding() }
		if (projectionBindings.size != 1) {
			return
		}
		val projection = projectionBindings.single()
		val projectionSyncId = computeProjectionSyncId(
			source = projection.source,
			externalId = projection.externalId,
		)
		val syncIdOwner = findEntityBySyncId(projectionSyncId)
		val resolvedSyncId = entity.resolveProjectionSyncId(
			projectionSyncId = projectionSyncId,
			conflictingEntityId = syncIdOwner?.id,
		)
		if (syncIdOwner != null && syncIdOwner.id != entityId) {
			Log.w(
				TAG,
				"reconcileProjectionSyncId: projection sync id is already owned by " +
					"entityId=${syncIdOwner.id}; keeping entityId=$entityId syncId=$resolvedSyncId",
			)
		}
		if (entity.syncId == resolvedSyncId) {
			return
		}
		updateEntity(entity.copy(syncId = resolvedSyncId))
	}

	private suspend fun EntityGraphDao.attachLocalWorkBindingForMerge(
		entityId: Long,
		externalId: String,
		now: Long,
		confidence: Float = 1f,
	) {
		deleteBindingBySource("0", externalId)
		upsertBinding(
			EntityBindingRecord(
				entityId = entityId,
				source = "local_manga",
				externalId = externalId,
				confidence = confidence,
				isPrimary = findActiveBindingsByEntity(entityId).isEmpty(),
				state = EntityBindingState.MANUAL.name,
				createdBy = EntityBindingCreatedBy.USER.name,
				updatedAt = now,
			),
		)
	}

	private suspend fun EntityGraphDao.attachProjectionBindingWithoutSyncIdRewrite(
		entityId: Long,
		content: Content,
		now: Long,
		confidence: Float = 1f,
	) {
		val projectionKey = ProjectionIdentityKeys.bindingKey(content.url, content.publicUrl) ?: return
		val existing = findBinding(content.source.name, projectionKey)
		if (existing?.state in AUTO_BIND_OVERWRITE_BLOCKING_STATES) {
			return
		}
		upsertBinding(
			EntityBindingRecord(
				entityId = entityId,
				source = content.source.name,
				externalId = projectionKey,
				confidence = confidence,
				isPrimary = false,
				sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
				state = EntityBindingState.MANUAL.name,
				createdBy = EntityBindingCreatedBy.USER.name,
				updatedAt = now,
			),
		)
	}

	private suspend fun EntityGraphDao.upsertProjectionBindingForContent(
		entityId: Long,
		content: Content,
		confidence: Float,
		createdBy: EntityBindingCreatedBy,
		sourceKind: EntityBindingSourceKind,
		allowManualMove: Boolean = false,
	) {
		val projectionKey = ProjectionIdentityKeys.bindingKey(content.url, content.publicUrl) ?: return
		if (allowManualMove) {
			upsertBinding(
				EntityBindingRecord(
					entityId = entityId,
					source = content.source.name,
					externalId = projectionKey,
					confidence = confidence,
					isPrimary = false,
					sourceKind = sourceKind.name,
					state = EntityBindingState.MANUAL.name,
					createdBy = createdBy.name,
					updatedAt = System.currentTimeMillis(),
				),
			)
			reconcileProjectionSyncId(entityId)
			return
		}
		upsertBindingForSource(
			entityId = entityId,
			source = content.source.name,
			externalId = projectionKey,
			confidence = confidence,
			createdBy = createdBy,
			sourceKind = sourceKind,
		)
	}

	private suspend fun createEntity(
		type: EntityType,
		primaryName: String,
		aliases: List<String>,
		source: String?,
		externalId: String?,
		confidence: Float,
		contentType: ContentType? = null,
		now: Long,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
	): Entity {
		val dao = db.getEntityGraphDao()
		val trimmedName = resolveEntityPrimaryName(primaryName, aliases, source, externalId)
		val nameHash = computeNameHash(trimmedName)
		val record = EntityRecord(
			type = type.name,
			contentType = contentType?.name,
			syncId = if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
				computeProjectionSyncId(source, externalId)
			} else {
				java.util.UUID.randomUUID().toString()
			},
			primaryName = trimmedName,
			nameHash = nameHash,
			aliases = encodeStringList(mergeAliases(trimmedName, aliases + primaryName).drop(1)),
			createdAt = now,
			lastAccessed = now,
			accessCount = 1,
		)
		// INSERT OR IGNORE: if a concurrent request already created this entity (same type + name_hash + content type),
		// we fall back to merging into the existing one instead of creating a duplicate.
		var id = dao.insertEntityIgnore(record)
		if (id == -1L) {
			val existing = dao.findEntityByTypeAndNameHashAndContentType(
				type = type.name,
				nameHash = nameHash,
				contentType = contentType?.name,
			)
			if (existing != null) {
				return mergeIntoResolvedEntity(
					entity = existing.toModel(),
					primaryName = primaryName,
					aliases = aliases,
					source = source,
					externalId = externalId,
					confidence = confidence,
					contentType = contentType,
					now = now,
					createdBy = createdBy,
				)
			}
			id = dao.insertEntity(
				record.copy(
					syncId = java.util.UUID.randomUUID().toString(),
					nameHash = "$nameHash|$now|${record.syncId}".longHashCode(),
				),
			)
		}
		if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
			dao.upsertBindingForSource(
				entityId = id,
				source = source,
				externalId = externalId,
				confidence = confidence,
				createdBy = createdBy,
			)
		}
		return requireNotNull(dao.findEntity(id)).toModel()
	}

	private suspend fun createDetachedLocalWorkEntity(
		content: Content,
		now: Long,
	): Entity {
		val dao = db.getEntityGraphDao()
		val baseName = content.title.trim().ifBlank { content.id.toString() }
		val sourceLabel = content.source.name.trim().ifBlank { "local" }
		var suffixIndex = 0
		var id: Long
		while (true) {
			val identityName = when (suffixIndex) {
				0 -> baseName
				1 -> "$baseName ($sourceLabel)"
				2 -> "$baseName ($sourceLabel #${content.id})"
				else -> "$baseName ($sourceLabel #${content.id}-$suffixIndex)"
			}
			val nameHash = computeNameHash(identityName)
			if (dao.findEntityByTypeAndNameHashAndContentType(
					EntityType.WORK.name,
					nameHash,
					content.source.contentType.name,
				) == null) {
				id = dao.insertEntityIgnore(
					EntityRecord(
						type = EntityType.WORK.name,
						contentType = content.source.contentType.name,
						primaryName = baseName,
						nameHash = nameHash,
						aliases = encodeStringList(content.altTitles.distinct().take(MAX_ENTITY_ALIASES)),
						createdAt = now,
						lastAccessed = now,
						accessCount = 1,
					),
				)
				if (id != -1L) {
					break
				}
			}
			suffixIndex++
		}
		dao.upsertBindingForSource(
			entityId = id,
			source = "local_manga",
			externalId = content.id.toString(),
			confidence = 1f,
			createdBy = EntityBindingCreatedBy.USER,
		)
		dao.upsertProjectionBindingForContent(
			entityId = id,
			content = content,
			confidence = 1f,
			createdBy = EntityBindingCreatedBy.USER,
			sourceKind = EntityBindingSourceKind.READING_SOURCE,
			allowManualMove = true,
		)
		return requireNotNull(dao.findEntity(id)).toModel()
	}

	private suspend fun resetDetachedLocalWorkPrefs(
		dao: EntityGraphDao,
		entityId: Long,
		localMangaId: Long,
		now: Long,
	) {
		dao.upsertPrefsRecord(
			EntityPrefsRecord(
				entityId = entityId,
				preferredLocalMangaId = localMangaId,
				titleOverride = null,
				coverUrlOverride = null,
				contentRatingOverride = null,
				readingStatus = null,
				metadataSourceKind = "base",
				metadataBindingSource = null,
				metadataBindingExternalId = null,
				metadataSourceService = null,
				metadataSourceRemoteId = null,
				updatedAt = now,
			),
		)
		val prefsDao = db.getPreferencesDao()
		val prefs = prefsDao.find(localMangaId) ?: newMangaPrefs(localMangaId)
		prefsDao.upsert(
			prefs.copy(
				metadataSourceKind = null,
				metadataSourceService = null,
				metadataSourceRemoteId = null,
			),
		)
	}

	private fun newMangaPrefs(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = ReaderColorFilter.EMPTY.brightness,
		cfContrast = ReaderColorFilter.EMPTY.contrast,
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		metadataSourceKind = null,
		metadataSourceService = null,
		metadataSourceRemoteId = null,
		readingStatus = null,
		ignoredTrackingSuggestionService = null,
		ignoredTrackingSuggestionRemoteId = null,
	)

	private suspend fun createDetachedLocalWorkEntity(
		localMangaId: Long,
		previousEntity: EntityRecord,
		now: Long,
	): Entity {
		val dao = db.getEntityGraphDao()
		val baseName = stripEntityDisambiguationTitleSuffix(
			value = previousEntity.primaryName,
			sourceNames = decodeStringList(previousEntity.aliases) + "local",
		).trim().ifBlank { localMangaId.toString() }
		var suffixIndex = 0
		var id: Long
		while (true) {
			val identityName = when (suffixIndex) {
				0 -> "$baseName (local #$localMangaId)"
				else -> "$baseName (local #$localMangaId-$suffixIndex)"
			}
			val nameHash = computeNameHash(identityName)
			if (dao.findEntityByTypeAndNameHashAndContentType(
					EntityType.WORK.name,
					nameHash,
					previousEntity.contentType,
				) == null) {
				id = dao.insertEntityIgnore(
					EntityRecord(
						type = EntityType.WORK.name,
						contentType = previousEntity.contentType,
						primaryName = baseName,
						nameHash = nameHash,
						aliases = encodeStringList(
							decodeStringList(previousEntity.aliases)
								.distinct()
								.take(MAX_ENTITY_ALIASES),
						),
						createdAt = now,
						lastAccessed = now,
						accessCount = 1,
					),
				)
				if (id != -1L) {
					break
				}
			}
			suffixIndex++
		}
		dao.upsertBindingForSource(
			entityId = id,
			source = "local_manga",
			externalId = localMangaId.toString(),
			confidence = 1f,
			createdBy = EntityBindingCreatedBy.USER,
		)
		return requireNotNull(dao.findEntity(id)).toModel()
	}

	private suspend fun updateEntityAfterLocalProjectionSplit(
		dao: EntityGraphDao,
		entity: EntityRecord,
		namesToRemove: Set<String>,
		now: Long,
	) {
		val updatedAliases = if (namesToRemove.isEmpty()) {
			decodeStringList(entity.aliases)
		} else {
			decodeStringList(entity.aliases).filterNot { alias ->
				normalizeRepairName(alias) in namesToRemove
			}
		}
		dao.updateEntity(
			entity.copy(
				aliases = encodeStringList(updatedAliases.take(MAX_ENTITY_ALIASES)),
				lastAccessed = now,
				accessCount = entity.accessCount + 1,
			),
		)
	}

	private suspend fun moveDetachedLocalWorkState(
		oldEntityId: Long,
		newEntityId: Long,
		localMangaId: Long,
	) {
		val workFavouritesDao = db.getWorkFavouritesDao()
		val movedFavouriteRows = workFavouritesDao.moveAnchorToEntity(
			oldEntityId = oldEntityId,
			newEntityId = newEntityId,
			anchorMangaId = localMangaId,
		)
		if (movedFavouriteRows == 0) {
			workFavouritesDao.copyActiveCategoriesToEntity(
				oldEntityId = oldEntityId,
				newEntityId = newEntityId,
				anchorMangaId = localMangaId,
			)
		}
		db.getWorkHistoryDao().moveAnchorToEntity(
			oldEntityId = oldEntityId,
			newEntityId = newEntityId,
			anchorMangaId = localMangaId,
		)
		db.getWorkStatsDao().moveAnchorToEntity(
			oldEntityId = oldEntityId,
			newEntityId = newEntityId,
			anchorMangaId = localMangaId,
		)
	}

	private suspend fun recordProjectionIdentityAction(
		localMangaId: Long,
		oldEntityId: Long?,
		newEntityId: Long,
		action: String,
		status: String,
	) {
		recordProjectionIdentityActionInTransaction(
			localMangaId = localMangaId,
			oldEntityId = oldEntityId,
			newEntityId = newEntityId,
			action = action,
			status = status,
			now = System.currentTimeMillis(),
		)
	}

	private suspend fun recordProjectionIdentityActionInTransaction(
		localMangaId: Long,
		oldEntityId: Long?,
		newEntityId: Long,
		action: String,
		status: String,
		now: Long,
	) {
		db.getWorkMigrationLedgerDao().upsert(
			WorkMigrationLedgerEntity(
				legacyTable = WORK_PROJECTION_IDENTITY_ACTION_TABLE,
				legacyKey = localMangaId.toString(),
				legacyChecksum = listOf(
					oldEntityId?.toString().orEmpty(),
					newEntityId.toString(),
					action,
				).joinToString(separator = ":"),
				targetEntityId = newEntityId,
				migrationVersion = WORK_PROJECTION_IDENTITY_ACTION_VERSION,
				status = status,
				migratedAt = now,
			),
		)
	}

	private suspend fun clearPreferredLocalProjectionIfDetached(
		dao: EntityGraphDao,
		entityId: Long,
		detachedLocalMangaId: Long,
		fallbackLocalMangaId: Long?,
		now: Long,
	) {
		val prefs = dao.findEntityPrefs(entityId) ?: return
		if (prefs.preferredLocalMangaId != detachedLocalMangaId) {
			return
		}
		dao.updateEntityPreferredLocalMangaId(
			entityId = entityId,
			preferredLocalMangaId = fallbackLocalMangaId,
			updatedAt = now,
		)
	}

	private suspend fun setPreferredLocalProjectionInTransaction(
		dao: EntityGraphDao,
		entityId: Long,
		localMangaId: Long,
		now: Long,
	) {
		dao.insertEntityPrefsIgnore(newEntityPrefs(entityId, now))
		dao.updateEntityPreferredLocalMangaId(
			entityId = entityId,
			preferredLocalMangaId = localMangaId,
			updatedAt = now,
		)
	}

	private suspend fun reconcileDetachedLocalProjectionAnchors(
		entityId: Long,
		detachedLocalMangaId: Long,
		fallbackLocalMangaId: Long,
		now: Long,
	) {
		db.getWorkFavouritesDao().replaceAnchorMangaId(
			entityId = entityId,
			oldAnchorMangaId = detachedLocalMangaId,
			newAnchorMangaId = fallbackLocalMangaId,
			updatedAt = now,
		)
		val historyDao = db.getWorkHistoryDao()
		historyDao.replaceActiveAnchorMangaId(
			entityId = entityId,
			oldAnchorMangaId = detachedLocalMangaId,
			newAnchorMangaId = fallbackLocalMangaId,
			updatedAt = now,
		)
		db.getWorkStatsDao().replaceAnchorMangaId(
			entityId = entityId,
			oldAnchorMangaId = detachedLocalMangaId,
			newAnchorMangaId = fallbackLocalMangaId,
		)
	}

	private suspend fun reconcileSourceEntityWorkStateAfterProjectionSplit(
		dao: EntityGraphDao,
		entityId: Long,
		detachedLocalMangaId: Long,
		now: Long,
	) {
		val fallbackLocalMangaId = findFallbackLocalProjectionId(
			dao = dao,
			entityId = entityId,
			detachedLocalMangaId = detachedLocalMangaId,
		)
		clearPreferredLocalProjectionIfDetached(
			dao = dao,
			entityId = entityId,
			detachedLocalMangaId = detachedLocalMangaId,
			fallbackLocalMangaId = fallbackLocalMangaId,
			now = now,
		)
		if (fallbackLocalMangaId != null) {
			reconcileDetachedLocalProjectionAnchors(
				entityId = entityId,
				detachedLocalMangaId = detachedLocalMangaId,
				fallbackLocalMangaId = fallbackLocalMangaId,
				now = now,
			)
		} else {
			pruneUnexportableWorkStateByAnchor(
				entityId = entityId,
				danglingAnchorId = detachedLocalMangaId,
				now = now,
			)
			db.getWorkFavouritesDao().deactivateActiveWithoutAnchor(
				entityId = entityId,
				updatedAt = now,
			)
		}
	}

	private suspend fun findWorkStateAnchorCandidate(
		dao: EntityGraphDao,
		entityId: Long,
		excludedLocalMangaId: Long? = null,
	): Long? {
		val activeLocalMangaIds = dao.findActiveLocalBindingsByEntity(entityId)
			.asSequence()
			.mapNotNull { it.externalId.toLongOrNull() }
			.filter { it != excludedLocalMangaId }
			.toList()
		if (activeLocalMangaIds.isEmpty()) {
			return null
		}
		val preferredLocalMangaId = dao.findEntityPrefs(entityId)?.preferredLocalMangaId
		return preferredLocalMangaId
			?.takeIf { it in activeLocalMangaIds }
			?: activeLocalMangaIds.first()
	}

	private suspend fun findFallbackLocalProjectionId(
		dao: EntityGraphDao,
		entityId: Long,
		detachedLocalMangaId: Long,
	): Long? {
		return findWorkStateAnchorCandidate(
			dao = dao,
			entityId = entityId,
			excludedLocalMangaId = detachedLocalMangaId,
		)
	}

	private suspend fun pruneUnexportableWorkStateByAnchor(
		entityId: Long,
		danglingAnchorId: Long,
		now: Long,
	): Int {
		var affected = 0
		db.getWorkFavouritesDao().findActiveByAnchorMangaId(danglingAnchorId)
			.asSequence()
			.filter { it.entityId == entityId }
			.forEach { favourite ->
				db.getWorkFavouritesDao().delete(
					entityId = favourite.entityId,
					categoryId = favourite.categoryId,
				)
				affected++
			}
		val history = db.getWorkHistoryDao().findActiveByAnchorMangaId(danglingAnchorId)
		if (history?.entityId == entityId) {
			db.getWorkHistoryDao().deleteActiveByAnchor(
				entityId = entityId,
				anchorMangaId = danglingAnchorId,
				deletedAt = now,
			)
			affected++
		}
		affected += db.getWorkStatsDao().deleteByAnchorMangaId(
			entityId = entityId,
			anchorMangaId = danglingAnchorId,
		)
		return affected
	}

	private fun Content.localProjectionNameKeys(): Set<String> {
		return (listOf(title) + altTitles)
			.mapTo(LinkedHashSet()) { normalizeRepairName(it) }
			.filterTo(LinkedHashSet()) { it.isNotBlank() }
	}

	private suspend fun deleteLocalReadingBinding(
		dao: EntityGraphDao,
		externalId: String,
	) {
		dao.deleteBindingBySource("local_manga", externalId)
		dao.deleteBindingBySource("0", externalId)
	}

	private suspend fun deleteLocalProjectionBindings(
		dao: EntityGraphDao,
		localMangaId: Long,
	) {
		deleteLocalReadingBinding(dao, localMangaId.toString())
		db.getMangaDao().find(localMangaId)?.toContent()?.let { content ->
			deleteProjectionBinding(dao, content)
		}
	}

	private suspend fun deleteLocalProjectionBindings(
		dao: EntityGraphDao,
		content: Content,
	) {
		deleteLocalReadingBinding(dao, content.id.toString())
		deleteProjectionBinding(dao, content)
	}

	private suspend fun deleteProjectionBinding(
		dao: EntityGraphDao,
		content: Content,
	) {
		val projectionKey = ProjectionIdentityKeys.bindingKey(content.url, content.publicUrl) ?: return
		val binding = dao.findBinding(content.source.name, projectionKey) ?: return
		var hasSiblingWithSameProjectionKey = false
		for (localBinding in dao.findActiveLocalBindingsByEntity(binding.entityId)) {
			val siblingId = localBinding.externalId.toLongOrNull()
			if (siblingId == null || siblingId == content.id) {
				continue
			}
			val sibling = db.getMangaDao().find(siblingId)?.manga ?: continue
			if (
				sibling.source == content.source.name &&
					ProjectionIdentityKeys.bindingKey(sibling.url, sibling.publicUrl) == projectionKey
			) {
				hasSiblingWithSameProjectionKey = true
				break
			}
		}
		if (!hasSiblingWithSameProjectionKey) {
			dao.deleteBindingBySource(content.source.name, projectionKey)
		}
	}

	private fun String?.toScrobblerServiceOrNull(): ScrobblerService? {
		val raw = this?.trim().orEmpty()
		if (raw.isBlank()) {
			return null
		}
		return raw.toIntOrNull()?.let { id ->
			ScrobblerService.entries.firstOrNull { it.id == id }
		} ?: ScrobblerService.entries.firstOrNull {
			it.name.equals(raw, ignoreCase = true)
		}
	}

	private fun String.bindingSourceKeys(): List<String> {
		val raw = trim()
		if (raw.isBlank()) {
			return emptyList()
		}
		val service = raw.toScrobblerServiceOrNull()
		return buildList {
			add(raw)
			service?.let {
				add(it.id.toString())
				add(it.name.lowercase())
			}
		}.distinct()
	}

	private fun ContentType?.toMalSyncKindOrNull(): MALSyncMappingRepository.Kind? = when (this) {
		ContentType.VIDEO,
		ContentType.HENTAI_VIDEO,
		-> MALSyncMappingRepository.Kind.ANIME

		ContentType.MANGA,
		ContentType.MANHWA,
		ContentType.MANHUA,
		ContentType.HENTAI_MANGA,
		ContentType.HENTAI_NOVEL,
		ContentType.COMICS,
		ContentType.NOVEL,
		ContentType.ONE_SHOT,
		ContentType.DOUJINSHI,
		-> MALSyncMappingRepository.Kind.MANGA

		else -> null
	}

	private suspend fun pickCandidate(
		type: EntityType,
		primaryName: String,
		aliases: List<String>,
		contentType: ContentType?,
		now: Long,
	): CandidateMatch? {
		val probe = Entity(
			id = 0L,
			type = type,
			contentType = contentType,
			primaryName = primaryName.trim(),
			aliases = mergeAliases(primaryName, aliases).drop(1),
			createdAt = now,
			lastAccessed = now,
			accessCount = 1,
		)
		return db.getEntityGraphDao().findEntitiesByType(type.name, ENTITY_SCAN_LIMIT)
			.map { it.toModel() }
			.map { entity ->
				val confidence = bindingMatcher.tryBindEntities(probe, entity)
				CandidateMatch(
					entity = entity,
					confidence = confidence,
					strength = bindingMatcher.classify(confidence),
				)
			}
			.filter { it.strength != EntityBindingStrength.IGNORE }
			.maxWithOrNull(
				compareBy<CandidateMatch> { it.confidence }
					.thenBy { it.entity.accessCount }
					.thenBy { it.entity.lastAccessed },
			)
	}

	private suspend fun insertRelationIfAbsent(
		fromEntityId: Long,
		toEntityId: Long,
		type: RelationType,
		now: Long,
		weight: Float = RELATION_WEIGHT_DEFAULT,
		relationSource: RelationSourceKey? = null,
	) {
		if (fromEntityId <= 0L || toEntityId <= 0L || fromEntityId == toEntityId) {
			return
		}
		db.getEntityGraphDao().insertRelation(
			RelationRecord(
				fromEntityId = fromEntityId,
				toEntityId = toEntityId,
				type = type.name,
				weight = weight,
				createdAt = now,
				sourceBindingSource = relationSource?.source.orEmpty(),
				sourceBindingExternalId = relationSource?.externalId.orEmpty(),
				origin = if (relationSource != null) {
					EntityRelationOrigin.TRACKING_INGEST.name
				} else {
					EntityRelationOrigin.LEGACY.name
				},
				state = if (relationSource != null) {
					EntityRelationState.ACTIVE.name
				} else {
					EntityRelationState.LEGACY.name
				},
				updatedAt = now,
			),
		)
	}

	private fun mergeEntityRecord(
		record: EntityRecord,
		primaryName: String,
		aliases: List<String>,
		now: Long,
	): EntityRecord {
		val fallbackName = resolveEntityPrimaryName(
			record.primaryName,
			decodeStringList(record.aliases) + aliases,
			source = null,
			externalId = record.id.takeIf { it > 0L }?.toString(),
		)
		val mergedNames = mergeAliases(
			primaryName = fallbackName,
			aliases = decodeStringList(record.aliases) + listOf(primaryName) + aliases,
		)
		val newPrimaryName = mergedNames.firstOrNull() ?: fallbackName
		return record.copy(
			primaryName = newPrimaryName,
			nameHash = computeNameHash(newPrimaryName),
			aliases = encodeStringList(mergedNames.drop(1).take(MAX_ENTITY_ALIASES)),
			lastAccessed = now,
		)
	}

	private suspend fun EntityRecord.withInferredContentType(
		dao: EntityGraphDao,
		fallback: ContentType?,
	): EntityRecord {
		if (contentType != null || type != EntityType.WORK.name) {
			return this
		}
		val knownTypes = dao.findActiveLocalBindingsByEntity(id)
			.mapNotNull { binding ->
				val localMangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
				val manga = db.getMangaDao().find(localMangaId)?.manga ?: return@mapNotNull null
				manga.contentType?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
					?: ContentSource(manga.source).resolvedContentTypeForSnapshot()
			}
			.toMutableSet()
		fallback?.let(knownTypes::add)
		val inferredType = knownTypes.firstOrNull()?.takeIf { first ->
			knownTypes.all { first.isWorkContentTypeCompatibleWith(it) }
		}
		return inferredType?.name?.let { copy(contentType = it) } ?: this
	}

	private suspend fun updateEntityResolvingNameHashConflict(
		dao: EntityGraphDao,
		original: EntityRecord,
		merged: EntityRecord,
		primaryName: String,
		aliases: List<String>,
		now: Long,
	): EntityRecord {
		val conflict = dao.findEntityByTypeAndNameHashAndContentType(
			merged.type,
			merged.nameHash,
			merged.contentType,
		)
		if (conflict == null || conflict.id == original.id) {
			dao.updateEntity(merged)
			return merged
		}
		val target = mergeEntityRecord(
			record = conflict,
			primaryName = primaryName,
			aliases = aliases + original.primaryName + decodeStringList(original.aliases),
			now = now,
		)
		dao.updateEntity(target)
		remapBindingsAndRelations(
			dao = dao,
			targetEntityId = conflict.id,
			sourceEntityIds = listOf(original.id),
		)
		dao.deleteEntitiesByIds(listOf(original.id))
		return target
	}

	private fun resolveEntityPrimaryName(
		primaryName: String,
		aliases: List<String>,
		source: String?,
		externalId: String?,
	): String {
		return sequenceOf(primaryName)
			.plus(aliases.asSequence())
			.map { it.trim() }
			.firstOrNull { it.isNotEmpty() }
			?: listOfNotNull(source?.trim()?.takeIf { it.isNotEmpty() }, externalId?.trim()?.takeIf { it.isNotEmpty() })
				.joinToString(":")
				.takeIf { it.isNotEmpty() }
			?: "Untitled"
	}

	private suspend fun remapWorkOwnedState(
		sourceEntityId: Long,
		targetEntityId: Long,
	) {
		db.getWorkFavouritesDao().remapEntityId(sourceEntityId, targetEntityId)
		db.getWorkHistoryDao().remapEntityId(sourceEntityId, targetEntityId)
		db.getWorkStatsDao().remapEntityId(sourceEntityId, targetEntityId)
	}

	/**
	 * Shared helper for mergeEntities and mergeLocalWorkEntities:
	 * remaps bindings (with confidence-aware overwrite protection) and relations
	 * from source entities to the target entity.
	 */
	private suspend fun remapBindingsAndRelations(
		dao: EntityGraphDao,
		targetEntityId: Long,
		sourceEntityIds: Collection<Long>,
	) {
		sourceEntityIds.forEach { sourceEntityId ->
			// Bindings: move to target, preserving higher confidence
			dao.findBindingsByEntity(sourceEntityId).forEach { sourceBinding ->
				val existingTarget = dao.findBinding(sourceBinding.source, sourceBinding.externalId)
				if (
					existingTarget != null &&
					existingTarget.entityId != sourceEntityId &&
					existingTarget.confidence >= sourceBinding.confidence
				) {
					return@forEach
				}
				val isPrimary = existingTarget
					?.takeIf { it.entityId != sourceEntityId }
					?.isPrimary
					?: false
				dao.upsertBinding(
					sourceBinding.copy(entityId = targetEntityId, isPrimary = isPrimary),
				)
			}
			// Relations: remap from/to source entity to target
			dao.findRelationsForEntity(sourceEntityId).forEach { relation ->
				val remappedFrom = if (relation.fromEntityId == sourceEntityId) targetEntityId else relation.fromEntityId
				val remappedTo = if (relation.toEntityId == sourceEntityId) targetEntityId else relation.toEntityId
				if (remappedFrom != remappedTo) {
					dao.insertRelation(
						relation.copy(id = 0L, fromEntityId = remappedFrom, toEntityId = remappedTo),
					)
				}
			}
		}
	}

	private fun Entity.toRecord(): EntityRecord = EntityRecord(
		id = id,
		type = type.name,
		contentType = contentType?.name,
		primaryName = primaryName,
		nameHash = computeNameHash(primaryName),
		aliases = encodeStringList(aliases),
		createdAt = createdAt,
		lastAccessed = lastAccessed,
		accessCount = accessCount,
	)

	private data class CandidateMatch(
		val entity: Entity,
		val confidence: Float,
		val strength: EntityBindingStrength,
	)

	data class SplitLocalWorkProjectionResult(
		val localMangaId: Long,
		val oldEntityId: Long? = null,
		val newEntityId: Long? = null,
		val oldSource: String? = null,
		val hadLocalContent: Boolean = false,
		val failure: SplitLocalWorkProjectionFailure? = null,
	) {
		val isSuccess: Boolean
			get() = newEntityId != null

		companion object {
			fun failed(
				localMangaId: Long,
				reason: SplitLocalWorkProjectionFailure,
				oldEntityId: Long? = null,
				oldSource: String? = null,
				hadLocalContent: Boolean = false,
			): SplitLocalWorkProjectionResult {
				return SplitLocalWorkProjectionResult(
					localMangaId = localMangaId,
					oldEntityId = oldEntityId,
					oldSource = oldSource,
					hadLocalContent = hadLocalContent,
					failure = reason,
				)
			}
		}
	}

	data class MoveLocalWorkProjectionResult(
		val localMangaId: Long,
		val sourceEntityId: Long,
		val targetEntityId: Long,
		val failure: MoveLocalWorkProjectionFailure? = null,
	) {
		val isSuccess: Boolean
			get() = failure == null

		companion object {
			fun failed(
				localMangaId: Long,
				sourceEntityId: Long,
				targetEntityId: Long,
				reason: MoveLocalWorkProjectionFailure,
			): MoveLocalWorkProjectionResult = MoveLocalWorkProjectionResult(
				localMangaId = localMangaId,
				sourceEntityId = sourceEntityId,
				targetEntityId = targetEntityId,
				failure = reason,
			)
		}
	}

	enum class MoveLocalWorkProjectionFailure {
		INVALID_ARGUMENT,
		LOCAL_CONTENT_MISSING,
		NO_ACTIVE_LOCAL_BINDING,
		OWNER_CHANGED,
		SOURCE_ENTITY_MISSING,
		TARGET_ENTITY_MISSING,
		CONTENT_TYPE_CONFLICT,
		REBIND_FAILED,
	}

	private class MoveLocalWorkProjectionTransactionException(
		val reason: MoveLocalWorkProjectionFailure,
	) : IllegalStateException(reason.name)

	enum class SplitLocalWorkProjectionFailure {
		INVALID_LOCAL_ID,
		NO_ACTIVE_LOCAL_BINDING,
		BOUND_ENTITY_MISSING,
	}

	private data class RelationSourceKey(
		val source: String,
		val externalId: String,
	)

	private data class TrackingRepairDiagnostic(
		val branch: String,
		val entityId: Long?,
		val entityName: String?,
		val entityKeys: Set<String>,
		val localMangaId: Long?,
		val localTitle: String?,
		val localSource: String?,
		val localKeys: Set<String>,
		val serviceId: Int,
		val remoteId: Long,
		val trackingNames: List<String>,
		val trackingKeys: Set<String>,
	)

	private inner class EntityRepairDiagnosticCollector {
		private val branchCounts = linkedMapOf<String, Int>()
		private val samples = ArrayList<TrackingRepairDiagnostic>(MAX_REPAIR_DIAGNOSTIC_LOGS)

		fun record(
			branch: String,
			entity: EntityRecord?,
			localContent: Content?,
			serviceId: Int,
			remoteId: Long,
			trackingNames: List<String>,
		) {
			branchCounts[branch] = (branchCounts[branch] ?: 0) + 1
			if (samples.size >= MAX_REPAIR_DIAGNOSTIC_LOGS) {
				return
			}
			samples += TrackingRepairDiagnostic(
				branch = branch,
				entityId = entity?.id,
				entityName = entity?.primaryName,
				entityKeys = entity?.strictRepairNameKeys().orEmpty(),
				localMangaId = localContent?.id,
				localTitle = localContent?.title,
				localSource = localContent?.source?.name,
				localKeys = localContent?.localStrictTitleKeys().orEmpty(),
				serviceId = serviceId,
				remoteId = remoteId,
				trackingNames = trackingNames.filter { it.isNotBlank() }.distinct(),
				trackingKeys = trackingNames.strictTitleKeys(),
			)
		}

		fun logIfNeeded(totalIssues: Int) {
			if (totalIssues == 0 && samples.isEmpty()) {
				return
			}
			Log.w(
				TAG,
				"repair tracking suspect diagnostics: total=$totalIssues branches=${branchCounts.toLogString()} " +
					"samples=${samples.size}/${MAX_REPAIR_DIAGNOSTIC_LOGS}",
			)
			samples.forEachIndexed { index, sample ->
				Log.w(TAG, "repair tracking suspect sample #${index + 1}: ${sample.toLogString()}")
			}
		}
	}

	private fun TrackingRepairDiagnostic.toLogString(): String {
		return "branch=$branch, entityId=$entityId, entityName=${entityName.orEmpty()}, " +
			"entityKeys=${entityKeys.toLogString()}, localMangaId=$localMangaId, " +
			"localTitle=${localTitle.orEmpty()}, localSource=${localSource.orEmpty()}, " +
			"localKeys=${localKeys.toLogString()}, service=$serviceId, remoteId=$remoteId, " +
			"trackingNames=${trackingNames.toLogString()}, trackingKeys=${trackingKeys.toLogString()}"
	}

	private fun Map<String, Int>.toLogString(): String {
		return entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }
	}

	private fun Iterable<String>.toLogString(): String {
		return joinToString(prefix = "[", postfix = "]", limit = 8, truncated = "...") { it }
	}

	private companion object {
		private val AUTO_BIND_OVERWRITE_BLOCKING_STATES = setOf(
			EntityBindingState.MANUAL.name,
			EntityBindingState.CANDIDATE.name,
			EntityBindingState.REJECTED.name,
		)
	}

	/**
	 * Rebuilds the Work identity layer from projection anchors.
	 *
	 * This is an intentionally conservative disaster-recovery operation. It
	 * only groups duplicate local projections when they share a source-scoped
	 * URL/public URL key, never by fuzzy title similarity.
	 */
	suspend fun resetAllEntities(): EntityIdentityResetResult = withContext(Dispatchers.Default) {
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val workHistorySnapshot = db.getWorkHistoryDao().dump().toList()
			val workFavouriteSnapshot = db.getWorkFavouritesDao().dump().toList()
			val workStatsSnapshot = db.getWorkStatsDao().dumpEnabled().toList()
			val favouriteActiveRowsBefore = workFavouriteSnapshot.count { it.anchorMangaId != null && it.deletedAt == 0L }
			val favouriteActiveWorksBefore = workFavouriteSnapshot
				.asSequence()
				.filter { it.anchorMangaId != null && it.deletedAt == 0L }
				.map { it.entityId }
				.distinct()
				.count()
			val allMangaIds = collectResetAnchorMangaIds(
				workHistorySnapshot = workHistorySnapshot,
				workFavouriteSnapshot = workFavouriteSnapshot,
				workStatsSnapshot = workStatsSnapshot,
			)
			Log.d(TAG, "resetAll: anchors=${allMangaIds.size}, collecting projections")

			val mangaById = loadMangaById(allMangaIds)
			val groups = buildResetProjectionGroups(
				mangaIds = allMangaIds,
				mangaById = mangaById,
				workHistorySnapshot = workHistorySnapshot,
				workFavouriteSnapshot = workFavouriteSnapshot,
			)
			Log.d(TAG, "resetAll: groups=${groups.size}, clearing identity tables")

			db.getTracksDao().clear()
			db.getTrackingSiteDao().deleteAllLinks()
			db.getWorkHistoryDao().clear()
			db.getWorkFavouritesDao().deleteAll()
			db.getWorkStatsDao().clear()
			dao.deleteAllRelations()
			dao.deleteAllBindings()
			dao.deleteAllPrefs()
			dao.deleteAllEntities()

			val now = System.currentTimeMillis()
			val entityIdByMangaId = LinkedHashMap<Long, Long>()
			var rebuiltEntities = 0
			var duplicateProjectionGroups = 0
			groups.forEach { group ->
				val canonical = mangaById[group.canonicalMangaId]
				val title = canonical?.title?.ifBlank { null } ?: "Manga #${group.canonicalMangaId}"
				val entityId = insertResetEntity(
					dao = dao,
					title = title,
					canonicalMangaId = group.canonicalMangaId,
					contentType = group.contentType,
					syncId = resetProjectionSyncId(group, mangaById),
					now = now,
				) ?: return@forEach
				rebuiltEntities++
				if (group.mangaIds.size > 1) {
					duplicateProjectionGroups++
				}
				group.mangaIds.forEach { mangaId ->
					dao.upsertBinding(
						EntityBindingRecord(
							entityId = entityId,
							source = LOCAL_MANGA_BINDING_SOURCE,
							externalId = mangaId.toString(),
							confidence = 1f,
							isPrimary = mangaId == group.canonicalMangaId,
							sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
							state = EntityBindingState.CONFIRMED.name,
							createdBy = EntityBindingCreatedBy.MIGRATION.name,
							updatedAt = now,
						),
					)
					entityIdByMangaId[mangaId] = entityId
				}
				buildResetProjectionBindingKeys(group, mangaById).forEach { binding ->
					dao.upsertBinding(
						EntityBindingRecord(
							entityId = entityId,
							source = binding.source,
							externalId = binding.externalId,
							confidence = 1f,
							isPrimary = false,
							sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
							state = EntityBindingState.CONFIRMED.name,
							createdBy = EntityBindingCreatedBy.MIGRATION.name,
							updatedAt = now,
						),
					)
				}
				dao.upsertPrefsRecord(
					EntityPrefsRecord(
						entityId = entityId,
						preferredLocalMangaId = group.canonicalMangaId,
						titleOverride = null,
						coverUrlOverride = null,
						contentRatingOverride = null,
						readingStatus = null,
						metadataSourceKind = null,
						metadataBindingSource = null,
						metadataBindingExternalId = null,
						metadataSourceService = null,
						metadataSourceRemoteId = null,
						updatedAt = now,
					),
				)
			}

			val restoredHistory = restoreResetWorkHistory(workHistorySnapshot, entityIdByMangaId)
			val restoredFavourites = restoreResetWorkFavourites(workFavouriteSnapshot, entityIdByMangaId)
			val restoredStats = restoreResetWorkStats(workStatsSnapshot, entityIdByMangaId)
			val favouriteActiveRowsAfter = db.getWorkFavouritesDao().countActive()
			val favouriteActiveWorksAfter = db.getWorkFavouritesDao().countActiveWorks()
			settings.isWorkMigrationSyncWriteBlocked = true
			settings.requiresWorkMigrationNormalization = true

			EntityIdentityResetResult(
				anchorMangaCount = allMangaIds.size,
				rebuiltEntityCount = rebuiltEntities,
				duplicateProjectionGroupCount = duplicateProjectionGroups,
				restoredHistoryCount = restoredHistory,
				restoredFavouriteCount = restoredFavourites,
				favouriteActiveRowsBefore = favouriteActiveRowsBefore,
				favouriteActiveRowsAfter = favouriteActiveRowsAfter,
				favouriteActiveWorksBefore = favouriteActiveWorksBefore,
				favouriteActiveWorksAfter = favouriteActiveWorksAfter,
				restoredStatsCount = restoredStats,
				skippedAnchorCount = allMangaIds.count { it !in entityIdByMangaId },
			).also { result ->
				Log.d(TAG, "resetAll: complete result=$result")
			}
		}
	}

	private fun collectResetAnchorMangaIds(
		workHistorySnapshot: List<WorkHistoryEntity>,
		workFavouriteSnapshot: List<WorkFavouriteEntity>,
		workStatsSnapshot: List<WorkStatsEntity>,
	): List<Long> {
		return buildSet {
			workHistorySnapshot.forEach { add(it.anchorMangaId) }
			workFavouriteSnapshot.forEach { it.anchorMangaId?.let(::add) }
			workStatsSnapshot.forEach { add(it.anchorMangaId) }
		}.toList()
	}

	private suspend fun loadMangaById(mangaIds: Collection<Long>): Map<Long, MangaEntity> {
		if (mangaIds.isEmpty()) return emptyMap()
		return mangaIds.chunked(MAX_BINDING_QUERY_PARAMS)
			.flatMap { db.getMangaDao().findEntitiesByIds(it) }
			.associateBy { it.id }
	}

	private suspend fun insertResetEntity(
		dao: EntityGraphDao,
		title: String,
		canonicalMangaId: Long,
		contentType: String?,
		syncId: String?,
		now: Long,
	): Long? {
		val baseHash = "reset|$canonicalMangaId|$title".longHashCode()
		val record = EntityRecord(
			type = EntityType.WORK.name,
			contentType = contentType,
			syncId = syncId ?: java.util.UUID.randomUUID().toString(),
			primaryName = title,
			nameHash = baseHash,
			aliases = null,
			createdAt = now,
			lastAccessed = now,
			accessCount = 0,
		)
		val insertedId = dao.insertEntityIgnore(record)
		if (insertedId != -1L) {
			return insertedId
		}
		return dao.insertEntity(record.copy(nameHash = "reset-collision|$canonicalMangaId|$now".longHashCode()))
	}

	private suspend fun restoreResetWorkHistory(
		snapshot: List<WorkHistoryEntity>,
		entityIdByMangaId: Map<Long, Long>,
	): Int {
		val mergedByEntityId = LinkedHashMap<Long, WorkHistoryEntity>()
		snapshot.forEach { entry ->
			val entityId = entityIdByMangaId[entry.anchorMangaId] ?: return@forEach
			val moved = entry.copy(entityId = entityId)
			val existing = mergedByEntityId[entityId]
			mergedByEntityId[entityId] = if (existing == null) {
				moved
			} else {
				mergeRestoredWorkHistory(existing, moved)
			}
		}
		mergedByEntityId.values.forEach { db.getWorkHistoryDao().upsert(it) }
		return mergedByEntityId.size
	}

	private suspend fun restoreResetWorkFavourites(
		snapshot: List<WorkFavouriteEntity>,
		entityIdByMangaId: Map<Long, Long>,
	): Int {
		val mergedByKey = LinkedHashMap<Pair<Long, Long>, WorkFavouriteEntity>()
		snapshot.forEach { entry ->
			val anchorMangaId = entry.anchorMangaId ?: return@forEach
			val entityId = entityIdByMangaId[anchorMangaId] ?: return@forEach
			val moved = entry.copy(entityId = entityId)
			val key = entityId to entry.categoryId
			val existing = mergedByKey[key]
			mergedByKey[key] = if (existing == null) {
				moved
			} else {
				mergeRestoredWorkFavourites(existing, moved)
			}
		}
		mergedByKey.values.forEach { db.getWorkFavouritesDao().upsert(it) }
		return mergedByKey.size
	}

	private suspend fun restoreResetWorkStats(
		snapshot: List<WorkStatsEntity>,
		entityIdByMangaId: Map<Long, Long>,
	): Int {
		val mergedByKey = LinkedHashMap<Pair<Long, Long>, WorkStatsEntity>()
		snapshot.forEach { entry ->
			val entityId = entityIdByMangaId[entry.anchorMangaId] ?: return@forEach
			val moved = entry.copy(entityId = entityId)
			val key = entityId to entry.startedAt
			val existing = mergedByKey[key]
			mergedByKey[key] = if (existing == null) {
				moved
			} else {
				mergeRestoredWorkStats(existing, moved)
			}
		}
		mergedByKey.values.forEach { db.getWorkStatsDao().upsert(it) }
		return mergedByKey.size
	}

}

data class EntityIdentityResetResult(
	val anchorMangaCount: Int,
	val rebuiltEntityCount: Int,
	val duplicateProjectionGroupCount: Int,
	val restoredHistoryCount: Int,
	val restoredFavouriteCount: Int,
	val favouriteActiveRowsBefore: Int,
	val favouriteActiveRowsAfter: Int,
	val favouriteActiveWorksBefore: Int,
	val favouriteActiveWorksAfter: Int,
	val restoredStatsCount: Int,
	val skippedAnchorCount: Int,
)
