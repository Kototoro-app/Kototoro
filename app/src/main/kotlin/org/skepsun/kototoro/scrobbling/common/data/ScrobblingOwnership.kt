package org.skepsun.kototoro.scrobbling.common.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.work.domain.WorkResolver

private const val SCROBBLING_SCORE_ENTITY_OWNER = 16
private const val SCROBBLING_SCORE_LOCAL_PROJECTION = 8
private const val SCROBBLING_SCORE_RATING = 4
private const val SCROBBLING_SCORE_COMMENT = 2
private const val SCROBBLING_SCORE_PROGRESS = 1
private const val SCROBBLING_SCORE_REMOTE_PREVIEW = 1
private const val SCROBBLING_SCORE_MEDIA_TYPE = 1

suspend fun MangaDatabase.attachEntityOwnership(
	entity: ScrobblingEntity,
	workResolver: WorkResolver,
): ScrobblingEntity {
	return entity.withScrobblingOwnership(fallbackEntityId = workResolver.resolveScrobblingEntityId(entity.mangaId))
}

private fun ScrobblingEntity.withResolvedOwnership(resolvedEntityId: Long?): ScrobblingEntity {
	val resolvedOwnerId = resolveScrobblingOwnerId(resolvedEntityId, mangaId)
	return if (entityId == resolvedEntityId && ownerId == resolvedOwnerId) {
		this
	} else {
		copy(
			ownerId = resolvedOwnerId,
			entityId = resolvedEntityId,
		)
	}
}

private fun ScrobblingEntity.withScrobblingOwnership(fallbackEntityId: Long?): ScrobblingEntity {
	val resolvedEntityId = when {
		entityId != null -> entityId
		else -> fallbackEntityId
	}
	return withResolvedOwnership(resolvedEntityId)
}

suspend fun MangaDatabase.findScrobblingByWorkOrManga(
	scrobbler: Int,
	mangaId: Long,
	workResolver: WorkResolver,
): ScrobblingEntity? {
	val entityId = workResolver.resolveScrobblingEntityId(mangaId)
	return entityId?.let { getScrobblingDao().findByEntity(scrobbler, it) }
		?: getScrobblingDao().findByLocalManga(scrobbler, mangaId)
}

suspend fun MangaDatabase.findPreferredScrobblingByWorkTargetAndMediaType(
	scrobbler: Int,
	mangaId: Long,
	targetId: Long,
	mediaType: String,
	workResolver: WorkResolver,
): ScrobblingEntity? {
	val entityId = workResolver.resolveScrobblingEntityId(mangaId)
	return entityId?.let {
		getScrobblingDao().findPreferredByEntityTargetAndMediaType(
			scrobbler = scrobbler,
			entityId = it,
			targetId = targetId,
			mediaType = mediaType,
		)
	} ?: getScrobblingDao().findByTargetIdAndMediaType(
		scrobbler = scrobbler,
		targetId = targetId,
		mediaType = mediaType,
	)
}

suspend fun MangaDatabase.deleteScrobblingByWorkOrManga(
	scrobbler: Int,
	mangaId: Long,
	workResolver: WorkResolver,
) {
	val entityId = workResolver.resolveScrobblingEntityId(mangaId)
	if (entityId != null) {
		getScrobblingDao().deleteByEntity(scrobbler, entityId)
	} else {
		getScrobblingDao().deleteByLocalManga(scrobbler, mangaId)
	}
}

suspend fun MangaDatabase.rebindScrobblingToManga(
	scrobbler: Int,
	sourceMangaId: Long,
	targetMangaId: Long,
	workResolver: WorkResolver,
	fallback: () -> ScrobblingEntity,
): ScrobblingEntity {
	val dao = getScrobblingDao()
	val current = findScrobblingByWorkOrManga(scrobbler, sourceMangaId, workResolver)
	val targetEntityId = workResolver.resolveScrobblingEntityId(targetMangaId)
	return rebindScrobblingToManga(
		dao = dao,
		current = current,
		scrobbler = scrobbler,
		targetMangaId = targetMangaId,
		targetEntityId = targetEntityId,
		fallback = fallback,
	)
}

