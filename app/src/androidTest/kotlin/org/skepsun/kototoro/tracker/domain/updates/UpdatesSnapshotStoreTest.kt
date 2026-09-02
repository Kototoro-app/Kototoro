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
 * Interface-level tests for [UpdatesSnapshotStore]
 * (history-updates-feed komikku-alignment plan, Phase U2).
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
    fun groupsTracksOfOneEntityIntoASingleGroup() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "First")
        FavouriteLibrarySeed.insertManga(sql, 102, "Second")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 102)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 2, lastChapterDate = 300, lastCheckTime = 350, ownerId = 100)
        insertTrack(mangaId = 102, entityId = 10, newChapters = 5, lastChapterDate = 900, lastCheckTime = 950, ownerId = 101)

        val snapshot = store.observe().first()

        assertEquals(1, snapshot.groups.size)
        val group = snapshot.groups.single()
        assertEquals(10L, group.entityId)
        assertEquals(7, group.totalNewChapters)
        assertEquals(900L, group.lastChapterDate)
        assertEquals(listOf(101L, 102L), group.mangaIds)
    }

    @Test
    fun representativePrefersThePreferredProjection() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertManga(sql, 105, "Preferred")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertPrefs(sql, 10, preferredLocalMangaId = 105)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 2, lastChapterDate = 300, lastCheckTime = 350, ownerId = 100)

        val group = store.observe().first().groups.single()

        assertEquals(105L, group.displayMangaId)
        assertEquals("Preferred", group.title)
        assertEquals(105L, group.preferredLocalMangaId)
    }

    @Test
    fun representativeFallsBackToFreshestTrack() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Stale")
        FavouriteLibrarySeed.insertManga(sql, 102, "Fresh")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 10, 102)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 2, lastChapterDate = 100, lastCheckTime = 150, ownerId = 100)
        insertTrack(mangaId = 102, entityId = 10, newChapters = 1, lastChapterDate = 900, lastCheckTime = 950, ownerId = 101)

        val group = store.observe().first().groups.single()

        // no preferred projection: the freshest track is the representative
        assertEquals("Fresh", group.title)
        assertEquals(102L, group.displayMangaId)
    }

    @Test
    fun uiIdEncodesEntityAndContentType() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 100)

        val group = store.observe().first().groups.single()

        assertEquals(-((10L shl 8) or (group.displayContentTypeOrdinal + 1).toLong()), group.uiId)
    }

    @Test
    fun tracksWithoutEntityGetTheirOwnGroups() = runTest {
        FavouriteLibrarySeed.insertManga(sql, 101, "No entity A")
        FavouriteLibrarySeed.insertManga(sql, 102, "No entity B")
        insertTrack(mangaId = 101, entityId = null, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 201)
        insertTrack(mangaId = 102, entityId = null, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 202)

        val snapshot = store.observe().first()

        assertEquals(2, snapshot.groups.size)
        assertTrue(snapshot.groups.all { it.entityId == null })
        assertEquals(setOf(101L, 102L), snapshot.groups.map { it.uiId }.toSet())
    }

    @Test
    fun groupsStayInStableTrackOrderForTheDeriversTieBreak() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertEntity(sql, 20, "work-20")
        FavouriteLibrarySeed.insertManga(sql, 101, "Older")
        FavouriteLibrarySeed.insertManga(sql, 201, "Newer")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertBinding(sql, 20, 201)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 3, lastChapterDate = 100, lastCheckTime = 150, ownerId = 100)
        insertTrack(mangaId = 201, entityId = 20, newChapters = 1, lastChapterDate = 900, lastCheckTime = 950, ownerId = 120)

        val groups = store.observe().first().groups

        // the store keeps the DAO's stable order (entity id ASC here): it is the
        // tie-break source; the visible lastChapterDate DESC order is the
        // deriver's job (UpdatesDeriverTest.orders by last chapter date descending)
        assertEquals(listOf(10L, 20L), groups.map { it.entityId })
    }

    @Test
    fun pinnedFlagAndMetadataAuthoritySurviveGrouping() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Reading")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 1, anchorMangaId = 101, pinned = true)
        FavouriteLibrarySeed.insertPrefs(
            sql,
            10,
            metadataSourceKind = "tracking",
            metadataService = 3,
            metadataRemoteId = 77L,
        )
        FavouriteLibrarySeed.insertTrackingSiteItem(sql, service = 3, remoteId = 77L, title = "Site title", coverUrl = null)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 100)

        val group = store.observe().first().groups.single()

        assertTrue(group.isPinned)
        assertEquals(3, group.metadataTrackingService)
        assertEquals("Site title", group.metadataTrackingTitle)
    }

    @Test
    fun favouriteCategoryFacetFollowsTheEntity() = runTest {
        FavouriteLibrarySeed.insertCategory(sql, 7, "Reading")
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        FavouriteLibrarySeed.insertFavourite(sql, 10, 7, anchorMangaId = 101)
        FavouriteLibrarySeed.insertManga(sql, 201, "No favourite")
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 100)
        insertTrack(mangaId = 201, entityId = null, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 201)

        val groups = store.observe().first().groups

        assertEquals(setOf(7L), groups.first { it.entityId == 10L }.categoryIds)
        assertEquals(emptySet<Long>(), groups.first { it.mangaIds == listOf(201L) }.categoryIds)
    }

    @Test
    fun emissionReflectsDatabaseChanges() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 5, lastChapterDate = 0, lastCheckTime = 0, ownerId = 100)

        val before = store.observe().first()
        assertEquals(5, before.groups.single().totalNewChapters)

        sql.execSQL("UPDATE tracks SET chapters_new = 0 WHERE manga_id = 101")
        val after = store.observe().first()
        assertTrue(after.isEmpty)
    }

    @Test
    fun readPathNeverWrites() = runTest {
        FavouriteLibrarySeed.insertEntity(sql, 10, "work-10")
        FavouriteLibrarySeed.insertManga(sql, 101, "Anchor")
        FavouriteLibrarySeed.insertBinding(sql, 10, 101)
        insertTrack(mangaId = 101, entityId = 10, newChapters = 1, lastChapterDate = 0, lastCheckTime = 0, ownerId = 100)

        store.observe().first()

        val tracks = db.getTracksDao().findAll(offset = 0, limit = 10)
        assertEquals(1, tracks.size)
        assertFalse(tracks.single().newChapters == 0)
    }
}
