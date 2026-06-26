package org.skepsun.kototoro.favourites.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkFavouriteRestoreMergeTest {

	private fun fav(
		entityId: Long = 1L,
		categoryId: Long = 10L,
		anchorMangaId: Long? = 100L,
		sortKey: Int = 0,
		isPinned: Boolean = false,
		createdAt: Long = 0L,
		deletedAt: Long = 0L,
		updatedAt: Long = 0L,
	) = WorkFavouriteEntity(
		entityId = entityId,
		categoryId = categoryId,
		anchorMangaId = anchorMangaId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = deletedAt,
		updatedAt = updatedAt,
	)

	@Test
	fun `newest updated_at wins active state and anchor`() {
		val older = fav(anchorMangaId = 100L, deletedAt = 0L, updatedAt = 100L)
		val newerDeleted = fav(anchorMangaId = 200L, deletedAt = 500L, updatedAt = 500L)

		val merged = mergeRestoredWorkFavourites(older, newerDeleted)

		assertEquals(500L, merged.deletedAt)
		assertEquals(200L, merged.anchorMangaId)
		assertEquals(500L, merged.updatedAt)
	}

	@Test
	fun `created_at takes the earliest`() {
		val a = fav(createdAt = 300L, updatedAt = 400L)
		val b = fav(createdAt = 100L, updatedAt = 200L)

		assertEquals(100L, mergeRestoredWorkFavourites(a, b).createdAt)
		assertEquals(100L, mergeRestoredWorkFavourites(b, a).createdAt)
	}

	@Test
	fun `pinned is OR'd among active rows`() {
		val activePinned = fav(isPinned = true, deletedAt = 0L, updatedAt = 100L)
		val activePlain = fav(isPinned = false, deletedAt = 0L, updatedAt = 200L)

		assertTrue(mergeRestoredWorkFavourites(activePlain, activePinned).isPinned)
	}

	@Test
	fun `pinned from a deleted row is ignored`() {
		val deletedPinned = fav(isPinned = true, deletedAt = 500L, updatedAt = 500L)
		val activePlain = fav(isPinned = false, deletedAt = 0L, updatedAt = 100L)

		assertFalse(mergeRestoredWorkFavourites(activePlain, deletedPinned).isPinned)
	}

	@Test
	fun `result always keeps the base key`() {
		val base = fav(entityId = 9L, categoryId = 3L, updatedAt = 10L)
		val other = fav(entityId = 1L, categoryId = 3L, updatedAt = 999L)

		val merged = mergeRestoredWorkFavourites(base, other)

		assertEquals(9L, merged.entityId)
		assertEquals(3L, merged.categoryId)
	}
}
