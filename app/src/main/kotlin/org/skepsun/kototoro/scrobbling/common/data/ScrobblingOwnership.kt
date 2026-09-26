package org.skepsun.kototoro.scrobbling.common.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.core.db.MangaDatabase

private const val SCROBBLING_SCORE_LOCAL_PROJECTION = 8
private const val SCROBBLING_SCORE_RATING = 4
private const val SCROBBLING_SCORE_COMMENT = 2
private const val SCROBBLING_SCORE_PROGRESS = 1
private const val SCROBBLING_SCORE_REMOTE_PREVIEW = 1
private const val SCROBBLING_SCORE_MEDIA_TYPE = 1

suspend fun MangaDatabase.findScrobblingByManga(
    scrobbler: Int,
    mangaId: Long,
): ScrobblingEntity? {
    return getScrobblingDao().findByLocalManga(scrobbler, mangaId)
}

suspend fun MangaDatabase.findPreferredScrobblingByTargetAndMediaType(
    scrobbler: Int,
    mangaId: Long,
    targetId: Long,
    mediaType: String,
): ScrobblingEntity? {
    return getScrobblingDao().findByTargetIdAndMediaType(
        scrobbler = scrobbler,
        targetId = targetId,
        mediaType = mediaType,
    )
}

suspend fun MangaDatabase.deleteScrobblingByManga(
    scrobbler: Int,
    mangaId: Long,
) {
    getScrobblingDao().deleteByLocalManga(scrobbler, mangaId)
}

suspend fun MangaDatabase.rebindScrobblingToManga(
    scrobbler: Int,
    sourceMangaId: Long,
    targetMangaId: Long,
    fallback: () -> ScrobblingEntity,
): ScrobblingEntity {
    val dao = getScrobblingDao()
    val current = dao.findByLocalManga(scrobbler, sourceMangaId)
    return rebindScrobblingToManga(
        dao = dao,
        current = current,
        scrobbler = scrobbler,
        targetMangaId = targetMangaId,
        fallback = fallback,
    )
}

private suspend fun rebindScrobblingToManga(
    dao: ScrobblingDao,
    current: ScrobblingEntity?,
    scrobbler: Int,
    targetMangaId: Long,
    fallback: () -> ScrobblingEntity,
): ScrobblingEntity {
    val rebound = (current ?: fallback()).copy(
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
    mangaId: Long = entity.mangaId,
): ScrobblingEntity {
    val normalized = entity.copy(
        mangaId = mangaId,
    )
    getScrobblingDao().upsert(normalized)
    return normalized
}

suspend fun MangaDatabase.upsertScrobbling(
    entity: ScrobblingEntity,
): ScrobblingEntity {
    getScrobblingDao().upsert(entity)
    return entity
}

suspend fun MangaDatabase.upsertScrobblingPreview(
    entity: ScrobblingEntity,
    title: String? = entity.remoteTitle,
    coverUrl: String? = entity.remoteCoverUrl,
    url: String? = entity.remoteUrl,
): ScrobblingEntity {
    val normalized = entity.copy(
        remoteTitle = title ?: entity.remoteTitle,
        remoteCoverUrl = coverUrl ?: entity.remoteCoverUrl,
        remoteUrl = url ?: entity.remoteUrl,
    )
    return upsertScrobbling(normalized)
}

fun ScrobblingDao.observeByMangaCandidates(
    scrobbler: Int,
    mangaIds: List<Long>,
): Flow<List<ScrobblingEntity>> {
    val distinctMangaIds = mangaIds.distinct()
    if (distinctMangaIds.isEmpty()) {
        return flowOf(emptyList())
    }
    return observeByLocalMangaIds(scrobbler, distinctMangaIds)
}

suspend fun ScrobblingDao.findByMangaCandidates(
    scrobbler: Int,
    mangaIds: List<Long>,
): List<ScrobblingEntity> {
    val distinctMangaIds = mangaIds.distinct()
    if (distinctMangaIds.isEmpty()) {
        return emptyList()
    }
    return findByLocalMangaIds(scrobbler, distinctMangaIds)
}

fun List<ScrobblingEntity>.preferredScrobblingEntity(): ScrobblingEntity? {
    return maxWithOrNull(
        compareBy<ScrobblingEntity> { it.scrobblingOwnershipScore() }
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

data class ScrobblingTargetKey(
    val targetId: Long,
    val mediaType: String,
)
