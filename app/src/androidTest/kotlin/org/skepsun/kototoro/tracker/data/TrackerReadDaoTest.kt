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
 * Phase F1/U1 of the history-updates-feed komikku-alignment plan: the narrow
 * read DAO must reproduce the legacy identity/pinned/display resolution pinned
 * by `FeedLogSemanticsCharacterizationTest`, with no filter parameters and no
 * writes.
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

    private fun insertLog(
        mangaId: Long,
        entityId: Long?,
        createdAt: Long,
        unread: Boolean = true,
        ownerId: Long = entityId ?: -mangaId,
    ) {
        sql.execSQL(
            "INSERT INTO track_logs(owner_id, manga_id, entity_id, chapters, created_at, unread) " +
                "VALUES (?, ?, ?, 'New chapters', ?, ?)",
            arrayOf<Any?>(ownerId, mangaId, entityId, createdAt, if (unread) 1 else 0),
        )
    }

    private fun insertTrack(
        mangaId: Long,
        entityId: Long?,
        newChapters: Int,
        lastChapterDate: Long,
        lastCheckTime: Long,
        ownerId: Long = entityId ?: -mangaId,
    ) {
        sql.execSQL(
            "INSERT INTO tracks VALUES (?, ?, ?, 42, ?, ?, ?, 1, NULL)",
            arrayOf<Any?>(ownerId, mangaId, entityId, newChapters, lastCheckTime, lastChapterDate),
        )
    }

    @Test
    fun feedLogRowResolvesEntityFromBindingWhenColumnIsNull() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = null, createdAt = 100)

        val rows = db.getTrackerReadDao().observeFeedLogRows().first()
        assertEquals(1, rows.size)
        assertEquals(10L, rows.single().entityId)
        assertEquals(101L, rows.single().anchorMangaId)
        assertTrue(rows.single().unread)
        assertEquals("New chapters", rows.single().chapters)
    }

    @Test
    fun feedLogRowPrefersEntityColumnOverBindingLookup() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        // Binding resolves to 10, the column says 20: the column wins (legacy COALESCE).
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = 20, createdAt = 100)

        val rows = db.getTrackerReadDao().observeFeedLogRows().first()
        assertEquals(20L, rows.single().entityId)
    }

    @Test
    fun feedLogRowDisplayFollowsPreferredLocalProjection() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor title")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred title")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        val row = db.getTrackerReadDao().observeFeedLogRows().first().single()
        assertEquals(105L, row.displayMangaId)
        assertEquals("Preferred title", row.displayTitle)
        assertEquals(105L, row.preferredLocalMangaId)
        assertTrue(row.hasDisplay)
    }

    @Test
    fun feedLogRowPinnedFlagAggregatesActiveFavourites() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertCategory(sql, 2, "Archive")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        // Pinned=false in category 1, pinned=true in a deleted category 2, pinned=true
        // via a favourite with a NULL anchor: only the first one counts.
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101, pinned = false)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 2, anchorMangaId = 101, pinned = true, deletedAt = 5)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 3, anchorMangaId = null, pinned = true)
        FavouriteLibrarySeed.insertCategory(sql, 3, "Tracked")
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        assertFalse(db.getTrackerReadDao().observeFeedLogRows().first().single().entityPinned)
    }

    @Test
    fun feedLogRowKeepsDanglingAnchorAsBrokenRow() = runTest {
        // No manga row at all: display fields stay null, the log survives.
        insertLog(mangaId = 999, entityId = null, createdAt = 100)

        val row = db.getTrackerReadDao().observeFeedLogRows().first().single()
        assertFalse(row.hasDisplay)
        assertNull(row.displayTitle)
        assertEquals(999L, row.anchorMangaId)
    }

    @Test
    fun updateTrackRowsIncludeOnlyPendingNewChapters() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Pending")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 3, lastChapterDate = 300, lastCheckTime = 350)

        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "Settled")
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        insertTrack(mangaId = 201, entityId = 20, newChapters = 0, lastChapterDate = 100, lastCheckTime = 150)

        val rows = db.getTrackerReadDao().observeUpdateTrackRows().first()
        assertEquals(listOf(101L), rows.map { it.mangaId })
        assertEquals(3, rows.single().newChapters)
        assertEquals(300L, rows.single().lastChapterDate)
        assertEquals(42L, rows.single().lastChapterId)
    }

    @Test
    fun updateTrackRowAggregatesNewChaptersPerEntityInStore() = runTest {
        // Two tracks of one entity (owner_id differs from entity_id): both rows are kept,
        // the per-entity sum is derived later in the store.
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "First")
        FavouriteLibrarySeed.insertManga(sql, 102, "Second")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 102)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 2, lastChapterDate = 300, lastCheckTime = 350, ownerId = 100)
        insertTrack(mangaId = 102, entityId = 10, newChapters = 5, lastChapterDate = 900, lastCheckTime = 950, ownerId = 101)

        val rows = db.getTrackerReadDao().observeUpdateTrackRows().first()
        assertEquals(setOf(101L, 102L), rows.map { it.mangaId }.toSet())
        assertEquals(7, rows.sumOf { it.newChapters })
    }

    @Test
    fun bindingFacetsCoverTrackAndTrackLogEntities() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 101, "Bound")
        FavouriteLibrarySeed.insertManga(sql, 105, "Also bound")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 105)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        insertLog(mangaId = 101, entityId = null, createdAt = 100)

        val facets = db.getTrackerReadDao().observeTrackedBindingFacets().first()
        assertEquals(setOf(101L, 105L), facets.map { it.mangaId }.toSet())
        assertTrue(facets.all { it.entityId == 10L })
    }

    @Test
    fun tagFacetsResolveOnTheRepresentativeManga() = runTest {
        FavouriteLibrarySeed.insertTag(sql, 1, "Drama")
        FavouriteLibrarySeed.insertTag(sql, 2, "Action")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        // Drama on the anchor, Action on the preferred: the facet uses the representative.
        FavouriteLibrarySeed.insertMangaTag(sql, 101, 1)
        FavouriteLibrarySeed.insertMangaTag(sql, 105, 2)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val facets = db.getTrackerReadDao().observeTrackedTagFacets(includeFeedLogs = true).first()
        assertEquals(listOf(2L), facets.filter { it.mangaId == 105L }.map { it.tagId })
        assertTrue(facets.none { it.mangaId == 101L && it.tagId == 1L })
    }

    @Test
    fun overrideFacetsCarryManualTitleAndCover() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        insertTrack(mangaId = 101, entityId = null, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        sql.execSQL(
            """
            INSERT INTO preferences (
                manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                cf_book, title_override, cover_override
            ) VALUES (?, 0, 0, 0, 0, 0, 0, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(101, "Manual title", "http://cover"),
        )

        val overrides = db.getTrackerReadDao().observeTrackedOverrides(includeFeedLogs = true).first()
        val override = overrides.single()
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
        insertTrack(mangaId = 101, entityId = null, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        insertLog(mangaId = 202, entityId = null, createdAt = 100)
        insertLegacyOverride(mangaId = 101, title = "Update override")
        insertLegacyOverride(mangaId = 202, title = "Feed override")

        val dao = db.getTrackerReadDao()
        assertEquals(setOf(101L), dao.observeTrackedTagFacets(includeFeedLogs = false).first().map { it.mangaId }.toSet())
        assertEquals(setOf(101L), dao.observeTrackedOverrides(includeFeedLogs = false).first().map { it.mangaId }.toSet())
    }

    @Test
    fun chapterCountsCoverRepresentativeMangaOnly() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)
        sql.execSQL(
            "INSERT INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, " +
                "\"index\") VALUES (1, 105, 'c1', 1, 0, 'u1', NULL, 0, NULL, 'TEST', 0)",
        )
        sql.execSQL(
            "INSERT INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, " +
                "\"index\") VALUES (2, 105, 'c2', 2, 0, 'u2', NULL, 0, NULL, 'TEST', 1)",
        )

        val counts = db.getTrackerReadDao().observeTrackedChapterCounts().first()
        assertEquals(listOf(TrackedChapterCountRow(105L, 2)), counts)
    }

    @Test
    fun updateTrackRowCarriesMetadataAuthorityWhenTracking() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(
            sql,
            10,
            metadataSourceKind = "tracking",
            metadataService = 3,
            metadataRemoteId = 77L,
        )
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = "http://site")
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val row = db.getTrackerReadDao().observeUpdateTrackRows().first().single()
        assertEquals(3, row.metadataTrackingService)
        assertEquals("Site title", row.metadataTrackingTitle)
        assertEquals("http://site", row.metadataTrackingCoverUrl)
    }

    @Test
    fun updateTrackRowMetadataIsNullForNonTrackingKind() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(
            sql,
            10,
            metadataSourceKind = "manual",
            metadataService = 3,
            metadataRemoteId = 77L,
        )
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = null)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val row = db.getTrackerReadDao().observeUpdateTrackRows().first().single()
        assertNull(row.metadataTrackingService)
        assertNull(row.metadataTrackingTitle)
    }

    @Test
    fun updateTrackRowDisplayFollowsPreferredProjection() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor title")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred title")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0)

        val row = db.getTrackerReadDao().observeUpdateTrackRows().first().single()
        assertEquals(105L, row.displayMangaId)
        assertEquals("Preferred title", row.displayTitle)
        assertEquals(105L, row.preferredLocalMangaId)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100)

        val dao = db.getTrackerReadDao()
        dao.observeFeedLogRows().first()
        dao.observeUpdateTrackRows().first()
        dao.observeTrackedBindingFacets().first()
        dao.observeTrackedTagFacets(includeFeedLogs = true).first()
        dao.observeTrackedOverrides(includeFeedLogs = true).first()
        dao.observeTrackedChapterCounts().first()

        // Nothing was written: the log is still unread and the log count is unchanged.
        val logs = db.getTrackLogsDao().dump()
        assertEquals(1, logs.size)
        assertTrue(logs.single().isUnread)
    }

    private fun insertLegacyOverride(mangaId: Long, title: String) {
        sql.execSQL(
            """
            INSERT INTO preferences (
                manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                cf_book, title_override
            ) VALUES (?, 0, 0, 0, 0, 0, 0, ?)
            """.trimIndent(),
            arrayOf<Any?>(mangaId, title),
        )
    }
}
