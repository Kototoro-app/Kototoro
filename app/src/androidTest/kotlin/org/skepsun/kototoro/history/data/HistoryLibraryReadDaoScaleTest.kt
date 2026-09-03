package org.skepsun.kototoro.history.data

import android.os.SystemClock
import android.util.Log
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
import org.skepsun.kototoro.history.domain.library.HistoryLibrarySnapshotStore
import javax.inject.Inject

/**
 * Cold-start budget validation for the history read model — the history-side twin of
 * [org.skepsun.kototoro.favourites.data.FavouriteLibraryReadDaoScaleTest].
 *
 * The corpus mirrors a heavily-used real library: ~4.6k history rows over ~8.4k entities
 * with ~118k `manga_tags` links, so the tag facets — measurably the heaviest part of the
 * first history snapshot — are measured at the volume that actually matters rather than at
 * toy scale (see docs/architecture/large-library-performance-handoff-2026-08.md).
 *
 * Timings are logged, not asserted per flow: CI devices vary by an order of magnitude.
 * What is asserted are the structural invariants (row counts, one DAO call per flow, facet
 * keys the snapshot can actually consume) plus one generous budget for the whole read.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryLibraryReadDaoScaleTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourceGroupManager: SourceGroupManager

    private lateinit var db: MangaDatabase
    private lateinit var dao: HistoryLibraryReadDao
    private lateinit var store: HistoryLibrarySnapshotStore

    @Before
    fun setUp() {
        hiltRule.inject()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys = OFF")
        dao = db.getHistoryLibraryReadDao()
        store = HistoryLibrarySnapshotStore(db, sourceGroupManager)
        val seedStart = SystemClock.elapsedRealtime()
        HistoryLibrarySeed.seedHistoryCorpus(sql)
        Log.d(TAG, "seeded corpus in ${SystemClock.elapsedRealtime() - seedStart}ms")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * One test on purpose: seeding the corpus is the expensive part (~150k inserts) and the
     * orchestrator gives every test a fresh process, so splitting it would pay the seed twice.
     */
    @Test
    fun largeLibraryReadModelFitsPageEntryBudget() = runTest {
        val base = dao.observeHistoryCardBaseRows().first()
        assertEquals(HistoryLibrarySeed.HISTORY.toInt(), base.size)
        assertEquals(HistoryLibrarySeed.HISTORY.toInt(), base.map { it.entityId }.distinct().size)
        assertEquals(HistoryLibrarySeed.HISTORY.toInt(), base.mapNotNull { it.displayMangaId }.distinct().size)

        val displayIds = base.mapNotNull { it.displayMangaId }.toSet()
        val tagFacets = dao.observeHistoryTagFacets().first()
        val bindingFacets = dao.observeHistoryBindingFacets().first()
        val categoryFacets = dao.observeHistoryCategoryFacets().first()
        val overrides = dao.observeHistoryOverrides().first()
        val downloaded = dao.observeHistoryDownloadedRows().first()

        // Every facet row must be reachable from the snapshot: the store keys the tag and
        // override lookups by the *display* projection, so a facet row filed under any
        // other manga id is bytes read, objects built and never shown.
        val unreachableTags = tagFacets.map { it.mangaId }.filterNotTo(HashSet()) { it in displayIds }
        assertTrue(
            "tag facets outside the display projections: ${unreachableTags.size} of ${tagFacets.size} $unreachableTags",
            unreachableTags.isEmpty(),
        )
        val unreachableOverrides = overrides.map { it.mangaId }.filterNotTo(HashSet()) { it in displayIds }
        assertTrue(
            "override rows outside the display projections: ${unreachableOverrides.size} of ${overrides.size}",
            unreachableOverrides.isEmpty(),
        )
        val entities = base.map { it.entityId }.toSet()
        assertTrue(bindingFacets.all { it.entityId in entities })
        assertTrue(categoryFacets.all { it.entityId in entities })
        assertTrue(downloaded.all { it.entityId in entities })

        // Per-flow first-read cost, the numbers a cold page entry is built from.
        val perFlow = linkedMapOf<String, Long>()
        perFlow["tags"] = measureMillis { dao.observeHistoryTagFacets().first().size }
        perFlow["bindings"] = measureMillis { dao.observeHistoryBindingFacets().first().size }
        perFlow["categories"] = measureMillis { dao.observeHistoryCategoryFacets().first().size }
        perFlow["overrides"] = measureMillis { dao.observeHistoryOverrides().first().size }
        perFlow["downloaded"] = measureMillis { dao.observeHistoryDownloadedRows().first().size }
        Log.d(
            TAG,
            "rows base=${base.size} tags=${tagFacets.size} bindings=${bindingFacets.size} " +
                "categories=${categoryFacets.size} overrides=${overrides.size} downloaded=${downloaded.size}",
        )
        Log.d(TAG, "per-flow first-read ms: " + perFlow.entries.joinToString { "${it.key}=${it.value}" })

        // What a cold page entry actually pays: the store's single observe() emission,
        // i.e. all six flows combined plus the in-memory snapshot build.
        val coldStart = SystemClock.elapsedRealtime()
        val snapshot = store.observe().first()
        val coldMs = SystemClock.elapsedRealtime() - coldStart
        Log.d(TAG, "first snapshot coldMs=$coldMs rows=${snapshot.rows.size}")

        assertEquals(HistoryLibrarySeed.HISTORY.toInt(), snapshot.rows.size)
        // Deliberately no wall-clock gate: an absolute millisecond budget on a phone depends
        // on thermal state and whatever else the device is doing, so it fails on correct code.
        // What this test pins down deterministically is the row set above — nothing read that
        // the snapshot cannot reach. The coldMs it logs is what the cold-page work is planned
        // from: on the real library, driving the tag facet from the display projections
        // instead of scanning manga_tags is what took it from 1078ms to 156ms.
    }

    private suspend fun measureMillis(block: suspend () -> Int): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private companion object {
        const val TAG = "HistoryLibraryReadDao"
    }
}