private suspend fun rebindScrobblingToManga(
	dao: ScrobblingDao,
	current: ScrobblingEntity?,
	scrobbler: Int,
	targetMangaId: Long,
	targetEntityId: Long?,
	fallback: () -> ScrobblingEntity,
): ScrobblingEntity {
	val rebound = (current ?: fallback()).copy(
		ownerId = resolveScrobblingOwnerId(targetEntityId, targetMangaId),
		entityId = targetEntityId,
		mangaId = targetMangaId,
	)
	if (current != null && (current.mangaId != targetMangaId || current.mediaType != rebound.mediaType || current.id != rebound.id)) {
		dao.delete(current)
	}
	dao.upsert(rebound)
	return rebound
}

suspend fun MangaDatabase.upsertScrobblingForManga(
	entity: ScrobblingEntity,
	workResolver: WorkResolver,
	mangaId: Long = entity.mangaId,
): ScrobblingEntity {
	val entityId = workResolver.resolveScrobblingEntityId(mangaId)
	return upsertScrobblingForManga(entity, mangaId, entityId)
}

private suspend fun MangaDatabase.upsertScrobblingForManga(
	entity: ScrobblingEntity,
	mangaId: Long,
	entityId: Long?,
): ScrobblingEntity {
	val normalized = entity.copy(
		ownerId = resolveScrobblingOwnerId(entityId, mangaId),
		entityId = entityId,
		mangaId = mangaId,
	)
	getScrobblingDao().upsert(normalized)
	return normalized
}

suspend fun MangaDatabase.upsertScrobbling(
	entity: ScrobblingEntity,
	workResolver: WorkResolver,
): ScrobblingEntity {
	val normalized = attachEntityOwnership(entity, workResolver)
	getScrobblingDao().upsert(normalized)
	return normalized
}

suspend fun MangaDatabase.upsertScrobblingPreview(
	entity: ScrobblingEntity,
	workResolver: WorkResolver,
	title: String? = entity.remoteTitle,
	coverUrl: String? = entity.remoteCoverUrl,
	url: String? = entity.remoteUrl,
): ScrobblingEntity {
	val normalized = entity.copy(
		remoteTitle = title ?: entity.remoteTitle,
		remoteCoverUrl = coverUrl ?: entity.remoteCoverUrl,
		remoteUrl = url ?: entity.remoteUrl,
	)
	return upsertScrobbling(normalized, workResolver)
}

private suspend fun WorkResolver.resolveScrobblingEntityId(mangaId: Long): Long? {
	return when (mangaId) {
		0L -> null
		else -> resolveByMangaId(mangaId).entityId
	}
}

fun ScrobblingDao.observeByWorkOrMangaCandidates(
	scrobbler: Int,
	entityId: Long?,
	mangaIds: List<Long>,
): Flow<List<ScrobblingEntity>> {
	val distinctMangaIds = mangaIds.distinct()
	if (entityId == null && distinctMangaIds.isEmpty()) {
		return flowOf(emptyList())
	}
	if (entityId == null) {
		return observeByLocalMangaIds(scrobbler, distinctMangaIds)
	}
	if (distinctMangaIds.isEmpty()) {
		return observeByEntityIds(scrobbler, listOf(entityId))
	}
	return observeByEntityIds(scrobbler, listOf(entityId))
		.combine(observeByLocalMangaIds(scrobbler, distinctMangaIds)) { entityOwned, mangaAnchored ->
			mergeScrobblingCandidates(entityOwned, mangaAnchored)
		}
}

suspend fun ScrobblingDao.findByWorkOrMangaCandidates(
	scrobbler: Int,
	entityId: Long?,
	mangaIds: List<Long>,
): List<ScrobblingEntity> {
	val distinctMangaIds = mangaIds.distinct()
	if (entityId == null && distinctMangaIds.isEmpty()) {
		return emptyList()
	}
	if (entityId == null) {
		return findByLocalMangaIds(scrobbler, distinctMangaIds)
	}
	if (distinctMangaIds.isEmpty()) {
		return findByEntityIds(scrobbler, listOf(entityId))
	}
	return mergeScrobblingCandidates(
		findByEntityIds(scrobbler, listOf(entityId)),
		findByLocalMangaIds(scrobbler, distinctMangaIds),
	)
}

