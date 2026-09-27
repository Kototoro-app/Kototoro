package org.skepsun.kototoro.favourites.domain.library

import androidx.room.Room
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject

/**
 * Interface-level tests for [FavouriteLibrarySnapshotStore]: the caller only needs
 * `observe()` — everything about flow combination, memberships and invalidation is
 * behind that single function. Each favourite manga is one library row (manga-keyed).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FavouriteLibrarySnapshotStoreTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var store: FavouriteLibrarySnapshotStore

    private val dramaTagId = "drama_TEST".longHashCode()

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        store = FavouriteLibrarySnapshotStore(db, sourceGroupManager)
        seed()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun snapshotContainsOneRowPerMangaWithConsistentMemberships() = runTest {
        val snapshot = store.observe().first()

        assertEquals(setOf(M1, M2, M3, M5, M12), snapshot.rowsByEntityId.keys)
        assertEquals(snapshot.rowsByEntityId.keys.sorted(), snapshot.allEntityIds)

        // M2 keeps both memberships
        val m2Memberships = snapshot.membershipsByCategory.values.flatten().filter { it.entityId == M2 }
        assertEquals(2, m2Memberships.size)
        assertTrue(m2Memberships.any { it.categoryId == 10L && !it.isPinned })
        assertTrue(m2Memberships.any { it.categoryId == 11L && it.isPinned })
        // membership lists only contain known rows
        snapshot.membershipsByCategory.values.forEach { list ->
            assertTrue(list.all { it.entityId in snapshot.rowsByEntityId })
        }
    }

    @Test
    fun rowsCarryCardFieldsOverridesAndFacets() = runTest {
        val snapshot = store.observe().first()

        // each row displays its own manga
        val m5 = snapshot.rowsByEntityId.getValue(M5)
        assertEquals(M5, m5.displayMangaId)
        assertEquals("Epsilon", m5.title)
        assertEquals(0.9f, m5.rating)
        assertEquals(setOf(M5), m5.localMangaIds)

        // the manga's preferences override is the card title
        val m1 = snapshot.rowsByEntityId.getValue(M1)
        assertEquals("Renamed", m1.overrideTitle)
        assertEquals("Renamed", m1.resolvedTitle)

        // tag facets: identity + display list
        val m12 = snapshot.rowsByEntityId.getValue(M12)
        assertTrue(dramaTagId in m12.tagIds)
        assertEquals(listOf(FavouriteCardTag(dramaTagId, "Drama")), m12.displayTags)

        // download mapping (none seeded -> false)
        assertFalse(m12.isDownloaded)

        // quick filter metadata derived from facets
        assertTrue("TEST" in snapshot.quickFilterMetadata.sources)
        assertEquals(4, snapshot.quickFilterMetadata.sourceEntityCounts["TEST"])
        assertEquals(1, snapshot.quickFilterMetadata.sourceEntityCounts["OTHER"])
        assertTrue(snapshot.quickFilterMetadata.tags.any { it.tagId == dramaTagId })
    }

    @Test
    fun progressUpdateReemitsConsistentSnapshot() = runTest {
        val before = store.observe().first()
        assertEquals(0.5f, before.rowsByEntityId.getValue(M3).progressPercent)

        db.openHelper.writableDatabase.execSQL(
            "UPDATE history SET percent = 0.9, updated_at = 12345 WHERE manga_id = $M3",
        )

        val after = store.observe().first()
        assertEquals(0.9f, after.rowsByEntityId.getValue(M3).progressPercent)
        assertEquals(12345L, after.rowsByEntityId.getValue(M3).lastReadAt)
        // reading status follows progress
        assertEquals(ScrobblingStatus.READING, after.rowsByEntityId.getValue(M3).readingStatus)
        // the rest of the snapshot is untouched
        assertEquals(before.rowsByEntityId.keys, after.rowsByEntityId.keys)
        assertEquals(before.allEntityIds, after.allEntityIds)
    }

    @Test
    fun membershipDeletionRemovesRowAndMembership() = runTest {
        val before = store.observe().first()
        assertTrue(M12 in before.rowsByEntityId)

        db.openHelper.writableDatabase.execSQL("UPDATE favourites SET deleted_at = 1 WHERE manga_id = $M12")

        val after = store.observe().first()
        assertNull(after.rowsByEntityId[M12])
        assertFalse(M12 in after.allEntityIds)
        assertFalse(after.membershipsByCategory.values.flatten().any { it.entityId == M12 })
        // quick filter counts no longer include M12's source
        assertFalse("OTHER" in after.quickFilterMetadata.sources)
    }

    @Test
    fun categoryChangeOnlyMovesTheMembership() = runTest {
        val before = store.observe().first()
        assertEquals(1, before.membershipsByCategory.getValue(10L).count { it.entityId == M1 })

        db.openHelper.writableDatabase.execSQL(
            "UPDATE favourites SET category_id = 11 WHERE manga_id = $M1 AND category_id = 10",
        )

        val after = store.observe().first()
        assertNull(after.membershipsByCategory[10L]?.firstOrNull { it.entityId == M1 })
        assertNotNull(after.membershipsByCategory.getValue(11L).firstOrNull { it.entityId == M1 })
        // the card row itself is unchanged
        assertEquals(before.rowsByEntityId.getValue(M1), after.rowsByEntityId.getValue(M1))
    }

    @Test
    fun readingNeverWritesToTheDatabase() = runTest {
        val sql = db.openHelper.writableDatabase
        fun count(table: String): Long = sql.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }
        val prefsBefore = count("preferences")
        val favouritesBefore = count("favourites")
        val mangaBefore = count("manga")

        store.observe().first()

        assertEquals(prefsBefore, count("preferences"))
        assertEquals(favouritesBefore, count("favourites"))
        assertEquals(mangaBefore, count("manga"))
    }

    @Test
    fun coverOverrideResolvesTheCardCover() = runTest {
        FavouriteLibrarySeed.insertPrefs(
            db.openHelper.writableDatabase,
            M2,
            titleOverride = "Alpha renamed",
            coverOverride = "/cover/alpha.jpg",
        )
        val m2 = store.observe().first().rowsByEntityId.getValue(M2)
        assertEquals("Alpha renamed", m2.overrideTitle)
        assertEquals("Alpha renamed", m2.resolvedTitle)
        assertEquals("/cover/alpha.jpg", m2.resolvedCoverUrl)
    }

    @Test
    fun emptyLibraryEmitsEmptySnapshot() = runTest {
        db.openHelper.writableDatabase.execSQL("DELETE FROM favourites")
        val snapshot = store.observe().first()
        assertEquals(FavouriteLibrarySnapshot.Empty, snapshot)
        assertEquals(0, snapshot.allEntityIds.size)
    }

    // ------------------------------------------------------------------ seeding

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            with(FavouriteLibrarySeed) {
                insertCategory(sql, 10, "Reading")
                insertCategory(sql, 11, "Planned")

                // M1: plain row + preferences title override
                insertManga(sql, M1, "Beta")
                insertFavourite(sql, M1, 10, createdAt = 100, updatedAt = 100)
                insertPrefs(sql, M1, titleOverride = "Renamed")

                // M2: membership in two categories, pinned in the older one
                insertManga(sql, M2, "Alpha")
                insertFavourite(sql, M2, 10, pinned = false, createdAt = 100, updatedAt = 100)
                insertFavourite(sql, M2, 11, pinned = true, createdAt = 50, updatedAt = 50)

                // M3: history progress 0.5
                insertManga(sql, M3, "Gamma")
                insertFavourite(sql, M3, 10, createdAt = 10, updatedAt = 10)
                insertHistory(sql, M3, percent = 0.5f, updatedAt = 500)

                // M5: display fields straight from the manga row
                insertManga(sql, M5, "Epsilon", rating = 0.9f)
                insertFavourite(sql, M5, 10, createdAt = 10, updatedAt = 10)

                // M12: tagged, from another source
                insertManga(sql, M12, "Mu", source = "OTHER")
                insertFavourite(sql, M12, 10, createdAt = 10, updatedAt = 10)
                insertTag(sql, dramaTagId, "Drama")
                insertMangaTag(sql, M12, dramaTagId)
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private companion object {
        const val M1 = 1001L
        const val M2 = 2001L
        const val M3 = 3001L
        const val M5 = 5001L
        const val M12 = 12001L
    }
}