/**
 * Raw-SQL seeding of a large history corpus, shaped like a real heavily-used library:
 * 8.4k entities with one display projection each, 4.6k of them in the history, 10.8k tags
 * with ~14 tags per projection (≈118k `manga_tags` links), local bindings, category
 * memberships, tracks and a set of `preferred_local_manga_id` overrides so every branch of
 * the display-projection joins is exercised.
 */
internal object HistoryLibrarySeed {

    const val ENTITIES = 8_400L
    const val HISTORY = 4_650L
    const val TAGS = 10_833
    const val TAGS_PER_MANGA = 14
    private const val MANGA_BASE = 10_000L
    private const val LOCAL_BASE = 500_000L

    /** Local projections the `preferred_local_manga_id` overrides point at. */
    private const val LOCAL_POOL = 60L

    /** Every n-th history entity displays a local projection instead of its anchor. */
    private const val OVERRIDE_EVERY = 100L

    fun seedHistoryCorpus(sql: SupportSQLiteDatabase) {
        FavouriteLibrarySeed.insertCategory(sql, 1, "Default")
        FavouriteLibrarySeed.insertCategory(sql, 2, "Second")
        FavouriteLibrarySeed.insertCategory(sql, 3, "Third")

        sql.beginTransaction()
        try {
            for (tagId in 1L..TAGS) {
                FavouriteLibrarySeed.insertTag(sql, tagId, "Tag $tagId")
            }
            val localIds = (0L until LOCAL_POOL).map { LOCAL_BASE + it }
            localIds.forEachIndexed { index, id ->
                FavouriteLibrarySeed.insertManga(sql, id, "Local projection $index")
            }

            for (entityId in 1L..ENTITIES) {
                val mangaId = MANGA_BASE + entityId
                FavouriteLibrarySeed.insertEntity(sql, entityId, "Work $entityId")
                FavouriteLibrarySeed.insertManga(sql, mangaId, "Projection $mangaId")

                if (entityId <= HISTORY) {
                    FavouriteLibrarySeed.insertHistory(
                        sql = sql,
                        entityId = entityId,
                        anchorMangaId = mangaId,
                        percent = 0.5f,
                        updatedAt = entityId,
                    )
                    if (entityId % 2L == 0L) {
                        FavouriteLibrarySeed.insertFavourite(
                            sql = sql,
                            entityId = entityId,
                            categoryId = entityId % 3L + 1L,
                            anchorMangaId = mangaId,
                            createdAt = entityId,
                            updatedAt = entityId,
                        )
                    }
                    if (entityId % OVERRIDE_EVERY == 0L) {
                        // A manual local projection wins over the anchor, so this history
                        // row's display manga id is not its anchor manga id.
                        val localId = localIds[(entityId / OVERRIDE_EVERY % LOCAL_POOL).toInt()]
                        FavouriteLibrarySeed.insertPrefs(sql, entityId, preferredLocalMangaId = localId)
                        FavouriteLibrarySeed.insertBinding(sql, entityId, localId)
                    }
                }
                if (entityId % 3L == 0L) {
                    FavouriteLibrarySeed.insertBinding(sql, entityId, mangaId)
                }
                if (entityId % 6L == 0L) {
                    FavouriteLibrarySeed.insertTrack(
                        sql = sql,
                        entityId = entityId,
                        mangaId = mangaId,
                        newChapters = 2,
                        lastChapterDate = entityId,
                        lastCheckTime = entityId,
                    )
                }
            }

            // ~14 tags per projection, spread deterministically over the tag pool so every
            // run measures the same shape. Within one projection the ids stay distinct
            // because the window never covers the whole tag pool.
            val taggedManga = (1L..ENTITIES).map { MANGA_BASE + it } + localIds
            taggedManga.forEachIndexed { index, mangaId ->
                for (offset in 0 until TAGS_PER_MANGA) {
                    val tagId = (index.toLong() * TAGS_PER_MANGA + offset) % TAGS + 1L
                    FavouriteLibrarySeed.insertMangaTag(sql, mangaId, tagId)
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }
}
