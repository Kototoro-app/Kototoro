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
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity

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
		val first = source.load(refreshParams()) as PagingSource.LoadResult.Page
		val second = source.load(appendParams(requireNotNull(first.nextKey)))
			as PagingSource.LoadResult.Page
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
	fun favouritesAndHistoryPageByUniqueEntity() = runTest {
		val favourites = favouritePagingSource()
		val favouriteFirst = favourites.load(refreshParams()) as PagingSource.LoadResult.Page
		val favouriteSecond = favourites.load(appendParams(requireNotNull(favouriteFirst.nextKey)))
			as PagingSource.LoadResult.Page
		assertUniqueEntities(favouriteFirst.data.map { it.entityId }, favouriteSecond.data.map { it.entityId })

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
		val historyFirst = history.load(refreshParams()) as PagingSource.LoadResult.Page
		val historySecond = history.load(appendParams(requireNotNull(historyFirst.nextKey)))
			as PagingSource.LoadResult.Page
		assertUniqueEntities(historyFirst.data.map { it.entityId }, historySecond.data.map { it.entityId })
	}

	@Test
	fun favouriteFirstScreenBenchmarkFitsBudget() = runTest {
		// 6500 favourites + 3200 history are seeded in @Before. This measures the
		// exact first-screen window the FavouriteLibraryPagingConfig prefetch
		// distance (128) fills: initial 64 plus the two 64-row appends.
		val favourites = favouritePagingSource()
		val t0 = SystemClock.elapsedRealtime()
		val first = favourites.load(refreshParams()) as PagingSource.LoadResult.Page
		val t1 = SystemClock.elapsedRealtime()
		val second = favourites.load(appendParams(requireNotNull(first.nextKey)))
			as PagingSource.LoadResult.Page
		val t2 = SystemClock.elapsedRealtime()
		val third = favourites.load(appendParams(requireNotNull(second.nextKey)))
			as PagingSource.LoadResult.Page
		val t3 = SystemClock.elapsedRealtime()

		Log.d("LibraryPaging", "favourites-bench Refresh rawItems=" + first.data.size + " firstPageMs=" + (t1 - t0))
		Log.d(
			"LibraryPaging",
			"favourites-bench Append(64) ms=" + (t2 - t1) + " Append(128) ms=" + (t3 - t2) +
				" total192Ms=" + (t3 - t0),
		)

		assertEquals(64, first.data.size)
		assertEquals(64, second.data.size)
		assertEquals(64, third.data.size)
		assertEquals(192, (first.data + second.data + third.data).map { it.entityId }.distinct().size)
		// Loose budget: catches pathological regressions, not microsecond noise.
		assertTrue("first favourite page took " + (t1 - t0) + "ms", t1 - t0 < 5_000)
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

		assertEquals(64, firstPage(favouritePagingSource(exactSources = setOf("TEST"))).data.size)
		assertTrue(firstPage(favouritePagingSource(exactSources = setOf("MISSING"))).data.isEmpty())
		assertEquals(
			listOf(1L),
			firstPage(favouritePagingSource(exactSources = setOf("MISSING", "SECONDARY"))).data.map { it.entityId },
		)
		assertEquals(64, firstPage(favouritePagingSource(contentTypes = setOf("MANGA"))).data.size)
		assertTrue(firstPage(favouritePagingSource(contentTypes = setOf("NOVEL"))).data.isEmpty())
		assertEquals(
			listOf(1L),
			firstPage(favouritePagingSource(publicationStates = setOf("ONGOING"))).data.map { it.entityId },
		)
		assertEquals(listOf(1L), firstPage(favouritePagingSource(nsfwMode = 1)).data.map { it.entityId })
		assertEquals(listOf(1L), firstPage(favouritePagingSource(requireDownloaded = true)).data.map { it.entityId })
		assertEquals(listOf(1L), firstPage(favouritePagingSource(tagIds = setOf(tagId))).data.map { it.entityId })
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
		val first = history.load(refreshParams()) as PagingSource.LoadResult.Page
		val pageMs = SystemClock.elapsedRealtime() - t0
		Log.d("LibraryPaging", "history-bench Refresh rawItems=" + first.data.size + " firstPageMs=" + pageMs)
		assertEquals(64, first.data.size)
		assertTrue("first history page took " + pageMs + "ms", pageMs < 5_000)
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

	private suspend fun firstPage(
		source: PagingSource<Int, WorkFavouriteEntity>,
	) =
		source.load(refreshParams()) as PagingSource.LoadResult.Page

	private fun favouritePagingSource(
		contentTypes: Set<String> = emptySet(),
		publicationStates: Set<String> = emptySet(),
		nsfwMode: Int = -1,
		requireDownloaded: Boolean = false,
		requireNewChapters: Boolean = false,
		exactSources: Set<String> = emptySet(),
		tagIds: Set<Long> = emptySet(),
	) = db.getWorkFavouritesDao().pagingSource(
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
