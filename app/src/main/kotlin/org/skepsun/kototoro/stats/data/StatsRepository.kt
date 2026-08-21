package org.skepsun.kototoro.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.MangaDatabase
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
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkResolver
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
    private val workResolver: WorkResolver,
    private val workAggregateRepository: WorkAggregateRepository,
) {

    suspend fun getReadingStats(period: StatsPeriod, categories: Set<Long>): List<StatsRecord> {
        val fromDate = if (period == StatsPeriod.ALL) {
            0L
        } else {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(period.days.toLong())
        }
        val stats = db.getWorkStatsDao().getDurationStats(fromDate, null, categories)
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

        val identities = sessions.asSequence()
            .map { it.mangaId }
            .distinct()
            .associateWith { workResolver.resolveByMangaId(it) }
        val aggregates = workAggregateRepository.findAggregatesByEntityIds(
            identities.values.mapNotNull { it.entityId }.distinct(),
        )
        val grouped = sessions.groupBy { session ->
            identities[session.mangaId]?.entityId?.let { "entity:$it" } ?: "manga:${session.mangaId}"
        }
        val records = grouped.values.mapNotNull { workSessions ->
            val anchorId = workSessions.first().mangaId
            val identity = identities[anchorId]
            val aggregate = identity?.entityId?.let(aggregates::get)
            if (categories.isNotEmpty() && aggregate?.categories?.none { it.id in categories } != false) {
                return@mapNotNull null
            }
            val content = aggregate?.displayProjection
                ?: db.getMangaDao().find(anchorId)?.toContent()
            val contentKind = content?.source?.contentType.toStatsContentKind()
            if (kind != StatsContentKind.ALL && contentKind != kind) return@mapNotNull null
            val duration = workSessions.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
            val units = when (contentKind) {
                StatsContentKind.MANGA -> {
                    val entityId = identity?.entityId
                    if (entityId == null) 0 else db.getWorkStatsDao().findAll(entityId)
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
            val identity = identities[session.mangaId]
            val displayId = identity?.entityId?.let(aggregates::get)?.displayProjection?.id ?: session.mangaId
            displayId in includedIds && (kind == StatsContentKind.ALL || includedKinds[displayId] == kind)
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
        val durationStats = db.getWorkStatsDao().getDurationStats(fromDate, null, categories)
        val activityByDate = mutableMapOf<LocalDate, Long>()
        val activityIntervals = mutableListOf<Pair<Long, Long>>()
        val records = durationStats.mapNotNull { (mangaEntity, duration) ->
            val content = mangaEntity.toContent(emptySet(), null)
            val contentKind = content.source.contentType.toStatsContentKind()
            if (kind != StatsContentKind.ALL && contentKind != kind) return@mapNotNull null
            val entityId = workResolver.resolveByMangaId(content.id).entityId
            val entries = entityId?.let { db.getWorkStatsDao().findAll(it) }
                .orEmpty()
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
        val aggregate = workAggregateRepository.findAggregateByMangaId(mangaId) ?: return@withTransaction 0L
        val pages = aggregate.stats?.totalPages ?: 0
        val time = if (pages >= 10) {
            aggregate.stats?.averageTimePerPage ?: 0L
        } else {
            db.getWorkStatsDao().getAverageTimePerPage()
        }
        time
    }

    suspend fun getTotalPagesRead(mangaId: Long): Int {
        return workAggregateRepository.findAggregateByMangaId(mangaId)?.stats?.totalPages ?: 0
    }

    suspend fun getContentTimeline(mangaId: Long): NavigableMap<Long, Int> {
        val entityId = resolveStatsEntityId(mangaId) ?: return TreeMap()
        val workEntities = db.getWorkStatsDao().findAll(entityId)
        val map = TreeMap<Long, Int>()
        for (e in workEntities) {
            map[e.startedAt] = e.pages
        }
        return map
    }

    suspend fun getContentSnapshot(content: Content): StatsContentSnapshot {
        val identity = workResolver.resolveByMangaId(content.id)
        val mangaIds = identity.localMangaIds.ifEmpty { setOf(content.id) }.toList()
        val sessions = db.getReadingRecordDao().findSessions(mangaIds)
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
                StatsContentKind.MANGA -> identity.entityId
                    ?.let { db.getWorkStatsDao().findAll(it).sumOf { entry -> entry.pages } }
                    ?: 0
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

        val entityId = identity.entityId ?: return StatsContentSnapshot(kind = kind)
        val legacyEntries = db.getWorkStatsDao().findAll(entityId)
        if (legacyEntries.isEmpty()) return StatsContentSnapshot(kind = kind)
        val zoneId = ZoneId.systemDefault()
        val activityByDate = legacyEntries.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(zoneId).toLocalDate()
        }.mapValues { (_, entries) -> entries.sumOf { it.pages } }
        val firstDate = activityByDate.keys.minOrNull() ?: LocalDate.now(zoneId)
        val today = LocalDate.now(zoneId)
        return StatsContentSnapshot(
            dailyActivity = generateSequence(firstDate) { date ->
                date.plusDays(1).takeIf { it <= today }
            }.map { activityByDate[it] ?: 0 }.toList(),
            firstActivityAt = legacyEntries.minOf { it.startedAt },
            totalDuration = legacyEntries.sumOf { it.duration },
            sessionCount = legacyEntries.size,
            units = if (kind == StatsContentKind.MANGA) legacyEntries.sumOf { it.pages } else 0,
            kind = kind,
        )
    }

    suspend fun clearStats() {
        db.getWorkStatsDao().clear()
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
        return (workAggregateRepository.findAggregateByMangaId(mangaId)?.stats?.entryCount ?: 0) > 0
    }

    // The incoming mangaId is a projection/local anchor. User-visible stats are work-owned.
    private suspend fun resolveStatsEntityId(mangaId: Long): Long? {
        return workResolver.resolveByMangaId(mangaId).entityId
    }
}
