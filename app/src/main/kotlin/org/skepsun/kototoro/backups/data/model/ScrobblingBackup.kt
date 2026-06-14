package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.resolveScrobblingOwnerId

@Serializable
class ScrobblingBackup(
	@SerialName("scrobbler") val scrobbler: Int,
	@SerialName("id") val id: Int,
	@SerialName("owner_id") val ownerId: Long? = null,
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("target_id") val targetId: Long,
	@SerialName("status") val status: String?,
	@SerialName("chapter") val chapter: Int,
	@SerialName("comment") val comment: String?,
	@SerialName("rating") val rating: Float,
	@SerialName("media_type") val mediaType: String = "",
	@SerialName("remote_title") val remoteTitle: String? = null,
	@SerialName("remote_cover_url") val remoteCoverUrl: String? = null,
	@SerialName("remote_url") val remoteUrl: String? = null,
) {

	constructor(entity: ScrobblingEntity) : this(
		scrobbler = entity.scrobbler,
		id = entity.id,
		ownerId = entity.ownerId,
		entityId = entity.entityId,
		mangaId = entity.mangaId,
		targetId = entity.targetId,
		status = entity.status,
		chapter = entity.chapter,
		comment = entity.comment,
		rating = entity.rating,
		mediaType = entity.mediaType,
		remoteTitle = entity.remoteTitle,
		remoteCoverUrl = entity.remoteCoverUrl,
		remoteUrl = entity.remoteUrl,
	)

	fun toEntity() = ScrobblingEntity(
		scrobbler = scrobbler,
		id = id,
		ownerId = ownerId ?: resolveScrobblingOwnerId(entityId, mangaId),
		entityId = entityId,
		mangaId = mangaId,
		targetId = targetId,
		status = status,
		chapter = chapter,
		comment = comment,
		rating = rating,
		mediaType = mediaType,
		remoteTitle = remoteTitle,
		remoteCoverUrl = remoteCoverUrl,
		remoteUrl = remoteUrl,
	)
}
