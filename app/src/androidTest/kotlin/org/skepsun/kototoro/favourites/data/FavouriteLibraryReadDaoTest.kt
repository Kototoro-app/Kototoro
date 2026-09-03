package org.skepsun.kototoro.favourites.data

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
import org.skepsun.kototoro.parsers.util.longHashCode

/**
 * Semantics tests for the narrow favourites read DAO
 * (favourites-komikku-alignment plan, section 5.1 / 5.2).
 *
 * The fixture mirrors [FavouriteLibrarySemanticsCharacterizationTest] so the semantics
 * documented there are directly comparable here; once the snapshot store replaces the
 * paging path, the characterization suite is deleted and this file remains the
 * authoritative contract.
 */
@RunWith(AndroidJUnit4::class)
class FavouriteLibraryReadDaoTest {

    private lateinit var db: MangaDatabase
    private lateinit var dao: FavouriteLibraryReadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
        dao = db.getFavouriteLibraryReadDao()
        seed()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ base rows

    @Test
    fun baseRowsReturnOneRowPerEntityWithRepresentativeMembership() = runTest {
        val rows = dao.observeFavouriteCardBaseRows().first()
        val byEntity = rows.associateBy { it.entityId }

        // one row per entity
        assertEquals(rows.size, rows.map { it.entityId }.distinct().size)
        // pinned wins over created_at (E2's representative is the older cat-11 row)
        assertEquals(11L, byEntity.getValue(2L).let { it.entityId }.let { representeeOf(it) })
        assertEquals(true, representativeIsPinned(2L))
        // identical everything -> lower category id
        assertEquals(10L, representeeOf(3L))
        // identical pinned/created -> newer updated_at
        assertEquals(11L, representeeOf(4L))
        // unanchored / deleted memberships never show up
        assertNull(byEntity[21L])
        assertNull(byEntity[22L])
    }

    @Test
    fun metadataAuthorityReadsTheCachedSiteItem() = runTest {
        val byEntity = dao.observeFavouriteCardBaseRows().first().associateBy { it.entityId }

        // 'tracking' authority with a cached item: service + title + cover arrive together
        val tracking = byEntity.getValue(25L)
        assertEquals(3, tracking.metadataTrackingService)
        assertEquals("Site Title", tracking.metadataTrackingTitle)
        assertEquals("https://site/cover.jpg", tracking.metadataTrackingCoverUrl)

        // authority without a cached item stays entirely null (no half-resolved display)
        val missing = byEntity.getValue(26L)
        assertNull(missing.metadataTrackingService)
        assertNull(missing.metadataTrackingTitle)
        assertNull(missing.metadataTrackingCoverUrl)

        // 'base' authority never joins, even when a site item would match by id
        val base = byEntity.getValue(27L)
        assertNull(base.metadataTrackingService)
        assertNull(base.metadataTrackingTitle)

        // no prefs row at all
        assertNull(byEntity.getValue(1L).metadataTrackingService)
    }

    @Test
    fun displayMangaFollowsPreferredThenAnchorAndKeepsBrokenRows() = runTest {
        val byEntity = dao.observeFavouriteCardBaseRows().first().associateBy { it.entityId }

        // preferred projection wins (E5)
        assertEquals(5002L, byEntity.getValue(5L).displayMangaId)
        assertEquals("Epsilon preferred", byEntity.getValue(5L).displayTitle)
        // anchor fallback (E1)
        assertEquals(1001L, byEntity.getValue(1L).displayMangaId)
        // dangling preferred -> broken row survives with null display (E6)
        assertNull(byEntity.getValue(6L).displayMangaId)
        assertNull(byEntity.getValue(6L).displayTitle)
        assertTrue(byEntity.getValue(6L).hasDisplay.not())
    }

