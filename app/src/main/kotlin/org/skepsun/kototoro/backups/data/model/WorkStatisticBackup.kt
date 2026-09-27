package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.stats.data.StatsEntity

@Serializable
class WorkStatisticBackup(
    @SerialName("entity_id") val entityId: Long,
    @SerialName("anchor_manga_id") val anchorMangaId: Long,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("pages") val pages: Int,
) {
    fun toStatsEntity(targetMangaId: Long = anchorMangaId) = StatsEntity(
        mangaId = targetMangaId,
        startedAt = startedAt,
        duration = duration,
        pages = pages,
    )
}
