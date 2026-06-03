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
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import org.skepsun.kototoro.tracking.malsync.data.MALSyncMappingRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTITY_SCAN_LIMIT = 120
private const val RELATION_WEIGHT_DEFAULT = 1f
private const val STALE_ENTITY_DAYS = 30L
private const val STALE_ENTITY_ACCESS_THRESHOLD = 2
private const val MAX_BINDING_QUERY_PARAMS = 500

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

			workDto.characters.forEach { character ->
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
		db.getEntityGraphDao().findBindingsByEntity(entityId).map { it.toModel() }
	}

	suspend fun findEntityIdsByLocalMangaIds(localMangaIds: Collection<Long>): Map<Long, Long> = withContext(Dispatchers.Default) {
		if (localMangaIds.isEmpty()) {
			return@withContext emptyMap()
		}
		val ids = localMangaIds.distinct()
		buildMap {
			ids.map(Long::toString).chunked(MAX_BINDING_QUERY_PARAMS).forEach { chunk ->
				db.getEntityGraphDao().findBindingsBySources(
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
		source: String?,
		externalId: String?,
		contentType: ContentType? = null,
		now: Long,
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
			dao.findBinding(candidateSource, externalId)?.let { return it }
		}
		return null
	}

	private suspend fun EntityGraphDao.upsertBindingForSource(
		entityId: Long,
		source: String,
		externalId: String,
		confidence: Float,
	) {
		val bindings = findBindingsByEntity(entityId)
		upsertBinding(
			EntityBindingRecord(
				entityId = entityId,
				source = source,
				externalId = externalId,
				confidence = confidence,
				isPrimary = bindings.isEmpty(),
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
	): Entity {
		val dao = db.getEntityGraphDao()
		val id = dao.insertEntity(
			EntityRecord(
				type = type.name,
				primaryName = primaryName.trim(),
				aliases = encodeStringList(mergeAliases(primaryName, aliases).drop(1)),
				createdAt = now,
				lastAccessed = now,
				accessCount = 1,
			),
		)
		if (!source.isNullOrBlank() && !externalId.isNullOrBlank()) {
			dao.upsertBinding(
				EntityBindingRecord(
					entityId = id,
					source = source,
					externalId = externalId,
					confidence = confidence,
					isPrimary = true,
				),
			)
		}
		return requireNotNull(dao.findEntity(id)).toModel()
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
		now: Long,
	): EntityRecord {
		val mergedNames = mergeAliases(
			primaryName = record.primaryName,
			aliases = decodeStringList(record.aliases) + listOf(primaryName) + aliases,
		)
		return record.copy(
			primaryName = mergedNames.first(),
			aliases = encodeStringList(mergedNames.drop(1)),
			lastAccessed = now,
		)
	}

	private fun Entity.toRecord(): EntityRecord = EntityRecord(
		id = id,
		type = type.name,
		primaryName = primaryName,
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
}