    @Test
    fun baseRowsCarrySortAndFilterFields() = runTest {
        val byEntity = dao.observeFavouriteCardBaseRows().first().associateBy { it.entityId }

        // history (E11)
        assertEquals(0.5f, byEntity.getValue(11L).historyPercent)
        assertEquals(5000L, byEntity.getValue(11L).historyUpdatedAt)
        // tracking aggregate (E10: 2 + 3 chapters over two tracks)
        assertEquals(5, byEntity.getValue(10L).trackingNewChapters)
        assertEquals(2000L, byEntity.getValue(10L).trackingLastChapterDate)
        // display fields (E14)
        assertEquals("ONGOING", byEntity.getValue(14L).displayState)
        assertEquals(true, byEntity.getValue(8L).displayNsfw)
        assertEquals(0.9f, byEntity.getValue(5L).displayRating)
        // entity prefs reading status + overrides (E7 / E16)
        assertEquals("ON_HOLD", byEntity.getValue(7L).readingStatus)
        assertEquals("Renamed", byEntity.getValue(16L).titleOverride)
        // entity content type (E13 NOVEL entity)
        assertEquals("NOVEL", byEntity.getValue(13L).entityContentType)
        // alt title (E15 subtitle source)
        assertEquals("Alternate", byEntity.getValue(15L).displayAltTitle)
    }

    // ---------------------------------------------------------------- memberships

    @Test
    fun membershipRowsExposeEveryActiveMembership() = runTest {
        val memberships = dao.observeFavouriteMembershipRows().first()
        val byEntity = memberships.groupBy { it.entityId }

        assertEquals(2, byEntity.getValue(2L).size)
        assertEquals(true, byEntity.getValue(2L).first { it.categoryId == 11L }.isPinned)
        assertEquals(false, byEntity.getValue(2L).first { it.categoryId == 10L }.isPinned)
        // unanchored (E21) and deleted (E22) are excluded
        assertNull(byEntity[21L])
        assertNull(byEntity[22L])
        // dangling-category membership (E24) is still listed, matching the legacy SQL
        assertTrue(byEntity.getValue(24L).any { it.categoryId == 12L })
    }

    // -------------------------------------------------------------------- facets

    @Test
    fun projectionFacetsAreBindingBased() = runTest {
        val facets = dao.observeFavouriteProjectionFacets().first().groupBy { it.entityId }

        // E1: single binding
        assertEquals(setOf(1001L), facets.getValue(1L).map { it.mangaId }.toSet())
        // E4: the unbound anchor is NOT a facet (binding-based, MULTI_PROJECTION safe)
        assertEquals(setOf(4002L), facets.getValue(4L).map { it.mangaId }.toSet())
        // E3: two bindings from different sources
        val e3Sources = facets.getValue(3L).map { it.source }.toSet()
        assertTrue("TEST" in e3Sources)
        assertTrue("OTHER" in e3Sources)
        // E13: candidate-state binding is excluded
        assertFalse(facets.getValue(13L).any { it.mangaId == 13002L })
    }

    @Test
    fun tagRelationsCoverEveryBoundProjectionAndResolveThroughTheDictionary() = runTest {
        val relations = dao.observeFavouriteTagIdRows().first().groupBy { it.entityId }
        val dictionary = dao.observeFavouriteTagDictionary().first().associateBy { it.tagId }

        // E12: the tag lives on the bound projection, not the display manga
        val e12Tags = relations.getValue(12L).map { dictionary.getValue(it.tagId).tagTitle }.toSet()
        assertTrue("Drama" in e12Tags)
        // tag identity uses the deterministic TagEntity id
        val dramaTagId = "drama_TEST".longHashCode()
        assertTrue(dramaTagId in relations.getValue(12L).map { it.tagId })

        // The two flows must compose: every relation resolves to the identity and title the
        // filter and the detailed-list chip show. Per-entity rows carry ids only, so the tag
        // strings travel once per tag instead of once per entity-tag pair.
        val allRelations = dao.observeFavouriteTagIdRows().first()
        assertTrue(allRelations.all { it.tagId in dictionary })
        assertTrue(dictionary.values.all { it.tagTitle.isNotEmpty() && it.tagKey.isNotEmpty() })
    }

