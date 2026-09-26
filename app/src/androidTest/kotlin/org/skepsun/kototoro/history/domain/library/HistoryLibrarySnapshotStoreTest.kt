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
 * Interface-level tests for [HistoryLibrarySnapshotStore]: one card per history row,
 * owned by its `manga_id` (projection-first).
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
        store = HistoryLibrarySnapshotStore(db, sourceGroupManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertRead(mangaId: Long, title: String = "Manga $mangaId", percent: Float = 0.5f, chapters: Int = 12) {
        FavouriteLibrarySeed.insertManga(sql, mangaId, title)
        FavouriteLibrarySeed.insertHistory(sql, mangaId, percent = percent, updatedAt = 100L, chaptersCount = chapters)
    }

    private fun insertTag(mangaId: Long, tagId: Long, title: String, key: String) {
        sql.execSQL("INSERT INTO tags VALUES (?, ?, ?, ?, 0)", arrayOf<Any?>(tagId, title, key, "TEST"))
        sql.execSQL(
            "INSERT INTO manga_tags (manga_id, tag_id) VALUES (?, ?)",
            arrayOf<Any?>(mangaId, tagId),
        )
    }

    @Test
    fun oneRowPerActiveHistoryDisplayingItsManga() = runTest {
        insertRead(101, "Read title", percent = 0.25f, chapters = 40)

        val snapshot = store.observe().first()

        assertEquals(1, snapshot.rows.size)
        val row = snapshot.rows.single()
        assertEquals(101L, row.entityId)
        assertEquals(101L, row.displayMangaId)
        assertEquals(101L, row.preferredLocalMangaId)
        assertEquals("Read title", row.title)
        assertEquals(0.25f, row.percent)
        assertEquals(40, row.chaptersCount)
        assertEquals(listOf(101L), row.localMangaIds)
    }

    @Test
    fun uiIdEncodesMangaAndContentType() = runTest {
        insertRead(101)

        val row = store.observe().first().rows.single()

        assertEquals(-((101L shl 8) or (row.displayContentTypeOrdinal + 1).toLong()), row.uiId)
    }

    @Test
    fun trackingSummaryAndMembershipFoldIntoTheRow() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 7, "Reading")
        insertRead(101)
        FavouriteLibrarySeed.insertFavourite(sql, 101, 7, pinned = true)
        FavouriteLibrarySeed.insertTrack(sql, 101, newChapters = 4, lastChapterDate = 900L, lastCheckTime = 950L)

        val row = store.observe().first().rows.single()

        assertEquals(4, row.newChapters)
        assertEquals(900L, row.lastChapterDate)
        assertTrue(row.isPinned)
        assertTrue(row.isFavourite)
        assertEquals(setOf(7L), row.categoryIds)
    }

    @Test
    fun tagsFollowTheManga() = runTest {
        insertRead(101)
        insertTag(101, 1, "Action", "action_1")

        val row = store.observe().first().rows.single()

        assertEquals(listOf(HistoryCardTag("Action", "action_1")), row.tags)
    }

    @Test
    fun downloadedRowsFoldIntoTheRow() = runTest {
        insertRead(101)
        // a second history row without any local download stays unmarked
        insertRead(201)
        sql.execSQL("INSERT INTO local_index (manga_id, path) VALUES (101, '/tmp/item')")

        val rows = store.observe().first().rows.associateBy { it.entityId }

        assertEquals(true, rows.getValue(101L).isDownloaded)
        assertEquals(false, rows.getValue(201L).isDownloaded)
    }

    @Test
    fun deletedHistoryRowsDisappear() = runTest {
        insertRead(101)

        assertEquals(1, store.observe().first().rows.size)

        sql.execSQL("UPDATE history SET deleted_at = 1 WHERE manga_id = 101")
        assertTrue(store.observe().first().isEmpty)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        insertRead(101, percent = 0.5f)

        store.observe().first()

        val history = db.getHistoryDao().findAllEntriesIncludingDeleted()
        assertEquals(1, history.size)
        assertEquals(0L, history.single().deletedAt)
        assertEquals(0.5f, history.single().percent)
    }
}
