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
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventEmitter
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkResolver

class FavouritesFeedCategoryIdsTest {

    private val favouritesDao = mockk<WorkFavouritesDao>()
    private val mangaDao = mockk<MangaDao>()
    private val workResolver = mockk<WorkResolver>()
    private val db = mockk<MangaDatabase> {
        every { getWorkFavouritesDao() } returns favouritesDao
        every { getMangaDao() } returns mangaDao
    }
    private val repository = FavouritesRepository(
        db = db,
        workResolver = workResolver,
        entityGraphRepository = mockk<EntityGraphRepository>(relaxed = true),
        workAggregateRepository = mockk<WorkAggregateRepository>(relaxed = true),
        settings = mockk<AppSettings>(relaxed = true),
        sourceTrackerEvents = mockk<SourceTrackerEventEmitter>(relaxed = true),
    )

    @Test
    fun `feed category index batch loads manga for a large library`() = runTest {
        val entries = (1L..2_000L).map { id ->
            WorkFavouriteEntity(
                entityId = id,
                categoryId = if (id % 2L == 0L) 2L else 1L,
                anchorMangaId = id,
                sortKey = 0,
                isPinned = false,
                createdAt = id,
                deletedAt = 0L,
                updatedAt = id,
            )
        }
        coEvery { favouritesDao.findActive() } returns entries
        coEvery { mangaDao.findEntitiesByIds(any()) } answers {
            firstArg<Collection<Long>>().map(::manga)
        }

        val result = repository.buildWorkFavouriteCategoryIdsByFeedKey()

        assertEquals(setOf(1L), result["source|/1"])
        assertEquals(setOf(2L), result["source|/2000"])
        assertEquals(2_000, result.size)
        coVerify(exactly = 1) { mangaDao.findEntitiesByIds(any()) }
        coVerify(exactly = 0) { workResolver.resolveManyByEntityIds(any()) }
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