    @Test
    fun downloadedRowsMapTheLocalIndexOntoEntities() = runTest {
        val downloaded = dao.observeDownloadedFavouriteRows().first()
        val entityIds = downloaded.map { it.entityId }.toSet()

        // E9: display manga in local_index
        assertTrue(9L in entityIds)
        // E17: download only on the secondary binding still counts
        assertTrue(17L in entityIds)
        // E1 has no download
        assertFalse(1L in entityIds)
    }

    @Test
    fun legacyOverridesExposeTitleAndCoverOnly() = runTest {
        val overrides = dao.observeFavouriteLegacyOverrides().first().associateBy { it.mangaId }

        assertEquals("Legacy Title", overrides.getValue(1001L)?.titleOverride)
        assertEquals("/cover/legacy.jpg", overrides.getValue(1001L)?.coverOverride)
        // rows without any override are not returned at all
        assertNull(overrides[2001L])
        // overrides unrelated to an active favourite are outside this read model
        assertNull(overrides[99_001L])
    }

    // ------------------------------------------------------- read-only guarantee

    @Test
    fun readingNeverWritesEntityPreferences() = runTest {
        val before = db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM entity_preferences")
            .use { it.moveToFirst(); it.getLong(0) }
        dao.observeFavouriteCardBaseRows().first()
        dao.observeFavouriteMembershipRows().first()
        dao.observeFavouriteProjectionFacets().first()
        dao.observeFavouriteTagIdRows().first()
        dao.observeFavouriteTagDictionary().first()
        dao.observeDownloadedFavouriteRows().first()
        dao.observeFavouriteLegacyOverrides().first()
        val after = db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM entity_preferences")
            .use { it.moveToFirst(); it.getLong(0) }
        assertEquals(before, after)
    }

    @Test
    fun snapshotIsSelfConsistentAcrossAllFlows() = runTest {
        val base = dao.observeFavouriteCardBaseRows().first()
        val memberships = dao.observeFavouriteMembershipRows().first()

        // every membership references a base row entity; every base row has >=1 membership
        val baseEntities = base.map { it.entityId }.toSet()
        val membershipEntities = memberships.map { it.entityId }.toSet()
        assertEquals(baseEntities, membershipEntities)
        // facets / tags / downloads never reference unknown entities
        val facetEntities = dao.observeFavouriteProjectionFacets().first().map { it.entityId }.toSet()
        val tagEntities = dao.observeFavouriteTagIdRows().first().map { it.entityId }.toSet()
        val downloadedEntities = dao.observeDownloadedFavouriteRows().first().map { it.entityId }.toSet()
        assertTrue(baseEntities.containsAll(facetEntities))
        assertTrue(baseEntities.containsAll(tagEntities))
        assertTrue(baseEntities.containsAll(downloadedEntities))
    }

    // ------------------------------------------------------------------ helpers

