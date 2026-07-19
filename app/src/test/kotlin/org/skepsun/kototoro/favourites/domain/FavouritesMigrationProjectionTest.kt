package org.skepsun.kototoro.favourites.domain

import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FavouritesMigrationProjectionTest {

	@Test
	fun `migration keeps every available legacy projection`() {
		val entries = (1L..7L).map(::favourite)

		assertEquals(
			(1L..7L).toList(),
			selectLegacyFavouriteMangaIds(entries, (1L..7L).toSet()),
		)
	}

	@Test
	fun `migration does not invent missing projections`() {
		val entries = (1L..7L).map(::favourite)

		assertEquals(
			listOf(1L, 2L, 3L, 4L),
			selectLegacyFavouriteMangaIds(entries, (1L..4L).toSet()),
		)
	}

	@Test
	fun `migration skips deleted legacy projections`() {
		val entries = listOf(favourite(1L), favourite(2L, deletedAt = 10L))

		assertEquals(
			listOf(1L),
			selectLegacyFavouriteMangaIds(entries, setOf(1L, 2L)),
		)
	}

	private fun favourite(mangaId: Long, deletedAt: Long = 0L) = FavouriteEntity(
		mangaId = mangaId,
		categoryId = 1L,
		sortKey = mangaId.toInt(),
		isPinned = false,
		createdAt = mangaId,
		deletedAt = deletedAt,
		updatedAt = mangaId,
	)
}
