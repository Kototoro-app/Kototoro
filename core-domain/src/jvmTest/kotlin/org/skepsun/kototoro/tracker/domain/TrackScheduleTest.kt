package org.skepsun.kototoro.tracker.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

class TrackScheduleTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val start = 18_262 * day // 2020-01-01

    private fun every(count: Int, step: Long) = (1..count).map { start + it * step }

    @Test
    fun `the interval follows Mihon's FetchInterval`() {
        assertEquals(7, fetchIntervalDays(List(50) { start + day }, emptyList(), 50), "too few distinct days")
        assertEquals(7, fetchIntervalDays(List(50) { 0L }, emptyList(), 50), "no dates at all")
        assertEquals(1, fetchIntervalDays(every(20, day), emptyList(), 20))
        assertEquals(2, fetchIntervalDays(every(20, 2 * day), emptyList(), 20))
        assertEquals(1, fetchIntervalDays(every(20, 15 * hour), emptyList(), 20), "less than a day apart")
        assertEquals(2, fetchIntervalDays(every(20, 43 * hour), emptyList(), 20), "just below two days")
        // Recent chapters decide: weekly long ago, daily lately.
        val old = every(5, 7 * day)
        val recent = (1..10).map { old.last() + it * day }
        assertEquals(1, fetchIntervalDays(old + recent, emptyList(), 15))
        // A short list looks at its last three release days only.
        val first = every(3, 7 * day)
        val later = (1..3).map { first.last() + 365 * day + it * 7 * day }
        assertEquals(7, fetchIntervalDays(first + later, emptyList(), 6))
        assertEquals(28, fetchIntervalDays(every(5, 60 * day), emptyList(), 5), "capped at four weeks")
    }

    @Test
    fun `without upload dates the days updates were discovered set the interval`() {
        assertEquals(3, fetchIntervalDays(List(10) { 0L }, every(6, 3 * day), 10))
    }

    @Test
    fun `the next check lands on the release cycle and is never more than a week away`() {
        val released = start
        // Released every 2 days; checked a day after the release: due on the next release day.
        assertEquals(start + 2 * day, nextCheckAfterSuccess(released, 2, now = start + day))
        // A monthly series is still checked within a week.
        assertEquals(start + day + 7 * day, nextCheckAfterSuccess(released, 28, now = start + day))
        // Ten silent daily cycles double the interval.
        val silent = nextCheckAfterSuccess(released, 1, now = start + 12 * day)
        assertEquals(start + 14 * day, silent)
    }

    @Test
    fun `failures push a work back 1, 2, 4 then 7 days`() {
        assertEquals(listOf(1L, 2L, 4L, 7L, 7L), (1..5).map { nextCheckAfterFailure(it, now = 0L) / day })
    }

    @Test
    fun `a work is due from a day before its time and without a schedule`() {
        assertTrue(isTrackDue(0L, now = start))
        assertTrue(isTrackDue(start + 20 * hour, now = start))
        assertFalse(isTrackDue(start + 2 * day, now = start))
    }

    @Test
    fun `background batches leave out works that are not due`() {
        val queue = listOf("a1" to 0L, "a2" to start + 3 * day, "b1" to start)
        assertEquals(listOf("a1", "b1"), selectTrackBatch(queue, { it.first.take(1) }, 10, emptyMap(), emptySet(),
            now = start, due = { isTrackDue(it.second, start) }).map { it.first })
    }

    @Test
    fun `checks record the schedule and failures push it back`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<MangaDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            db.getMangaDao().upsert(MangaEntity(
                id = 1, title = "Work", altTitles = null, url = "/w", publicUrl = "https://example.org/w",
                rating = 0f, isNsfw = false, contentRating = null, coverUrl = "", largeCoverUrl = null,
                state = null, authors = null, source = "REMOTE",
            ))
            db.getTracksDao().upsert(TrackEntity(1, 0, 0, 0, 0, TrackEntity.RESULT_NONE, null))
            db.getChaptersDao().replaceAll(1, (1..10).map {
                ChapterEntity(it.toLong(), 1, "Chapter $it", it.toFloat(), 0, "/c/$it", null, start + it * 3 * day,
                    null, "REMOTE", it)
            })
            val now = start + 31 * day

            db.updateTrackSchedule(1, succeeded = true, now = now)
            val scheduled = db.getTrackScheduleDao().find(1)!!
            assertEquals(3, scheduled.fetchInterval)
            assertEquals(start + 33 * day, scheduled.nextCheckTime)
            assertEquals(0, scheduled.failCount)

            db.updateTrackSchedule(1, succeeded = false, now = now)
            db.updateTrackSchedule(1, succeeded = false, now = now)
            val failing = db.getTrackScheduleDao().find(1)!!
            assertEquals(2, failing.failCount)
            assertEquals(3, failing.fetchInterval, "the release pace is kept")
            assertEquals(now + 2 * day, failing.nextCheckTime)

            db.getTracksDao().delete(1)
            db.getTrackScheduleDao().gc()
            assertEquals(null, db.getTrackScheduleDao().find(1))
        } finally {
            db.close()
        }
    }
}
