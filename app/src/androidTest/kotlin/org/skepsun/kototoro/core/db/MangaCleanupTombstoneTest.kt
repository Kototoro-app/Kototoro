package org.skepsun.kototoro.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed

/**
 * Deleted favourites/history are soft-deleted tombstones that cascade with their manga row.
 * Sync merges rely on them to win over stale remote rows, so manga cleanup must keep any
 * manga that still owns a tombstone; otherwise the next sync resurrects the deleted item.
 */
@RunWith(AndroidJUnit4::class)
class MangaCleanupTombstoneTest {

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        val sql = db.openHelper.writableDatabase
        FavouriteLibrarySeed.insertCategory(sql, id = 1, title = "Default")
        FavouriteLibrarySeed.insertManga(sql, id = FAVOURITE_TOMBSTONE, title = "Removed favourite")
        FavouriteLibrarySeed.insertFavourite(
            sql,
            mangaId = FAVOURITE_TOMBSTONE,
            categoryId = 1,
            updatedAt = 2_000,
            deletedAt = 2_000,
        )
        FavouriteLibrarySeed.insertManga(sql, id = HISTORY_TOMBSTONE, title = "Removed history")
        FavouriteLibrarySeed.insertHistory(sql, mangaId = HISTORY_TOMBSTONE, percent = 0.5f, updatedAt = 2_000, deletedAt = 2_000)
        FavouriteLibrarySeed.insertManga(sql, id = UNREFERENCED, title = "Unreferenced")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncResidueCleanupKeepsTombstones() = runTest {
        val deleted = db.getMangaDao().cleanupSyncResidue()

        assertEquals(1, deleted)
        assertTombstonesSurvived()
    }

    @Test
    fun databaseCleanupKeepsTombstones() = runTest {
        db.getMangaDao().cleanup(emptySet())

        assertTombstonesSurvived()
    }

    private suspend fun assertTombstonesSurvived() {
        val mangaDao = db.getMangaDao()
        assertFalse(mangaDao.contains(UNREFERENCED))
        assertTrue(mangaDao.contains(FAVOURITE_TOMBSTONE))
        assertTrue(mangaDao.contains(HISTORY_TOMBSTONE))
        val favourite = db.getFavouritesDao().findAllEntriesIncludingDeleted().single()
        assertEquals(FAVOURITE_TOMBSTONE, favourite.mangaId)
        assertEquals(2_000L, favourite.deletedAt)
        val history = db.getHistoryDao().findAllEntriesIncludingDeleted().single()
        assertEquals(HISTORY_TOMBSTONE, history.mangaId)
        assertEquals(2_000L, history.deletedAt)
    }

    private companion object {
        const val FAVOURITE_TOMBSTONE = 1L
        const val HISTORY_TOMBSTONE = 2L
        const val UNREFERENCED = 3L
    }
}
