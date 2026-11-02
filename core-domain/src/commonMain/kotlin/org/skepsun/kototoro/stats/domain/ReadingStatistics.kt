package org.skepsun.kototoro.stats.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import kotlin.time.Instant

enum class StatsContentKind {
    ALL, MANGA, NOVEL, VIDEO;

    companion object {
        fun fromType(type: String?): StatsContentKind = when (type?.removePrefix("HENTAI_")) {
            "NOVEL" -> NOVEL
            "VIDEO" -> VIDEO
            else -> MANGA
        }
    }
}

enum class ReadingStatsPeriod(val days: Int) {
    DAY(1), WEEK(7), MONTH(30), MONTHS_3(90), ALL(0),
}

/** Calendar-day window in the user's time zone. All intervals are half-open, in milliseconds. */
class ReadingStatsWindow(
    val period: ReadingStatsPeriod,
    val now: Long,
    val zone: TimeZone,
) {
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val from: Long = if (period == ReadingStatsPeriod.ALL) 0 else {
        today.minus(period.days - 1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    }
}

data class ReadingDayStats(val date: String, val startedAt: Long, val duration: Long)

data class ReadingWorkStats(
    val contentId: Long,
    val kind: StatsContentKind,
    val duration: Long,
    val sessions: Int,
    val units: Int,
    val activeDays: Int,
    val firstActivityAt: Long,
    val lastActivityAt: Long,
    val dailyActivity: List<ReadingDayStats>,
)

data class ReadingStatistics(
    val works: List<ReadingWorkStats>,
    val dailyActivity: List<ReadingDayStats>,
    val hourlyActivity: List<Long>,
    val totalDuration: Long,
    val activeDays: Int,
    val currentStreak: Int,
    val sessionCount: Int,
)

/** Shared by Android and iOS: sessions own their covered time, legacy rows fill only uncovered intervals. */
fun deriveReadingStatistics(
    sessions: List<ReadingRecordEntity>,
    legacy: List<StatsEntity>,
    kinds: Map<Long, StatsContentKind>,
    window: ReadingStatsWindow,
): ReadingStatistics {
    val sessionGroups = sessions.filter { it.endAt > it.startAt }.groupBy { it.mangaId }
    val legacyGroups = legacy.groupBy { it.mangaId }
    val days = mutableMapOf<String, Long>()
    val hours = LongArray(24)
    val records = kinds.mapNotNull { (id, kind) ->
        val ownSessions = sessionGroups[id].orEmpty()
        val covered = mergeStatsIntervals(ownSessions.map { it.startAt to it.endAt })
        val intervals = ArrayList<Pair<Long, Long>>()
        var count = 0
        ownSessions.forEach { session ->
            val start = maxOf(window.from, session.startAt)
            val end = minOf(window.now, session.endAt)
            if (end > start) { intervals += start to end; count++ }
        }
        legacyGroups[id].orEmpty().forEach { row ->
            if (row.duration <= 0 || row.startedAt > Long.MAX_VALUE - row.duration) return@forEach
            val start = maxOf(window.from, row.startedAt)
            val end = minOf(window.now, row.startedAt + row.duration)
            val remainder = uncoveredStatsIntervals(start, end, covered)
            if (remainder.isNotEmpty()) { intervals += remainder; count++ }
        }
        if (intervals.isEmpty()) return@mapNotNull null
        val ownDays = mutableMapOf<String, Long>()
        for ((start, end) in intervals) {
            // Minute boundaries also handle half-hour DST transitions and repeated/skipped local hours.
            var cursor = start
            while (cursor < end) {
                val local = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(window.zone)
                val nextMinute = cursor + (60_000L - (local.second * 1000L + local.nanosecond / 1_000_000))
                val stop = minOf(end, nextMinute)
                val date = local.date.toString()
                val duration = stop - cursor
                ownDays[date] = (ownDays[date] ?: 0L) + duration
                days[date] = (days[date] ?: 0L) + duration
                hours[local.hour] += duration
                cursor = stop
            }
        }
        val units = if (kind == StatsContentKind.MANGA) {
            legacyGroups[id].orEmpty().filter {
                it.startedAt < window.now && it.duration > 0 && it.startedAt + it.duration > window.from
            }.sumOf { it.pages.coerceAtLeast(0) }
        } else {
            ownSessions.filter { it.startAt < window.now && it.endAt > window.from }
                .map { it.endChapterId }.distinct().size
        }
        ReadingWorkStats(id, kind, intervals.sumOf { it.second - it.first }, count, units, ownDays.size,
            intervals.minOf { it.first }, intervals.maxOf { it.second }, ownDays.map { (date, duration) ->
                ReadingDayStats(date, LocalDate.parse(date).atStartOfDayIn(window.zone).toEpochMilliseconds(), duration)
            }.sortedBy { it.startedAt })
    }.sortedWith(compareByDescending<ReadingWorkStats> { it.duration }.thenBy { it.contentId })
    val visibleDays = if (window.period == ReadingStatsPeriod.ALL) 90 else window.period.days
    val daily = (visibleDays - 1 downTo 0).map { offset ->
        val date = window.today.minus(offset, DateTimeUnit.DAY)
        ReadingDayStats(date.toString(), date.atStartOfDayIn(window.zone).toEpochMilliseconds(), days[date.toString()] ?: 0)
    }
    var streakDate = window.today
    if (days[streakDate.toString()] == null) streakDate = streakDate.minus(1, DateTimeUnit.DAY)
    var streak = 0
    while (days[streakDate.toString()] != null) {
        streak++
        streakDate = streakDate.minus(1, DateTimeUnit.DAY)
    }
    return ReadingStatistics(records, daily, hours.toList(), records.sumOf { it.duration }, days.size, streak,
        records.sumOf { it.sessions })
}

private fun mergeStatsIntervals(values: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    val merged = ArrayList<Pair<Long, Long>>()
    for (value in values.sortedBy { it.first }) {
        val previous = merged.lastOrNull()
        if (previous != null && value.first <= previous.second) {
            merged[merged.lastIndex] = previous.first to maxOf(previous.second, value.second)
        } else merged += value
    }
    return merged
}

private fun uncoveredStatsIntervals(start: Long, end: Long, covered: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (end <= start) return emptyList()
    val result = ArrayList<Pair<Long, Long>>()
    var cursor = start
    for ((from, until) in covered) {
        if (until <= cursor) continue
        if (from >= end) break
        if (from > cursor) result += cursor to minOf(from, end)
        cursor = maxOf(cursor, until)
        if (cursor >= end) break
    }
    if (cursor < end) result += cursor to end
    return result
}
