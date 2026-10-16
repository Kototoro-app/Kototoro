package org.skepsun.kototoro.stats.domain

import org.skepsun.kototoro.details.data.ReadingTime
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class StatsRecord(
    val manga: Content?,
    val duration: Long,
    val sessions: Int = 0,
    val units: Int = 0,
    val lastActivityAt: Long = 0L,
    val kind: StatsContentKind = manga?.source?.contentType.toStatsContentKind(),
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is StatsRecord && other.manga == manga
    }

    val time: ReadingTime

    init {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration).toInt()
        time = ReadingTime(
            minutes = minutes % 60,
            hours = minutes / 60,
            isContinue = false,
        )
    }
}

data class StatsDailyActivity(
    val date: LocalDate,
    val duration: Long,
)

data class StatsKindSummary(
    val kind: StatsContentKind,
    val duration: Long,
    val works: Int,
)

data class StatsDashboard(
    val records: List<StatsRecord> = emptyList(),
    val dailyActivity: List<StatsDailyActivity> = emptyList(),
    val kindSummaries: List<StatsKindSummary> = emptyList(),
    val totalDuration: Long = 0L,
    val activeDays: Int = 0,
    val currentStreak: Int = 0,
    val sessionCount: Int = 0,
    val workCount: Int = 0,
    val hourlyActivity: List<Long> = List(24) { 0L },
)

data class StatsContentSnapshot(
    val dailyActivity: List<Int> = emptyList(),
    val firstActivityAt: Long? = null,
    val totalDuration: Long = 0L,
    val sessionCount: Int = 0,
    val units: Int = 0,
    val kind: StatsContentKind = StatsContentKind.MANGA,
)

fun ContentType?.toStatsContentKind(): StatsContentKind = when (this) {
    ContentType.NOVEL,
    ContentType.HENTAI_NOVEL,
    -> StatsContentKind.NOVEL

    ContentType.VIDEO,
    ContentType.HENTAI_VIDEO,
    -> StatsContentKind.VIDEO

    ContentType.MANGA,
    ContentType.MANHWA,
    ContentType.MANHUA,
    ContentType.HENTAI_MANGA,
    ContentType.COMICS,
    ContentType.ONE_SHOT,
    ContentType.DOUJINSHI,
    ContentType.IMAGE_SET,
    ContentType.ARTIST_CG,
    ContentType.GAME_CG,
    ContentType.OTHER,
    null,
    -> StatsContentKind.MANGA
}
