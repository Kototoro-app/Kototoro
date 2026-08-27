package org.skepsun.kototoro.work.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.history.data.WorkHistoryDao
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TracksDao

class WorkAggregateTrackingTest {

	private val tracksDao = mockk<TracksDao>()
	private val historyDao = mockk<WorkHistoryDao>()
	private val favouritesDao = mockk<WorkFavouritesDao>()
	private val mangaDao = mockk<MangaDao>()
	private val entityGraphDao = mockk<EntityGraphDao>()
	private val workResolver = mockk<WorkResolver>()
	private val db = mockk<MangaDatabase> {
		every { getTracksDao() } returns tracksDao
		every { getWorkHistoryDao() } returns historyDao
		every { getWorkFavouritesDao() } returns favouritesDao
		every { getMangaDao() } returns mangaDao
		every { getEntityGraphDao() } returns entityGraphDao
		every { getWorkStatsDao() } returns mockk(relaxed = true)
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
	fun `tracking aggregates do not re-read history favourite or tracks per entity`() = runTest {
		val display = MangaEntity(
			id = 10_001L,
			title = "Tracked work",
			altTitles = null,
			url = "/item/10001",
			publicUrl = "",
			rating = 0.5f,
			isNsfw = false,
			contentRating = null,
			coverUrl = "",
			largeCoverUrl = null,
			state = null,
			authors = "Author",
			source = "TEST",
			description = null,
			contentType = "MANGA",
			sourceData = null,
		)
		val identity = WorkIdentity(
			entityId = 1L,
			requestedMangaId = 10_001L,
			preferredMangaId = 10_001L,
			localMangaIds = setOf(10_001L),
			migrationState = WorkMigrationState.VALID,
		)
		val track = TrackEntity(
			ownerId = 1L,
			mangaId = 10_001L,
			entityId = 1L,
			lastChapterId = 7L,
			newChapters = 3,
			lastCheckTime = 10L,
			lastChapterDate = 11L,
			lastResult = TrackEntity.RESULT_HAS_UPDATE,
			lastError = null,
		)
		coEvery { workResolver.resolveManyByEntityIds(any()) } returns mapOf(1L to identity)
		coEvery { mangaDao.findWithTagsByIds(any()) } returns listOf(MangaWithTags(display, emptyList()))
		coEvery { entityGraphDao.findEntitiesByIds(any()) } returns emptyList()
		coEvery { tracksDao.findByEntityIds(any()) } returns listOf(track)
		coEvery { favouritesDao.findCategoryMemberships(any()) } returns emptyList()
		coEvery { historyDao.find(any()) } returns null
		coEvery { favouritesDao.findActiveForEntity(any()) } returns null

		val aggregates = repository.buildTrackingAggregates(listOf(track))
		val aggregate = aggregates.single()

		assertEquals(1L, aggregate.identity.entityId)
		assertEquals(10_001L, aggregate.displayProjection?.id)
		assertEquals("Tracked work", aggregate.displayProjection?.title)
		assertEquals(10_001L, aggregate.tracking?.anchorMangaId)
		assertEquals(3, aggregate.tracking?.newChapters)
		assertEquals(11L, aggregate.tracking?.lastChapterDate)
		// The tracking page/feed consumes display + identity + tracking only; history
		// and favourite are never fetched for the aggregate, and the tracking summary
		// comes from the track already in hand instead of a re-query.
		assertNull(aggregate.history)
		assertNull(aggregate.favourite)
		coVerify(exactly = 0) { historyDao.find(any()) }
		coVerify(exactly = 0) { favouritesDao.findActiveForEntity(any()) }
		coVerify(exactly = 0) { tracksDao.findByEntityIds(any()) }
	}
}
