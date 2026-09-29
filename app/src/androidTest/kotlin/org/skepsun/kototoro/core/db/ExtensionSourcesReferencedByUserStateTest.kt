package org.skepsun.kototoro.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed

/**
 * Missing-extension recommendations must follow the user's live library: only Mihon/Aniyomi
 * sources that still back an active favourite or history entry count. Deleted (tombstoned)
 * entries and plain browse-cache rows must not keep recommending an extension.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionSourcesReferencedByUserStateTest {

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        val sql = db.openHelper.writableDatabase
        FavouriteLibrarySeed.insertCategory(sql, id = 1, title = "Default")

        FavouriteLibrarySeed.insertManga(sql, id = 1, title = "Active favourite", source = "MIHON_1")
        FavouriteLibrarySeed.insertFavourite(sql, mangaId = 1, categoryId = 1)

        FavouriteLibrarySeed.insertManga(sql, id = 2, title = "Active history", source = "ANIYOMI_2")
        FavouriteLibrarySeed.insertHistory(sql, mangaId = 2, percent = 0.5f, updatedAt = 1_000)

        FavouriteLibrarySeed.insertManga(sql, id = 3, title = "Deleted favourite", source = "MIHON_3")
        FavouriteLibrarySeed.insertFavourite(sql, mangaId = 3, categoryId = 1, updatedAt = 2_000, deletedAt = 2_000)

        FavouriteLibrarySeed.insertManga(sql, id = 4, title = "Deleted history", source = "MIHON_4")
        FavouriteLibrarySeed.insertHistory(sql, mangaId = 4, percent = 0.5f, updatedAt = 2_000, deletedAt = 2_000)

        FavouriteLibrarySeed.insertManga(sql, id = 5, title = "Browse cache only", source = "MIHON_5")

        FavouriteLibrarySeed.insertManga(sql, id = 6, title = "Native source favourite", source = "MANGADEX")
        FavouriteLibrarySeed.insertFavourite(sql, mangaId = 6, categoryId = 1)

        FavouriteLibrarySeed.insertManga(sql, id = 7, title = "Second work of same source", source = "MIHON_1")
        FavouriteLibrarySeed.insertHistory(sql, mangaId = 7, percent = 0.1f, updatedAt = 1_000)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun onlyActiveFavouriteOrHistoryExtensionSourcesAreReported() = runTest {
        val sources = db.getMangaDao().observeExtensionSourcesReferencedByUserState().first()

        assertEquals(listOf("ANIYOMI_2", "MIHON_1"), sources.sorted())
    }

    @Test
    fun deletingTheLastFavouriteDropsTheSource() = runTest {
        db.openHelper.writableDatabase.execSQL("UPDATE favourites SET deleted_at = 3000 WHERE manga_id = 1")
        db.openHelper.writableDatabase.execSQL("UPDATE history SET deleted_at = 3000 WHERE manga_id = 7")

        val sources = db.getMangaDao().observeExtensionSourcesReferencedByUserState().first()

        assertEquals(listOf("ANIYOMI_2"), sources)
    }
}
