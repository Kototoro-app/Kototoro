package org.skepsun.kototoro.tracker.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tracker.data.TrackEntity

class TrackRulesTest {

	private val ids = listOf(1L, 2L, 3L, 4L)

	@Test
	fun `refreshing every chapter date does not report the list as new`() {
		data class Chapter(val id: Long, val uploadedAt: Long)
		val chapters = (1L..32L).map { Chapter(it, uploadedAt = 100) }
		var track = TrackEntity(7, lastChapterId = 32, newChapters = 2, lastCheckTime = 1,
			lastChapterDate = 100, lastResult = TrackEntity.RESULT_HAS_UPDATE, lastError = null)
		repeat(20) { check ->
			val refreshed = chapters.map { it.copy(uploadedAt = 200L + check) }
			val comparison = compareTrackedChapters(refreshed, Chapter::id, track.lastChapterId)
			assertTrue(comparison.isValid)
			assertTrue(comparison.newChapters.isEmpty())
			track = track.afterSuccessfulCheck(7, 32, comparison.newChapters.size, comparison.isValid,
				lastChapterDate = refreshed.last().uploadedAt, now = 300L + check)
			assertEquals(2, track.newChapters, "repeated date changes must not accumulate chapters")
		}
		val appended = chapters.map { it.copy(uploadedAt = 500) } + Chapter(33, uploadedAt = 500)
		assertEquals(listOf(33L), compareTrackedChapters(appended, Chapter::id, track.lastChapterId)
			.newChapters.map(Chapter::id))
	}

	@Test
	fun `replacing the whole chapter identity set silently resets the anchor`() {
		val comparison = compareTrackedChapters((101L..132L).toList(), { it }, lastChapterId = 32)
		assertFalse(comparison.isValid)
		assertTrue(comparison.newChapters.isEmpty())
	}

	@Test
	fun `comparison follows Android's tracker cases`() {
		assertEquals(TrackComparison(emptyList<Long>(), isValid = false), compareTrackedChapters(ids, { it }, 0L))
		assertEquals(TrackComparison(emptyList<Long>(), isValid = true), compareTrackedChapters(ids, { it }, 4L))
		assertEquals(TrackComparison(listOf(3L, 4L), isValid = true), compareTrackedChapters(ids, { it }, 2L))
		// The last known chapter vanished: re-anchor without reporting every chapter.
		assertEquals(TrackComparison(emptyList<Long>(), isValid = false), compareTrackedChapters(ids, { it }, 99L))
	}

	@Test
	fun `row updates keep pending counters and record failures`() {
		val pending = TrackEntity(7, lastChapterId = 2, newChapters = 3, lastCheckTime = 1, lastChapterDate = 10,
			lastResult = TrackEntity.RESULT_HAS_UPDATE, lastError = null)
		val none = pending.afterSuccessfulCheck(7, 4, newChapterCount = 0, isValid = true, lastChapterDate = 0, now = 50)
		assertEquals(3, none.newChapters, "no new chapters keeps the unread counter")
		assertEquals(10, none.lastChapterDate, "an unknown date keeps the previous one")
		assertEquals(TrackEntity.RESULT_NO_UPDATE, none.lastResult)
		val found = pending.afterSuccessfulCheck(7, 6, newChapterCount = 2, isValid = true, lastChapterDate = 60, now = 50)
		assertEquals(2, found.newChapters)
		assertEquals(TrackEntity.RESULT_HAS_UPDATE, found.lastResult)
		assertEquals(0, pending.afterSuccessfulCheck(7, 6, 0, isValid = false, lastChapterDate = 0, now = 50).newChapters)
		val failed = pending.afterFailedCheck("offline", now = 70)
		assertEquals(3, failed.newChapters)
		assertEquals(2, failed.lastChapterId)
		assertEquals(TrackEntity.RESULT_FAILED, failed.lastResult)
		assertEquals("offline", failed.lastError)
	}

	@Test
	fun `scheduler interval matches Android's formula`() {
		assertEquals(18, trackerCheckIntervalHours(trackCount = 0, batchSize = 46, frequency = 1f))
		assertEquals(9, trackerCheckIntervalHours(trackCount = 92, batchSize = 46, frequency = 1f))
		// 100 tracks = 3 runs; Android divides 18 / 3 as integers before applying the frequency.
		assertEquals(6, trackerCheckIntervalHours(trackCount = 100, batchSize = 46, frequency = 1f))
		assertEquals(4, trackerCheckIntervalHours(trackCount = 184, batchSize = 46, frequency = 1f), "18 / 4 = 4")
		assertEquals(45, trackerCheckIntervalHours(trackCount = 1, batchSize = Int.MAX_VALUE, frequency = 0.4f))
		assertEquals(2, trackerCheckIntervalHours(trackCount = 1000, batchSize = 46, frequency = 2f), "never under 2 h")
		assertEquals(null, trackerCheckIntervalHours(trackCount = 1, batchSize = 46, frequency = -1f), "manual")
	}

	@Test
	fun `dates and logs match Android's format`() {
		assertEquals(30, trackedLastChapterDate(listOf(20, 30), lastChapterDate = 5, now = 99))
		assertEquals(99, trackedLastChapterDate(listOf(0), lastChapterDate = 5, now = 99), "undated new chapters use now")
		assertEquals(5, trackedLastChapterDate(emptyList(), lastChapterDate = 5, now = 99))
		assertEquals("a\nb", trackLogChapters(listOf("a", "b")))
		assertTrue(isEmptyTrack(0))
		assertFalse(isEmptyTrack(1))
	}
}
