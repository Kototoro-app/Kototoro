package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity

@Serializable
class StatisticBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("duration") val duration: Long,
    @SerialName("pages") val pages: Int,
    // Optional additive metadata: older readers retain duration/pages and safely ignore the session detail.
    @SerialName("session") val session: ReadingSessionBackup? = null,
) {

    constructor(entity: StatsEntity, session: ReadingRecordEntity? = null) : this(
        mangaId = entity.mangaId,
        startedAt = entity.startedAt,
        duration = if (session == null) entity.duration else maxOf(entity.duration, session.endAt - session.startAt),
        pages = entity.pages,
        session = session?.let(::ReadingSessionBackup),
    )

    fun toEntity() = StatsEntity(
        mangaId = mangaId,
        startedAt = startedAt,
        duration = duration,
        pages = pages,
    )
}

@Serializable
class ReadingSessionBackup(
    @SerialName("end_at") val endAt: Long,
    @SerialName("start_chapter_id") val startChapterId: Long,
    @SerialName("start_page") val startPage: Int,
    @SerialName("start_scroll") val startScroll: Int,
    @SerialName("end_chapter_id") val endChapterId: Long,
    @SerialName("end_page") val endPage: Int,
    @SerialName("end_scroll") val endScroll: Int,
    @SerialName("start_percent") val startPercent: Float,
    @SerialName("end_percent") val endPercent: Float,
) {
    constructor(value: ReadingRecordEntity) : this(value.endAt, value.startChapterId, value.startPage,
        value.startScroll, value.endChapterId, value.endPage, value.endScroll, value.startPercent, value.endPercent)

    fun toEntity(mangaId: Long, startedAt: Long) = ReadingRecordEntity(mangaId = mangaId,
        startAt = startedAt, endAt = endAt, startChapterId = startChapterId, startPage = startPage,
        startScroll = startScroll, endChapterId = endChapterId, endPage = endPage, endScroll = endScroll,
        startPercent = startPercent, endPercent = endPercent)
}
