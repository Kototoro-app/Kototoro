package org.skepsun.kototoro.history.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventBus
import javax.inject.Provider

class HistoryRepositoryResumeFilterTest {

	private val historyDao = mockk<HistoryDao>()
	private val spaceContentPolicy = mockk<SpaceContentPolicy>()
	private val repository = HistoryRepository(
		db = mockk<MangaDatabase>(relaxed = true) {
			every { getHistoryDao() } returns historyDao
		},
		settings = mockk<AppSettings>(relaxed = true),
		scrobblers = emptySet<Scrobbler>(),
		mangaRepository = mockk<ContentDataRepository>(relaxed = true),
		localObserver = mockk<HistoryLocalObserver>(relaxed = true),
		newChaptersUseCaseProvider = mockk<Provider<CheckNewChaptersUseCase>>(relaxed = true),
		spaceContentPolicy = spaceContentPolicy,
		sourceTrackerEvents = SourceTrackerEventBus,
	)

	@Test
	fun `adult history is skipped when selecting resume content`() = runTest {
		val rows = listOf(row(1L, ContentRating.ADULT), row(2L, ContentRating.SAFE))
		coEvery { historyDao.findRecent(any()) } answers { rows.take(firstArg()) }

		assertEquals(2L, repository.getLastOrNull(excludeNsfw = true)?.id)
		assertEquals(1L, repository.getLastOrNull(excludeNsfw = false)?.id)
	}

	@Test
	fun `resume search continues past a full adult batch`() = runTest {
		val rows = List(32) { index -> row(index.toLong(), ContentRating.ADULT) } + row(100L, ContentRating.SAFE)
		coEvery { historyDao.findRecent(any()) } answers { rows.take(firstArg()) }

		assertEquals(100L, repository.getLastOrNull(excludeNsfw = true)?.id)
		coVerify(exactly = 1) { historyDao.findRecent(32) }
		coVerify(exactly = 1) { historyDao.findRecent(64) }
	}

	@Test
	fun `space resume filters by space content types and adult rating`() = runTest {
		every { spaceContentPolicy.allowedSourceNames(BuiltInSpaces.Anime) } returns null
		every { spaceContentPolicy.allowedTypes(BuiltInSpaces.Anime) } returns setOf(ContentType.VIDEO)
		coEvery {
			historyDao.findRecentForSpace(listOf(ContentType.VIDEO.name), any())
		} returns listOf(
			row(1L, ContentRating.ADULT, ContentType.VIDEO),
			row(2L, ContentRating.SAFE, ContentType.VIDEO),
		)

		assertEquals(
			2L,
			repository.getLastOrNull(spaceId = BuiltInSpaces.Anime, excludeNsfw = true)?.id,
		)
	}

	@Test
	fun `popular filter options reuse one history load`() = runTest {
		val action = tag(1L, "Action", "alpha")
		val drama = tag(2L, "Drama", "alpha")
		coEvery { historyDao.findRecent(Int.MAX_VALUE) } returns listOf(
			row(1L, ContentRating.SAFE, tags = listOf(action, drama), source = "alpha"),
			row(2L, ContentRating.SAFE, tags = listOf(action), source = "beta"),
			row(3L, ContentRating.SAFE, tags = listOf(action), source = "alpha"),
		)

		val options = repository.getPopularFilterOptions(tagLimit = 1, sourceLimit = 1)

		assertEquals(listOf("Action"), options.tags.map { it.title })
		assertEquals(listOf("alpha"), options.sources.map { it.name })
		coVerify(exactly = 1) { historyDao.findRecent(Int.MAX_VALUE) }
	}

	private fun tag(id: Long, title: String, source: String) = TagEntity(
		id = id,
		title = title,
		key = title.lowercase(),
		source = source,
		isPinned = false,
	)

	private fun row(
		id: Long,
		contentRating: ContentRating,
		contentType: ContentType = ContentType.MANGA,
		tags: List<TagEntity> = emptyList(),
		source: String = "test",
	) = HistoryWithContent(
		history = HistoryEntity(
			mangaId = id,
			createdAt = id,
			updatedAt = id,
			chapterId = 0L,
			page = 0,
			scroll = 0f,
			percent = 0f,
			deletedAt = 0L,
			chaptersCount = 0,
		),
		manga = MangaEntity(
			id = id,
			title = "Work $id",
			altTitles = null,
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = -1f,
			isNsfw = contentRating == ContentRating.ADULT,
			contentRating = contentRating.name,
			coverUrl = "",
			largeCoverUrl = null,
			state = null,
			authors = null,
			source = source,
			contentType = contentType.name,
		),
		tags = tags,
	)
}
