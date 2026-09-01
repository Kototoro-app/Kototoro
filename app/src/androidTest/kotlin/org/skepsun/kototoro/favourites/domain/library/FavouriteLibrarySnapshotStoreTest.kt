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
import javax.inject.Inject

/**
 * Interface-level tests for [FavouriteLibrarySnapshotStore]
 * (favourites-komikku-alignment plan, section 10.2): the caller only needs
 * `observe()` — everything about flow combination, broken rows, memberships and
 * invalidation is behind that single function.
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
    fun snapshotContainsOneRowPerEntityWithConsistentMemberships() = runTest {
        val snapshot = store.observe().first()

        assertEquals(setOf(1L, 2L, 3L, 5L, 6L, 12L), snapshot.rowsByEntityId.keys)
        assertEquals(snapshot.rowsByEntityId.keys.sorted(), snapshot.allEntityIds)

        // every membership references a known row; E2 keeps both memberships
        val e2Memberships = snapshot.membershipsByCategory.values.flatten().filter { it.entityId == 2L }
        assertEquals(2, e2Memberships.size)
        assertTrue(e2Memberships.any { it.categoryId == 10L && !it.isPinned })
        assertTrue(e2Memberships.any { it.categoryId == 11L && it.isPinned })
        // membership lists only contain known entities
        snapshot.membershipsByCategory.values.forEach { list ->
            assertTrue(list.all { it.entityId in snapshot.rowsByEntityId })
        }
    }

    @Test
    fun rowsCarryCardFieldsOverridesAndFacets() = runTest {
        val snapshot = store.observe().first()

        // display follows preferred projection (E5)
        val e5 = snapshot.rowsByEntityId.getValue(5L)
        assertEquals(5002L, e5.displayMangaId)
        assertEquals("Epsilon preferred", e5.title)
        assertEquals(0.9f, e5.rating)
        assertEquals(setOf(5001L, 5002L), e5.localMangaIds)
        assertEquals(2, e5.projectionCount)

        // entity override wins, legacy preferences override is the fallback (E1).
        // Priority mirrors ContentDataRepository.getOverridesForWorkItems: entity
        // prefs first, then the per-manga legacy preferences row.
        val e1 = snapshot.rowsByEntityId.getValue(1L)
        assertEquals("Entity Renamed", e1.overrideTitle)
        assertEquals("Entity Renamed", e1.resolvedTitle)

        // tag facets: identity + display list (E12)
        val e12 = snapshot.rowsByEntityId.getValue(12L)
        assertTrue(dramaTagId in e12.tagIds)
        assertEquals(listOf(FavouriteCardTag(dramaTagId, "Drama")), e12.displayTags)

        // download mapping (none seeded -> false)
        assertFalse(e12.isDownloaded)

        // quick filter metadata derived from facets: every entity binds at least one
        // TEST projection (E1/E2/E3/E5/E6/E12 — E5 binds two, counted once).
        assertTrue("TEST" in snapshot.quickFilterMetadata.sources)
        assertEquals(6, snapshot.quickFilterMetadata.sourceEntityCounts["TEST"])
        assertTrue(snapshot.quickFilterMetadata.tags.any { it.tagId == dramaTagId })
    }

    @Test
    fun brokenRowsSurviveWithDeterministicFields() = runTest {
        val snapshot = store.observe().first()

        // E6: dangling preferred -> no display manga, but the row survives
        val e6 = snapshot.rowsByEntityId.getValue(6L)
        assertFalse(e6.hasDisplayProjection)
        assertNull(e6.displayMangaId)
        assertTrue(e6.hasBrokenProjection)
        // sort fields still deterministic (title empty -> orders last alphabetically)
        assertEquals("", e6.title)
        // membership intact: entity organize remains reachable
        assertTrue(snapshot.membershipsByCategory.getValue(10L).any { it.entityId == 6L })
    }

    @Test
    fun progressUpdateReemitsConsistentSnapshot() = runTest {
        val before = store.observe().first()
        assertEquals(0.5f, before.rowsByEntityId.getValue(3L).progressPercent)

        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "UPDATE work_history SET percent = 0.9, updated_at = 12345 WHERE entity_id = 3",
        )

        val after = store.observe().first()
        assertEquals(0.9f, after.rowsByEntityId.getValue(3L).progressPercent)
        assertEquals(12345L, after.rowsByEntityId.getValue(3L).lastReadAt)
        // reading status follows progress
        assertEquals(
            org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus.READING,
            after.rowsByEntityId.getValue(3L).readingStatus,
        )
        // the rest of the snapshot is untouched
        assertEquals(before.rowsByEntityId.keys, after.rowsByEntityId.keys)
        assertEquals(before.allEntityIds, after.allEntityIds)
    }

    @Test
    fun membershipDeletionRemovesRowAndMembership() = runTest {
        val before = store.observe().first()
        assertTrue(12L in before.rowsByEntityId)

        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE work_favourites SET deleted_at = 1, anchor_manga_id = NULL WHERE entity_id = 12")

        val after = store.observe().first()
        assertNull(after.rowsByEntityId[12L])
        assertFalse(12L in after.allEntityIds)
        assertFalse(after.membershipsByCategory.values.flatten().any { it.entityId == 12L })
        // quick filter counts no longer include E12's projection source
        assertFalse("OTHER" in after.quickFilterMetadata.sources)
    }

    @Test
    fun categoryChangeOnlyMovesTheMembership() = runTest {
        val before = store.observe().first()
        assertEquals(1, before.membershipsByCategory.getValue(10L).count { it.entityId == 1L })

        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE work_favourites SET category_id = 11 WHERE entity_id = 1 AND category_id = 10")

        val after = store.observe().first()
        assertNull(after.membershipsByCategory[10L]?.firstOrNull { it.entityId == 1L })
        assertNotNull(after.membershipsByCategory.getValue(11L).firstOrNull { it.entityId == 1L })
        // the card row itself is unchanged
        assertEquals(before.rowsByEntityId.getValue(1L), after.rowsByEntityId.getValue(1L))
    }

    @Test
    fun readingNeverWritesToTheDatabase() = runTest {
        val sql = db.openHelper.writableDatabase
        fun count(table: String): Long = sql.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }
        val prefsBefore = count("entity_preferences")
        val favouritesBefore = count("work_favourites")
        val mangaBefore = count("manga")

        store.observe().first()

        assertEquals(prefsBefore, count("entity_preferences"))
        assertEquals(favouritesBefore, count("work_favourites"))
        assertEquals(mangaBefore, count("manga"))
    }

    @Test
    fun legacyOverrideAppliesOnlyWithoutEntityOverride() = runTest {
        // E2 has no entity override; the legacy preferences row on its display manga
        // is the fallback (same priority as getOverridesForWorkItems).
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO preferences (
                manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                cf_book, title_override, cover_override
            ) VALUES (2001, 0, 0, 0, 0, 0, 0, 'Legacy Alpha', '/cover/alpha.jpg')
            """.trimIndent(),
        )
        val snapshot = store.observe().first()
        val e2 = snapshot.rowsByEntityId.getValue(2L)
        assertEquals("Legacy Alpha", e2.overrideTitle)
        assertEquals("Legacy Alpha", e2.resolvedTitle)
        assertEquals("/cover/alpha.jpg", e2.resolvedCoverUrl)
    }

    @Test
    fun emptyLibraryEmitsEmptySnapshot() = runTest {
        db.openHelper.writableDatabase.execSQL("DELETE FROM work_favourites")
        val snapshot = store.observe().first()
        assertEquals(FavouriteLibrarySnapshot.Empty, snapshot)
        assertEquals(0, snapshot.allEntityIds.size)
    }

    // ------------------------------------------------------------------ seeding

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            FavouriteLibrarySeed.insertCategory(sql, 10, "Reading")
            FavouriteLibrarySeed.insertCategory(sql, 11, "Planned")

            // E1: plain row + entity override (entity prefs win over legacy preferences)
            FavouriteLibrarySeed.insertEntity(sql, 1, "E1")
            FavouriteLibrarySeed.insertManga(sql, 1001, "Beta")
            FavouriteLibrarySeed.insertFavourite(sql, 1, 10, 1001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 1, 1001)
            FavouriteLibrarySeed.insertPrefs(sql, 1, titleOverride = "Entity Renamed")
            sql.execSQL(
                """
                INSERT INTO preferences (
                    manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                    cf_book, title_override, cover_override
                ) VALUES (1001, 0, 0, 0, 0, 0, 0, 'Legacy Title', NULL)
                """.trimIndent(),
            )

            // E2: membership in two categories, pinned in the older one
            FavouriteLibrarySeed.insertEntity(sql, 2, "E2")
            FavouriteLibrarySeed.insertManga(sql, 2001, "Alpha")
            FavouriteLibrarySeed.insertFavourite(sql, 2, 10, 2001, pinned = false, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertFavourite(sql, 2, 11, 2001, pinned = true, createdAt = 50, updatedAt = 50)
            FavouriteLibrarySeed.insertBinding(sql, 2, 2001)

            // E3: history progress 0.5
            FavouriteLibrarySeed.insertEntity(sql, 3, "E3")
            FavouriteLibrarySeed.insertManga(sql, 3001, "Gamma")
            FavouriteLibrarySeed.insertFavourite(sql, 3, 10, 3001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 3, 3001)
            FavouriteLibrarySeed.insertHistory(sql, 3, 3001, percent = 0.5f, updatedAt = 500)

            // E5: preferred projection + multi binding
            FavouriteLibrarySeed.insertEntity(sql, 5, "E5")
            FavouriteLibrarySeed.insertManga(sql, 5001, "Epsilon anchor", rating = 0.5f)
            FavouriteLibrarySeed.insertManga(sql, 5002, "Epsilon preferred", rating = 0.9f)
            FavouriteLibrarySeed.insertFavourite(sql, 5, 10, 5001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5001)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5002)
            FavouriteLibrarySeed.insertPrefs(sql, 5, preferredLocalMangaId = 5002)

            // E6: dangling preferred -> broken row
            FavouriteLibrarySeed.insertEntity(sql, 6, "E6")
            FavouriteLibrarySeed.insertManga(sql, 6001, "Zeta")
            FavouriteLibrarySeed.insertFavourite(sql, 6, 10, 6001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 6, 6001)
            FavouriteLibrarySeed.insertPrefs(sql, 6, preferredLocalMangaId = 999_999)

            // E12: tag on the bound projection, display from another source
            FavouriteLibrarySeed.insertEntity(sql, 12, "E12")
            FavouriteLibrarySeed.insertManga(sql, 12001, "Mu display", source = "OTHER")
            FavouriteLibrarySeed.insertManga(sql, 12002, "Mu binding")
            FavouriteLibrarySeed.insertFavourite(sql, 12, 10, 12001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 12, 12002)
            FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")
            FavouriteLibrarySeed.insertMangaTag(sql, 12002, dramaTagId)

            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }
}
