package org.skepsun.kototoro.core.paging

import android.os.SystemClock
import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord

@RunWith(AndroidJUnit4::class)
class WorkPagingDaoTest {

	private lateinit var db: MangaDatabase

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext(),
			MangaDatabase::class.java,
		).build()
		seedLargeLibrary()
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun updatedContentPagesHaveStableUniqueEntityOrder() = runTest {
		seedTracks()
		val source = db.getTracksDao().pagingUpdatedContent(emptySet())
		val first = source.load(refreshParams()).requirePage()
		val second = source.load(appendParams(requireNotNull(first.nextKey)))
			.requirePage()
		assertEquals(64, first.data.size)
		assertEquals(64, second.data.size)
		val entities = (first.data + second.data).map { requireNotNull(it.entityId) }
		assertEquals(128, entities.distinct().size)
		// pinned is 0 for every seeded favourite, so the stable sort falls back to
		// last_chapter_date DESC and must stay monotonic across page boundaries.
		val dates = (first.data + second.data).map { it.lastChapterDate }
		assertEquals(dates.sortedDescending(), dates)
	}

	@Test
	fun favouritesListAndHistoryPageByUniqueEntity() = runTest {
		val favourites = db.getWorkFavouritesDao().findListRepresentatives(-1L)
		assertEquals(6_500, favourites.size)
		assertEquals(6_500, favourites.map { it.entityId }.distinct().size)

		val history = db.getWorkHistoryDao().pagingSource(
			orderName = "LAST_READ",
			applySpaceFilter = false,
			allowedTypes = emptyList(),
			classifiedTypes = emptyList(),
			applySourceFilter = false,
			allowedSources = emptyList(),
			applyTabFilter = false,
			tabAllowedTypes = emptyList(),
		)
		val historyFirst = history.load(refreshParams()).requirePage()
		val historySecond = history.load(appendParams(requireNotNull(historyFirst.nextKey)))
			.requirePage()
		assertUniqueEntities(historyFirst.data.map { it.history.entityId }, historySecond.data.map { it.history.entityId })
	}

	@Test
	fun favouriteColdStartLoadsOnlyTheInitialWindow() = runTest {
		val first = db.getWorkFavouritesDao().pagingSource(
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
		).load(refreshParams()).requirePage()

		assertEquals(64, first.data.size)
		assertEquals(64, first.data.map { it.favourite.entityId }.distinct().size)
	}

	@Test
	fun favouritePageCarriesDisplayHistoryAndTrackingSummary() = runTest {
		seedTracks()
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
		// The UPDATED-desc window starts at the newest entities (id 6437..6500), while
		// history is only seeded for entityId <= 3200. Walk pages until a history-backed
		// row is found instead of assuming it lands in the first window.
		var page = source.load(refreshParams()).requirePage()
		var nextKey = page.nextKey
		var historyRow = page.data.firstOrNull { it.favourite.entityId <= 3_200L }
		while (historyRow == null && nextKey != null) {
			page = source.load(appendParams(nextKey)).requirePage()
			historyRow = page.data.firstOrNull { it.favourite.entityId <= 3_200L }
			nextKey = page.nextKey
		}

		val row = requireNotNull(historyRow) { "no history-backed row found in favourites pages" }
		assertEquals(row.favourite.anchorMangaId, row.displayManga?.id)
		assertEquals(row.favourite.entityId, row.history?.entityId)
		assertTrue((row.trackingNewChapters ?: 0) > 0)
		assertNotNull(row.trackingLastChapterDate)
	}

	@Test
	fun favouriteRepresentativeCarriesPreferredProjectionInTheSameQuery() = runTest {
		db.getEntityGraphDao().upsertPrefsRecord(
			EntityPrefsRecord(
				entityId = 1L,
				preferredLocalMangaId = 10_002L,
				titleOverride = null,
				coverUrlOverride = null,
				contentRatingOverride = null,
				readingStatus = null,
				metadataSourceKind = null,
				metadataBindingSource = null,
				metadataBindingExternalId = null,
				metadataSourceService = null,
				metadataSourceRemoteId = null,
				updatedAt = 1L,
			),
		)

		val representative = db.getWorkFavouritesDao()
			.findLibraryRepresentatives(-1L)
			.single { it.favourite.entityId == 1L }

		assertEquals(10_001L, representative.favourite.anchorMangaId)
		assertEquals(10_002L, representative.preferredLocalMangaId)
	}

	@Test
	fun favouriteFullListBenchmarkFitsBudget() = runTest {
		// The library UI consumes the complete representative list, matching the
		// Mihon/Komikku library contract rather than incrementally loading pages.
		val t0 = SystemClock.elapsedRealtime()
		val favourites = db.getWorkFavouritesDao().findListRepresentatives(-1L)
		val elapsed = SystemClock.elapsedRealtime() - t0

		Log.d("LibraryList", "favourites-bench items=" + favourites.size + " fullListMs=" + elapsed)
		assertEquals(6_500, favourites.size)
		assertTrue("full favourite list took " + elapsed + "ms", elapsed < 5_000)
	}

	@Test
	fun favouriteFiltersAreAppliedBeforePaging() = runTest {
		val sql = db.openHelper.writableDatabase
		val mangaId = 10_001L
		val secondaryMangaId = 20_001L
		val tagId = 91L
		sql.execSQL("UPDATE manga SET state = 'ONGOING', nsfw = 1 WHERE manga_id = ?", arrayOf<Any?>(mangaId))
		sql.execSQL("INSERT INTO local_index VALUES (?, ?)", arrayOf<Any?>(mangaId, "/tmp/one"))
		sql.execSQL(
			"""
			INSERT INTO manga (
				manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
				cover_url, large_cover_url, state, author, source, description, content_type
			) VALUES (?, 'Secondary', NULL, '', '', 0, 0, NULL, '', NULL, NULL, NULL, 'SECONDARY', NULL, 'MANGA')
			""".trimIndent(),
			arrayOf<Any?>(secondaryMangaId),
		)
		sql.execSQL(
			"INSERT INTO entity_binding VALUES (1, 'local_manga', ?, 1, 0, 'UNKNOWN', 'CONFIRMED', 'LEGACY', 0)",
			arrayOf<Any?>(secondaryMangaId.toString()),
		)
		sql.execSQL("INSERT INTO tags VALUES (?, 'Action', 'action', 'TEST', 0)", arrayOf<Any?>(tagId))
		sql.execSQL("INSERT INTO manga_tags VALUES (?, ?)", arrayOf<Any?>(secondaryMangaId, tagId))

		assertEquals(6_500, favouriteList(exactSources = setOf("TEST")).size)
		assertTrue(favouriteList(exactSources = setOf("MISSING")).isEmpty())
		assertEquals(
			listOf(1L),
			favouriteList(exactSources = setOf("MISSING", "SECONDARY")).map { it.entityId },
		)
		assertEquals(6_500, favouriteList(contentTypes = setOf("MANGA")).size)
		assertTrue(favouriteList(contentTypes = setOf("NOVEL")).isEmpty())
		assertEquals(
			listOf(1L),
			favouriteList(publicationStates = setOf("ONGOING")).map { it.entityId },
		)
		assertEquals(listOf(1L), favouriteList(nsfwMode = 1).map { it.entityId })
		assertEquals(listOf(1L), favouriteList(requireDownloaded = true).map { it.entityId })
		assertEquals(listOf(1L), favouriteList(tagIds = setOf(tagId)).map { it.entityId })
	}

	@Test
	fun favouriteQuickFilterMetadataUsesDatabaseAggregation() = runTest {
		val sql = db.openHelper.writableDatabase
		val tagId = 92L
		sql.execSQL("INSERT INTO tags VALUES (?, 'Drama', 'drama', 'TEST', 0)", arrayOf<Any?>(tagId))
		sql.execSQL("INSERT INTO manga_tags VALUES (?, ?)", arrayOf<Any?>(10_001L, tagId))

		assertEquals("TEST", db.getWorkFavouritesDao().findQuickFilterSourceNames(-1L).first())
		assertEquals(tagId, db.getWorkFavouritesDao().findQuickFilterTags(-1L, 3).first().id)
	}

	@Test
	fun historyFirstScreenBenchmarkFitsBudget() = runTest {
		val history = db.getWorkHistoryDao().pagingSource(
			orderName = "LAST_READ",
			applySpaceFilter = false,
			allowedTypes = emptyList(),
			classifiedTypes = emptyList(),
			applySourceFilter = false,
			allowedSources = emptyList(),
			applyTabFilter = false,
			tabAllowedTypes = emptyList(),
		)
		val t0 = SystemClock.elapsedRealtime()
		val first = history.load(refreshParams()).requirePage()
		val pageMs = SystemClock.elapsedRealtime() - t0
		Log.d("LibraryPaging", "history-bench Refresh rawItems=" + first.data.size + " firstPageMs=" + pageMs)
		assertEquals(64, first.data.size)
		assertTrue("first history page took " + pageMs + "ms", pageMs < 5_000)
	}

	@Test
	fun historyPageCarriesDisplayLocalMangaAndTrackingSummary() = runTest {
		seedTracks()
		val source = db.getWorkHistoryDao().pagingSource(
			orderName = "LAST_READ",
			applySpaceFilter = false,
			allowedTypes = emptyList(),
			classifiedTypes = emptyList(),
			applySourceFilter = false,
			allowedSources = emptyList(),
			applyTabFilter = false,
			tabAllowedTypes = emptyList(),
		)
		// LAST_READ desc starts at the most recent seed rows (entityId 3200 downward),
		// which all carry history + tracks; the display manga must be embedded in the
		// same query instead of re-resolved per page.
		var page = source.load(refreshParams()).requirePage()
		var nextKey = page.nextKey
		var seededRow = page.data.firstOrNull { it.history.entityId <= 3_200L }
		while (seededRow == null && nextKey != null) {
			page = source.load(appendParams(nextKey)).requirePage()
			seededRow = page.data.firstOrNull { it.history.entityId <= 3_200L }
			nextKey = page.nextKey
		}

		val row = requireNotNull(seededRow) { "no seeded history row found in history pages" }
		assertEquals(row.history.entityId + 10_000L, row.history.anchorMangaId)
		assertEquals(row.history.anchorMangaId, row.displayManga?.id)
		assertTrue((row.trackingNewChapters ?: 0) > 0)
		assertNotNull(row.trackingLastChapterDate)
		assertEquals("MANGA", row.displayManga?.contentType)
	}

	private fun seedTracks() {
		val sql = db.openHelper.writableDatabase
		sql.beginTransaction()
		try {
			(1L..6_500L).forEach { entityId ->
				val mangaId = entityId + 10_000L
				sql.execSQL(
					"INSERT INTO tracks VALUES (?, ?, ?, 0, ?, ?, ?, 1, NULL)",
					arrayOf<Any?>(entityId, mangaId, entityId, entityId % 7 + 1, entityId * 1000L, entityId * 1000L),
				)
			}
			sql.setTransactionSuccessful()
		} finally {
			sql.endTransaction()
		}
	}

	private fun refreshParams() = PagingSource.LoadParams.Refresh<Int>(
		key = null,
		loadSize = LargeLibraryPagingConfig.initialLoadSize,
		placeholdersEnabled = false,
	)

	private fun appendParams(key: Int) = PagingSource.LoadParams.Append(
		key = key,
		loadSize = LargeLibraryPagingConfig.pageSize,
		placeholdersEnabled = false,
	)

	private suspend fun favouriteList(
		contentTypes: Set<String> = emptySet(),
		publicationStates: Set<String> = emptySet(),
		nsfwMode: Int = -1,
		requireDownloaded: Boolean = false,
		requireNewChapters: Boolean = false,
		exactSources: Set<String> = emptySet(),
		tagIds: Set<Long> = emptySet(),
	) = db.getWorkFavouritesDao().findList(
		categoryId = -1L,
		orderName = "NEWEST",
		applySpaceFilter = false,
		allowedTypes = emptyList(),
		classifiedTypes = emptyList(),
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

	@Suppress("UNCHECKED_CAST")
	private fun <T : Any> PagingSource.LoadResult<Int, T>.requirePage(): PagingSource.LoadResult.Page<Int, T> =
		this as PagingSource.LoadResult.Page<Int, T>

	private fun assertUniqueEntities(first: List<Long>, second: List<Long>) {
		assertEquals(64, first.size)
		assertEquals(64, second.size)
		assertNotNull(first.lastOrNull())
		assertEquals(128, (first + second).distinct().size)
	}

	private fun seedLargeLibrary() {
		val sql = db.openHelper.writableDatabase
		sql.beginTransaction()
		try {
			sql.execSQL("INSERT INTO favourite_categories VALUES (1, 0, 0, 'Default', 'NEWEST', 0, 1, 0)")
			sql.execSQL("INSERT INTO favourite_categories VALUES (2, 1, 0, 'Second', 'NEWEST', 0, 1, 0)")
			(1L..6_500L).forEach { entityId ->
				val mangaId = entityId + 10_000L
				sql.execSQL(
					"INSERT INTO entity (id, type, sync_id, primary_name, name_hash, aliases, created_at, last_accessed, access_count) " +
						"VALUES (?, 'WORK', ?, ?, ?, NULL, 0, 0, 0)",
					arrayOf<Any?>(entityId, "sync-$entityId", "Work $entityId", entityId),
				)
				sql.execSQL(
					"""
					INSERT INTO manga (
						manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
						cover_url, large_cover_url, state, author, source, description, content_type
					) VALUES (?, ?, NULL, '', '', 0, 0, NULL, '', NULL, NULL, NULL, 'TEST', NULL, 'MANGA')
					""".trimIndent(),
					arrayOf<Any?>(mangaId, "Projection $mangaId"),
				)
				sql.execSQL(
					"INSERT INTO work_favourites VALUES (?, 1, ?, 0, 0, ?, 0, ?)",
					arrayOf<Any?>(entityId, mangaId, entityId, entityId),
				)
				if (entityId % 10L == 0L) {
					sql.execSQL(
						"INSERT INTO work_favourites VALUES (?, 2, ?, 0, 0, ?, 0, ?)",
						arrayOf<Any?>(entityId, mangaId, entityId, entityId),
					)
				}
				if (entityId <= 3_200L) {
					sql.execSQL(
						"INSERT INTO work_history VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, NULL)",
						arrayOf<Any?>(entityId, mangaId, entityId, entityId),
					)
				}
			}
			sql.setTransactionSuccessful()
		} finally {
			sql.endTransaction()
		}
	}
}
