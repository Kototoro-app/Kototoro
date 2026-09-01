package org.skepsun.kototoro.favourites.data

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase

/**
 * Phase 0 performance baseline for the current favourites paging read path
 * (docs/architecture/favourites-komikku-alignment-implementation-plan-2026-09.md,
 * section 11). The 6.5k synthetic library mirrors the user's real backup size
 * (~6.3k entities, 25 categories).
 *
 * The measured numbers are recorded in the migration notes and compared against the
 * new snapshot store once it lands (warm full-snapshot build P95 <= 250 ms budget).
 */
@RunWith(AndroidJUnit4::class)
class FavouriteLibraryBaselineBenchmarkTest {

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        FavouriteLibrarySeed.seedLargeLibrary(db.openHelper.writableDatabase)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fullRepresentativeQueryBaseline() = runTest {
        // Warm-up (page cache)
        db.getWorkFavouritesDao().findLibraryRepresentatives(-1L)
        val t0 = SystemClock.elapsedRealtime()
        val representatives = db.getWorkFavouritesDao().findLibraryRepresentatives(-1L)
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d("FavouriteLibrary", "baseline representatives=${representatives.size} warmMs=$elapsed")
        assertEquals(6_500, representatives.size)
        // Generous guard: this documents the CURRENT cost, not the new budget.
        assertTrue("representative query took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun widePagingRowQueryBaseline() = runTest {
        suspend fun load(): Int {
            val source = db.getWorkFavouritesDao().pagingSource(
                categoryId = -1L,
                orderName = "UPDATED",
                applySpaceFilter = false,
                allowedTypes = emptyList(),
                classifiedTypes = emptyList(),
                applySourceFilter = false,
                allowedSources = emptyList(),
                applyContentTypeFilter = false,
                contentTypes = emptyList(),
                applyPublicationStateFilter = false,
                publicationStates = emptyList(),
                nsfwMode = -1,
                requireDownloaded = false,
                requireNewChapters = false,
                applyExactSourceFilter = false,
                exactSources = emptyList(),
                applyTagFilter = false,
                tagIds = emptyList(),
            )
            var count = 0
            var nextKey: Int? = null
            var refresh = true
            do {
                val params = if (refresh) {
                    androidx.paging.PagingSource.LoadParams.Refresh(nextKey, 500, false)
                } else {
                    androidx.paging.PagingSource.LoadParams.Append(requireNotNull(nextKey), 500, false)
                }
                when (val page = source.load(params)) {
                    is androidx.paging.PagingSource.LoadResult.Page -> {
                        count += page.data.size
                        nextKey = page.nextKey
                    }
                    is androidx.paging.PagingSource.LoadResult.Error -> throw page.throwable
                    is androidx.paging.PagingSource.LoadResult.Invalid -> error("invalidated")
                }
                refresh = false
            } while (nextKey != null)
            return count
        }

        load() // warm-up
        val t0 = SystemClock.elapsedRealtime()
        val count = load()
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d("FavouriteLibrary", "baseline widePagingRows=$count warmMs=$elapsed")
        assertEquals(6_500, count)
        assertTrue("wide paging read took ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun explainQueryPlanForPagingSource() {
        val sql = db.openHelper.writableDatabase
        val plan = sql.query(
            """
            EXPLAIN QUERY PLAN
            WITH selected AS (
                SELECT wf.* FROM work_favourites wf
                WHERE wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0
                    AND (:categoryId = -1 OR wf.category_id = :categoryId)
            )
            SELECT selected.entity_id, m.title
            FROM selected
            LEFT JOIN entity_preferences ep ON ep.entity_id = selected.entity_id
            LEFT JOIN manga m ON m.manga_id = COALESCE(ep.preferred_local_manga_id, selected.anchor_manga_id)
            ORDER BY selected.entity_id
            """.trimIndent(),
        ).use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    appendLine(cursor.getString(0) + " | " + cursor.getString(1) + " | " + cursor.getString(2) + " | " + cursor.getString(3))
                }
            }
        }
        Log.d("FavouriteLibrary", "baseline explainQueryPlan:\n$plan")
        assertTrue(plan.isNotBlank())
    }
}
