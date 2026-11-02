package org.skepsun.kototoro.desktop.runtime

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.library.SourceLibrary
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind
import java.nio.file.Path

/**
 * Android counts reading time in a view-model collector and closes the stop in `onPause`; this host has no reader
 * lifetime, so the library holds the open stop instead and the reader screen opens and closes it. These tests pin the
 * two tables Android writes — the per-work statistics row and the reading session — and the dashboard derived from them.
 */
class DesktopReadingStatsTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_42", "zh", "MANGA")
    private val novelSource = SourceRef("MIHON_43", "zh", "NOVEL")
    private val chapters = listOf(chapter(1, "A"), chapter(2, "A"))
    private val content = SourceContent(
        Long.MAX_VALUE, "统计作品", setOf("另一标题"), "/work", "https://fixture.invalid/work", .8f, null, null,
        setOf(SourceTag("标签", "tag", source)), "ONGOING", setOf("作者"), source,
        description = "说明", chapters = chapters,
    )
    private val other = content.copy(id = Long.MAX_VALUE - 1, title = "另一部作品", chapters = listOf(chapter(3, "A")))

    /** Android reads the wall clock; the tests need to move it, so the library takes its clock as a parameter. */
    private val start = 1_760_000_000_000L
    private var now = start

    private fun library(runtime: DesktopRuntime) = SourceLibrary(
        runtime.database,
        clock = { now },
        sourceLookup = { name -> when (name) { source.name -> source; novelSource.name -> novelSource; else -> null } },
    )

    /** The rows the dashboard cannot distinguish from the legacy ones, read straight from their table. */
    private suspend fun sessionRows(runtime: DesktopRuntime) = runtime.database.useReaderConnection { connection ->
        connection.deferredTransaction {
            runtime.database.getReadingRecordDao().findOverlapping(0, Long.MAX_VALUE)
        }
    }

    /** This week's dashboard at the test clock, optionally narrowed to one content kind like Android's chip row. */
    private suspend fun SourceLibrary.stats(kind: StatsContentKind = StatsContentKind.ALL) =
        readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now, kind = kind)

    @Test
    fun `an open reader writes Android's statistics row and reading session`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 30_000
            library.recordPage(content, chapters[0], 0, 10)
            now += 5_000
            library.recordPage(content, chapters[0], 1, 10)
            // A page of another work while this stop is open is not this stop's time (Android's collector is per work).
            library.recordPage(other, other.chapters!!.single(), 0, 3)
            now += 10_000
            library.finishReadingSession()

            val stats = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertEquals(45_000L, stats.totalDuration)
            assertEquals(1, stats.sessionCount)
            assertEquals(1, stats.activeDays)
            val work = stats.works.single()
            assertEquals(content.id, work.content.id)
            assertEquals(StatsContentKind.MANGA, work.kind)
            // The page counter mirrors Android's collector: the first tick only opens the row, each turned page adds one.
            assertEquals(1, work.units)
            assertEquals(45_000L, work.duration)
            // The other work was opened without a session of its own.
            assertTrue(stats.works.none { it.content.id == other.id })
            val session = sessionRows(runtime).single()
            assertEquals(start, session.startAt)
            assertEquals(start + 45_000, session.endAt)
            assertEquals(chapters[0].id, session.startChapterId)
            assertEquals(1, session.endPage)
        }
    }

    @Test
    fun `a page recorded without an open session never reaches the statistics`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            // Mark-as-read and batch history writes go through the same seam; they are not reading time.
            library.recordPage(content, chapters[0], 0, 10)
            library.finishReadingSession()
            val stats = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertTrue(stats.works.isEmpty())
            assertEquals(0L, stats.totalDuration)
            assertEquals(0, stats.sessionCount)
        }
    }

    @Test
    fun `a stop under five seconds keeps its statistics row but no session`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 2_000
            library.recordPage(content, chapters[0], 0, 10)
            library.finishReadingSession()

            // Android's ReadingRecordRepository drops sessions shorter than MIN_SESSION_DURATION_MS.
            val stats = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertEquals(2_000L, stats.totalDuration)
            assertTrue(sessionRows(runtime).isEmpty())
            assertEquals(content.id, stats.works.single().content.id)
        }
    }

    @Test
    fun `abandoning a stop adds no session even though the pages it recorded stay`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 20_000
            library.recordPage(content, chapters[0], 0, 10)
            // The stats row is written per state change, exactly like Android's collector; abandoning only drops the
            // session the reader would otherwise close on exit.
            library.abandonReadingSession()
            library.finishReadingSession()

            val stats = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertTrue(sessionRows(runtime).isEmpty())
            assertEquals(20_000L, stats.totalDuration)
            assertEquals(1, stats.works.size)
        }
    }

    @Test
    fun `clearing statistics empties both tables and keeps the reading history`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 30_000
            library.recordPage(content, chapters[0], 2, 10)
            library.finishReadingSession()
            assertEquals(1, library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now).sessionCount)

            library.clearReadingStatistics()
            val stats = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertTrue(stats.works.isEmpty())
            assertEquals(0, stats.sessionCount)
            // Android's "clear statistics" leaves the history alone.
            assertEquals(chapters[0].id, library.progress(content.id)?.chapterId)
        }
    }

    @Test
    fun `periods fold the same rows into their own windows`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 30_000
            library.recordPage(content, chapters[0], 0, 10)
            library.finishReadingSession()
            val day = library.readingStatistics(ReadingStatsPeriod.DAY, TimeZone.UTC, now)
            val week = library.readingStatistics(ReadingStatsPeriod.WEEK, TimeZone.UTC, now)
            assertEquals(day.totalDuration, week.totalDuration)
            assertEquals(1, day.dailyActivity.size)
            assertEquals(7, week.dailyActivity.size)
            assertEquals(90, library.readingStatistics(ReadingStatsPeriod.ALL, TimeZone.UTC, now).dailyActivity.size)
        }
    }

    @Test
    fun `the kind filter keeps only the works of the picked content kind`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            val novel = content.copy(id = Long.MAX_VALUE - 2, title = "小说作品", source = novelSource,
                chapters = listOf(SourceChapter(4, "第一章", 1f, 0, "/chapter/4", "组", Long.MAX_VALUE, "A",
                    novelSource)))
            library.beginReadingSession()
            now += 20_000
            library.recordPage(content, chapters[0], 0, 10)
            library.finishReadingSession()
            library.beginReadingSession()
            now += 40_000
            library.recordPage(novel, novel.chapters!!.single(), 0, 5)
            library.finishReadingSession()

            val all = library.stats()
            assertEquals(setOf(StatsContentKind.MANGA, StatsContentKind.NOVEL), all.works.map { it.kind }.toSet())
            assertEquals(60_000L, all.totalDuration)

            // Android filters the contents before deriving, so the day/hour series and the totals follow the pick.
            val manga = library.stats(StatsContentKind.MANGA)
            assertEquals(listOf(content.id), manga.works.map { it.content.id })
            assertEquals(20_000L, manga.totalDuration)
            assertEquals(1, manga.sessionCount)
            val novels = library.stats(StatsContentKind.NOVEL)
            assertEquals(listOf(novel.id), novels.works.map { it.content.id })
            assertEquals(40_000L, novels.totalDuration)
            assertTrue(library.stats(StatsContentKind.VIDEO).works.isEmpty())
        }
    }

    @Test
    fun `one work's own statistics match the dashboard row it stands for`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.beginReadingSession()
            now += 20_000
            library.recordPage(content, chapters[0], 0, 10)
            now += 10_000
            library.recordPage(content, chapters[0], 1, 10)
            library.finishReadingSession()
            now += 60 * 60_000
            library.beginReadingSession()
            now += 15_000
            library.recordPage(content, chapters[1], 0, 5)
            library.finishReadingSession()
            library.beginReadingSession()
            now += 60_000
            library.recordPage(other, other.chapters!!.single(), 0, 3)
            library.finishReadingSession()

            val sheet = library.contentReadingStats(content.id, TimeZone.UTC, now)
            assertEquals(StatsContentKind.MANGA, sheet.kind)
            assertEquals(45_000L, sheet.totalDuration)
            assertEquals(2, sheet.sessionCount)
            // Same page counter as the dashboard: one tick opens the row, each turned page adds one.
            assertEquals(1, sheet.units)
            // Android reads the first activity out of the derived record, which is the start of its first interval.
            assertEquals(start, sheet.firstActivityAt)
            // The "all" window: ninety days of days, only this work's two stops filled in (the other work's rows stay out).
            assertEquals(90, sheet.dailyActivity.size)
            assertEquals(45_000L, sheet.dailyActivity.sumOf { it.duration })
            // A work with no rows of its own still opens a sheet, all zeros, like Android's empty snapshot.
            val empty = library.contentReadingStats(content.id - 42, TimeZone.UTC, now)
            assertEquals(0L, empty.totalDuration)
            assertEquals(0, empty.sessionCount)
            assertEquals(0, empty.units)
            assertNull(empty.firstActivityAt)
            assertTrue(empty.dailyActivity.all { it.duration == 0L })
        }
    }

    private fun chapter(id: Long, branch: String) = SourceChapter(id, "章节 $id", 1f, 0, "/chapter/$id", "组",
        Long.MAX_VALUE, branch, source)
}
