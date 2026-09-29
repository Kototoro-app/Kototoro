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
 * Remote manga rows without url and public url cannot be opened and never match their own
 * copy on sync, which kept adding copies carrying other works' favourites and tracks.
 */
@RunWith(AndroidJUnit4::class)
class UnidentifiableMangaCleanupTest {

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        val sql = db.openHelper.writableDatabase
        FavouriteLibrarySeed.insertCategory(sql, id = 1, title = "Default")
        insertManga(URL_LESS, url = "", publicUrl = "", source = "RAWKUMA")
        FavouriteLibrarySeed.insertFavourite(sql, mangaId = URL_LESS, categoryId = 1)
        insertManga(WITH_URL, url = "/manga/a/", publicUrl = "", source = "RAWKUMA")
        FavouriteLibrarySeed.insertFavourite(sql, mangaId = WITH_URL, categoryId = 1)
        insertManga(WITH_PUBLIC_URL, url = "", publicUrl = "https://example.org/b", source = "RAWKUMA")
        insertManga(LOCAL, url = "", publicUrl = "", source = "LOCAL")
        insertManga(DOWNLOADED, url = "", publicUrl = "", source = "RAWKUMA")
        FavouriteLibrarySeed.insertDownloaded(sql, mangaId = DOWNLOADED)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deletesOnlyUrlLessRemoteManga() = runTest {
        val deleted = db.getMangaDao().deleteUnidentifiableRemote()

        assertEquals(1, deleted)
        val dao = db.getMangaDao()
        assertFalse(dao.contains(URL_LESS))
        assertTrue(dao.contains(WITH_URL))
        assertTrue(dao.contains(WITH_PUBLIC_URL))
        assertTrue(dao.contains(LOCAL))
        assertTrue(dao.contains(DOWNLOADED))
        assertEquals(listOf(WITH_URL), db.getFavouritesDao().findAllEntriesIncludingDeleted().map { it.mangaId })
    }

    private fun insertManga(id: Long, url: String, publicUrl: String, source: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO manga (
                manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
                cover_url, large_cover_url, state, author, source, description, content_type
            ) VALUES (?, 'Title', NULL, ?, ?, -1, 0, NULL, '', NULL, NULL, NULL, ?, NULL, 'MANGA')
            """.trimIndent(),
            arrayOf<Any?>(id, url, publicUrl, source),
        )
    }

    private companion object {
        const val URL_LESS = -9_220_559_807_833_555_131L
        const val WITH_URL = 2L
        const val WITH_PUBLIC_URL = 3L
        const val LOCAL = 4L
        const val DOWNLOADED = 5L
    }
}
