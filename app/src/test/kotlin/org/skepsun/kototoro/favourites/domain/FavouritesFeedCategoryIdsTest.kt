package org.skepsun.kototoro.favourites.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.parser.StoredContentIdentityResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.FavouritesDao
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventEmitter

class FavouritesFeedCategoryIdsTest {

    private val favouritesDao = mockk<FavouritesDao>()
    private val mangaDao = mockk<MangaDao>()
    private val db = mockk<MangaDatabase> {
        every { getFavouritesDao() } returns favouritesDao
        every { getMangaDao() } returns mangaDao
    }
    private val repository = FavouritesRepository(
        db = db,
        settings = mockk<AppSettings>(relaxed = true),
        sourceTrackerEvents = mockk<SourceTrackerEventEmitter>(relaxed = true),
        storedContentIdentityResolver = mockk<StoredContentIdentityResolver>(relaxed = true),
    )

    @Test
    fun `feed category index batch loads manga for a large library`() = runTest {
        val entries = (1L..2_000L).map { id ->
            FavouriteEntity(
                mangaId = id,
                categoryId = if (id % 2L == 0L) 2L else 1L,
                sortKey = 0,
                isPinned = false,
                createdAt = id,
                deletedAt = 0L,
                updatedAt = id,
            )
        }
        coEvery { favouritesDao.findAllActiveEntries() } returns entries
        coEvery { mangaDao.findEntitiesByIds(any()) } answers {
            firstArg<Collection<Long>>().map(::manga)
        }

        val result = repository.buildFavouriteCategoryIdsByFeedKey()

        assertEquals(setOf(1L), result["source|/1"])
        assertEquals(setOf(2L), result["source|/2000"])
        assertEquals(setOf(1L), result["manga:1"])
        assertEquals(4_000, result.size)
        coVerify(exactly = 1) { mangaDao.findEntitiesByIds(any()) }
    }

    private fun manga(id: Long) = MangaEntity(
        id = id,
        title = "Title $id",
        altTitles = null,
        url = "/$id",
        publicUrl = "",
        rating = 0f,
        isNsfw = false,
        contentRating = null,
        coverUrl = "",
        largeCoverUrl = null,
        state = null,
        authors = null,
        source = "source",
        description = null,
        contentType = "MANGA",
        sourceData = null,
    )
}
