package org.skepsun.kototoro.tracker.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed

/**
 * The narrow tracker read DAO: feed logs and pending updates, each owned by its own
 * `manga_id` (projection-first), with no filter parameters and no writes.
 */
@RunWith(AndroidJUnit4::class)
class TrackerReadDaoTest {

    private lateinit var db: MangaDatabase
    private lateinit var sql: SupportSQLiteDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        sql = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertLog(mangaId: Long, createdAt: Long, unread: Boolean = true) {
        FavouriteLibrarySeed.insertTrackLog(sql, mangaId, "New chapters", createdAt, unread)
    }

    private fun insertTrack(mangaId: Long, newChapters: Int, lastChapterDate: Long, lastCheckTime: Long) {
        sql.execSQL(
            "INSERT INTO tracks (manga_id, last_chapter_id, chapters_new, last_check_time, " +
                "last_chapter_date, last_result, last_error) VALUES (?, 42, ?, ?, ?, 1, NULL)",
            arrayOf<Any?>(mangaId, newChapters, lastCheckTime, lastChapterDate),
        )
    }

    @Test
    fun feedLogRowIsOwnedAndDisplayedByItsManga() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor title")
        insertLog(mangaId = 101, createdAt = 100)

        val row = db.getTrackerReadDao().observeFeedLogRows().first().single()
        assertEquals(101L, row.entityId)
        assertEquals(101L, row.anchorMangaId)
        assertEquals(101L, row.preferredLocalMangaId)
        assertEquals(101L, row.displayMangaId)
        assertEquals("Anchor title", row.displayTitle)
        assertTrue(row.hasDisplay)
        assertTrue(row.unread)
        assertEquals("New chapters", row.chapters)
    }

    @Test
    fun feedLogRowPinnedFlagAggregatesActiveFavourites() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertCategory(sql, 2, "Archive")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        // Pinned=false in category 1, pinned=true only in a deleted membership: not pinned.
        FavouriteLibrarySeed.insertFavourite(sql, 101, 1, pinned = false)
        FavouriteLibrarySeed.insertFavourite(sql, 101, 2, pinned = true, deletedAt = 5)
        insertLog(mangaId = 101, createdAt = 100)

        assertFalse(db.getTrackerReadDao().observeFeedLogRows().first().single().entityPinned)

        sql.execSQL("UPDATE favourites SET pinned = 1 WHERE manga_id = 101 AND category_id = 1")
        assertTrue(db.getTrackerReadDao().observeFeedLogRows().first().single().entityPinned)
    }

    @Test
    fun feedLogRowKeepsDanglingMangaAsBrokenRow() = runTest {
        // No manga row at all: display fields stay null, the log survives.
        insertLog(mangaId = 999, createdAt = 100)

        val row = db.getTrackerReadDao().observeFeedLogRows().first().single()
        assertFalse(row.hasDisplay)
        assertNull(row.displayTitle)
        assertEquals(999L, row.anchorMangaId)
    }

    @Test
    fun updateTrackRowsIncludeOnlyPendingNewChapters() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Pending")
        insertTrack(mangaId = 101, newChapters = 3, lastChapterDate = 300, lastCheckTime = 350)
        FavouriteLibrarySeed.insertManga(sql, 201, "Settled")
        insertTrack(mangaId = 201, newChapters = 0, lastChapterDate = 100, lastCheckTime = 150)

        val rows = db.getTrackerReadDao().observeUpdateTrackRows().first()
        assertEquals(listOf(101L), rows.map { it.mangaId })
        assertEquals(3, rows.single().newChapters)
        assertEquals(300L, rows.single().lastChapterDate)
        assertEquals(42L, rows.single().lastChapterId)
        assertEquals(101L, rows.single().displayMangaId)
        assertEquals(101L, rows.single().preferredLocalMangaId)
    }

    @Test
    fun tagFacetsResolveOnTheTrackedManga() = runTest {
        FavouriteLibrarySeed.insertTag(sql, 1, "Drama")
        FavouriteLibrarySeed.insertManga(sql, 101, "Tracked")
        FavouriteLibrarySeed.insertManga(sql, 105, "Untracked")
        FavouriteLibrarySeed.insertMangaTag(sql, 101, 1)
        FavouriteLibrarySeed.insertMangaTag(sql, 105, 1)
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val facets = db.getTrackerReadDao().observeTrackedTagFacets(includeFeedLogs = true).first()
        assertEquals(listOf(101L to 1L), facets.map { it.mangaId to it.tagId })
    }

    @Test
    fun overrideFacetsCarryManualTitleAndCover() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        FavouriteLibrarySeed.insertPrefs(sql, 101, titleOverride = "Manual title", coverOverride = "http://cover")

        val override = db.getTrackerReadDao().observeTrackedOverrides(includeFeedLogs = true).first().single()
        assertEquals(101L, override.mangaId)
        assertEquals("Manual title", override.titleOverride)
        assertEquals("http://cover", override.coverOverride)
    }

    @Test
    fun updateFacetsExcludeFeedOnlyMetadata() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Pending update")
        FavouriteLibrarySeed.insertManga(sql, 202, "Feed only")
        FavouriteLibrarySeed.insertTag(sql, 1, "Update tag")
        FavouriteLibrarySeed.insertTag(sql, 2, "Feed tag")
        FavouriteLibrarySeed.insertMangaTag(sql, 101, 1)
        FavouriteLibrarySeed.insertMangaTag(sql, 202, 2)
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        insertLog(mangaId = 202, createdAt = 100)
        FavouriteLibrarySeed.insertPrefs(sql, 101, titleOverride = "Update override")
        FavouriteLibrarySeed.insertPrefs(sql, 202, titleOverride = "Feed override")

        val dao = db.getTrackerReadDao()
        assertEquals(setOf(101L), dao.observeTrackedTagFacets(includeFeedLogs = false).first().map { it.mangaId }.toSet())
        assertEquals(setOf(101L), dao.observeTrackedOverrides(includeFeedLogs = false).first().map { it.mangaId }.toSet())
        assertEquals(
            setOf(101L, 202L),
            dao.observeTrackedTagFacets(includeFeedLogs = true).first().map { it.mangaId }.toSet(),
        )
    }

    @Test
    fun chapterCountsCoverTrackedMangaOnly() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Tracked")
        FavouriteLibrarySeed.insertManga(sql, 105, "Untracked")
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        insertChapter(1, 101)
        insertChapter(2, 101)
        insertChapter(3, 105)

        val counts = db.getTrackerReadDao().observeTrackedChapterCounts(includeFeedLogs = false).first()
        assertEquals(listOf(TrackedChapterCountRow(101L, 2)), counts)
    }

    @Test
    fun updateTrackRowCarriesMetadataAuthorityWhenTracking() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertPrefs(sql, 101, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 77L)
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = "http://site")
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val row = db.getTrackerReadDao().observeUpdateTrackRows().first().single()
        assertEquals(3, row.metadataTrackingService)
        assertEquals("Site title", row.metadataTrackingTitle)
        assertEquals("http://site", row.metadataTrackingCoverUrl)
    }

    @Test
    fun updateTrackRowMetadataIsNullForNonTrackingKind() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertPrefs(sql, 101, metadataSourceKind = "manual", metadataService = 3, metadataRemoteId = 77L)
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = null)
        insertTrack(mangaId = 101, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val row = db.getTrackerReadDao().observeUpdateTrackRows().first().single()
        assertNull(row.metadataTrackingService)
        assertNull(row.metadataTrackingTitle)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertLog(mangaId = 101, createdAt = 100)

        val dao = db.getTrackerReadDao()
        dao.observeFeedLogRows().first()
        dao.observeUpdateTrackRows().first()
        dao.observeTrackedTagFacets(includeFeedLogs = true).first()
        dao.observeTrackedOverrides(includeFeedLogs = true).first()
        dao.observeTrackedChapterCounts(includeFeedLogs = true).first()

        // Nothing was written: the log is still unread and the log count is unchanged.
        val logs = db.getTrackLogsDao().dump()
        assertEquals(1, logs.size)
        assertTrue(logs.single().isUnread)
    }

    private fun insertChapter(id: Long, mangaId: Long) {
        sql.execSQL(
            "INSERT INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, " +
                "\"index\") VALUES (?, ?, ?, ?, 0, ?, NULL, 0, NULL, 'TEST', ?)",
            arrayOf<Any?>(id, mangaId, "c$id", id, "u$id", id),
        )
    }
}
