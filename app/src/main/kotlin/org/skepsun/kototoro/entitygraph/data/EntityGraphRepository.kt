package org.skepsun.kototoro.entitygraph.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.domain.Entity
import org.skepsun.kototoro.entitygraph.domain.EntityBinding
import org.skepsun.kototoro.entitygraph.domain.EntityBindingMatcher
import org.skepsun.kototoro.entitygraph.domain.EntityBindingStrength
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.Relation
import org.skepsun.kototoro.entitygraph.domain.RelationType
import org.skepsun.kototoro.entitygraph.domain.TrackingCharacterDto
import org.skepsun.kototoro.entitygraph.domain.TrackingPersonDto
import org.skepsun.kototoro.entitygraph.domain.TrackingStaffDto
import org.skepsun.kototoro.entitygraph.domain.TrackingWorkDto
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTITY_SCAN_LIMIT = 120
private const val MAX_CHILD_CHARACTERS = 10
private const val MAX_CHILD_STAFF = 10
private const val MAX_VOICE_ACTORS = 10
private const val RELATION_WEIGHT_DEFAULT = 1f
private const val STALE_ENTITY_DAYS = 30L
private const val STALE_ENTITY_ACCESS_THRESHOLD = 2

@Singleton
class EntityGraphRepository @Inject constructor(
	private val db: MangaDatabase,
	private val bindingMatcher: EntityBindingMatcher,
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
			coverUrl = workDto.coverUrl,
			description = workDto.description,
			source = source,
			externalId = workDto.externalId,
			externalLinks = workDto.externalLinks,
			now = now,
		)

			workDto.characters.take(MAX_CHILD_CHARACTERS).forEach { character ->
				val characterEntity = resolveOrCreateCharacter(
					source = source,
					workEntity = work,
					character = character,
					now = now,
				)
				insertRelationIfAbsent(
					fromEntityId = work.id,
					toEntityId = characterEntity.id,
					type = RelationType.HAS_CHARACTER,
					now = now,
				)
				character.voiceActors.take(MAX_VOICE_ACTORS).forEach { actor ->
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
					)
				}
			}

			workDto.staff.take(MAX_CHILD_STAFF).forEach { staff ->
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
		val binding = dao.findBinding(source, externalId) ?: return@withContext null
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
		db.getEntityGraphDao().findBindingsByEntity(entityId).map { it.toModel() }
	}

	suspend fun getRelations(entityId: Long): List<Relation> = withContext(Dispatchers.Default) {
		db.getEntityGraphDao().findRelationsForEntity(entityId).map { it.toModel() }
	}

	suspend fun tryBindEntities(
		entityA: Entity,
		entityB: Entity,
	): Float = withContext(Dispatchers.Default) {
		bindingMatcher.tryBindEntities(entityA, entityB)
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
			db.getEntityGraphDao().deleteBindingsByEntityIds(entityIds)
			db.getEntityGraphDao().deleteRelationsByEntityIds(entityIds)
			db.getEntityGraphDao().deleteEntitiesByIds(entityIds)
			entityIds.size
		}
	}

	private suspend fun resolveOrCreateCharacter(
		source: String,
		workEntity: Entity,
		character: TrackingCharacterDto,
		now: Long,
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
		)
		return entity
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
		coverUrl: String? = null,
		description: String? = null,
		source: String?,
		externalId: String?,
		externalLinks: Map<String, String> = emptyMap(),
		now: Long,
	): Entity {
		val dao = db.getEntityGraphDao()
		
		val searchLinks = buildMap {
			if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
				put(source, externalId)
			}
			putAll(externalLinks)
		}

		var existingEntityId: Long? = null
		for ((s, eId) in searchLinks) {
			val binding = dao.findBinding(s, eId)
			if (binding != null) {
				existingEntityId = binding.entityId
				break
			}
		}

		if (existingEntityId != null) {
			dao.findEntity(existingEntityId)?.let { record ->
				val merged = mergeEntityRecord(
					record = record,
					primaryName = primaryName,
					aliases = aliases,
					coverUrl = coverUrl,
					description = description,
					now = now,
				)
				dao.updateEntity(merged)
				dao.touchEntity(merged.id, now)
				
				searchLinks.forEach { (s, eId) ->
					val isPrimary = (s == source && eId == externalId)
					dao.upsertBinding(
						EntityBindingRecord(
							entityId = merged.id,
							source = s,
							externalId = eId,
							confidence = 1.0f,
							isPrimary = isPrimary,
						)
					)
				}
				
				return dao.findEntity(merged.id)?.toModel() ?: merged.toModel()
			}
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
					val merged = mergeEntityRecord(
						record = candidate.entity.toRecord(),
						primaryName = primaryName,
						aliases = aliases,
						coverUrl = coverUrl,
						description = description,
						now = now,
					)
					dao.updateEntity(merged)
					dao.touchEntity(merged.id, now)
					if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
						val bindings = dao.findBindingsByEntity(candidate.entity.id)
						dao.upsertBinding(
							EntityBindingRecord(
								entityId = candidate.entity.id,
								source = source,
								externalId = externalId,
								confidence = candidate.confidence,
								isPrimary = bindings.isEmpty(),
							),
						)
					}
					// Auto-bind also needs to propagate newly discovered external links that didn't exist before!
					searchLinks.forEach { (s, eId) ->
						if (s != source || eId != externalId) {
							dao.upsertBinding(
								EntityBindingRecord(
									entityId = candidate.entity.id,
									source = s,
									externalId = eId,
									confidence = 1.0f,
									isPrimary = false,
								)
							)
						}
					}
					return dao.findEntity(candidate.entity.id)?.toModel() ?: candidate.entity
				}

				EntityBindingStrength.WEAK_BIND -> {
					val created = createEntity(
						type = type,
						primaryName = primaryName,
						aliases = aliases,
						coverUrl = coverUrl,
						description = description,
						source = source,
						externalId = externalId,
						externalLinks = searchLinks,
						confidence = 1f,
						now = now,
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
			coverUrl = coverUrl,
			description = description,
			source = source,
			externalId = externalId,
			externalLinks = searchLinks,
			confidence = 1f,
			now = now,
		)
	}

	private suspend fun createEntity(
		type: EntityType,
		primaryName: String,
		aliases: List<String>,
		coverUrl: String? = null,
		description: String? = null,
		source: String?,
		externalId: String?,
		externalLinks: Map<String, String> = emptyMap(),
		confidence: Float,
		now: Long,
	): Entity {
		val dao = db.getEntityGraphDao()
		val id = dao.insertEntity(
			EntityRecord(
				type = type.name,
				primaryName = primaryName.trim(),
				aliases = encodeStringList(mergeAliases(primaryName, aliases).drop(1)),
				coverUrl = coverUrl,
				description = description,
				createdAt = now,
				lastAccessed = now,
				accessCount = 1,
			),
		)
		externalLinks.forEach { (s, eId) ->
			val isPrimary = (s == source && eId == externalId)
			dao.upsertBinding(
				EntityBindingRecord(
					entityId = id,
					source = s,
					externalId = eId,
					confidence = confidence,
					isPrimary = isPrimary,
				),
			)
		}
		return requireNotNull(dao.findEntity(id)).toModel()
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
			),
		)
	}

	private fun mergeEntityRecord(
		record: EntityRecord,
		primaryName: String,
		aliases: List<String>,
		coverUrl: String? = null,
		description: String? = null,
		now: Long,
	): EntityRecord {
		val mergedNames = mergeAliases(
			primaryName = record.primaryName,
			aliases = decodeStringList(record.aliases) + listOf(primaryName) + aliases,
		)
		return record.copy(
			primaryName = mergedNames.first(),
			aliases = encodeStringList(mergedNames.drop(1)),
			coverUrl = coverUrl ?: record.coverUrl,
			description = description ?: record.description,
			lastAccessed = now,
		)
	}

	private fun Entity.toRecord(): EntityRecord = EntityRecord(
		id = id,
		type = type.name,
		primaryName = primaryName,
		aliases = encodeStringList(aliases),
		coverUrl = coverUrl,
		description = description,
		createdAt = createdAt,
		lastAccessed = lastAccessed,
		accessCount = accessCount,
	)

	private data class CandidateMatch(
		val entity: Entity,
		val confidence: Float,
		val strength: EntityBindingStrength,
	)
}
