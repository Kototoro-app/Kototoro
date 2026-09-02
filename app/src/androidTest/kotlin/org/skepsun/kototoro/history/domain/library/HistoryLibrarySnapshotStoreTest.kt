package org.skepsun.kototoro.history.domain.library

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
 * Interface-level tests for [HistoryLibrarySnapshotStore]
 * (history-updates-feed komikku-alignment plan, Phase H2).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryLibrarySnapshotStoreTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var store: HistoryLibrarySnapshotStore

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys = OFF")
        store = HistoryLibrarySnapshotStore(db, sourceGroupManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertHistory(
        entityId: Long,
        anchorMangaId: Long,
        percent: Float = 0.5f,
        updatedAt: Long = 100L,
        chapters: Int = 12,
    ) {
        // columns: entity_id, anchor_manga_id, created_at, updated_at,
        // chapter_id, page, scroll, percent, deleted_at, chapters, parent_chapter_id
        sql.execSQL(
            "INSERT INTO work_history VALUES (?, ?, ?, ?, 0, 0, 0, ?, 0, ?, NULL)",
            arrayOf<Any?>(entityId, anchorMangaId, updatedAt, updatedAt, percent, chapters),
        )
    }

    private fun insertTag(mangaId: Long, tagId: Long, title: String, key: String) {
        sql.execSQL("INSERT INTO tags VALUES (?, ?, ?, ?, 0)", arrayOf<Any?>(tagId, title, key, "TEST"))
        sql.execSQL(
            "INSERT INTO manga_tags (manga_id, tag_id) VALUES (?, ?)",
            arrayOf<Any?>(mangaId, tagId),
        )
    }

    @Test
    fun oneRowPerActiveEntityWithDisplayProjection() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor title")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101, percent = 0.25f, chapters = 40)

        val snapshot = store.observe().first()

        assertEquals(1, snapshot.rows.size)
        val row = snapshot.rows.single()
        assertEquals(10L, row.entityId)
        assertEquals(101L, row.displayMangaId)
        assertEquals("Anchor title", row.title)
        assertEquals(0.25f, row.percent)
        assertEquals(40, row.chaptersCount)
    }

    @Test
    fun preferredProjectionWinsOverAnchor() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 105)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertHistory(10, 101)

        val row = store.observe().first().rows.single()

        assertEquals(105L, row.displayMangaId)
        assertEquals("Preferred", row.title)
        assertEquals(105L, row.preferredLocalMangaId)
    }

    @Test
    fun uiIdEncodesEntityAndContentType() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101)

        val row = store.observe().first().rows.single()

        assertEquals(-((10L shl 8) or (row.displayContentTypeOrdinal + 1).toLong()), row.uiId)
    }

    @Test
    fun trackingSummaryAndMembershipFoldIntoTheRow() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 7, "Reading")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 7, anchorMangaId = 101, pinned = true)
        FavouriteLibrarySeed.insertTrack(sql, 10, 101, newChapters = 4, lastChapterDate = 900L, lastCheckTime = 950L)
        insertHistory(10, 101)

        val row = store.observe().first().rows.single()

        assertEquals(4, row.newChapters)
        assertEquals(900L, row.lastChapterDate)
        assertTrue(row.isPinned)
        assertTrue(row.isFavourite)
        assertEquals(setOf(7L), row.categoryIds)
    }

    @Test
    fun tagsFollowTheDisplayProjection() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101)
        insertTag(101, 1, "Action", "action_1")

        val row = store.observe().first().rows.single()

        assertEquals(listOf(HistoryCardTag("Action", "action_1")), row.tags)
    }

    @Test
    fun localBindingsFeedTheSpaceFilterData() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 102, "Second")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 102)
        insertHistory(10, 101)

        val row = store.observe().first().rows.single()

        assertEquals(listOf(101L, 102L), row.localMangaIds)
        assertEquals(2, row.bindings.size)
    }

    @Test
    fun downloadedRowsFoldIntoTheRow() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101)
        // a second entity without any local download stays unmarked
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 201, "Other")
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        insertHistory(20, 201)
        sql.execSQL("INSERT INTO local_index (manga_id, path) VALUES (101, '/tmp/anchor')")

        val rows = store.observe().first().rows.associateBy { it.entityId }

        assertEquals(true, rows.getValue(10L).isDownloaded)
        assertEquals(false, rows.getValue(20L).isDownloaded)
    }

    @Test
    fun deletedHistoryRowsDisappear() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101)

        assertEquals(1, store.observe().first().rows.size)

        sql.execSQL("UPDATE work_history SET deleted_at = 1 WHERE entity_id = 10")
        assertTrue(store.observe().first().isEmpty)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertHistory(10, 101, percent = 0.5f)

        store.observe().first()

        val history = db.getWorkHistoryDao().findAll(offset = 0, limit = 10)
        assertEquals(1, history.size)
        assertFalse(history.single().deletedAt != 0L)
    }
}
