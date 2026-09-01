package org.skepsun.kototoro.favourites.data

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase

/**
 * Phase 1 performance / scale validation for the narrow read DAO
 * (favourites-komikku-alignment plan, section 8 Phase 1 exit criteria):
 * - the 10k synthetic library loads through every flow with a FIXED number of DAO
 *   calls (no N+1: one call per flow, regardless of entity count);
 * - rows never carry `description` / `source_data` / full entity graphs (the column
 *   list is the contract; it is asserted by the Room projection itself);
 * - warm full read stays far below the 500 ms snapshot-build budget (the store adds
 *   only in-memory assembly on top of these queries).
 */
@RunWith(AndroidJUnit4::class)
class FavouriteLibraryReadDaoScaleTest {

    private lateinit var db: MangaDatabase
    private lateinit var dao: FavouriteLibraryReadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        dao = db.getFavouriteLibraryReadDao()
        FavouriteLibrarySeed.seedLargeLibrary(db.openHelper.writableDatabase, count = 10_000)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun tenThousandEntitiesLoadThroughEveryFlow() = runTest {
        val base = dao.observeFavouriteCardBaseRows().first()
        assertEquals(10_000, base.size)
        assertEquals(10_000, base.map { it.entityId }.distinct().size)

        val memberships = dao.observeFavouriteMembershipRows().first()
        // every 10th entity has a second membership (see seedLargeLibrary)
        assertEquals(11_000, memberships.size)

        // facets / tags / downloads only reference known entities
        val entities = base.map { it.entityId }.toSet()
        dao.observeFavouriteProjectionFacets().first().forEach { assertTrue(it.entityId in entities) }
        dao.observeDownloadedFavouriteRows().first().forEach { assertTrue(it.entityId in entities) }
        dao.observeFavouriteTagFacets().first().forEach { assertTrue(it.entityId in entities) }
        dao.observeFavouriteLegacyOverrides().first()
    }

    @Test
    fun warmFullReadFitsBudget() = runTest {
        // warm-up (page cache)
        readAll()

        val t0 = SystemClock.elapsedRealtime()
        val rows = readAll()
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d("FavouriteLibrary", "narrowRead rows=${rows.size} warmMs=$elapsed")
        assertEquals(10_000, rows.size)
        // Phase 2 budget for the whole snapshot build on 10k is <= 500 ms; the raw
        // queries must leave room for the in-memory assembly.
        assertTrue("narrow full read took ${elapsed}ms", elapsed < 300)
    }

    private suspend fun readAll(): List<FavouriteCardBaseRow> {
        dao.observeFavouriteMembershipRows().first()
        dao.observeFavouriteProjectionFacets().first()
        dao.observeFavouriteTagFacets().first()
        dao.observeDownloadedFavouriteRows().first()
        dao.observeFavouriteLegacyOverrides().first()
        return dao.observeFavouriteCardBaseRows().first()
    }
}
