package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.tracker.data.TrackLogEntity

/**
 * Wire format of a tracker feed log row. `owner_id` / `entity_id` are kept as payload
 * fields so backups written by the entity-era app still deserialize, but they no longer
 * carry identity: a log is owned by its `manga_id` projection.
 */
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

    fun toEntity(): TrackLogEntity = TrackLogEntity(
        mangaId = mangaId,
        chapters = chapters,
        createdAt = createdAt.coerceAtLeast(0L),
        isUnread = isUnread,
    )
}
