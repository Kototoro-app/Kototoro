package org.skepsun.kototoro.work.domain

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.history.data.HistoryLibraryPagingRow
import org.skepsun.kototoro.history.data.WorkHistoryDao
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.core.db.dao.MangaDao

class WorkAggregateHistoryPagingTest {

	private val historyDao = mockk<WorkHistoryDao>()
	private val favouritesDao = mockk<WorkFavouritesDao>()
	private val mangaDao = mockk<MangaDao>()
	private val entityGraphDao = mockk<EntityGraphDao>()
	private val workResolver = mockk<org.skepsun.kototoro.work.domain.WorkResolver>()
	private val db = mockk<MangaDatabase> {
		every { getWorkHistoryDao() } returns historyDao
		every { getWorkFavouritesDao() } returns favouritesDao
		every { getMangaDao() } returns mangaDao
		every { getEntityGraphDao() } returns entityGraphDao
	}
	private val repository = WorkAggregateRepository(
		db = db,
		workResolver = workResolver,
		spaceContentPolicy = DefaultSpaceContentPolicy(
			TestSpaceCatalogRepository(),
			mockk<SourceRuleResolver>(relaxed = true),
		),
		contentSourcesRepository = mockk<ContentSourcesRepository>(relaxed = true),
	)

	@Test
	fun `history page builds aggregates from embedded row without entity resolution`() = runTest {
		val display = MangaEntity(
			id = 10_001L,
			title = "Projection",
			altTitles = null,
			url = "/item/10001",
			publicUrl = "",
			rating = 0f,
			isNsfw = false,
			contentRating = null,
			coverUrl = "",
			largeCoverUrl = null,
			state = "ONGOING",
			authors = "Author",
			source = "TEST",
			description = null,
			contentType = "MANGA",
			sourceData = null,
		)
		val history = WorkHistoryEntity(
			entityId = 1L,
			anchorMangaId = 10_001L,
			createdAt = 1L,
			updatedAt = 2L,
			chapterId = 5L,
			page = 0,
			scroll = 0f,
			percent = 0.5f,
			deletedAt = 0L,
			chaptersCount = 3,
		)
		val row = HistoryLibraryPagingRow(
			history = history,
			preferredLocalMangaId = 10_001L,
			displayManga = display,
			trackingAnchorMangaId = 10_001L,
			trackingLastChapterId = 5L,
			trackingNewChapters = 2,
			trackingLastCheckTime = 10L,
			trackingLastChapterDate = 11L,
		)
		coEvery { historyDao.pagingSource(any(), any(), any(), any(), any(), any(), any(), any()) } returns
			object : PagingSource<Int, HistoryLibraryPagingRow>() {
				override suspend fun load(params: LoadParams<Int>): LoadResult<Int, HistoryLibraryPagingRow> =
					LoadResult.Page(data = listOf(row), prevKey = null, nextKey = null)

				override fun getRefreshKey(state: PagingState<Int, HistoryLibraryPagingRow>): Int? = null
			}
		coEvery { entityGraphDao.findActiveLocalBindingsByEntities(any()) } returns emptyList()
		coEvery { entityGraphDao.findEntitiesByIds(any()) } returns emptyList()
		coEvery { mangaDao.findWithTagsByIds(any()) } returns listOf(MangaWithTags(display, emptyList()))
		coEvery { favouritesDao.findCategoryMemberships(any()) } returns emptyList()

		val source = repository.createHistoryPagingSource(
			order = ListSortOrder.LAST_READ,
			spaceId = null,
			groupTab = BrowseGroupTab.All,
		)
		val page = source.load(
			PagingSource.LoadParams.Refresh(null, 64, false),
		) as PagingSource.LoadResult.Page

		val aggregate = page.data.single()
		assertEquals(1L, aggregate.identity.entityId)
		assertEquals(10_001L, aggregate.identity.preferredMangaId)
		assertEquals(10_001L, aggregate.identity.requestedMangaId)
		assertEquals(10_001L, aggregate.displayProjection?.id)
		assertEquals("Projection", aggregate.displayProjection?.title)
		assertNotNull(aggregate.history)
		assertEquals(10_001L, aggregate.tracking?.anchorMangaId)
		assertEquals(2, aggregate.tracking?.newChapters)
		assertEquals(11L, aggregate.tracking?.lastChapterDate)
		assertEquals("MANGA", aggregate.contentType?.name)

		// The lightweight path must never walk the old per-page entity resolution or
		// stats/history re-reads; those would recreate the favourites regression.
		coVerify(exactly = 0) { workResolver.resolveManyByEntityIds(any()) }
		coVerify(exactly = 0) { historyDao.findByEntityIds(any()) }
	}

	@Test
	fun `history page keeps favourite categories for the favorite quick filter`() = runTest {
		val history = WorkHistoryEntity(
			entityId = 7L,
			anchorMangaId = 10_007L,
			createdAt = 1L,
			updatedAt = 2L,
			chapterId = 5L,
			page = 0,
			scroll = 0f,
			percent = 0.5f,
			deletedAt = 0L,
			chaptersCount = 1,
		)
		val display = MangaEntity(
			id = 10_007L,
			title = "Projection 2",
			altTitles = null,
			url = "/item/10007",
			publicUrl = "",
			rating = 0f,
			isNsfw = false,
			contentRating = null,
			coverUrl = "",
			largeCoverUrl = null,
			state = null,
			authors = null,
			source = "TEST",
			description = null,
			contentType = "MANGA",
			sourceData = null,
		)
		val row = HistoryLibraryPagingRow(
			history = history,
			preferredLocalMangaId = null,
			displayManga = display,
			trackingAnchorMangaId = null,
			trackingLastChapterId = null,
			trackingNewChapters = null,
			trackingLastCheckTime = null,
			trackingLastChapterDate = null,
		)
		coEvery { historyDao.pagingSource(any(), any(), any(), any(), any(), any(), any(), any()) } returns
			object : PagingSource<Int, HistoryLibraryPagingRow>() {
				override suspend fun load(params: LoadParams<Int>): LoadResult<Int, HistoryLibraryPagingRow> =
					LoadResult.Page(data = listOf(row), prevKey = null, nextKey = null)

				override fun getRefreshKey(state: PagingState<Int, HistoryLibraryPagingRow>): Int? = null
			}
		coEvery { entityGraphDao.findActiveLocalBindingsByEntities(any()) } returns emptyList()
		coEvery { entityGraphDao.findEntitiesByIds(any()) } returns emptyList()
		coEvery { mangaDao.findWithTagsByIds(any()) } returns listOf(MangaWithTags(display, emptyList()))
		coEvery { favouritesDao.findCategoryMemberships(any()) } returns emptyList()

		val source = repository.createHistoryPagingSource(
			order = ListSortOrder.LAST_READ,
			spaceId = null,
			groupTab = BrowseGroupTab.All,
		)
		val page = source.load(
			PagingSource.LoadParams.Refresh(null, 64, false),
		) as PagingSource.LoadResult.Page

		val aggregate = page.data.single()
		assertEquals(7L, aggregate.identity.entityId)
		assertEquals(10_007L, aggregate.displayProjection?.id)
		assertEquals(emptySet<Long>(), aggregate.categories.mapTo(LinkedHashSet()) { it.id })
		// Categories underpin the favorite quick filter, so they must still be read
		// (in one batch) even though the row itself carries display + tracking.
		coVerify(exactly = 1) { favouritesDao.findCategoryMemberships(any()) }
	}
}
