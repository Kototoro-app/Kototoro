package org.skepsun.kototoro.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.TimeZone
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.ReadingStatsWindow
import org.skepsun.kototoro.stats.domain.deriveReadingStatistics
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_READING_SESSIONS
import org.skepsun.kototoro.core.db.TABLE_STATS
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.StatsContentSnapshot
import org.skepsun.kototoro.stats.domain.StatsDailyActivity
import org.skepsun.kototoro.stats.domain.StatsDashboard
import org.skepsun.kototoro.stats.domain.StatsKindSummary
import org.skepsun.kototoro.stats.domain.StatsRecord
import org.skepsun.kototoro.stats.domain.toStatsContentKind
import org.skepsun.kototoro.parsers.model.Content
import java.time.LocalDate
import javax.inject.Inject

class StatsRepository @Inject constructor(
    private val settings: AppSettings,
    private val db: MangaDatabase,
) {

    /**
     * Reactive dashboard for summaries: recomputes whenever reading sessions,
     * stats rows or history change.
     */
    fun observeDashboard(
        period: StatsPeriod,
        categories: Set<Long> = emptySet(),
        kind: StatsContentKind = StatsContentKind.ALL,
    ): Flow<StatsDashboard> {
        val invalidations = db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_READING_SESSIONS,
                TABLE_STATS,
                TABLE_HISTORY,
            ),
            emitInitialState = true,
        )
        return invalidations.mapLatest {
            getDashboard(period, categories, kind)
        }.distinctUntilChanged()
    }

    suspend fun getDashboard(
        period: StatsPeriod,
        categories: Set<Long>,
        kind: StatsContentKind,
    ): StatsDashboard = db.withTransaction {
        val window = ReadingStatsWindow(ReadingStatsPeriod.valueOf(period.name),
            System.currentTimeMillis(), TimeZone.currentSystemDefault())
        val sessions = db.getReadingRecordDao().findOverlapping(window.from, window.now)
        val legacy = db.getStatsDao().findOverlapping(window.from, window.now)
        val ids = (sessions.map { it.mangaId } + legacy.map { it.mangaId }).distinct()
        val categoryIds = if (categories.isEmpty()) null else db.getFavouritesDao().findAllActiveEntries()
            .filter { it.categoryId in categories }.map { it.mangaId }.toSet()
        val contents = ids.chunked(200).flatMap { db.getMangaDao().findWithTagsByIds(it) }
            .associate { it.manga.id to it.toContent() }
            .filter { (id, content) -> (categoryIds == null || id in categoryIds) &&
                (kind == StatsContentKind.ALL || content.source.contentType.toStatsContentKind() == kind) }
        val snapshot = deriveReadingStatistics(sessions, legacy,
            contents.mapValues { it.value.source.contentType.toStatsContentKind() }, window)
        val records = snapshot.works.map { record -> StatsRecord(
            manga = contents[record.contentId], duration = record.duration, sessions = record.sessions,
            units = record.units, lastActivityAt = record.lastActivityAt, kind = record.kind,
        ) }
        StatsDashboard(
            records = records,
            dailyActivity = snapshot.dailyActivity.map { StatsDailyActivity(LocalDate.parse(it.date), it.duration) },
            kindSummaries = records.groupBy { it.kind }.map { (type, values) ->
                StatsKindSummary(type, values.sumOf { it.duration }, values.size)
            },
            totalDuration = snapshot.totalDuration, activeDays = snapshot.activeDays,
            currentStreak = snapshot.currentStreak, sessionCount = snapshot.sessionCount,
            workCount = records.size, hourlyActivity = snapshot.hourlyActivity,
        )
    }

    suspend fun getTimePerPage(mangaId: Long): Long = db.withTransaction {
        val pages = db.getStatsDao().getReadPagesCount(mangaId)
        if (pages >= 10) {
            db.getStatsDao().getAverageTimePerPage(mangaId)
        } else {
            db.getStatsDao().getAverageTimePerPage()
        }
    }

    suspend fun getContentSnapshot(content: Content): StatsContentSnapshot = db.withTransaction {
        val kind = content.source.contentType.toStatsContentKind()
        val value = deriveReadingStatistics(db.getReadingRecordDao().findSessions(content.id),
            db.getStatsDao().findAll(content.id), mapOf(content.id to kind),
            ReadingStatsWindow(ReadingStatsPeriod.ALL, System.currentTimeMillis(), TimeZone.currentSystemDefault()))
        val record = value.works.singleOrNull()
        StatsContentSnapshot(
            dailyActivity = if (record == null) emptyList() else value.dailyActivity.map {
                (it.duration / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
            firstActivityAt = record?.firstActivityAt, totalDuration = value.totalDuration,
            sessionCount = value.sessionCount, units = record?.units ?: 0, kind = kind,
        )
    }

    suspend fun clearStats() {
        db.getStatsDao().clear()
        db.getReadingRecordDao().clearAllSessions()
    }

    fun observeHasStats(mangaId: Long): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_STATS_ENABLED) {
        isStatsEnabled
    }.flatMapLatest { isEnabled ->
        if (isEnabled) {
            db.invalidationTracker.createFlow(TABLE_STATS, TABLE_READING_SESSIONS, emitInitialState = true)
                .mapLatest { hasStats(mangaId) }
        } else {
            flowOf(false)
        }
    }.distinctUntilChanged()

    private suspend fun hasStats(mangaId: Long): Boolean {
        return db.getStatsDao().findAll(mangaId).any { it.duration > 0 || it.pages > 0 } ||
            db.getReadingRecordDao().findSessions(mangaId).any { it.endAt > it.startAt }
    }
}
