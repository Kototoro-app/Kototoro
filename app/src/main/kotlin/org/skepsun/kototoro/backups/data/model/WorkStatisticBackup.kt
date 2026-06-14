package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.stats.data.WorkStatsEntity

@Serializable
class WorkStatisticBackup(
	@SerialName("entity_id") val entityId: Long,
	@SerialName("anchor_manga_id") val anchorMangaId: Long,
	@SerialName("started_at") val startedAt: Long,
	@SerialName("duration") val duration: Long,
	@SerialName("pages") val pages: Int,
) {

	constructor(entity: WorkStatsEntity) : this(
		entityId = entity.entityId,
		anchorMangaId = entity.anchorMangaId,
		startedAt = entity.startedAt,
		duration = entity.duration,
		pages = entity.pages,
	)

	fun toEntity() = WorkStatsEntity(
		entityId = entityId,
		anchorMangaId = anchorMangaId,
		startedAt = startedAt,
		duration = duration,
		pages = pages,
	)
}