    /** Category id of the representative membership, recovered from the membership flow. */
    private suspend fun representeeOf(entityId: Long): Long {
        val memberships = dao.observeFavouriteMembershipRows().first()
            .filter { it.entityId == entityId }
        // mirror the representative ranking in memory
        return memberships.sortedWith(
            compareByDescending<FavouriteMembershipRow> { it.isPinned }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.updatedAt }
                .thenBy { it.categoryId },
        ).first().categoryId
    }

    private suspend fun representativeIsPinned(entityId: Long): Boolean {
        return dao.observeFavouriteCardBaseRows().first().single { it.entityId == entityId }.representativePinned
    }

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            FavouriteLibrarySeed.insertCategory(sql, 10, "Reading")
            FavouriteLibrarySeed.insertCategory(sql, 11, "Planned")
            FavouriteLibrarySeed.insertCategory(sql, 12, "Deleted", deletedAt = 1)

            FavouriteLibrarySeed.insertEntity(sql, 1, "E1")
            FavouriteLibrarySeed.insertManga(sql, 1001, "Beta", altTitle = null)
            FavouriteLibrarySeed.insertFavourite(sql, 1, 10, 1001, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertBinding(sql, 1, 1001)
            FavouriteLibrarySeed.insertPrefs(sql, 1) // no prefs
            // legacy override on the display manga
            sql.execSQL(
                """
                INSERT INTO preferences (
                    manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                    cf_book, title_override, cover_override
                ) VALUES (?, 0, 0, 0, 0, 0, 0, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(1001, "Legacy Title", "/cover/legacy.jpg"),
            )
            FavouriteLibrarySeed.insertManga(sql, 99_001, "Not a favourite")
            sql.execSQL(
                """
                INSERT INTO preferences (
                    manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
                    cf_book, title_override
                ) VALUES (?, 0, 0, 0, 0, 0, 0, ?)
                """.trimIndent(),
                arrayOf<Any?>(99_001, "Unrelated override"),
            )

            FavouriteLibrarySeed.insertEntity(sql, 2, "E2")
            FavouriteLibrarySeed.insertManga(sql, 2001, "Alpha")
            FavouriteLibrarySeed.insertFavourite(sql, 2, 10, 2001, pinned = false, createdAt = 100, updatedAt = 100)
            FavouriteLibrarySeed.insertFavourite(sql, 2, 11, 2001, pinned = true, createdAt = 50, updatedAt = 50)
            FavouriteLibrarySeed.insertBinding(sql, 2, 2001)

            FavouriteLibrarySeed.insertEntity(sql, 3, "E3")
            FavouriteLibrarySeed.insertManga(sql, 3001, "Gamma")
            FavouriteLibrarySeed.insertFavourite(sql, 3, 10, 3001, createdAt = 200, updatedAt = 200)
            FavouriteLibrarySeed.insertFavourite(sql, 3, 11, 3001, createdAt = 200, updatedAt = 200)
            FavouriteLibrarySeed.insertBinding(sql, 3, 3001)
            FavouriteLibrarySeed.insertManga(sql, 3002, "Gamma remote", source = "OTHER")
            FavouriteLibrarySeed.insertBinding(sql, 3, 3002)

            FavouriteLibrarySeed.insertEntity(sql, 4, "E4")
            FavouriteLibrarySeed.insertManga(sql, 4001, "Delta orphan anchor")
            FavouriteLibrarySeed.insertManga(sql, 4002, "Delta bound")
            FavouriteLibrarySeed.insertFavourite(sql, 4, 10, 4001, createdAt = 300, updatedAt = 10)
            FavouriteLibrarySeed.insertFavourite(sql, 4, 11, 4001, createdAt = 300, updatedAt = 99)
            // anchor 4001 is NOT a binding: only 4002 is bound, so the facet set must
            // exclude the anchor (binding-based projection identity).
            FavouriteLibrarySeed.insertBinding(sql, 4, 4002)

            FavouriteLibrarySeed.insertEntity(sql, 5, "E5")
            FavouriteLibrarySeed.insertManga(sql, 5001, "Epsilon anchor", rating = 0.5f)
            FavouriteLibrarySeed.insertManga(sql, 5002, "Epsilon preferred", rating = 0.9f)
            FavouriteLibrarySeed.insertFavourite(sql, 5, 10, 5001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5001)
            FavouriteLibrarySeed.insertBinding(sql, 5, 5002)
            FavouriteLibrarySeed.insertPrefs(sql, 5, preferredLocalMangaId = 5002)

            FavouriteLibrarySeed.insertEntity(sql, 6, "E6")
            FavouriteLibrarySeed.insertManga(sql, 6001, "Zeta")
            FavouriteLibrarySeed.insertFavourite(sql, 6, 10, 6001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 6, 6001)
            FavouriteLibrarySeed.insertPrefs(sql, 6, preferredLocalMangaId = 999_999)

            FavouriteLibrarySeed.insertEntity(sql, 7, "E7")
            FavouriteLibrarySeed.insertManga(sql, 7001, "Eta")
            FavouriteLibrarySeed.insertFavourite(sql, 7, 10, 7001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 7, 7001)
            FavouriteLibrarySeed.insertPrefs(sql, 7, readingStatus = "ON_HOLD")

            FavouriteLibrarySeed.insertEntity(sql, 8, "E8")
            FavouriteLibrarySeed.insertManga(sql, 8001, "Theta", nsfw = true)
            FavouriteLibrarySeed.insertFavourite(sql, 8, 10, 8001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 8, 8001)

            FavouriteLibrarySeed.insertEntity(sql, 9, "E9")
            FavouriteLibrarySeed.insertManga(sql, 9001, "Iota")
            FavouriteLibrarySeed.insertFavourite(sql, 9, 10, 9001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 9, 9001)
            FavouriteLibrarySeed.insertDownloaded(sql, 9001)

            FavouriteLibrarySeed.insertEntity(sql, 10, "E10")
            FavouriteLibrarySeed.insertManga(sql, 10001, "Kappa")
            FavouriteLibrarySeed.insertManga(sql, 10002, "Kappa alt")
            FavouriteLibrarySeed.insertFavourite(sql, 10, 10, 10001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 10, 10001)
            FavouriteLibrarySeed.insertBinding(sql, 10, 10002)
            FavouriteLibrarySeed.insertTrack(sql, 10, 10001, newChapters = 2, lastChapterDate = 1000, lastCheckTime = 1500)
            FavouriteLibrarySeed.insertTrack(sql, 10, 10002, newChapters = 3, lastChapterDate = 2000, lastCheckTime = 2500, ownerId = 10_000L)

            FavouriteLibrarySeed.insertEntity(sql, 11, "E11")
            FavouriteLibrarySeed.insertManga(sql, 11001, "Lambda")
            FavouriteLibrarySeed.insertFavourite(sql, 11, 10, 11001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 11, 11001)
            FavouriteLibrarySeed.insertHistory(sql, 11, 11001, percent = 0.5f, updatedAt = 5000)

            FavouriteLibrarySeed.insertEntity(sql, 12, "E12")
            FavouriteLibrarySeed.insertManga(sql, 12001, "Mu display", source = "OTHER")
            FavouriteLibrarySeed.insertManga(sql, 12002, "Mu binding")
            FavouriteLibrarySeed.insertFavourite(sql, 12, 10, 12001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 12, 12002)
            val dramaTagId = "drama_TEST".longHashCode()
            FavouriteLibrarySeed.insertTag(sql, dramaTagId, "Drama")
            FavouriteLibrarySeed.insertMangaTag(sql, 12002, dramaTagId)

            FavouriteLibrarySeed.insertEntity(sql, 13, "E13", contentType = "NOVEL")
            FavouriteLibrarySeed.insertManga(sql, 13001, "Nu", contentType = "NOVEL")
            FavouriteLibrarySeed.insertManga(sql, 13002, "Nu candidate", contentType = "NOVEL")
            FavouriteLibrarySeed.insertFavourite(sql, 13, 10, 13001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 13, 13001)
            FavouriteLibrarySeed.insertBinding(sql, 13, 13002, state = "CANDIDATE")

            FavouriteLibrarySeed.insertEntity(sql, 14, "E14")
            FavouriteLibrarySeed.insertManga(sql, 14001, "Xi", state = "ONGOING")
            FavouriteLibrarySeed.insertFavourite(sql, 14, 10, 14001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 14, 14001)

            FavouriteLibrarySeed.insertEntity(sql, 15, "E15")
            FavouriteLibrarySeed.insertManga(sql, 15001, "abc", altTitle = "Alternate")
            FavouriteLibrarySeed.insertFavourite(sql, 15, 10, 15001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 15, 15001)

            FavouriteLibrarySeed.insertEntity(sql, 16, "E16")
            FavouriteLibrarySeed.insertManga(sql, 16001, "XYZ")
            FavouriteLibrarySeed.insertFavourite(sql, 16, 10, 16001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 16, 16001)
            FavouriteLibrarySeed.insertPrefs(sql, 16, titleOverride = "Renamed")

            FavouriteLibrarySeed.insertEntity(sql, 17, "E17")
            FavouriteLibrarySeed.insertManga(sql, 17001, "Omicron")
            FavouriteLibrarySeed.insertManga(sql, 17002, "Omicron alt")
            FavouriteLibrarySeed.insertFavourite(sql, 17, 10, 17001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 17, 17001)
            FavouriteLibrarySeed.insertBinding(sql, 17, 17002)
            FavouriteLibrarySeed.insertDownloaded(sql, 17002)

            FavouriteLibrarySeed.insertEntity(sql, 21, "E21")
            FavouriteLibrarySeed.insertManga(sql, 21001, "Tau")
            FavouriteLibrarySeed.insertFavourite(sql, 21, 10, null, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 21, 21001)

            FavouriteLibrarySeed.insertEntity(sql, 22, "E22")
            FavouriteLibrarySeed.insertManga(sql, 22001, "Upsilon")
            FavouriteLibrarySeed.insertFavourite(sql, 22, 10, 22001, createdAt = 10, updatedAt = 10, deletedAt = 5)
            FavouriteLibrarySeed.insertBinding(sql, 22, 22001)

            FavouriteLibrarySeed.insertEntity(sql, 24, "E24")
            FavouriteLibrarySeed.insertManga(sql, 24001, "Chi")
            FavouriteLibrarySeed.insertFavourite(sql, 24, 12, 24001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 24, 24001)

            // Display metadata authority (the tracking site behind the card title/cover):
            // E25 has a cached site item, E26 points at a missing one, E27 chooses the
            // local base projection as authority.
            FavouriteLibrarySeed.insertEntity(sql, 25, "E25")
            FavouriteLibrarySeed.insertManga(sql, 25001, "Psi projection")
            FavouriteLibrarySeed.insertFavourite(sql, 25, 10, 25001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 25, 25001)
            FavouriteLibrarySeed.insertPrefs(sql, 25, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 777L)
            FavouriteLibrarySeed.insertTrackingSiteItem(sql, 3, 777L, "Site Title", "https://site/cover.jpg")

            FavouriteLibrarySeed.insertEntity(sql, 26, "E26")
            FavouriteLibrarySeed.insertManga(sql, 26001, "Omega projection")
            FavouriteLibrarySeed.insertFavourite(sql, 26, 10, 26001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 26, 26001)
            FavouriteLibrarySeed.insertPrefs(sql, 26, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 778L)

            FavouriteLibrarySeed.insertEntity(sql, 27, "E27")
            FavouriteLibrarySeed.insertManga(sql, 27001, "Phi projection")
            FavouriteLibrarySeed.insertFavourite(sql, 27, 10, 27001, createdAt = 10, updatedAt = 10)
            FavouriteLibrarySeed.insertBinding(sql, 27, 27001)
            // Stale numeric columns with kind='base': the kind guard, not the id match,
            // decides, so the card must keep the projection display.
            FavouriteLibrarySeed.insertPrefs(sql, 27, metadataSourceKind = "base", metadataService = 3, metadataRemoteId = 779L)
            FavouriteLibrarySeed.insertTrackingSiteItem(sql, 3, 779L, "Phi site title", "https://site/phi.jpg")

            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }
}
