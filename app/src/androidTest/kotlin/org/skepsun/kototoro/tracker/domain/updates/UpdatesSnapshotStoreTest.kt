package org.skepsun.kototoro.tracker.domain.updates

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed
import javax.inject.Inject

/**
 * Interface-level tests for [UpdatesSnapshotStore]: one group per tracked manga
 * with pending new chapters (manga-keyed).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UpdatesSnapshotStoreTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var store: UpdatesSnapshotStore

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys = OFF")
        store = UpdatesSnapshotStore(db, sourceGroupManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertTrack(mangaId: Long, newChapters: Int, lastChapterDate: Long = 0, lastCheckTime: Long = 0) {
        FavouriteLibrarySeed.insertTrack(sql, mangaId, newChapters, lastChapterDate, lastCheckTime)
    }

    @Test
    fun eachTrackedMangaIsItsOwnGroup() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "First")
        FavouriteLibrarySeed.insertManga(sql, 102, "Second")
        insertTrack(mangaId = 101, newChapters = 2, lastChapterDate = 300, lastCheckTime = 350)
        insertTrack(mangaId = 102, newChapters = 5, lastChapterDate = 900, lastCheckTime = 950)

        val groups = store.observe().first().groups.associateBy { it.entityId }

        assertEquals(setOf(101L, 102L), groups.keys)
        val second = groups.getValue(102L)
        assertEquals(5, second.totalNewChapters)
        assertEquals(900L, second.lastChapterDate)
        assertEquals(listOf(102L), second.mangaIds)
        assertEquals(102L, second.displayMangaId)
        assertEquals(102L, second.preferredLocalMangaId)
        assertEquals("Second", second.title)
    }

    @Test
    fun uiIdEncodesMangaAndContentType() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertTrack(mangaId = 101, newChapters = 1)

        val group = store.observe().first().groups.single()

        assertEquals(-((101L shl 8) or (group.displayContentTypeOrdinal + 1).toLong()), group.uiId)
    }

    @Test
    fun groupsStayInStableTrackOrderForTheDeriversTieBreak() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Older")
        FavouriteLibrarySeed.insertManga(sql, 201, "Newer")
        insertTrack(mangaId = 101, newChapters = 3, lastChapterDate = 100, lastCheckTime = 150)
        insertTrack(mangaId = 201, newChapters = 1, lastChapterDate = 900, lastCheckTime = 950)

        val groups = store.observe().first().groups

        // the store keeps the DAO's stable order: it is the tie-break source; the visible
        // lastChapterDate DESC order is the deriver's job
        assertEquals(listOf(101L, 201L), groups.map { it.entityId })
    }

    @Test
    fun pinnedFlagAndMetadataAuthoritySurvive() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertFavourite(sql, 101, 1, pinned = true)
        FavouriteLibrarySeed.insertPrefs(sql, 101, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 77L)
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = null)
        insertTrack(mangaId = 101, newChapters = 1)

        val group = store.observe().first().groups.single()

        assertTrue(group.isPinned)
        assertEquals(3, group.metadataTrackingService)
        assertEquals("Site title", group.metadataTrackingTitle)
    }

    @Test
    fun favouriteCategoryFacetFollowsTheManga() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 7, "Reading")
        FavouriteLibrarySeed.insertManga(sql, 101, "Favourite")
        FavouriteLibrarySeed.insertFavourite(sql, 101, 7)
        FavouriteLibrarySeed.insertManga(sql, 201, "No favourite")
        insertTrack(mangaId = 101, newChapters = 1)
        insertTrack(mangaId = 201, newChapters = 1)

        val groups = store.observe().first().groups.associateBy { it.entityId }

        assertEquals(setOf(7L), groups.getValue(101L).categoryIds)
        assertEquals(emptySet<Long>(), groups.getValue(201L).categoryIds)
    }

    @Test
    fun emissionReflectsDatabaseChanges() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertTrack(mangaId = 101, newChapters = 5)

        val before = store.observe().first()
        assertEquals(5, before.groups.single().totalNewChapters)

        sql.execSQL("UPDATE tracks SET chapters_new = 0 WHERE manga_id = 101")
        assertTrue(store.observe().first().isEmpty)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertTrack(mangaId = 101, newChapters = 1)

        store.observe().first()

        val tracks = db.getTracksDao().findAll(offset = 0, limit = 10)
        assertEquals(1, tracks.size)
        assertFalse(tracks.single().newChapters == 0)
    }
}
