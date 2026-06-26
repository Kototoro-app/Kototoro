package org.skepsun.kototoro.history.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkHistoryRestoreMergeTest {

	private fun history(
		entityId: Long = 1L,
		anchorMangaId: Long = 100L,
		createdAt: Long = 0L,
		updatedAt: Long = 0L,
		percent: Float = 0f,
	) = WorkHistoryEntity(
		entityId = entityId,
		anchorMangaId = anchorMangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = 0L,
		page = 0,
		scroll = 0f,
		percent = percent,
		deletedAt = 0L,
		chaptersCount = 0,
		parentChapterId = null,
	)

	@Test
	fun `newest updated_at wins position and anchor`() {
		val older = history(anchorMangaId = 100L, updatedAt = 100L, percent = 0.2f)
		val newer = history(anchorMangaId = 200L, updatedAt = 500L, percent = 0.8f)

		val merged = mergeRestoredWorkHistory(older, newer)

		assertEquals(200L, merged.anchorMangaId)
		assertEquals(0.8f, merged.percent)
		assertEquals(500L, merged.updatedAt)
	}

	@Test
	fun `created_at takes the earliest`() {
		val a = history(createdAt = 300L, updatedAt = 400L)
		val b = history(createdAt = 100L, updatedAt = 200L)

		assertEquals(100L, mergeRestoredWorkHistory(a, b).createdAt)
		assertEquals(100L, mergeRestoredWorkHistory(b, a).createdAt)
	}

	@Test
	fun `result keeps the base entity id`() {
		val base = history(entityId = 9L, updatedAt = 10L)
		val other = history(entityId = 1L, updatedAt = 999L)

		assertEquals(9L, mergeRestoredWorkHistory(base, other).entityId)
	}
}
