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
 * Semantics tests for the narrow favourites read DAO (projection-first).
 *
 * Every favourite manga row is its own library item: the read models keep the
 * `entity_id` column name for the card identity, but it is the favourite's `manga_id`.
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
    fun baseRowsReturnOneRowPerMangaWithRepresentativeMembership() = runTest {
        val rows = dao.observeFavouriteCardBaseRows().first()
        val byManga = rows.associateBy { it.entityId }

        // one row per favourite manga
        assertEquals(rows.size, rows.map { it.entityId }.distinct().size)
        // pinned wins over created_at (M2's representative is the older cat-11 row)
        assertEquals(11L, representeeOf(M2))
        assertTrue(byManga.getValue(M2).representativePinned)
        // identical everything -> lower category id
        assertEquals(10L, representeeOf(M3))
        // identical pinned/created -> newer updated_at
        assertEquals(11L, representeeOf(M4))
        // deleted memberships never show up
        assertNull(byManga[M22])
    }

    @Test
    fun cardIdentityIsTheFavouriteManga() = runTest {
        val rows = dao.observeFavouriteCardBaseRows().first()

        rows.forEach { row ->
            assertEquals(row.entityId, row.displayMangaId)
            assertEquals(row.entityId, row.preferredLocalMangaId)
            assertTrue(row.hasDisplay)
        }
        assertEquals("Beta", rows.single { it.entityId == M1 }.displayTitle)
    }

    @Test
    fun metadataAuthorityReadsTheCachedSiteItem() = runTest {
        val byManga = dao.observeFavouriteCardBaseRows().first().associateBy { it.entityId }

        // 'tracking' authority with a cached item: service + title + cover arrive together
        val tracking = byManga.getValue(M25)
        assertEquals(3, tracking.metadataTrackingService)
        assertEquals("Site Title", tracking.metadataTrackingTitle)
        assertEquals("https://site/cover.jpg", tracking.metadataTrackingCoverUrl)

        // authority without a cached item stays entirely null (no half-resolved display)
        val missing = byManga.getValue(M26)
        assertNull(missing.metadataTrackingService)
        assertNull(missing.metadataTrackingTitle)
        assertNull(missing.metadataTrackingCoverUrl)

        // 'base' authority never joins, even when a site item would match by id
        val base = byManga.getValue(M27)
        assertNull(base.metadataTrackingService)
        assertNull(base.metadataTrackingTitle)

        // no metadata selection at all
        assertNull(byManga.getValue(M2).metadataTrackingService)
    }

    @Test
    fun baseRowsCarrySortAndFilterFields() = runTest {
        val byManga = dao.observeFavouriteCardBaseRows().first().associateBy { it.entityId }

        // history
        assertEquals(0.5f, byManga.getValue(M11).historyPercent)
        assertEquals(5000L, byManga.getValue(M11).historyUpdatedAt)
        // a deleted history row is not the reading progress
        assertNull(byManga.getValue(M12).historyPercent)
        // tracking
        assertEquals(2, byManga.getValue(M10).trackingNewChapters)
        assertEquals(1000L, byManga.getValue(M10).trackingLastChapterDate)
        // display fields
        assertEquals("ONGOING", byManga.getValue(M14).displayState)
        assertEquals(true, byManga.getValue(M8).displayNsfw)
        assertEquals(0.9f, byManga.getValue(M5).displayRating)
        // preferences: reading status + overrides
        assertEquals("ON_HOLD", byManga.getValue(M7).readingStatus)
        assertEquals("Renamed", byManga.getValue(M16).titleOverride)
        // content type of the manga itself
        assertEquals("NOVEL", byManga.getValue(M13).entityContentType)
        // alt title
        assertEquals("Alternate", byManga.getValue(M15).displayAltTitle)
    }

    // ---------------------------------------------------------------- memberships

    @Test
    fun membershipRowsExposeEveryActiveMembership() = runTest {
        val byManga = dao.observeFavouriteMembershipRows().first().groupBy { it.entityId }

        assertEquals(2, byManga.getValue(M2).size)
        assertEquals(true, byManga.getValue(M2).first { it.categoryId == 11L }.isPinned)
        assertEquals(false, byManga.getValue(M2).first { it.categoryId == 10L }.isPinned)
        // deleted membership is excluded
        assertNull(byManga[M22])
        // dangling-category membership is still listed
        assertTrue(byManga.getValue(M24).any { it.categoryId == 12L })
    }

    // -------------------------------------------------------------------- facets

    @Test
    fun projectionFacetsAreTheFavouriteMangaItself() = runTest {
        val facets = dao.observeFavouriteProjectionFacets().first().groupBy { it.entityId }

        assertEquals(listOf(M1), facets.getValue(M1).map { it.mangaId })
        assertEquals(listOf("OTHER"), facets.getValue(M3).map { it.source })
        assertEquals(listOf("NOVEL"), facets.getValue(M13).map { it.contentType })
        // M2 is in two categories but is still one facet
        assertEquals(1, facets.getValue(M2).size)
    }

    @Test
    fun tagRelationsResolveThroughTheDictionary() = runTest {
        val relations = dao.observeFavouriteTagIdRows().first().groupBy { it.entityId }
        val dictionary = dao.observeFavouriteTagDictionary().first().associateBy { it.tagId }

        val m12Tags = relations.getValue(M12).map { dictionary.getValue(it.tagId).tagTitle }.toSet()
        assertTrue("Drama" in m12Tags)
        // tag identity uses the deterministic TagEntity id
        assertTrue("drama_TEST".longHashCode() in relations.getValue(M12).map { it.tagId })

        // Every relation resolves to the identity and title the filter and the chip show.
        val allRelations = dao.observeFavouriteTagIdRows().first()
        assertTrue(allRelations.all { it.tagId in dictionary })
        assertTrue(dictionary.values.all { it.tagTitle.isNotEmpty() && it.tagKey.isNotEmpty() })
    }

    @Test
    fun downloadedRowsMapTheLocalIndexOntoFavourites() = runTest {
        val downloaded = dao.observeDownloadedFavouriteRows().first().map { it.entityId }.toSet()

        assertTrue(M9 in downloaded)
        assertFalse(M1 in downloaded)
        // a download of a non-favourite manga is outside this read model
        assertFalse(NOT_FAVOURITE in downloaded)
    }

    @Test
    fun legacyOverridesExposeTitleAndCoverOnly() = runTest {
        val overrides = dao.observeFavouriteLegacyOverrides().first().associateBy { it.mangaId }

        assertEquals("Legacy Title", overrides.getValue(M1).titleOverride)
        assertEquals("/cover/legacy.jpg", overrides.getValue(M1).coverOverride)
        // rows without any override are not returned at all
        assertNull(overrides[M2])
        // overrides unrelated to an active favourite are outside this read model
        assertNull(overrides[NOT_FAVOURITE])
    }

    // ------------------------------------------------------- read-only guarantee

    @Test
    fun readingNeverWritesPreferences() = runTest {
        val before = countPreferences()
        dao.observeFavouriteCardBaseRows().first()
        dao.observeFavouriteMembershipRows().first()
        dao.observeFavouriteProjectionFacets().first()
        dao.observeFavouriteTagIdRows().first()
        dao.observeFavouriteTagDictionary().first()
        dao.observeDownloadedFavouriteRows().first()
        dao.observeFavouriteLegacyOverrides().first()
        assertEquals(before, countPreferences())
    }

    @Test
    fun snapshotIsSelfConsistentAcrossAllFlows() = runTest {
        val baseIds = dao.observeFavouriteCardBaseRows().first().map { it.entityId }.toSet()
        val membershipIds = dao.observeFavouriteMembershipRows().first().map { it.entityId }.toSet()

        // every membership references a base row; every base row has >=1 membership
        assertEquals(baseIds, membershipIds)
        // facets / tags / downloads never reference unknown items
        assertTrue(baseIds.containsAll(dao.observeFavouriteProjectionFacets().first().map { it.entityId }))
        assertTrue(baseIds.containsAll(dao.observeFavouriteTagIdRows().first().map { it.entityId }))
        assertTrue(baseIds.containsAll(dao.observeDownloadedFavouriteRows().first().map { it.entityId }))
    }

    // ------------------------------------------------------------------ helpers

    /** Category id of the representative membership, recovered from the membership flow. */
    private suspend fun representeeOf(mangaId: Long): Long {
        return dao.observeFavouriteMembershipRows().first()
            .filter { it.entityId == mangaId }
            .sortedWith(
                compareByDescending<FavouriteMembershipRow> { it.isPinned }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.categoryId },
            ).first().categoryId
    }

    private fun countPreferences(): Long {
        return db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM preferences")
            .use { it.moveToFirst(); it.getLong(0) }
    }

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            with(FavouriteLibrarySeed) {
                insertCategory(sql, 10, "Reading")
                insertCategory(sql, 11, "Planned")
                insertCategory(sql, 12, "Deleted", deletedAt = 1)

                insertManga(sql, M1, "Beta")
                insertFavourite(sql, M1, 10, createdAt = 100, updatedAt = 100)
                insertPrefs(sql, M1, titleOverride = "Legacy Title", coverOverride = "/cover/legacy.jpg")
                insertManga(sql, NOT_FAVOURITE, "Not a favourite")
                insertPrefs(sql, NOT_FAVOURITE, titleOverride = "Unrelated override")
                insertDownloaded(sql, NOT_FAVOURITE)

                insertManga(sql, M2, "Alpha")
                insertFavourite(sql, M2, 10, pinned = false, createdAt = 100, updatedAt = 100)
                insertFavourite(sql, M2, 11, pinned = true, createdAt = 50, updatedAt = 50)

                insertManga(sql, M3, "Gamma", source = "OTHER")
                insertFavourite(sql, M3, 10, createdAt = 200, updatedAt = 200)
                insertFavourite(sql, M3, 11, createdAt = 200, updatedAt = 200)

                insertManga(sql, M4, "Delta")
                insertFavourite(sql, M4, 10, createdAt = 300, updatedAt = 10)
                insertFavourite(sql, M4, 11, createdAt = 300, updatedAt = 99)

                insertManga(sql, M5, "Epsilon", rating = 0.9f)
                insertFavourite(sql, M5, 10, createdAt = 10, updatedAt = 10)

                insertManga(sql, M7, "Eta")
                insertFavourite(sql, M7, 10, createdAt = 10, updatedAt = 10)
                insertPrefs(sql, M7, readingStatus = "ON_HOLD")

                insertManga(sql, M8, "Theta", nsfw = true)
                insertFavourite(sql, M8, 10, createdAt = 10, updatedAt = 10)

                insertManga(sql, M9, "Iota")
                insertFavourite(sql, M9, 10, createdAt = 10, updatedAt = 10)
                insertDownloaded(sql, M9)

                insertManga(sql, M10, "Kappa")
                insertFavourite(sql, M10, 10, createdAt = 10, updatedAt = 10)
                insertTrack(sql, M10, newChapters = 2, lastChapterDate = 1000, lastCheckTime = 1500)

                insertManga(sql, M11, "Lambda")
                insertFavourite(sql, M11, 10, createdAt = 10, updatedAt = 10)
                insertHistory(sql, M11, percent = 0.5f, updatedAt = 5000)

                insertManga(sql, M12, "Mu")
                insertFavourite(sql, M12, 10, createdAt = 10, updatedAt = 10)
                insertHistory(sql, M12, percent = 0.7f, updatedAt = 6000, deletedAt = 6000)
                val dramaTagId = "drama_TEST".longHashCode()
                insertTag(sql, dramaTagId, "Drama")
                insertMangaTag(sql, M12, dramaTagId)

                insertManga(sql, M13, "Nu", contentType = "NOVEL")
                insertFavourite(sql, M13, 10, createdAt = 10, updatedAt = 10)

                insertManga(sql, M14, "Xi", state = "ONGOING")
                insertFavourite(sql, M14, 10, createdAt = 10, updatedAt = 10)

                insertManga(sql, M15, "abc", altTitle = "Alternate")
                insertFavourite(sql, M15, 10, createdAt = 10, updatedAt = 10)

                insertManga(sql, M16, "XYZ")
                insertFavourite(sql, M16, 10, createdAt = 10, updatedAt = 10)
                insertPrefs(sql, M16, titleOverride = "Renamed")

                insertManga(sql, M22, "Upsilon")
                insertFavourite(sql, M22, 10, createdAt = 10, updatedAt = 10, deletedAt = 5)

                insertManga(sql, M24, "Chi")
                insertFavourite(sql, M24, 12, createdAt = 10, updatedAt = 10)

                // Display metadata authority (the tracking site behind the card title/cover):
                // M25 has a cached site item, M26 points at a missing one, M27 chooses the
                // local base projection as authority.
                insertManga(sql, M25, "Psi")
                insertFavourite(sql, M25, 10, createdAt = 10, updatedAt = 10)
                insertPrefs(sql, M25, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 777L)
                insertTrackingSiteItem(sql, 3, 777L, "Site Title", "https://site/cover.jpg")

                insertManga(sql, M26, "Omega")
                insertFavourite(sql, M26, 10, createdAt = 10, updatedAt = 10)
                insertPrefs(sql, M26, metadataSourceKind = "tracking", metadataService = 3, metadataRemoteId = 778L)

                insertManga(sql, M27, "Phi")
                insertFavourite(sql, M27, 10, createdAt = 10, updatedAt = 10)
                // Stale numeric columns with kind='base': the kind guard, not the id match,
                // decides, so the card must keep the projection display.
                insertPrefs(sql, M27, metadataSourceKind = "base", metadataService = 3, metadataRemoteId = 779L)
                insertTrackingSiteItem(sql, 3, 779L, "Phi site title", "https://site/phi.jpg")
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
        const val M4 = 4001L
        const val M5 = 5001L
        const val M7 = 7001L
        const val M8 = 8001L
        const val M9 = 9001L
        const val M10 = 10001L
        const val M11 = 11001L
        const val M12 = 12001L
        const val M13 = 13001L
        const val M14 = 14001L
        const val M15 = 15001L
        const val M16 = 16001L
        const val M22 = 22001L
        const val M24 = 24001L
        const val M25 = 25001L
        const val M26 = 26001L
        const val M27 = 27001L
        const val NOT_FAVOURITE = 99_001L
    }
}
