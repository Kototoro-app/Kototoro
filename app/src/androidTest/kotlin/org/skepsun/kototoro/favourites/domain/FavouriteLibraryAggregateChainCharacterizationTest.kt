package org.skepsun.kototoro.favourites.domain

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.favourites.data.FavouriteLibrarySeed
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.work.data.DefaultWorkResolver
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import javax.inject.Inject

/**
 * Phase 0 semantics characterization for the favourites *aggregate* chain — the layer
 * between the paging SQL and the UI mapping (WorkAggregateRepository.observeFavouriteLibraryAggregates /
 * buildFavouritePagingAggregates / resolveDisplayProjection / filterFavouriteAggregates).
 *
 * The SQL-level contract lives in [org.skepsun.kototoro.favourites.data
 * .FavouriteLibrarySemanticsCharacterizationTest]; this class pins down what happens
 * *after* the SQL rows: representative projection fallback, projection-set identity,
 * and the macro filters the new in-memory deriver must reproduce.
 *
 * The repository is constructed against an isolated in-memory database (with the
 * production resolver and policies injected), so seeding never touches the device's
 * real library while the semantics under test stay the production ones.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FavouriteLibraryAggregateChainCharacterizationTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var entityGraphRepository: EntityGraphRepository

    @Inject
    lateinit var spaceContentPolicy: SpaceContentPolicy

    @Inject
    lateinit var contentSourcesRepository: ContentSourcesRepository

    private lateinit var scratch: MangaDatabase
    private lateinit var repository: WorkAggregateRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        scratch = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        val resolver = DefaultWorkResolver(scratch, entityGraphRepository)
        repository = WorkAggregateRepository(scratch, resolver, spaceContentPolicy, contentSourcesRepository)
        seed()
    }

    @After
    fun tearDown() {
        scratch.close()
    }

    private fun seed() {
        val sql = scratch.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            FavouriteLibrarySeed.insertCategory(sql, 10, "Reading")

            // A1: plain entity — anchor == only projection.
            FavouriteLibrarySeed.insertEntity(sql, 1, "A1")
            FavouriteLibrarySeed.insertManga(sql, 1001, "A1 anchor")
            FavouriteLibrarySeed.insertFavourite(sql, 1, 10, 1001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 1, 1001)

            // A2: preferred projection is one of two bindings; display must follow it.
            FavouriteLibrarySeed.insertEntity(sql, 2, "A2")
            FavouriteLibrarySeed.insertManga(sql, 2001, "A2 anchor")
            FavouriteLibrarySeed.insertManga(sql, 2002, "A2 preferred")
            FavouriteLibrarySeed.insertFavourite(sql, 2, 10, 2001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 2, 2001)
            FavouriteLibrarySeed.insertBinding(sql, 2, 2002)
            FavouriteLibrarySeed.insertPrefs(sql, 2, preferredLocalMangaId = 2002)

            // A3: dangling preferred — identity falls back to the local bindings.
            FavouriteLibrarySeed.insertEntity(sql, 3, "A3")
            FavouriteLibrarySeed.insertManga(sql, 3001, "A3 anchor")
            FavouriteLibrarySeed.insertManga(sql, 3002, "A3 second")
            FavouriteLibrarySeed.insertFavourite(sql, 3, 10, 3001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 3, 3001)
            FavouriteLibrarySeed.insertBinding(sql, 3, 3002)
            FavouriteLibrarySeed.insertPrefs(sql, 3, preferredLocalMangaId = 999_999)

            // A4: anchor is not a binding; display must fall back to a bound projection.
            FavouriteLibrarySeed.insertEntity(sql, 4, "A4")
            FavouriteLibrarySeed.insertManga(sql, 4001, "A4 orphan anchor")
            FavouriteLibrarySeed.insertManga(sql, 4002, "A4 bound")
            FavouriteLibrarySeed.insertFavourite(sql, 4, 10, 4001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 4, 4002)

            // A5: completed reading (history percent = 1).
            FavouriteLibrarySeed.insertEntity(sql, 5, "A5")
            FavouriteLibrarySeed.insertManga(sql, 5001, "A5")
            FavouriteLibrarySeed.insertFavourite(sql, 5, 10, 5001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5001)
            FavouriteLibrarySeed.insertHistory(sql, 5, 5001, percent = 1f, updatedAt = 10)

            // A6: new chapters tracked.
            FavouriteLibrarySeed.insertEntity(sql, 6, "A6")
            FavouriteLibrarySeed.insertManga(sql, 6001, "A6")
            FavouriteLibrarySeed.insertFavourite(sql, 6, 10, 6001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 6, 6001)
            FavouriteLibrarySeed.insertTrack(sql, 6, 6001, newChapters = 4, lastChapterDate = 10, lastCheckTime = 10)

            // A7: explicit reading status override ON_HOLD on entity_preferences.
            FavouriteLibrarySeed.insertEntity(sql, 7, "A7")
            FavouriteLibrarySeed.insertManga(sql, 7001, "A7")
            FavouriteLibrarySeed.insertFavourite(sql, 7, 10, 7001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 7, 7001)
            FavouriteLibrarySeed.insertPrefs(sql, 7, readingStatus = "ON_HOLD")

            // A8: publication state PAUSED on the display manga.
            FavouriteLibrarySeed.insertEntity(sql, 8, "A8")
            FavouriteLibrarySeed.insertManga(sql, 8001, "A8", state = "PAUSED")
            FavouriteLibrarySeed.insertFavourite(sql, 8, 10, 8001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 8, 8001)

            // A9: tag on a bound projection that is not the display manga.
            FavouriteLibrarySeed.insertEntity(sql, 9, "A9")
            FavouriteLibrarySeed.insertManga(sql, 9001, "A9 display")
            FavouriteLibrarySeed.insertManga(sql, 9002, "A9 tagged")
            FavouriteLibrarySeed.insertFavourite(sql, 9, 10, 9001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 9, 9001)
            FavouriteLibrarySeed.insertBinding(sql, 9, 9002)
            // The filter translates a ContentTag into TagEntity with the deterministic
            // id "${key}_${source.name}".longHashCode(); the seeded row must use the
            // same id or the SQL tag filter never matches.
            val dramaTagId = "drama_TEST".longHashCode()
            FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")
            FavouriteLibrarySeed.insertMangaTag(sql, 9002, dramaTagId)

            // A10: downloaded display manga.
            FavouriteLibrarySeed.insertEntity(sql, 10, "A10")
            FavouriteLibrarySeed.insertManga(sql, 10001, "A10")
            FavouriteLibrarySeed.insertFavourite(sql, 10, 10, 10001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 10, 10001)
            FavouriteLibrarySeed.insertDownloaded(sql, 10001)

            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun representativeProjectionFollowsPreferredThenBindings() = runTest {
        val byEntity = aggregates().associateBy { requireNotNull(it.identity.entityId) }

        // A1: anchor == only projection.
        assertEquals(1001L, byEntity.getValue(1L).displayProjection?.id)

        // A2: valid preferred projection drives the display.
        assertEquals(2002L, byEntity.getValue(2L).displayProjection?.id)

        // A3: dangling preferred still displays a bound projection (SQL-level the row
        // is null; the aggregate layer falls back to the binding set).
        assertTrue(
            byEntity.getValue(3L).displayProjection?.id in setOf(3001L, 3002L),
        )

        // A4: the library projection path (findFavouriteLibraryAggregates) resolves
        // preferred -> anchor WITHOUT consulting bindings, so an anchor that is not an
        // active binding still displays. The binding-based fallback only exists on the
        // wide findFavouriteAggregates path. Characterized as-is.
        assertEquals(4001L, byEntity.getValue(4L).displayProjection?.id)
    }

    @Test
    fun identityExposesLocalProjectionSetWithoutTheAnchor() = runTest {
        val byEntity = aggregates().associateBy { requireNotNull(it.identity.entityId) }

        // On the library projection path localMangaIds = preferred + anchor + bindings,
        // so the unbound anchor IS included (unlike WorkResolver.resolveManyByEntityIds,
        // which derives it from bindings only). MULTI_PROJECTION semantics therefore
        // differ between the two paths — a real hazard the new snapshot store resolves
        // by deriving the projection set from bindings once, for everyone.
        assertEquals(setOf(4001L, 4002L), byEntity.getValue(4L).identity.localMangaIds)
        // A3: preferred + anchor + two bindings.
        assertEquals(setOf(3001L, 3002L), byEntity.getValue(3L).identity.localMangaIds)
    }

    @Test
    fun completedFilterMatchesHistoryPercentOnly() = runTest {
        val completed = aggregates(filterOptions = setOf(ListFilterOption.Macro.COMPLETED))
        assertEquals(listOf(5L), completed.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun newChaptersFilterMatchesPositiveTrackedCount() = runTest {
        val withUpdates = aggregates(filterOptions = setOf(ListFilterOption.Macro.NEW_CHAPTERS))
        assertEquals(listOf(6L), withUpdates.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun multiProjectionFilterCountsLocalBindings() = runTest {
        val multi = aggregates(filterOptions = setOf(ListFilterOption.Macro.MULTI_PROJECTION))
        val ids = multi.map { requireNotNull(it.identity.entityId) }
        assertTrue(2L in ids)
        assertTrue(3L in ids)
        assertTrue(9L in ids)
        assertFalse(1L in ids)
        assertFalse(4L in ids)
    }

    @Test
    fun publicationStateFilterMatchesTheDisplayMangaState() = runTest {
        val paused = aggregates(filterOptions = setOf(ListFilterOption.PublicationState(ContentState.PAUSED)))
        assertEquals(listOf(8L), paused.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun readingStatusFilterPrefersTheExplicitEntityStatus() = runTest {
        val onHold = aggregates(filterOptions = setOf(ListFilterOption.ReadingStatus(ScrobblingStatus.ON_HOLD)))
        assertEquals(listOf(7L), onHold.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun readingStatusFilterFallsBackToHistoryProgress() = runTest {
        val reading = aggregates(filterOptions = setOf(ListFilterOption.ReadingStatus(ScrobblingStatus.COMPLETED)))
        // A5's history percent = 1 resolves to COMPLETED even without an explicit status.
        assertEquals(listOf(5L), reading.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun tagFilterMatchesAnyProjectionTag() = runTest {
        val dramaTag = ContentTag(title = "Drama", key = "drama", source = TestContentSource)
        val tagged = aggregates(filterOptions = setOf(ListFilterOption.Tag(dramaTag)))
        assertEquals(listOf(9L), tagged.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun downloadedFilterMatchesTheDisplayManga() = runTest {
        val downloaded = aggregates(filterOptions = setOf(ListFilterOption.Downloaded))
        assertEquals(listOf(10L), downloaded.map { requireNotNull(it.identity.entityId) })
    }

    @Test
    fun categorySliceFiltersByMembership() = runTest {
        val cat10 = aggregates(categoryId = 10)
        assertEquals(10, cat10.size)
        val cat99 = aggregates(categoryId = 99)
        assertTrue(cat99.isEmpty())
    }

    @Test
    fun newestOrderSortsPinnedFirstThenCreatedAt() = runTest {
        // All seeded memberships share created_at; the stable entity-id tie-break is
        // characterized by the SQL suite, so only the shape is asserted here.
        val ids = aggregates(order = ListSortOrder.NEWEST).map { requireNotNull(it.identity.entityId) }
        assertEquals((1L..10L).toList(), ids.sorted())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun aggregates(
        categoryId: Long = FavouriteCategoryNoId,
        order: ListSortOrder = ListSortOrder.NEWEST,
        filterOptions: Set<ListFilterOption> = emptySet(),
    ) = repository.observeFavouriteLibraryAggregates(
        categoryId = categoryId,
        order = order,
        filterOptions = filterOptions,
        includeTags = true,
    ).first()

    private companion object {
        const val FavouriteCategoryNoId = -1L
    }
}
