package org.skepsun.kototoro.tracker.domain.feed

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed
import org.skepsun.kototoro.parsers.util.longHashCode
import javax.inject.Inject

/**
 * Interface-level tests for [FeedSnapshotStore]: the caller only needs `observe()` —
 * flow combination, display resolution, broken rows and invalidation are all behind
 * that single function. Logs and tracks are owned by their `manga_id`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FeedSnapshotStoreTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var store: FeedSnapshotStore

    private val dramaTagId = "drama_TEST".longHashCode()

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys = OFF")
        store = FeedSnapshotStore(db, sourceGroupManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertLog(mangaId: Long, createdAt: Long, unread: Boolean = true) {
        FavouriteLibrarySeed.insertTrackLog(sql, mangaId, "New chapters x 2\nNew chapters", createdAt, unread)
    }

    @Test
    fun snapshotCarriesResolvedLogRowsAndUpdateRows() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertLog(mangaId = 101, createdAt = 100)
        FavouriteLibrarySeed.insertTrack(sql, 101, newChapters = 4, lastChapterDate = 300, lastCheckTime = 350)

        val snapshot = store.observe().first()

        val row = snapshot.rows.single()
        assertEquals(101L, row.entityId)
        assertEquals(101L, row.displayMangaId)
        assertEquals("Anchor", row.title)
        assertEquals(listOf("New chapters x 2", "New chapters"), row.chapters)
        assertTrue(row.unread)
        assertEquals(100L, row.createdAt)

        val update = snapshot.updateRowsByOwnerId.getValue(101L)
        assertEquals(4, update.newChapters)
        assertEquals(300L, update.lastChapterDate)
        assertEquals(101L, update.entityId)
    }

    @Test
    fun tagFacetsResolveOnTheManga() = runTest {
        FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertMangaTag(sql, 101, dramaTagId)
        insertLog(mangaId = 101, createdAt = 100)

        val row = store.observe().first().rows.single()
        assertEquals(setOf(dramaTagId), row.tagIds)
        assertEquals(listOf("Drama"), row.tagTitles)
    }

    @Test
    fun manualOverrideAttachesToTheRow() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertLog(mangaId = 101, createdAt = 100)
        FavouriteLibrarySeed.insertPrefs(sql, 101, titleOverride = "Manual title", coverOverride = "http://cover")

        val row = store.observe().first().rows.single()
        assertEquals("Manual title", row.overrideTitle)
        assertEquals("http://cover", row.overrideCoverUrl)
    }

    @Test
    fun brokenRowsSurviveWithNullDisplay() = runTest {
        // No manga row for the log: it stays reachable with empty display fields.
        insertLog(mangaId = 999, createdAt = 100)

        val row = store.observe().first().rows.single()
        assertFalse(row.hasDisplay)
        assertEquals("", row.title)
        assertEquals(999L, row.anchorMangaId)
    }

    @Test
    fun pinnedFlagSurvivesOnBothRowKinds() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertFavourite(sql, 101, 1, pinned = true)
        insertLog(mangaId = 101, createdAt = 100)
        FavouriteLibrarySeed.insertTrack(sql, 101, newChapters = 2, lastChapterDate = 0, lastCheckTime = 0)

        val snapshot = store.observe().first()
        assertTrue(snapshot.rows.single().isPinned)
        assertTrue(snapshot.updateRowsByOwnerId.getValue(101L).isPinned)
    }

    @Test
    fun emissionReflectsDatabaseChanges() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertLog(mangaId = 101, createdAt = 100)

        val before = store.observe().first()
        assertEquals(1, before.rows.size)

        sql.execSQL("UPDATE track_logs SET unread = 0 WHERE manga_id = 101")
        val after = store.observe().first()
        assertEquals(1, after.rows.size)
        assertFalse(after.rows.single().unread)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertLog(mangaId = 101, createdAt = 100)

        store.observe().first()

        val logs = db.getTrackLogsDao().dump()
        assertEquals(1, logs.size)
        assertTrue(logs.single().isUnread)
    }

    @Test
    fun emptyLibraryYieldsEmptySnapshot() = runTest {
        val snapshot = store.observe().first()
        assertTrue(snapshot.isEmpty)
        assertNotNull(snapshot)
    }
}
