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
		val favourites = db.getWorkFavouritesDao().pagingSource(
			categoryId = -1L,
			orderName = "NEWEST",
			applySpaceFilter = false,
			allowedTypes = emptyList(),
			classifiedTypes = emptyList(),
			applySourceFilter = false,
			allowedSources = emptyList(),
		)
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
		val favourites = db.getWorkFavouritesDao().pagingSource(
			categoryId = -1L,
			orderName = "NEWEST",
			applySpaceFilter = false,
			allowedTypes = emptyList(),
			classifiedTypes = emptyList(),
			applySourceFilter = false,
			allowedSources = emptyList(),
		)
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
		Log.d("LibraryPaging", "favourites-bench Append(64) ms=" + (t2 - t1) + " Append(128) ms=" + (t3 - t2) + " total192Ms=" + (t3 - t0))

		assertEquals(64, first.data.size)
		assertEquals(64, second.data.size)
		assertEquals(64, third.data.size)
		assertEquals(192, (first.data + second.data + third.data).map { it.entityId }.distinct().size)
		// Loose budget: catches pathological regressions, not microsecond noise.
		assertTrue("first favourite page took " + (t1 - t0) + "ms", t1 - t0 < 5_000)
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
