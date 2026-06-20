package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId

@Serializable
class TrackLogBackup(
	@SerialName("owner_id") val ownerId: Long,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("entity_id") val entityId: Long? = null,
	@SerialName("chapters") val chapters: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("unread") val isUnread: Boolean,
) {

	constructor(entity: TrackLogEntity) : this(
		ownerId = entity.ownerId,
		mangaId = entity.mangaId,
		entityId = entity.entityId,
		chapters = entity.chapters,
		createdAt = entity.createdAt,
		isUnread = entity.isUnread,
	)

	fun toEntity(entityIdMapping: Map<Long, Long> = emptyMap()): TrackLogEntity {
		val localEntityId = entityId?.let { entityIdMapping[it] ?: it }
		return TrackLogEntity(
			ownerId = resolveTrackOwnerId(localEntityId, mangaId).takeIf { it != 0L } ?: ownerId,
			mangaId = mangaId,
			entityId = localEntityId,
			chapters = chapters,
			createdAt = createdAt.coerceAtLeast(0L),
			isUnread = isUnread,
		)
	}
}
