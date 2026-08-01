package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.stats.data.WorkStatsEntity

@Serializable
class StatisticBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("started_at") val startedAt: Long,
	@SerialName("duration") val duration: Long,
	@SerialName("pages") val pages: Int,
) {

	constructor(entity: StatsEntity) : this(
		mangaId = entity.mangaId,
		startedAt = entity.startedAt,
		duration = entity.duration,
		pages = entity.pages,
	)

	constructor(entity: WorkStatsEntity) : this(
		mangaId = entity.anchorMangaId,
		startedAt = entity.startedAt,
		duration = entity.duration,
		pages = entity.pages,
	)

	fun toEntity() = StatsEntity(
		mangaId = mangaId,
		startedAt = startedAt,
		duration = duration,
		pages = pages,
	)
}
