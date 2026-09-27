package org.skepsun.kototoro.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
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
import org.skepsun.kototoro.stats.domain.calculateCurrentStatsStreak
import org.skepsun.kototoro.stats.domain.calculateHourlyStatsActivity
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
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

    suspend fun getReadingStats(period: StatsPeriod, categories: Set<Long>): List<StatsRecord> {
        val fromDate = if (period == StatsPeriod.ALL) {
            0L
        } else {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(period.days.toLong())
        }
        val stats = db.getStatsDao().getDurationStats(fromDate, null, categories)
        val result = ArrayList<StatsRecord>(stats.size)
        var other = StatsRecord(null, 0)
        val total = stats.values.sum()
        for ((mangaEntity, duration) in stats) {
            val manga = mangaEntity.toContent(emptySet(), null)
            val percent = duration.toDouble() / total
            if (percent < 0.05) {
                other = other.copy(duration = other.duration + duration)
            } else {
                result += StatsRecord(
                    manga = manga,
                    duration = duration,
                )
            }
        }
        if (other.duration != 0L) {
            result += other
        }
        return result
    }

    suspend fun getDashboard(
        period: StatsPeriod,
        categories: Set<Long>,
        kind: StatsContentKind,
    ): StatsDashboard {
        val now = System.currentTimeMillis()
        val fromDate = if (period == StatsPeriod.ALL) {
            0L
        } else {
            now - TimeUnit.DAYS.toMillis(period.days.toLong())
        }
        val zoneId = ZoneId.systemDefault()
        val sessions = db.getReadingRecordDao().findSessionsSince(fromDate)
            .filter { it.endAt > it.startAt }
        if (sessions.isEmpty()) {
            return getLegacyDashboard(period, categories, kind, fromDate, zoneId)
        }

        val grouped = sessions.groupBy { it.mangaId }
        val records = grouped.entries.mapNotNull { (mangaId, workSessions) ->
            if (categories.isNotEmpty()) {
                val mangaCategories = db.getFavouritesDao().findCategories(mangaId)
                if (mangaCategories.none { it in categories }) return@mapNotNull null
            }
            val content = db.getMangaDao().find(mangaId)?.toContent()
            val contentKind = content?.source?.contentType.toStatsContentKind()
            if (kind != StatsContentKind.ALL && contentKind != kind) return@mapNotNull null
            val duration = workSessions.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
            val units = when (contentKind) {
                StatsContentKind.MANGA -> {
                    db.getStatsDao().findAll(mangaId)
                        .asSequence()
                        .filter { it.startedAt >= fromDate }
                        .sumOf { it.pages }
                }
                StatsContentKind.NOVEL,
                StatsContentKind.VIDEO,
                -> workSessions.map { it.endChapterId }.distinct().size
                StatsContentKind.ALL -> 0
            }
            StatsRecord(
                manga = content,
                duration = duration,
                sessions = workSessions.size,
                units = units,
                lastActivityAt = workSessions.maxOf { it.endAt },
                kind = contentKind,
            )
        }.sortedByDescending { it.duration }

        val includedIds = records.mapNotNull { it.manga?.id }.toSet()
        val includedKinds = records.associate { it.manga?.id to it.kind }
        val filteredSessions = sessions.filter { session ->
            session.mangaId in includedIds && (kind == StatsContentKind.ALL || includedKinds[session.mangaId] == kind)
        }
        val activityByDate = filteredSessions.groupBy { session ->
            Instant.ofEpochMilli(session.startAt).atZone(zoneId).toLocalDate()
        }.mapValues { (_, values) ->
            values.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
        }
        val today = LocalDate.now(zoneId)
        val visibleDays = visibleActivityDays(period)
        val dailyActivity = (visibleDays - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            StatsDailyActivity(date, activityByDate[date] ?: 0L)
        }
        val activeDates = activityByDate.filterValues { it > 0L }.keys
        val streak = calculateCurrentStatsStreak(activeDates, today)
        val kindSummaries = StatsContentKind.entries
            .filter { it != StatsContentKind.ALL }
            .map { contentKind ->
                val kindRecords = records.filter { it.kind == contentKind }
                StatsKindSummary(
                    kind = contentKind,
                    duration = kindRecords.sumOf { it.duration },
                    works = kindRecords.size,
                )
            }.filter { it.duration > 0L }

        return StatsDashboard(
            records = records,
            dailyActivity = dailyActivity,
            kindSummaries = kindSummaries,
            totalDuration = records.sumOf { it.duration },
            activeDays = activeDates.size,
            currentStreak = streak,
            sessionCount = records.sumOf { it.sessions },
            workCount = records.size,
            hourlyActivity = calculateHourlyStatsActivity(
                filteredSessions.map { it.startAt to it.endAt },
                zoneId,
            ),
        )
    }

    private suspend fun getLegacyDashboard(
        period: StatsPeriod,
        categories: Set<Long>,
        kind: StatsContentKind,
        fromDate: Long,
        zoneId: ZoneId,
    ): StatsDashboard {
        val durationStats = db.getStatsDao().getDurationStats(fromDate, null, categories)
        val activityByDate = mutableMapOf<LocalDate, Long>()
        val activityIntervals = mutableListOf<Pair<Long, Long>>()
        val records = durationStats.mapNotNull { (mangaEntity, duration) ->
            val content = mangaEntity.toContent(emptySet(), null)
            val contentKind = content.source.contentType.toStatsContentKind()
            if (kind != StatsContentKind.ALL && contentKind != kind) return@mapNotNull null
            val entries = db.getStatsDao().findAll(content.id)
                .filter { it.startedAt >= fromDate }
            entries.forEach { entry ->
                val date = Instant.ofEpochMilli(entry.startedAt).atZone(zoneId).toLocalDate()
                activityByDate[date] = activityByDate.getOrDefault(date, 0L) + entry.duration
                activityIntervals += entry.startedAt to (entry.startedAt + entry.duration)
            }
            StatsRecord(
                manga = content,
                duration = duration,
                sessions = entries.size,
                units = if (contentKind == StatsContentKind.MANGA) entries.sumOf { it.pages } else 0,
                lastActivityAt = entries.maxOfOrNull { it.startedAt } ?: 0L,
                kind = contentKind,
            )
        }.sortedByDescending { it.duration }
        val today = LocalDate.now(zoneId)
        val visibleDays = visibleActivityDays(period)
        val dailyActivity = (visibleDays - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            StatsDailyActivity(date, activityByDate[date] ?: 0L)
        }
        val activeDates = activityByDate.filterValues { it > 0L }.keys
        val kindSummaries = StatsContentKind.entries
            .filter { it != StatsContentKind.ALL }
            .map { contentKind ->
                val kindRecords = records.filter { it.kind == contentKind }
                StatsKindSummary(contentKind, kindRecords.sumOf { it.duration }, kindRecords.size)
            }.filter { it.duration > 0L }
        return StatsDashboard(
            records = records,
            dailyActivity = dailyActivity,
            kindSummaries = kindSummaries,
            totalDuration = records.sumOf { it.duration },
            activeDays = activeDates.size,
            currentStreak = calculateCurrentStatsStreak(activeDates, today),
            sessionCount = records.sumOf { it.sessions },
            workCount = records.size,
            hourlyActivity = calculateHourlyStatsActivity(activityIntervals, zoneId),
        )
    }

    private fun visibleActivityDays(period: StatsPeriod): Int = when (period) {
        StatsPeriod.DAY -> 1
        StatsPeriod.WEEK -> 7
        StatsPeriod.MONTH -> 30
        StatsPeriod.MONTHS_3,
        StatsPeriod.ALL,
        -> 90
    }

    suspend fun getTimePerPage(mangaId: Long): Long = db.withTransaction {
        val pages = db.getStatsDao().getReadPagesCount(mangaId)
        if (pages >= 10) {
            db.getStatsDao().getAverageTimePerPage(mangaId)
        } else {
            db.getStatsDao().getAverageTimePerPage()
        }
    }

    suspend fun getTotalPagesRead(mangaId: Long): Int {
        return db.getStatsDao().getReadPagesCount(mangaId)
    }

    suspend fun getContentTimeline(mangaId: Long): NavigableMap<Long, Int> {
        val entries = db.getStatsDao().findAll(mangaId)
        val map = TreeMap<Long, Int>()
        for (e in entries) {
            map[e.startedAt] = e.pages
        }
        return map
    }

    suspend fun getContentSnapshot(content: Content): StatsContentSnapshot {
        val sessions = db.getReadingRecordDao().findSessions(listOf(content.id))
            .filter { it.endAt > it.startAt }
        val kind = content.source.contentType.toStatsContentKind()
        if (sessions.isNotEmpty()) {
            val zoneId = ZoneId.systemDefault()
            val activityByDate = sessions.groupBy {
                Instant.ofEpochMilli(it.startAt).atZone(zoneId).toLocalDate()
            }.mapValues { (_, daySessions) ->
                TimeUnit.MILLISECONDS.toSeconds(
                    daySessions.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) },
                ).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            val firstDate = activityByDate.keys.minOrNull() ?: LocalDate.now(zoneId)
            val today = LocalDate.now(zoneId)
            val activity = generateSequence(firstDate) { date ->
                date.plusDays(1).takeIf { it <= today }
            }.map { activityByDate[it] ?: 0 }.toList()
            val units = when (kind) {
                StatsContentKind.MANGA -> db.getStatsDao().findAll(content.id).sumOf { it.pages }
                StatsContentKind.NOVEL,
                StatsContentKind.VIDEO,
                -> sessions.map { it.endChapterId }.distinct().size
                StatsContentKind.ALL -> 0
            }
            return StatsContentSnapshot(
                dailyActivity = activity,
                firstActivityAt = sessions.minOf { it.startAt },
                totalDuration = sessions.sumOf { it.endAt - it.startAt },
                sessionCount = sessions.size,
                units = units,
                kind = kind,
            )
        }

        val entries = db.getStatsDao().findAll(content.id)
        if (entries.isEmpty()) return StatsContentSnapshot(kind = kind)
        val zoneId = ZoneId.systemDefault()
        val activityByDate = entries.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(zoneId).toLocalDate()
        }.mapValues { (_, dayEntries) -> dayEntries.sumOf { it.pages } }
        val firstDate = activityByDate.keys.minOrNull() ?: LocalDate.now(zoneId)
        val today = LocalDate.now(zoneId)
        return StatsContentSnapshot(
            dailyActivity = generateSequence(firstDate) { date ->
                date.plusDays(1).takeIf { it <= today }
            }.map { activityByDate[it] ?: 0 }.toList(),
            firstActivityAt = entries.minOf { it.startedAt },
            totalDuration = entries.sumOf { it.duration },
            sessionCount = entries.size,
            units = if (kind == StatsContentKind.MANGA) entries.sumOf { it.pages } else 0,
            kind = kind,
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
            flowOf(hasStats(mangaId))
        } else {
            flowOf(false)
        }
    }.distinctUntilChanged()

    private suspend fun hasStats(mangaId: Long): Boolean {
        return db.getStatsDao().getReadPagesCount(mangaId) > 0
    }
}
