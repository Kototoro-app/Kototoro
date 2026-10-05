package org.skepsun.kototoro.tracker.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tracker.data.TrackEntity

class TrackRulesTest {

	private val ids = listOf(1L, 2L, 3L, 4L)

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
