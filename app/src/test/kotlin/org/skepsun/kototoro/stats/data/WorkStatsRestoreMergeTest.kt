package org.skepsun.kototoro.stats.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkStatsRestoreMergeTest {

	private fun stats(
		entityId: Long = 1L,
		anchorMangaId: Long = 100L,
		startedAt: Long = 1000L,
		duration: Long = 0L,
		pages: Int = 0,
	) = WorkStatsEntity(
		entityId = entityId,
		anchorMangaId = anchorMangaId,
		startedAt = startedAt,
		duration = duration,
		pages = pages,
	)

	@Test
	fun `more pages wins`() {
		val small = stats(pages = 3, duration = 999L)
		val large = stats(pages = 10, duration = 1L)

		assertEquals(10, mergeRestoredWorkStats(small, large).pages)
		assertEquals(10, mergeRestoredWorkStats(large, small).pages)
	}

	@Test
	fun `equal pages falls back to longer duration`() {
		val shortRead = stats(pages = 5, duration = 100L)
		val longRead = stats(pages = 5, duration = 800L)

		assertEquals(800L, mergeRestoredWorkStats(shortRead, longRead).duration)
		assertEquals(800L, mergeRestoredWorkStats(longRead, shortRead).duration)
	}

	@Test
	fun `result keeps the base key`() {
		val base = stats(entityId = 9L, startedAt = 1000L, pages = 1)
		val other = stats(entityId = 1L, startedAt = 1000L, pages = 50)

		val merged = mergeRestoredWorkStats(base, other)

		assertEquals(9L, merged.entityId)
		assertEquals(1000L, merged.startedAt)
	}
}