fun List<ScrobblingEntity>.preferredScrobblingEntity(): ScrobblingEntity? {
	return maxWithOrNull(
		compareBy<ScrobblingEntity> { it.scrobblingOwnershipScore() }
			.thenBy { if (it.entityId != null) 1 else 0 }
			.thenBy { if (it.mangaId != 0L) 1 else 0 }
			.thenBy { if (it.mediaType.isNotBlank()) 1 else 0 }
			.thenBy { if (!it.remoteTitle.isNullOrBlank() || !it.remoteCoverUrl.isNullOrBlank() || !it.remoteUrl.isNullOrBlank()) 1 else 0 }
			.thenBy { it.mangaId }
			.thenBy { it.id },
	)
}

fun Iterable<ScrobblingEntity>.preferredScrobblingByTargetId(): Map<Long, ScrobblingEntity> {
	return groupBy { it.targetId }
		.mapValuesNotNull { (_, values) -> values.preferredScrobblingEntity() }
}

fun Iterable<ScrobblingEntity>.preferredMangaMappingByTargetId(): Map<Long, Long> {
	return preferredScrobblingByTargetId()
		.mapValuesNotNull { (_, entity) -> entity.mangaId.takeIf { it != 0L } }
}

fun Iterable<ScrobblingEntity>.preferredScrobblingByTargetAndMediaType(): Map<ScrobblingTargetKey, ScrobblingEntity> {
	return groupBy { ScrobblingTargetKey(it.targetId, it.mediaType) }
		.mapValuesNotNull { (_, values) -> values.preferredScrobblingEntity() }
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
	val result = LinkedHashMap<K, R>(size)
	for (entry in entries) {
		transform(entry)?.let { result[entry.key] = it }
	}
	return result
}

private fun ScrobblingEntity.scrobblingOwnershipScore(): Int {
	var score = 0
	if (entityId != null) score += SCROBBLING_SCORE_ENTITY_OWNER
	if (mangaId != 0L) score += SCROBBLING_SCORE_LOCAL_PROJECTION
	if (rating > 0f) score += SCROBBLING_SCORE_RATING
	if (!comment.isNullOrBlank()) score += SCROBBLING_SCORE_COMMENT
	if (chapter > 0) score += SCROBBLING_SCORE_PROGRESS
	if (!remoteTitle.isNullOrBlank() || !remoteCoverUrl.isNullOrBlank() || !remoteUrl.isNullOrBlank()) {
		score += SCROBBLING_SCORE_REMOTE_PREVIEW
	}
	if (mediaType.isNotBlank()) score += SCROBBLING_SCORE_MEDIA_TYPE
	return score
}

private fun mergeScrobblingCandidates(
	entityOwned: List<ScrobblingEntity>,
	mangaAnchored: List<ScrobblingEntity>,
): List<ScrobblingEntity> {
	if (entityOwned.isEmpty()) return mangaAnchored
	if (mangaAnchored.isEmpty()) return entityOwned
	return LinkedHashMap<ScrobblingRowKey, ScrobblingEntity>(entityOwned.size + mangaAnchored.size).apply {
		entityOwned.forEach { put(it.rowKey(), it) }
		mangaAnchored.forEach { put(it.rowKey(), it) }
	}.values.toList()
}

private fun ScrobblingEntity.rowKey(): ScrobblingRowKey {
	return ScrobblingRowKey(
		scrobbler = scrobbler,
		id = id,
		entityId = entityId,
		mangaId = mangaId,
		mediaType = mediaType,
	)
}

private data class ScrobblingRowKey(
	val scrobbler: Int,
	val id: Int,
	val entityId: Long?,
	val mangaId: Long,
	val mediaType: String,
)

data class ScrobblingTargetKey(
	val targetId: Long,
	val mediaType: String,
)
