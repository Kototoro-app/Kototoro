package org.skepsun.kototoro.favourites.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase

/**
 * Phase 0 semantics characterization for the favourites library SQL
 * (docs/architecture/favourites-komikku-alignment-implementation-plan-2026-09.md,
 * section 4.5 and 5.2).
 *
 * Every test here documents behaviour the new `FavouriteLibraryReadDao` /
 * `FavouriteLibrarySnapshotStore` must reproduce before the paging query can be
 * deleted. When the new read path lands, these tests are migrated onto it
 * (replace-don't-layer) instead of being kept twice.
 *
 * Seeded fixture (categories 10 "Reading" / 11 "Planned" / 12 deleted):
 * - E1  plain entity, one membership in cat 10
 * - E2  membership in 10 (unpinned, newer) + 11 (pinned, older) -> All representative is the pinned cat 11 row
 * - E3  membership in 10 + 11 with identical pinned/created/updated -> representative is the lower category id
 * - E4  membership in 10 + 11 with identical pinned/created -> representative is the newer updated_at
 * - E5  preferred_local_manga_id points at a valid second projection (rating 0.9) -> display manga is the preferred one
 * - E6  preferred_local_manga_id dangles (manga row missing) -> display manga is null at SQL level
 * - E7  anchor has no active binding, binding exists for another projection -> SQL still displays the anchor
 * - E8  display manga flagged nsfw
 * - E9  display manga present in local_index (downloaded)
 * - E10 two tracks -> summed new chapters + maxed dates
 * - E11 history row (percent 0.5 / updated_at 5000)
 * - E12 display manga from source OTHER, a bound projection from source TEST carrying tag 91
 * - E13 display manga of type NOVEL (with a NOVEL binding)
 * - E14 display manga in state ONGOING
 * - E15 "abc" / E16 "XYZ" for case-insensitive alphabetic order
 * - E17 / E18 identical rating 0.1 -> entity id tie-break
 * - E19 / E20 identical created_at 300 -> entity id tie-break
 * - E21 membership without anchor -> invisible
 * - E22 soft-deleted membership -> invisible
 * - E23 MANGA + NOVEL bindings -> excluded from a MANGA-only space filter
 * - E24 membership in the soft-deleted category 12
 * - E25 display manga with a NULL content type
 *
 * Every entity has a distinct representative created_at / updated_at unless the
 * tie-break is under test, so the full order lists below are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class FavouriteLibrarySemanticsCharacterizationTest {

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        seed()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun `newestOrdersPinnedFirstThenRepresentativeCreatedAtDescWithEntityIdTieBreak`() = runTest {
        val order = loadAll(orderName = "NEWEST").map { it.favourite.entityId }
        assertEquals(
            // E2 leads through its pinned representative; E4/E19/E20 share created_at 300
            // and resolve by entity id; the created_at = 10 tail follows in entity id order.
            listOf(2L, 4L, 19L, 20L, 3L, 1L) + longArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 23, 24, 25).toList(),
            order,
        )
    }

    @Test
    fun `oldestKeepsPinnedFirstThenOrdersByRepresentativeCreatedAtAsc`() = runTest {
        val order = loadAll(orderName = "OLDEST").map { it.favourite.entityId }
        assertEquals(
            listOf(2L) + longArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 23, 24, 25).toList() +
                listOf(1L, 3L, 4L, 19L, 20L),
            order,
        )
    }

    @Test
    fun `ratingOrdersByTheDisplayMangaRatingDesc`() = runTest {
        // E5's 0.9 rating lives on the *preferred* projection, proving the sort reads the
        // joined display manga rather than the anchor; E17/E18 share 0.1.
        val order = loadAll(orderName = "RATING").map { it.favourite.entityId }
        assertEquals(listOf(2L, 5L, 17L, 18L), order.take(4))
        assertEquals(0.1f, loadAll().single { it.favourite.entityId == 17L }.displayManga?.rating)
    }

    @Test
    fun `alphabeticOrderIsCaseInsensitiveAndReversible`() = runTest {
        val ascending = loadAll(orderName = "ALPHABETIC").map { it.favourite.entityId }
        assertEquals(
            listOf(
                2L, // Alpha (pinned AND alphabetically first)
                6L, // NULL title (missing display manga) sorts first
                15L, // abc
                1L, // Beta
                24L, // Chi
                4L, // Delta
                5L, // Epsilon preferred
                7L, // Eta anchor
            ),
            ascending.take(8),
        )
        assertEquals(16L, ascending.last()) // XYZ

        val descending = loadAll(orderName = "ALPHABETIC_REVERSE").map { it.favourite.entityId }
        assertEquals(2L, descending.first()) // pinned still leads
        assertEquals(16L, descending[1]) // XYZ
        assertEquals(6L, descending.last()) // NULL title last
    }

    @Test
    fun `lastReadOrdersByHistoryUpdatedAtDesc`() = runTest {
        val order = loadAll(orderName = "LAST_READ").map { it.favourite.entityId }
        assertEquals(2L, order.first()) // pinned, no history
        assertEquals(11L, order[1]) // the only history row
    }

    @Test
    fun `newChaptersOrdersBySummedCountThenLastChapterDateDesc`() = runTest {
        val order = loadAll(orderName = "NEW_CHAPTERS").map { it.favourite.entityId }
        assertEquals(2L, order.first())
        assertEquals(10L, order[1])
    }

    @Test
    fun `updatedFallsBackToTrackingLastChapterDateDesc`() = runTest {
        val order = loadAll(orderName = "UPDATED").map { it.favourite.entityId }
        assertEquals(2L, order.first())
        assertEquals(10L, order[1])
    }

    @Test
    fun `progressOrdersByHistoryPercentDescAndUnreadAsc`() = runTest {
        // pinned dominates every order: E2 (pinned, no history) leads both directions,
        // then E11 (the only history row, percent 0.5) lands first in DESC / last in ASC.
        val progress = loadAll(orderName = "PROGRESS").map { it.favourite.entityId }
        assertEquals(2L, progress.first())
        assertEquals(11L, progress[1])
        val unread = loadAll(orderName = "UNREAD").map { it.favourite.entityId }
        assertEquals(2L, unread.first())
        assertEquals(11L, unread.last())
    }

    @Test
    fun `unknownOrderNamesFallBackToRepresentativeUpdatedAtDesc`() = runTest {
        val order = loadAll(orderName = "WHATEVER").map { it.favourite.entityId }
        // representative updated_at: E3 = 200 > E1 = 100 > E4 = 99 > the created_at = 10 tail.
        assertEquals(listOf(2L, 3L, 1L, 4L), order.take(4))
        assertEquals(longArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 23, 24, 25).toList(), order.drop(4))
    }

    // --------------------------------------------------- dedup & representative

    @Test
    fun `allCategoryReturnsOneRowPerEntityUsingTheRepresentativeMembership`() = runTest {
        val rows = loadAll()
        val byEntity = rows.associateBy { it.favourite.entityId }

        assertEquals(23, rows.size)
        assertEquals(rows.size, rows.map { it.favourite.entityId }.distinct().size)
        // pinned wins over created_at
        assertEquals(11L, byEntity.getValue(2L).favourite.categoryId)
        // identical everything -> lower category id
        assertEquals(10L, byEntity.getValue(3L).favourite.categoryId)
        // identical pinned/created -> newer updated_at
        assertEquals(11L, byEntity.getValue(4L).favourite.categoryId)
        // plain single membership
        assertEquals(10L, byEntity.getValue(1L).favourite.categoryId)
        // unanchored / deleted memberships never show up
        assertNull(byEntity[21L])
        assertNull(byEntity[22L])
    }

    @Test
    fun `categorySlicesKeepTheirOwnMembershipAttributes`() = runTest {
        val cat10 = loadAll(categoryId = 10).associateBy { it.favourite.entityId }
        val cat11 = loadAll(categoryId = 11).associateBy { it.favourite.entityId }

        // The same entity is present in both slices with the slice's own row.
        assertEquals(10L, cat10.getValue(2L).favourite.categoryId)
        assertFalse(cat10.getValue(2L).favourite.isPinned)
        assertEquals(11L, cat11.getValue(2L).favourite.categoryId)
        assertTrue(cat11.getValue(2L).favourite.isPinned)

        // Ordering inside a slice uses the slice's own membership attributes: in cat 11
        // the pinned E2 leads even though its created_at is the oldest, while in cat 10
        // (where E2 is unpinned, created_at = 100) it does not.
        val cat11Order = loadAll(categoryId = 11, orderName = "NEWEST").map { it.favourite.entityId }
        assertEquals(2L, cat11Order.first())
        val cat10Order = loadAll(categoryId = 10, orderName = "NEWEST").map { it.favourite.entityId }
        assertFalse(cat10Order.first() == 2L)
    }

    @Test
    fun `danglingCategoryMembershipIsStillVisibleInAll`() = runTest {
        // Category 12 is soft-deleted; the current query does not join favourite_categories,
        // so E24's membership still shows up. Characterized as-is: the new snapshot must
        // either keep this or fix it consciously (see repairActiveDanglingCategoryRefs).
        val all = loadAll().map { it.favourite.entityId }
        assertTrue(24L in all)
        val cat12 = loadAll(categoryId = 12).map { it.favourite.entityId }
        assertEquals(listOf(24L), cat12)
    }

    // ------------------------------------------------------------- projection

    @Test
    fun `displayMangaFollowsPreferredThenAnchor`() = runTest {
        val byEntity = loadAll().associateBy { it.favourite.entityId }
        // valid preferred projection wins over the anchor
        assertEquals(5002L, byEntity.getValue(5L).displayManga?.id)
        assertEquals(5001L, byEntity.getValue(5L).favourite.anchorMangaId)
        // dangling preferred yields a null display manga at SQL level (the aggregate
        // layer falls back to bindings; see FavouriteLibraryAggregateChainCharacterizationTest)
        assertNull(byEntity.getValue(6L).displayManga)
        // the anchor drives the join even when it is not an active binding
        assertEquals(7001L, byEntity.getValue(7L).displayManga?.id)
    }

    @Test
    fun `trackingSummaryAggregatesAcrossTracks`() = runTest {
        val row = loadAll().single { it.favourite.entityId == 10L }
        assertEquals(5, row.trackingNewChapters)
        assertEquals(2000L, row.trackingLastChapterDate)
        assertEquals(2500L, row.trackingLastCheckTime)
    }

    @Test
    fun `historyJoinsOnEntityId`() = runTest {
        val row = loadAll().single { it.favourite.entityId == 11L }
        assertEquals(0.5f, row.history?.percent)
        assertEquals(5000L, row.history?.updatedAt)
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun `sfwAndNsfwModesFilterOnTheDisplayMangaNsfwFlag`() = runTest {
        val nsfwOnly = loadAll(nsfwMode = 1).map { it.favourite.entityId }
        val sfwOnly = loadAll(nsfwMode = 0).map { it.favourite.entityId }
        assertEquals(listOf(8L), nsfwOnly)
        // E6 (missing display manga -> NULL nsfw) matches neither mode, mirroring the
        // SQL NULL semantics the new snapshot must keep for broken rows.
        assertEquals(21, sfwOnly.size)
        assertFalse(8L in sfwOnly)
        assertTrue(1L in sfwOnly)
    }

    @Test
    fun `downloadedFilterRequiresTheDisplayMangaInLocalIndex`() = runTest {
        val downloaded = loadAll(requireDownloaded = true).map { it.favourite.entityId }
        assertEquals(listOf(9L), downloaded)
    }

    @Test
    fun `newChaptersFilterRequiresAPositiveTrackedCount`() = runTest {
        val withUpdates = loadAll(requireNewChapters = true).map { it.favourite.entityId }
        assertEquals(listOf(10L), withUpdates)
    }

    @Test
    fun `sourceFilterMatchesTheDisplayOrABoundProjectionSource`() = runTest {
        val fromTest = loadAll(exactSources = setOf("TEST")).map { it.favourite.entityId }
        // E12 displays from OTHER but has a TEST projection bound to its entity.
        assertTrue(12L in fromTest)
        val fromOther = loadAll(exactSources = setOf("OTHER")).map { it.favourite.entityId }
        assertEquals(listOf(12L), fromOther)
        val fromMissing = loadAll(exactSources = setOf("MISSING")).map { it.favourite.entityId }
        assertTrue(fromMissing.isEmpty())
    }

    @Test
    fun `tagFilterMatchesTheDisplayOrABoundProjectionTags`() = runTest {
        val tagged = loadAll(tagIds = setOf(91L)).map { it.favourite.entityId }
        // E12's tag lives on the bound projection, not on the display manga.
        assertEquals(listOf(12L), tagged)
    }

    @Test
    fun `publicationStateFilterMatchesTheDisplayMangaState`() = runTest {
        val ongoing = loadAll(publicationStates = setOf("ONGOING")).map { it.favourite.entityId }
        assertEquals(listOf(14L), ongoing)
    }

    @Test
    fun `contentTypeFilterMatchesTheDisplayMangaTypeAndNullPasses`() = runTest {
        val mangaOnly = loadAll(contentTypes = setOf("MANGA")).map { it.favourite.entityId }
        assertFalse(13L in mangaOnly)
        assertTrue(1L in mangaOnly)
        // display manga with a NULL content type is not filtered out
        assertTrue(25L in mangaOnly)
        assertEquals(22, mangaOnly.size)
    }

    @Test
    fun `spaceFilterExcludesEntitiesWithoutAnAllowedTypeBinding`() = runTest {
        val space = loadAll(
            applySpaceFilter = true,
            allowedTypes = listOf("MANGA"),
            classifiedTypes = listOf("MANGA", "NOVEL", "VIDEO"),
        ).map { it.favourite.entityId }
        // E1 has a single MANGA binding -> kept
        assertTrue(1L in space)
        // E13 only binds a NOVEL projection -> excluded
        assertFalse(13L in space)
        // E23 binds both MANGA and NOVEL -> excluded by the NOT EXISTS classified branch
        assertFalse(23L in space)
        // E25's only binding has a NULL content_type; `sm.content_type IN (:allowedTypes)`
        // does not accept NULL (unlike the display-side content-type filter), so it is
        // excluded too.
        assertFalse(25L in space)
        assertEquals(20, space.size)
    }

    @Test
    fun `categoryCountEntriesExposeAnchoredMembershipsPerCategory`() = runTest {
        val entries = db.getWorkFavouritesDao().observeCategoryCountEntries().first()
        val perCategory = entries.groupBy { it.categoryId }
        assertEquals(22, perCategory.getValue(10L).size)
        assertEquals(3, perCategory.getValue(11L).size)
        // E24's dangling category still counts
        assertEquals(1, perCategory.getValue(12L).size)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun loadAll(
        categoryId: Long = -1L,
        orderName: String = "UPDATED",
        applySpaceFilter: Boolean = false,
        allowedTypes: Collection<String> = emptyList(),
        classifiedTypes: Collection<String> = emptyList(),
        contentTypes: Collection<String> = emptyList(),
        publicationStates: Collection<String> = emptyList(),
        nsfwMode: Int = -1,
        requireDownloaded: Boolean = false,
        requireNewChapters: Boolean = false,
        exactSources: Set<String> = emptySet(),
        tagIds: Set<Long> = emptySet(),
    ): List<FavouriteLibraryPagingRow> {
        val source = db.getWorkFavouritesDao().pagingSource(
            categoryId = categoryId,
            orderName = orderName,
            applySpaceFilter = applySpaceFilter,
            allowedTypes = allowedTypes,
            classifiedTypes = classifiedTypes,
            applySourceFilter = false,
            allowedSources = emptyList(),
            applyContentTypeFilter = contentTypes.isNotEmpty(),
            contentTypes = contentTypes,
            applyPublicationStateFilter = publicationStates.isNotEmpty(),
            publicationStates = publicationStates,
            nsfwMode = nsfwMode,
            requireDownloaded = requireDownloaded,
            requireNewChapters = requireNewChapters,
            applyExactSourceFilter = exactSources.isNotEmpty(),
            exactSources = exactSources,
            applyTagFilter = tagIds.isNotEmpty(),
            tagIds = tagIds,
        )
        val result = ArrayList<FavouriteLibraryPagingRow>()
        var nextKey: Int? = null
        var refresh = true
        do {
            val params = if (refresh) {
                PagingSource.LoadParams.Refresh(nextKey, PAGE, false)
            } else {
                PagingSource.LoadParams.Append(requireNotNull(nextKey), PAGE, false)
            }
            when (val loaded = source.load(params)) {
                is PagingSource.LoadResult.Page -> {
                    result += loaded.data
                    nextKey = loaded.nextKey
                }
                is PagingSource.LoadResult.Error -> throw loaded.throwable
                is PagingSource.LoadResult.Invalid -> error("query invalidated while collecting rows")
            }
            refresh = false
        } while (nextKey != null)
        return result
    }

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            FavouriteLibrarySeed.insertCategory(sql, 10, "Reading", deletedAt = 0)
            FavouriteLibrarySeed.insertCategory(sql, 11, "Planned", deletedAt = 0)
            FavouriteLibrarySeed.insertCategory(sql, 12, "Deleted", deletedAt = 1)

            FavouriteLibrarySeed.insertEntity(sql, 1, "E1")
            FavouriteLibrarySeed.insertManga(sql, 1001, "Beta", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 1, 10, 1001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 1, 1001)

            FavouriteLibrarySeed.insertEntity(sql, 2, "E2")
            FavouriteLibrarySeed.insertManga(sql, 2001, "Alpha", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 2, 10, 2001, pinned = false, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertFavourite(sql, 2, 11, 2001, pinned = true, createdAt = 50, updatedAt = 50)
            FavouriteLibrarySeed.insertBinding(sql, 2, 2001)

            FavouriteLibrarySeed.insertEntity(sql, 3, "E3")
            FavouriteLibrarySeed.insertManga(sql, 3001, "Gamma", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 3, 10, 3001, createdAt = 200, updatedAt = 200)
            FavouriteLibrarySeed.insertFavourite(sql, 3, 11, 3001, createdAt = 200, updatedAt = 200)
            FavouriteLibrarySeed.insertBinding(sql, 3, 3001)

            FavouriteLibrarySeed.insertEntity(sql, 4, "E4")
            FavouriteLibrarySeed.insertManga(sql, 4001, "Delta", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 4, 10, 4001, createdAt = 300, updatedAt = 10)
            FavouriteLibrarySeed.insertFavourite(sql, 4, 11, 4001, createdAt = 300, updatedAt = 99)
            FavouriteLibrarySeed.insertBinding(sql, 4, 4001)

            FavouriteLibrarySeed.insertEntity(sql, 5, "E5")
            FavouriteLibrarySeed.insertManga(sql, 5001, "Epsilon anchor", source = "TEST", contentType = "MANGA", rating = 0.5f)
            FavouriteLibrarySeed.insertManga(sql, 5002, "Epsilon preferred", source = "TEST", contentType = "MANGA", rating = 0.9f)
            FavouriteLibrarySeed.insertFavourite(sql, 5, 10, 5001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5001)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5002)
            FavouriteLibrarySeed.insertPrefs(sql, 5, preferredLocalMangaId = 5002)

            FavouriteLibrarySeed.insertEntity(sql, 6, "E6")
            FavouriteLibrarySeed.insertManga(sql, 6001, "Zeta", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 6, 10, 6001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 6, 6001)
            FavouriteLibrarySeed.insertPrefs(sql, 6, preferredLocalMangaId = 999_999)

            FavouriteLibrarySeed.insertEntity(sql, 7, "E7")
            FavouriteLibrarySeed.insertManga(sql, 7001, "Eta anchor", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertManga(sql, 7002, "Eta binding", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 7, 10, 7001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 7, 7002)

            FavouriteLibrarySeed.insertEntity(sql, 8, "E8")
            FavouriteLibrarySeed.insertManga(sql, 8001, "Theta", source = "TEST", contentType = "MANGA", nsfw = true)
            FavouriteLibrarySeed.insertFavourite(sql, 8, 10, 8001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 8, 8001)

            FavouriteLibrarySeed.insertEntity(sql, 9, "E9")
            FavouriteLibrarySeed.insertManga(sql, 9001, "Iota", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 9, 10, 9001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 9, 9001)
            sql.execSQL("INSERT INTO local_index VALUES (?, ?)", arrayOf<Any?>(9001, "/tmp/iota"))

            FavouriteLibrarySeed.insertEntity(sql, 10, "E10")
            FavouriteLibrarySeed.insertManga(sql, 10001, "Kappa", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertManga(sql, 10002, "Kappa alt", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 10, 10, 10001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 10, 10001)
            FavouriteLibrarySeed.insertBinding(sql, 10, 10002)
            // One track per manga (tracks.manga_id is UNIQUE); the entity summary sums
            // chapters_new across every tracked projection of the entity.
            FavouriteLibrarySeed.insertTrack(sql, 10, 10001, newChapters = 2, lastChapterDate = 1000, lastCheckTime = 1500)
            FavouriteLibrarySeed.insertTrack(sql, 10, 10002, newChapters = 3, lastChapterDate = 2000, lastCheckTime = 2500, ownerId = 10_000L)

            FavouriteLibrarySeed.insertEntity(sql, 11, "E11")
            FavouriteLibrarySeed.insertManga(sql, 11001, "Lambda", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 11, 10, 11001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 11, 11001)
            FavouriteLibrarySeed.insertHistory(sql, 11, 11001, percent = 0.5f, updatedAt = 5000)

            FavouriteLibrarySeed.insertEntity(sql, 12, "E12")
            FavouriteLibrarySeed.insertManga(sql, 12001, "Mu display", source = "OTHER", contentType = "MANGA")
            FavouriteLibrarySeed.insertManga(sql, 12002, "Mu binding", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 12, 10, 12001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 12, 12002)
            FavouriteLibrarySeed.insertTag(sql, 91, "Drama")
            sql.execSQL("INSERT INTO manga_tags VALUES (?, ?)", arrayOf<Any?>(12002, 91))

            FavouriteLibrarySeed.insertEntity(sql, 13, "E13", contentType = "NOVEL")
            FavouriteLibrarySeed.insertManga(sql, 13001, "Nu", source = "TEST", contentType = "NOVEL")
            FavouriteLibrarySeed.insertFavourite(sql, 13, 10, 13001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 13, 13001)

            FavouriteLibrarySeed.insertEntity(sql, 14, "E14")
            FavouriteLibrarySeed.insertManga(sql, 14001, "Xi", source = "TEST", contentType = "MANGA", state = "ONGOING")
            FavouriteLibrarySeed.insertFavourite(sql, 14, 10, 14001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 14, 14001)

            FavouriteLibrarySeed.insertEntity(sql, 15, "E15")
            FavouriteLibrarySeed.insertManga(sql, 15001, "abc", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 15, 10, 15001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 15, 15001)

            FavouriteLibrarySeed.insertEntity(sql, 16, "E16")
            FavouriteLibrarySeed.insertManga(sql, 16001, "XYZ", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 16, 10, 16001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 16, 16001)

            FavouriteLibrarySeed.insertEntity(sql, 17, "E17")
            FavouriteLibrarySeed.insertManga(sql, 17001, "Omicron", source = "TEST", contentType = "MANGA", rating = 0.1f)
            FavouriteLibrarySeed.insertFavourite(sql, 17, 10, 17001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 17, 17001)

            FavouriteLibrarySeed.insertEntity(sql, 18, "E18")
            FavouriteLibrarySeed.insertManga(sql, 18001, "Pi", source = "TEST", contentType = "MANGA", rating = 0.1f)
            FavouriteLibrarySeed.insertFavourite(sql, 18, 10, 18001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 18, 18001)

            FavouriteLibrarySeed.insertEntity(sql, 19, "E19")
            FavouriteLibrarySeed.insertManga(sql, 19001, "Rho", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 19, 10, 19001, createdAt = 300, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 19, 19001)

            FavouriteLibrarySeed.insertEntity(sql, 20, "E20")
            FavouriteLibrarySeed.insertManga(sql, 20001, "Sigma", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 20, 10, 20001, createdAt = 300, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 20, 20001)

            FavouriteLibrarySeed.insertEntity(sql, 21, "E21")
            FavouriteLibrarySeed.insertManga(sql, 21001, "Tau", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 21, 10, null, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 21, 21001)

            FavouriteLibrarySeed.insertEntity(sql, 22, "E22")
            FavouriteLibrarySeed.insertManga(sql, 22001, "Upsilon", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 22, 10, 22001, createdAt = 10, updatedAt = 10, deletedAt = 5)
            FavouriteLibrarySeed.insertBinding(sql, 22, 22001)

            FavouriteLibrarySeed.insertEntity(sql, 23, "E23")
            FavouriteLibrarySeed.insertManga(sql, 23001, "Phi anchor", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertManga(sql, 23002, "Phi novel", source = "TEST", contentType = "NOVEL")
            FavouriteLibrarySeed.insertFavourite(sql, 23, 10, 23001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 23, 23001)
            FavouriteLibrarySeed.insertBinding(sql, 23, 23002)

            FavouriteLibrarySeed.insertEntity(sql, 24, "E24")
            FavouriteLibrarySeed.insertManga(sql, 24001, "Chi", source = "TEST", contentType = "MANGA")
            FavouriteLibrarySeed.insertFavourite(sql, 24, 12, 24001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 24, 24001)

            FavouriteLibrarySeed.insertEntity(sql, 25, "E25")
            FavouriteLibrarySeed.insertManga(sql, 25001, "Psi", source = "TEST", contentType = null)
            FavouriteLibrarySeed.insertFavourite(sql, 25, 10, 25001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 25, 25001)

            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private companion object {
        const val PAGE = 500
    }
}
