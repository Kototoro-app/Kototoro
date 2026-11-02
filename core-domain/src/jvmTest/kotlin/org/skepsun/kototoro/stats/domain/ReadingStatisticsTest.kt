package org.skepsun.kototoro.stats.domain

import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import kotlin.time.Instant

class ReadingStatisticsTest {
    private val minute = 60_000L
    private fun time(value: String) = Instant.parse(value).toEpochMilliseconds()
    private fun session(id: Long, start: Long, end: Long) =
        ReadingRecordEntity(mangaId = id, startAt = start, endAt = end,
            startChapterId = 1, endChapterId = 1, startPage = 0, endPage = 2,
            startScroll = 0, endScroll = 0, startPercent = 0f, endPercent = 0.5f)

    @Test
    fun `legacy works remain visible and overlapping time is counted once`() {
        val start = time("2026-10-16T02:00:00Z")
        val stats = deriveReadingStatistics(
            listOf(session(1, start + 5 * minute, start + 15 * minute)),
            listOf(StatsEntity(1, start, 20 * minute, 5), StatsEntity(2, start, 10 * minute, 3)),
            mapOf(1L to StatsContentKind.MANGA, 2L to StatsContentKind.MANGA),
            ReadingStatsWindow(ReadingStatsPeriod.ALL, start + 60 * minute, TimeZone.UTC),
        )
        assertEquals(30 * minute, stats.totalDuration)
        assertEquals(2, stats.works.size)
        assertEquals(20 * minute, stats.works.first().duration)
        assertEquals(5, stats.works.first().units)
    }

    @Test
    fun `period clips midnight crossing sessions and uses local calendar days`() {
        val start = time("2026-10-15T15:50:00Z")
        val now = time("2026-10-15T16:20:00Z")
        val sessions = listOf(session(1, start, now))
        val kinds = mapOf(1L to StatsContentKind.NOVEL)
        val zone = TimeZone.of("Asia/Shanghai")
        val today = deriveReadingStatistics(sessions, emptyList(), kinds,
            ReadingStatsWindow(ReadingStatsPeriod.DAY, now, zone))
        assertEquals(20 * minute, today.totalDuration)
        assertEquals(20 * minute, today.dailyActivity.single().duration)
        val week = deriveReadingStatistics(sessions, emptyList(), kinds,
            ReadingStatsWindow(ReadingStatsPeriod.WEEK, now, zone))
        assertEquals(listOf(10 * minute, 20 * minute), week.dailyActivity.takeLast(2).map { it.duration })
        assertEquals(2, week.activeDays)
        assertEquals(2, week.currentStreak)
        assertEquals(week.totalDuration, week.hourlyActivity.sum())
    }

    @Test
    fun `fall DST counts the repeated hour without losing elapsed time`() {
        val start = time("2026-11-01T05:30:00Z")
        val end = time("2026-11-01T07:15:00Z")
        val stats = deriveReadingStatistics(listOf(session(1, start, end)), emptyList(),
            mapOf(1L to StatsContentKind.VIDEO),
            ReadingStatsWindow(ReadingStatsPeriod.DAY, end, TimeZone.of("America/New_York")))
        assertEquals(90 * minute, stats.hourlyActivity[1])
        assertEquals(15 * minute, stats.hourlyActivity[2])
        assertEquals(end - start, stats.dailyActivity.single().duration)
    }

    @Test
    fun `streak includes today or yesterday and stops at an inactive day`() {
        val today = time("2026-08-13T00:00:00Z")
        val day = 86_400_000L
        for ((offsets, expected) in listOf(listOf(0, 1, 2, 4) to 3, listOf(1, 2) to 2, listOf(2, 3) to 0)) {
            val sessions = offsets.map { session(1, today - it * day, today - it * day + minute) }
            val stats = deriveReadingStatistics(sessions, emptyList(), mapOf(1L to StatsContentKind.MANGA),
                ReadingStatsWindow(ReadingStatsPeriod.ALL, today + 12 * 60 * minute, TimeZone.UTC))
            assertEquals(expected, stats.currentStreak)
        }
    }

    @Test
    fun `reading duration is split across local hours`() {
        val start = time("2026-08-13T02:30:00Z")
        val end = time("2026-08-13T04:15:00Z")
        val stats = deriveReadingStatistics(listOf(session(1, start, end)), emptyList(),
            mapOf(1L to StatsContentKind.MANGA),
            ReadingStatsWindow(ReadingStatsPeriod.DAY, end, TimeZone.of("Asia/Shanghai")))
        assertEquals(30 * minute, stats.hourlyActivity[10])
        assertEquals(60 * minute, stats.hourlyActivity[11])
        assertEquals(15 * minute, stats.hourlyActivity[12])
        assertEquals(end - start, stats.hourlyActivity.sum())
    }

    @Test
    fun `checkpoints keep identity and pauses cannot accrue time twice`() {
        val tracker = ReadingSessionTracker()
        val position = ReadingSessionPosition(1, 0, 0, 0f)
        assertNull(tracker.update(position, 1000, true))
        tracker.update(position.copy(page = 2), 2000, true)
        val checkpoint = tracker.snapshot(6000)!!
        val finished = tracker.update(position, 8000, false)!!
        assertEquals(checkpoint.startedAt, finished.startedAt)
        assertEquals(2, finished.pages)
        assertEquals(7000, finished.endedAt - finished.startedAt)
        assertNull(tracker.finish(9000))
        tracker.update(position, 20_000, true)
        assertEquals(20_000, tracker.snapshot(25_000)!!.startedAt)
    }

    @Test
    fun `chapter switches close the old chapter before starting another`() {
        val tracker = ReadingSessionTracker()
        tracker.update(ReadingSessionPosition(1, 0, 0, 0f), 1000, true, 1)
        val finished = tracker.update(ReadingSessionPosition(2, 0, 0, 0.5f), 9000, true)!!
        assertEquals(1, finished.end.chapterId)
        assertEquals(2, finished.pages)
        assertEquals(2, tracker.snapshot(15_000)!!.end.chapterId)
    }
}
