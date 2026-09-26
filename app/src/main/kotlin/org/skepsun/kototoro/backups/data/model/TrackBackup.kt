package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.tracker.data.TrackEntity

/**
 * Wire format of a tracked content row. `owner_id` / `entity_id` are kept as payload
 * fields so backups written by the entity-era app still deserialize, but they no longer
 * carry identity: a track is owned by its `manga_id` projection.
 */
@Serializable
class TrackBackup(
    @SerialName("owner_id") val ownerId: Long,
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("entity_id") val entityId: Long? = null,
    @SerialName("last_chapter_id") val lastChapterId: Long,
    @SerialName("chapters_new") val newChapters: Int,
    @SerialName("last_check_time") val lastCheckTime: Long,
    @SerialName("last_chapter_date") val lastChapterDate: Long,
    @SerialName("last_result") val lastResult: Int,
    @SerialName("last_error") val lastError: String?,
) {

    constructor(entity: TrackEntity) : this(
        ownerId = entity.ownerId,
        mangaId = entity.mangaId,
        entityId = entity.entityId,
        lastChapterId = entity.lastChapterId,
        newChapters = entity.newChapters,
        lastCheckTime = entity.lastCheckTime,
        lastChapterDate = entity.lastChapterDate,
        lastResult = entity.lastResult,
        lastError = entity.lastError,
    )

    fun toEntity(): TrackEntity = TrackEntity(
        mangaId = mangaId,
        lastChapterId = lastChapterId,
        newChapters = newChapters.coerceAtLeast(0),
        lastCheckTime = lastCheckTime.coerceAtLeast(0L),
        lastChapterDate = lastChapterDate.coerceAtLeast(0L),
        lastResult = lastResult,
        lastError = lastError,
    )
}
