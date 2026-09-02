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
 * Interface-level tests for [FeedSnapshotStore]
 * (history-updates-feed komikku-alignment plan, Phase F2): the caller only
 * needs `observe()` — flow combination, identity/display resolution, broken
 * rows and invalidation are all behind that single function.
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

    private fun insertLog(
        mangaId: Long,
        entityId: Long?,
        createdAt: Long,
        unread: Boolean = true,
        ownerId: Long = entityId ?: -mangaId,
    ) {
        sql.execSQL(
            "INSERT INTO track_logs(owner_id, manga_id, entity_id, chapters, created_at, unread) " +
                "VALUES (?, ?, ?, 'New chapters x 2\nNew chapters', ?, ?)",
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
    fun snapshotCarriesResolvedLogRowsAndUpdateRows() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 4, lastChapterDate = 300, lastCheckTime = 350, ownerId = 10)

        val snapshot = store.observe().first()

        val row = snapshot.rows.single()
        assertEquals(10L, row.entityId)
        assertEquals(105L, row.displayMangaId)
        assertEquals("Preferred", row.title)
        assertEquals(listOf("New chapters x 2", "New chapters"), row.chapters)
        assertTrue(row.unread)
        assertEquals(100L, row.createdAt)

        val update = snapshot.updateRowsByOwnerId.getValue(10L)
        assertEquals(4, update.newChapters)
        assertEquals(300L, update.lastChapterDate)
        assertEquals(10L, update.entityId)
    }

    @Test
    fun tagFacetsResolveOnTheDisplayProjection() = runTest {
        FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        FavouriteLibrarySeed.insertMangaTag(sql, 105, dramaTagId)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)

        val row = store.observe().first().rows.single()
        assertEquals(setOf(dramaTagId), row.tagIds)
        assertEquals(listOf("Drama"), row.tagTitles)
    }

    @Test
    fun manualOverrideAndChapterCountsAttachToTheRow() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)
        sql.execSQL(
            """
            INSERT INTO preferences (
                manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                cf_book, title_override, cover_override
            ) VALUES (101, 0, 0, 0, 0, 0, 0, 'Manual title', 'http://cover')
            """.trimIndent(),
        )
        sql.execSQL(
            "INSERT INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, " +
                "\"index\") VALUES (1, 101, 'c1', 1, 0, 'u1', NULL, 0, NULL, 'TEST', 0)",
        )

        val snapshot = store.observe().first()
        val row = snapshot.rows.single()
        assertEquals("Manual title", row.overrideTitle)
        assertEquals("http://cover", row.overrideCoverUrl)
    }

    @Test
    fun brokenRowsSurviveWithNullDisplay() = runTest {
        // No manga row for the anchor: the log stays reachable with empty display fields.
        insertLog(mangaId = 999, entityId = null, createdAt = 100, ownerId = -999)

        val row = store.observe().first().rows.single()
        assertFalse(row.hasDisplay)
        assertEquals("", row.title)
        assertEquals(999L, row.anchorMangaId)
    }

    @Test
    fun pinnedFlagSurvivesOnBothRowKinds() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101, pinned = true)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 2, lastChapterDate = 0, lastCheckTime = 0, ownerId = 10)

        val snapshot = store.observe().first()
        assertTrue(snapshot.rows.single().isPinned)
        assertTrue(snapshot.updateRowsByOwnerId.getValue(10L).isPinned)
    }

    @Test
    fun emissionReflectsDatabaseChanges() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)

        val before = store.observe().first()
        assertEquals(1, before.rows.size)

        sql.execSQL("UPDATE track_logs SET unread = 0 WHERE manga_id = 101")
        val after = store.observe().first()
        assertEquals(1, after.rows.size)
        assertFalse(after.rows.single().unread)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertLog(mangaId = 101, entityId = 10, createdAt = 100, ownerId = 10)

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
