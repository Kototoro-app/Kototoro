package org.skepsun.kototoro.history.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkMigrationState
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Provider

class HistoryRepositoryResumeFilterTest {

	private val workAggregateRepository = mockk<WorkAggregateRepository>()
	private val spaceContentPolicy = mockk<SpaceContentPolicy>()
	private val repository = HistoryRepository(
		db = mockk<MangaDatabase>(relaxed = true),
		settings = mockk<AppSettings>(relaxed = true),
		scrobblers = emptySet<Scrobbler>(),
		mangaRepository = mockk<ContentDataRepository>(relaxed = true),
		localObserver = mockk<HistoryLocalObserver>(relaxed = true),
		newChaptersUseCaseProvider = mockk<Provider<CheckNewChaptersUseCase>>(relaxed = true),
		entityGraphRepository = mockk<EntityGraphRepository>(relaxed = true),
		workResolver = mockk<WorkResolver>(relaxed = true),
		workAggregateRepository = workAggregateRepository,
		spaceContentPolicy = spaceContentPolicy,
	)

	@Test
	fun `adult history is skipped when selecting resume content`() = runTest {
		val adult = content(1L, ContentRating.ADULT)
		val safe = content(2L, ContentRating.SAFE)
		coEvery {
			workAggregateRepository.findRecentHistoryAggregates(any(), null, null)
		} answers {
			listOf(aggregate(adult), aggregate(safe)).take(firstArg())
		}

		assertEquals(safe, repository.getLastOrNull(excludeNsfw = true))
		assertEquals(adult, repository.getLastOrNull(excludeNsfw = false))
	}

	@Test
	fun `resume search continues past a full adult batch`() = runTest {
		val safe = content(100L, ContentRating.SAFE)
		val history = List(32) { index -> aggregate(content(index.toLong(), ContentRating.ADULT)) } + aggregate(safe)
		coEvery {
			workAggregateRepository.findRecentHistoryAggregates(any(), null, null)
		} answers {
			history.take(firstArg())
		}

		assertEquals(safe, repository.getLastOrNull(excludeNsfw = true))
		coVerify(exactly = 1) {
			workAggregateRepository.findRecentHistoryAggregates(32, null, null)
		}
		coVerify(exactly = 1) {
			workAggregateRepository.findRecentHistoryAggregates(64, null, null)
		}
	}

	@Test
	fun `space resume applies adult filtering inside the selected space`() = runTest {
		val safeAnime = content(2L, ContentRating.SAFE, ContentType.VIDEO)
		every { spaceContentPolicy.allowedSourceNames(BuiltInSpaces.Anime) } returns null
		coEvery {
			workAggregateRepository.findRecentHistoryAggregates(any(), BuiltInSpaces.Anime, null)
		} returns listOf(
			aggregate(content(1L, ContentRating.ADULT, ContentType.HENTAI_VIDEO)),
			aggregate(safeAnime),
		)

		assertEquals(
			safeAnime,
			repository.getLastOrNull(spaceId = BuiltInSpaces.Anime, excludeNsfw = true),
		)
	}

	private fun aggregate(content: Content) = WorkAggregate(
		identity = WorkIdentity(
			entityId = null,
			requestedMangaId = content.id,
			preferredMangaId = content.id,
			localMangaIds = setOf(content.id),
			migrationState = WorkMigrationState.VALID,
		),
		displayProjection = content,
		projections = listOf(content),
	)

	private fun content(
		id: Long,
		contentRating: ContentRating,
		contentType: ContentType = ContentType.MANGA,
	) = Content(
		id = id,
		title = "Work $id",
		altTitles = emptySet(),
		url = "/$id",
		publicUrl = "https://example.org/$id",
		rating = RATING_UNKNOWN,
		contentRating = contentRating,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = TestContentSource(contentType),
	).also { check(it.isNsfw() == (contentRating == ContentRating.ADULT)) }

	private data class TestContentSource(
		override val contentType: ContentType,
	) : ContentSource {
		override val name = "test-$contentType"
		override val locale = ""
	}
}
