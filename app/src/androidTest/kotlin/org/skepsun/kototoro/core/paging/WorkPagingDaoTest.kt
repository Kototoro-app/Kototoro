package org.skepsun.kototoro.core.paging

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
					"INSERT INTO entity VALUES (?, 'WORK', ?, ?, ?, NULL, 0, 0, 0)",
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
