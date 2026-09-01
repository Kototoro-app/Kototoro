package org.skepsun.kototoro.favourites.domain.library

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
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
 * Phase 2 performance gate (favourites-komikku-alignment plan, section 11.2):
 * the 10k warm snapshot build (queries + assembly) must fit the 500 ms budget.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FavouriteLibrarySnapshotStoreScaleTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var store: FavouriteLibrarySnapshotStore

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        store = FavouriteLibrarySnapshotStore(db, sourceGroupManager)
        FavouriteLibrarySeed.seedLargeLibrary(db.openHelper.writableDatabase, count = 10_000)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun tenThousandSnapshotBuildFitsBudget() = runTest {
        store.observe().first() // warm-up

        val t0 = SystemClock.elapsedRealtime()
        val snapshot = store.observe().first()
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d("FavouriteLibrary", "snapshotBuild rows=${snapshot.rowsByEntityId.size} warmMs=$elapsed")

        assertEquals(10_000, snapshot.rowsByEntityId.size)
        assertEquals(10_000, snapshot.allEntityIds.size)
        // 10k warm snapshot build budget (section 11.2)
        assertTrue("snapshot build took ${elapsed}ms", elapsed < 500)
    }
}
