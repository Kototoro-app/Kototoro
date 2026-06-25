package org.skepsun.kototoro.entitygraph.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
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
import org.skepsun.kototoro.entitygraph.domain.toTrackingServiceOrNull
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import org.skepsun.kototoro.tracking.malsync.data.MALSyncMappingRepository
import org.skepsun.kototoro.parsers.util.longHashCode
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

@Singleton
class EntityGraphRepository @Inject constructor(
	private val db: MangaDatabase,
	private val bindingMatcher: EntityBindingMatcher,
	private val animeOfflineRepository: AnimeOfflineRepository,
	private val malsyncMappingRepository: MALSyncMappingRepository,
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
		db.withTransaction {
			val dao = db.getEntityGraphDao()
			val existing = findEntityByLocalMangaId(localMangaId) ?: return@withTransaction false
			deleteLocalReadingBinding(dao, localMangaId.toString())
			dao.touchEntity(existing.entityId, System.currentTimeMillis())
			true
		}
	}

	suspend fun splitLocalWorkProjection(content: Content): Long? = withContext(Dispatchers.Default) {
		if (content.id == 0L) {
			return@withContext null
		}
		splitLocalWorkProjectionInTransaction(content)
	}

	suspend fun splitLocalWorkProjection(localMangaId: Long): Long? = withContext(Dispatchers.Default) {
		splitLocalWorkProjectionWithDiagnostics(localMangaId).newEntityId
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
			deleteLocalReadingBinding(dao, content.id.toString())
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
			deleteLocalReadingBinding(dao, localMangaId.toString())
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
							val merged = mergeEntityRecord(
								record = record,
								primaryName = content.title,
								aliases = content.altTitles.toList(),
								now = now,
							)
							if (merged != record) {
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
			val ensuredIds = ensureLocalWorkEntities(distinctContents)
			val entityIds = distinctContents.mapNotNullTo(LinkedHashSet()) { ensuredIds[it.id] }
			if (entityIds.isEmpty()) {
				return@withTransaction null
			}
			val records = dao.findEntitiesByIds(entityIds.toList()).associateBy { it.id }
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
			}
			dao.deleteEntitiesByIds(entityIds.filterNot { it == targetEntityId })
			dao.touchEntity(targetEntityId, now)
			targetEntityId
		}
	}

	suspend fun mergeEntities(
		targetEntityId: Long,
		sourceEntityIds: Collection<Long>,
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
				val conflicting = dao.findEntityByTypeAndNameHash(mergedRecord.type, mergedRecord.nameHash)
				if (conflicting == null || conflicting.id == targetEntityId) {
					break
				}
				if (conflicting.id in distinctSourceIds || conflicting.id in absorbedIds) {
					break
				}
				Log.w(TAG, "mergeEntities: absorbing conflicting entity ${conflicting.id} (type=${conflicting.type}, nameHash=${conflicting.nameHash})")
				// Remap bindings/relations from conflicting entity to target, then delete it
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
			// Remap bindings and relations from source entities to target
			remapBindingsAndRelations(
				dao = dao,
				targetEntityId = targetEntityId,
				sourceEntityIds = distinctSourceIds,
			)
			// FK constraints (CASCADE) handle deletions automatically on source entities
			dao.deleteEntitiesByIds(distinctSourceIds)
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
			}
			dao.updateEntity(record)
			dao.touchEntity(entityId, now)
			true
		}
	}

	suspend fun ensureLocalWorkEntity(
		content: Content,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.USER,
	): Entity = withContext(Dispatchers.Default) {
		db.withTransaction {
			val now = System.currentTimeMillis()
			val existing = findEntityByLocalMangaId(content.id)
			if (existing != null) {
				val dao = db.getEntityGraphDao()
				val record = dao.findEntity(existing.entityId)
				if (record != null) {
					dao.updateEntity(
						mergeEntityRecord(
							record = record,
							primaryName = content.title,
							aliases = content.altTitles.toList(),
							now = now,
						),
					)
				}
				dao.upsertBindingForSource(
					entityId = existing.entityId,
					source = "local_manga",
					externalId = content.id.toString(),
					confidence = existing.confidence,
					createdBy = createdBy,
				)
				dao.touchEntity(existing.entityId, now)
				return@withTransaction requireNotNull(dao.findEntity(existing.entityId)).toModel()
			}
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
			// Debug log for entity 1213
			if (entityId == 1213L) {
				Log.w(TAG, "repair suspectMismerged entity 1213: localBindings=${localBindings.map { it.externalId }} hasTracking=$hasTrackingBinding entityExists=${entitiesById[entityId] != null}")
			}
			if (localBindings.isEmpty()) {
				return@forEach
			}
			val entity = entitiesById[entityId] ?: return@forEach
			val strictEntityNameKeys = entity.strictRepairNameKeys()
			if (strictEntityNameKeys.isEmpty()) {
				return@forEach
			}
			Log.i(TAG, "repair suspectMismerged: entityId=$entityId name=${entity.primaryName} aliases=${decodeStringList(entity.aliases)} strictKeys=$strictEntityNameKeys localBindings=${localBindings.map { it.externalId }} hasTracking=$hasTrackingBinding")
			localBindings.forEach { binding ->
				val localMangaId = binding.externalId.toLongOrNull()
				val content = localMangaId?.let { db.getMangaDao().find(it)?.toContent() }
				if (content != null && content.localStrictTitleKeys().none { it in strictEntityNameKeys }) {
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
		dao.insertEntityPrefsIgnore(newEntityPrefs(entityId, now))
		dao.updateEntityMetadataSourceSelection(
			entityId = entityId,
			metadataSourceKind = if (selection == null) "base" else "tracking",
			metadataBindingSource = selection?.serviceId?.toString(),
			metadataBindingExternalId = selection?.remoteId?.toString(),
			metadataSourceService = selection?.serviceId,
			metadataSourceRemoteId = selection?.remoteId,
			updatedAt = now,
		)
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

		val animeOfflineCandidate = resolveAnimeOfflineCandidate(source, externalId, now)
		if (animeOfflineCandidate != null) {
			return mergeIntoResolvedEntity(
				entity = animeOfflineCandidate,
				primaryName = primaryName,
				aliases = aliases,
				source = source,
				externalId = externalId,
				confidence = 0.99f,
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
				now = now,
				createdBy = createdBy,
			)
		}
		val candidate = pickCandidate(
			type = type,
			primaryName = primaryName,
			aliases = aliases,
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
			confidence = 1f,
			now = now,
			createdBy = createdBy,
		)
	}

	private suspend fun resolveAnimeOfflineCandidate(
		source: String?,
		externalId: String?,
		now: Long,
	): Entity? {
		val service = source.toScrobblerServiceOrNull() ?: return null
		val remoteId = externalId?.toLongOrNull() ?: return null
		val mappings = animeOfflineRepository.resolveMappings(service, remoteId)
		return resolveMappedCandidate(
			now = now,
			mappings = mappings.map { it.service to it.remoteId },
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
		)
	}

	private suspend fun resolveMappedCandidate(
		now: Long,
		mappings: List<Pair<ScrobblerService, Long>>,
	): Entity? {
		if (mappings.isEmpty()) {
			return null
		}
		val dao = db.getEntityGraphDao()
		for ((service, remoteId) in mappings) {
			val binding = findBindingBySourceKey(service.id.toString(), remoteId.toString()) ?: continue
			dao.touchEntity(binding.entityId, now)
			return dao.findEntity(binding.entityId)?.toModel()
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
		now: Long,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
	): Entity {
		val dao = db.getEntityGraphDao()
		val merged = mergeEntityRecord(
			record = entity.toRecord(),
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
				state = if (createdBy == EntityBindingCreatedBy.USER) {
					EntityBindingState.MANUAL.name
				} else {
					EntityBindingState.CONFIRMED.name
				},
				createdBy = createdBy.name,
				updatedAt = System.currentTimeMillis(),
			),
		)
	}

	private suspend fun EntityGraphDao.attachLocalWorkBindingForMerge(
		entityId: Long,
		externalId: String,
		now: Long,
	) {
		deleteBindingBySource("0", externalId)
		upsertBinding(
			EntityBindingRecord(
				entityId = entityId,
				source = "local_manga",
				externalId = externalId,
				confidence = 1f,
				isPrimary = findActiveBindingsByEntity(entityId).isEmpty(),
				state = EntityBindingState.MANUAL.name,
				createdBy = EntityBindingCreatedBy.USER.name,
				updatedAt = now,
			),
		)
	}

	private suspend fun createEntity(
		type: EntityType,
		primaryName: String,
		aliases: List<String>,
		source: String?,
		externalId: String?,
		confidence: Float,
		now: Long,
		createdBy: EntityBindingCreatedBy = EntityBindingCreatedBy.INGEST,
	): Entity {
		val dao = db.getEntityGraphDao()
		val trimmedName = resolveEntityPrimaryName(primaryName, aliases, source, externalId)
		val nameHash = computeNameHash(trimmedName)
		val record = EntityRecord(
			type = type.name,
			primaryName = trimmedName,
			nameHash = nameHash,
			aliases = encodeStringList(mergeAliases(trimmedName, aliases + primaryName).drop(1)),
			createdAt = now,
			lastAccessed = now,
			accessCount = 1,
		)
		// INSERT OR IGNORE: if a concurrent request already created this entity (same type + name_hash),
		// we fall back to merging into the existing one instead of creating a duplicate.
		val id = dao.insertEntityIgnore(record)
		if (id == -1L) {
			// Conflict — another call won the race. Resolve the existing entity.
			val existing = dao.findEntityByTypeAndNameHash(type.name, nameHash)
			if (existing != null) {
				return mergeIntoResolvedEntity(
					entity = existing.toModel(),
					primaryName = primaryName,
					aliases = aliases,
					source = source,
					externalId = externalId,
					confidence = confidence,
					now = now,
					createdBy = createdBy,
				)
			}
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
			if (dao.findEntityByTypeAndNameHash(EntityType.WORK.name, nameHash) == null) {
				id = dao.insertEntityIgnore(
					EntityRecord(
						type = EntityType.WORK.name,
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
			if (dao.findEntityByTypeAndNameHash(EntityType.WORK.name, nameHash) == null) {
				id = dao.insertEntityIgnore(
					EntityRecord(
						type = EntityType.WORK.name,
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
		now: Long,
	): CandidateMatch? {
		val probe = Entity(
			id = 0L,
			type = type,
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

	private suspend fun updateEntityResolvingNameHashConflict(
		dao: EntityGraphDao,
		original: EntityRecord,
		merged: EntityRecord,
		primaryName: String,
		aliases: List<String>,
		now: Long,
	): EntityRecord {
		val conflict = dao.findEntityByTypeAndNameHash(merged.type, merged.nameHash)
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
	 * Complete entity reset: deletes all entities, bindings, relations, preferences,
	 * work_history, work_favourites, tracks, and tracking_site_links.
	 * Then re-creates one clean entity per manga found in current Work state.
	 */
	suspend fun resetAllEntities() = withContext(Dispatchers.Default) {
		db.withTransaction {
			val dao = db.getEntityGraphDao()

			Log.d(TAG, "resetAll: collecting Work state...")
			val workHistorySnapshot = db.getWorkHistoryDao().dump().toList()
			val workFavouriteSnapshot = db.getWorkFavouritesDao().dump().toList()
			val historyMangaIds = workHistorySnapshot.mapTo(LinkedHashSet()) { it.anchorMangaId }
			val favouriteMangaIds = workFavouriteSnapshot.mapNotNullTo(LinkedHashSet()) { it.anchorMangaId }
			val allMangaIds = (historyMangaIds + favouriteMangaIds).distinct()
			Log.d(TAG, "resetAll: ${allMangaIds.size} manga IDs, clearing tables...")

			db.getTracksDao().clear()
			db.getWorkHistoryDao().clear()
			db.getWorkFavouritesDao().deleteAll()
			db.getTrackingSiteDao().deleteAllLinks()

			dao.deleteAllRelations()
			dao.deleteAllBindings()
			dao.deleteAllPrefs()
			dao.deleteAllEntities()
			Log.d(TAG, "resetAll: core tables cleared, rebuilding...")

			val now = System.currentTimeMillis()
			val allMangaIdsList = allMangaIds.toList()
			val mangaById = mutableMapOf<Long, org.skepsun.kototoro.core.db.entity.MangaEntity>()
			allMangaIdsList.chunked(500).forEach { chunk ->
				db.getMangaDao().findEntitiesByIds(chunk).forEach { manga ->
					mangaById[manga.id] = manga
				}
			}
			for (mangaId in allMangaIdsList) {
				val manga = mangaById[mangaId]
				val title = manga?.title?.ifBlank { null } ?: "Manga #$mangaId"
				val nameHash = (title + "|" + mangaId.toString()).longHashCode()
				val entityId = dao.insertEntityIgnore(
					EntityRecord(
						type = EntityType.WORK.name,
						primaryName = title,
						nameHash = nameHash,
						aliases = null,
						createdAt = now,
						lastAccessed = now,
						accessCount = 0,
					)
				)
				if (entityId > 0L) {
					dao.upsertBinding(
						EntityBindingRecord(
							entityId = entityId,
							source = "local_manga",
							externalId = mangaId.toString(),
							confidence = 1f,
							isPrimary = true,
							sourceKind = "LOCAL_MANGA",
							state = EntityBindingState.CONFIRMED.name,
							createdBy = EntityBindingCreatedBy.USER.name,
							updatedAt = now,
						)
					)
				}
			}
			val bindings = db.getEntityGraphDao().findActiveBindingsBySources(
				sources = listOf("local_manga", "0"),
				externalIds = allMangaIdsList.map { it.toString() },
			)
			val entityIdByMangaId = HashMap<Long, Long>()
			for (binding in bindings) {
				val mangaId = binding.externalId.toLongOrNull() ?: continue
				entityIdByMangaId[mangaId] = binding.entityId
			}
			var restoredHistory = 0
			for (entry in workHistorySnapshot) {
				val entityId = entityIdByMangaId[entry.anchorMangaId] ?: continue
				db.getWorkHistoryDao().upsert(entry.copy(entityId = entityId))
				restoredHistory++
			}
			var restoredFavourites = 0
			for (entry in workFavouriteSnapshot) {
				val anchorMangaId = entry.anchorMangaId ?: continue
				val entityId = entityIdByMangaId[anchorMangaId] ?: continue
				db.getWorkFavouritesDao().upsert(entry.copy(entityId = entityId))
				restoredFavourites++
			}
			Log.d(
				TAG,
				"resetAll: complete, rebuilt ${allMangaIdsList.size} entities, " +
					"$restoredHistory history entries, $restoredFavourites favourite entries",
			)
		}
	}
}
