package org.skepsun.kototoro.work.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.history.data.WorkHistoryDao
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository

class WorkAggregateSpaceQueryTest {

	private val favouritesDao = mockk<WorkFavouritesDao>()
	private val historyDao = mockk<WorkHistoryDao>()
	private val db = mockk<MangaDatabase> {
		every { getWorkFavouritesDao() } returns favouritesDao
		every { getWorkHistoryDao() } returns historyDao
	}
	private val repository = WorkAggregateRepository(
		db = db,
		workResolver = mockk(),
		spaceContentPolicy = DefaultSpaceContentPolicy(
			TestSpaceCatalogRepository(),
			mockk<SourceRuleResolver>(relaxed = true),
		),
		contentSourcesRepository = mockk<ContentSourcesRepository>(),
	)

	@Test
	fun `unfiltered full favourite list uses representative fast path`() = runTest {
		coEvery { favouritesDao.findListRepresentatives(-1L) } returns emptyList()

		assertEquals(emptyList<WorkAggregate>(), repository.findFavouriteAggregates())

		coVerify(exactly = 1) { favouritesDao.findListRepresentatives(-1L) }
		coVerify(exactly = 0) {
			favouritesDao.findList(
				categoryId = any(),
				orderName = any(),
				applySpaceFilter = any(),
				allowedTypes = any(),
				classifiedTypes = any(),
				applySourceFilter = any(),
				allowedSources = any(),
				applyContentTypeFilter = any(),
				contentTypes = any(),
				applyPublicationStateFilter = any(),
				publicationStates = any(),
				nsfwMode = any(),
				requireDownloaded = any(),
				requireNewChapters = any(),
				applyExactSourceFilter = any(),
				exactSources = any(),
				applyTagFilter = any(),
				tagIds = any(),
			)
		}
	}

	@Test
	fun `default sfw setting keeps representative fast path`() = runTest {
		coEvery { favouritesDao.findListRepresentatives(-1L) } returns emptyList()

		assertEquals(
			emptyList<WorkAggregate>(),
			repository.findFavouriteAggregates(filterOptions = setOf(ListFilterOption.SFW)),
		)

		coVerify(exactly = 1) { favouritesDao.findListRepresentatives(-1L) }
	}

	@Test
	fun `full favourite list pushes content type scope into dao`() = runTest {
		coEvery {
			favouritesDao.findList(
				categoryId = any(),
				orderName = any(),
				applySpaceFilter = any(),
				allowedTypes = any(),
				classifiedTypes = any(),
				applySourceFilter = any(),
				allowedSources = any(),
				applyContentTypeFilter = any(),
				contentTypes = any(),
				applyPublicationStateFilter = any(),
				publicationStates = any(),
				nsfwMode = any(),
				requireDownloaded = any(),
				requireNewChapters = any(),
				applyExactSourceFilter = any(),
				exactSources = any(),
				applyTagFilter = any(),
				tagIds = any(),
			)
		} returns emptyList()

		assertEquals(
			emptyList<WorkAggregate>(),
			repository.findFavouriteAggregates(groupTab = BrowseGroupTab.Content),
		)
		coVerify {
			favouritesDao.findList(
				categoryId = -1L,
				orderName = ListSortOrder.UPDATED.name,
				applySpaceFilter = false,
				allowedTypes = emptySet(),
				classifiedTypes = any(),
				applySourceFilter = false,
				allowedSources = emptySet(),
				applyContentTypeFilter = true,
				contentTypes = match { types ->
					ContentType.MANGA.name in types && ContentType.NOVEL.name !in types
				},
				applyPublicationStateFilter = false,
				publicationStates = emptySet(),
				nsfwMode = -1,
				requireDownloaded = false,
				requireNewChapters = false,
				applyExactSourceFilter = false,
				exactSources = emptySet(),
				applyTagFilter = false,
				tagIds = emptySet(),
			)
		}
	}

	@Test
	fun `favourite query sends manga scope to dao before limiting`() = runTest {
		coEvery {
			favouritesDao.findActiveForSpace(
				categoryId = null,
				allowedTypes = any(),
				classifiedTypes = any(),
				oldestFirst = false,
				limit = 12,
			)
		} returns emptyList()

		assertEquals(
			emptyList<WorkAggregate>(),
			repository.findFavouriteAggregates(
				order = ListSortOrder.NEWEST,
				limit = 3,
				spaceId = BuiltInSpaces.Manga,
			),
		)
		coVerify {
			favouritesDao.findActiveForSpace(
				categoryId = null,
				allowedTypes = match { types ->
					ContentType.MANGA.name in types && ContentType.NOVEL.name !in types
				},
				classifiedTypes = match { types ->
					ContentType.MANGA.name in types && ContentType.NOVEL.name in types &&
						ContentType.VIDEO.name in types && ContentType.OTHER.name !in types
				},
				oldestFirst = false,
				limit = 12,
			)
		}
	}

	@Test
	fun `history query sends only target space types to dao`() = runTest {
		coEvery {
			historyDao.findRecentForSpace(any(), any(), 5)
		} returns emptyList()

		assertEquals(
			emptyList<WorkAggregate>(),
			repository.findRecentHistoryAggregates(limit = 5, spaceId = BuiltInSpaces.Anime),
		)
		coVerify {
			historyDao.findRecentForSpace(
				allowedTypes = match { it == setOf(ContentType.VIDEO.name, ContentType.HENTAI_VIDEO.name) },
				classifiedTypes = match { ContentType.OTHER.name !in it },
				limit = 5,
			)
		}
	}

	@Test
	fun `history query uses refreshed external source scope`() = runTest {
		coEvery {
			historyDao.findRecentForSpaceAndSources(any(), any(), setOf("MIHON_123"), 1)
		} returns emptyList()

		assertEquals(
			emptyList<WorkAggregate>(),
			repository.findRecentHistoryAggregates(
				limit = 1,
				spaceId = BuiltInSpaces.Manga,
				allowedSourceNames = setOf("MIHON_123"),
			),
		)
		coVerify {
			historyDao.findRecentForSpaceAndSources(
				allowedTypes = match { ContentType.MANGA.name in it },
				classifiedTypes = match { ContentType.OTHER.name !in it },
				allowedSources = setOf("MIHON_123"),
				limit = 1,
			)
		}
	}
}
