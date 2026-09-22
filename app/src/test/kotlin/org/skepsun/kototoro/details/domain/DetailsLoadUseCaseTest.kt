package org.skepsun.kototoro.details.domain

import android.text.Html
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.explore.domain.RecoverContentUseCase
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class DetailsLoadUseCaseTest {

	private val dataRepository = mockk<ContentDataRepository>()
	private val localContentRepository = mockk<LocalMangaRepository>()
	private val repositoryFactory = mockk<ContentRepository.Factory>()
	private val recoverUseCase = mockk<RecoverContentUseCase>()
	private val networkState = mockk<NetworkState>()
	private val database = mockk<MangaDatabase>(relaxed = true)
	private val useCase = DetailsLoadUseCase(
		mangaDataRepository = dataRepository,
		localContentRepository = localContentRepository,
		mangaRepositoryFactory = repositoryFactory,
		recoverUseCase = recoverUseCase,
		imageGetter = mockk<Html.ImageGetter>(relaxed = true),
		networkState = networkState,
		mangaDatabase = database,
	)

	@Test
	fun `initial seed keeps reading source before network details arrive`() = runTest {
		val seed = content(id = 1L, title = "Seed")
		val remote = content(id = 1L, title = "Remote")
		val repository = mockk<ContentRepository>()
		coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns seed
		coEvery { dataRepository.resolveStoredProjection(seed) } returns seed
		coEvery { dataRepository.getOverride(seed.id) } returns null
		coEvery { localContentRepository.findSavedContent(seed, withDetails = true) } returns null
		coEvery { dataRepository.findContentById(seed.id, withChapters = true) } returns null
		every { networkState.isOfflineOrRestricted() } returns false
		every { repositoryFactory.create(TestContentSource) } returns repository
		coEvery { repository.getDetails(seed) } returns remote
		coEvery { dataRepository.updateProjectionSnapshot(remote) } returns remote

		val emissions = useCase(ContentIntent.of(seed), force = false).toList()

		assertEquals(listOf("Seed", "Remote"), emissions.map { it.toContent().title })
		assertEquals(listOf("TEST", "TEST"), emissions.map { it.toContent().source.name })
	}

	@Test
	fun `complete cached snapshot retains its source identity`() {
		val cached = content(id = 3L, title = "Cached", description = "cached description")

		assertTrue(cached.hasCompleteDetailsSnapshot())
		assertEquals(TestContentSource.name, cached.source.name)
	}

	@Test
	fun `refresh stores and emits the network source`() = runTest {
		val seed = content(id = 2L, title = "Seed")
		val remote = content(id = 2L, title = "Remote")
		val repository = mockk<ContentRepository>()
		coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns seed
		coEvery { dataRepository.resolveStoredProjection(seed) } returns seed
		coEvery { dataRepository.getOverride(seed.id) } returns null
		coEvery { localContentRepository.findSavedContent(seed, withDetails = true) } returns null
		every { repositoryFactory.create(TestContentSource) } returns repository
		coEvery { repository.getDetails(seed) } returns remote
		coEvery { dataRepository.updateProjectionSnapshot(remote) } returns remote

		val emissions = useCase(ContentIntent.of(seed), force = true).toList()

		assertSame(remote.source, emissions.last().toContent().source)
		coVerify(exactly = 1) { dataRepository.updateProjectionSnapshot(remote) }
	}

	@Test
	fun `missing manga cover falls back to first page of first chapter`() = runTest {
		val seed = content(id = 4L, title = "Seed", source = TestMangaSource)
		val remote = content(id = 4L, title = "Remote", source = TestMangaSource)
		val repository = mockk<ContentRepository>()
		val firstChapter = remote.chapters.orEmpty().first()
		val firstPage = ContentPage(
			id = 1L,
			url = "/page/1",
			preview = null,
			source = TestMangaSource,
		)
		coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns seed
		coEvery { dataRepository.resolveStoredProjection(seed) } returns seed
		coEvery { dataRepository.getOverride(seed.id) } returns null
		coEvery { localContentRepository.findSavedContent(seed, withDetails = true) } returns null
		every { repositoryFactory.create(TestMangaSource) } returns repository
		coEvery { repository.getDetails(seed) } returns remote
		coEvery { repository.getPages(firstChapter) } returns listOf(firstPage)
		coEvery { repository.getPageUrl(firstPage) } returns "https://example.org/page/1.jpg"
		coEvery { dataRepository.updateProjectionSnapshot(any()) } answers { firstArg() }

		val result = useCase(ContentIntent.of(seed), force = true).toList().last().toContent()

		assertEquals("https://example.org/page/1.jpg", result.coverUrl)
		assertEquals("https://example.org/page/1.jpg", result.largeCoverUrl)
		coVerify(exactly = 1) { repository.getPages(firstChapter) }
	}

	@Test
	fun `existing manga cover does not request chapter pages`() = runTest {
		val seed = content(id = 5L, title = "Seed", source = TestMangaSource)
		val remote = content(
			id = 5L,
			title = "Remote",
			coverUrl = "https://example.org/cover.jpg",
			source = TestMangaSource,
		)
		val repository = mockk<ContentRepository>()
		coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns seed
		coEvery { dataRepository.resolveStoredProjection(seed) } returns seed
		coEvery { dataRepository.getOverride(seed.id) } returns null
		coEvery { localContentRepository.findSavedContent(seed, withDetails = true) } returns null
		every { repositoryFactory.create(TestMangaSource) } returns repository
		coEvery { repository.getDetails(seed) } returns remote
		coEvery { dataRepository.updateProjectionSnapshot(remote) } returns remote

		val result = useCase(ContentIntent.of(seed), force = true).toList().last().toContent()

		assertEquals("https://example.org/cover.jpg", result.coverUrl)
		coVerify(exactly = 0) { repository.getPages(any()) }
	}

	@Test
	fun `complete cached manga resolves and stores first page cover without refreshing details`() = runTest {
		val cached = content(
			id = 6L,
			title = "Cached",
			description = "Cached description",
			source = TestMangaSource,
		)
		val repository = mockk<ContentRepository>()
		val firstChapter = cached.chapters.orEmpty().first()
		val firstPage = ContentPage(
			id = 1L,
			url = "/page/1",
			preview = null,
			source = TestMangaSource,
		)
		coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns cached
		coEvery { dataRepository.resolveStoredProjection(cached) } returns cached
		coEvery { dataRepository.getOverride(cached.id) } returns null
		coEvery { localContentRepository.findSavedContent(cached, withDetails = true) } returns null
		coEvery { dataRepository.findContentById(cached.id, withChapters = true) } returns cached
		every { networkState.isOfflineOrRestricted() } returns false
		every { repositoryFactory.create(TestMangaSource) } returns repository
		coEvery { repository.getPages(firstChapter) } returns listOf(firstPage)
		coEvery { repository.getPageUrl(firstPage) } returns "https://example.org/cached-page.jpg"
		coEvery { dataRepository.updateProjectionSnapshot(any()) } answers { firstArg() }

		val result = useCase(ContentIntent.of(cached), force = false).toList().last().toContent()

		assertEquals("https://example.org/cached-page.jpg", result.coverUrl)
		coVerify(exactly = 0) { repository.getDetails(any()) }
		coVerify(exactly = 1) { dataRepository.updateProjectionSnapshot(any()) }
	}

    @Test
    fun `damaged Komiic identity is repaired before the reader receives cached chapters`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = KomiicSource).let {
            it.copy(url = "", publicUrl = "", chapters = it.chapters?.map { chapter -> chapter.copy(branch = "12345") })
        }
        val repaired = damaged.copy(url = "12345", publicUrl = "https://komiic.cc/comic/12345")
        val parserRepository = mockk<ParserContentRepository> {
            every { domain } returns "komiic.cc"
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.findContentById(damaged.id, withChapters = true) } returns damaged
        coEvery { dataRepository.resolveStoredProjection(any()) } answers { firstArg() }
        coEvery { dataRepository.getOverride(damaged.id) } returns null
        every { repositoryFactory.create(KomiicSource) } returns parserRepository
        coEvery { dataRepository.updateProjectionSnapshotAtAnchor(repaired, damaged.id) } returns repaired
        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        val first = useCase(ContentIntent.of(damaged.id), force = false).first().toContent()

        assertEquals(repaired, first)
        coVerify(exactly = 1) { dataRepository.updateProjectionSnapshotAtAnchor(repaired, damaged.id) }
        coVerify(exactly = 0) { dataRepository.resolveStoredProjection(any()) }
    }

    @Test
    fun `Komiic repair does not guess between different saved comic ids`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = KomiicSource).let {
            val chapter = it.chapters!!.single()
            it.copy(
                url = "",
                publicUrl = "",
                chapters = listOf(chapter.copy(branch = "12345"), chapter.copy(id = 421L, branch = "67890")),
            )
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.findContentById(damaged.id, withChapters = true) } returns damaged
        coEvery { dataRepository.resolveStoredProjection(damaged) } returns damaged
        coEvery { dataRepository.getOverride(damaged.id) } returns null

        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        assertEquals(damaged, useCase(ContentIntent.of(damaged.id), force = false).first().toContent())
        coVerify(exactly = 0) { dataRepository.updateProjectionSnapshotAtAnchor(any(), any()) }
    }

    @Test
    fun `Komiic repair never trusts chapter ids supplied only by the navigation payload`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = KomiicSource).let {
            it.copy(url = "", publicUrl = "", chapters = it.chapters?.map { chapter -> chapter.copy(branch = "12345") })
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.findContentById(damaged.id, withChapters = true) } returns damaged.copy(chapters = null)
        coEvery { dataRepository.resolveStoredProjection(damaged) } returns damaged
        coEvery { dataRepository.getOverride(damaged.id) } returns null
        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        assertEquals(damaged, useCase(ContentIntent.of(damaged), force = false).first().toContent())
        coVerify(exactly = 0) { dataRepository.updateProjectionSnapshotAtAnchor(any(), any()) }
    }

    @Test
    fun `Komiic public url is restored when only the comic id survived`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = KomiicSource).let {
            it.copy(url = "12345", publicUrl = "")
        }
        val repaired = damaged.copy(publicUrl = "https://komiic.cc/comic/12345")
        val parserRepository = mockk<ParserContentRepository> {
            every { domain } returns "komiic.cc"
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.findContentById(damaged.id, withChapters = true) } returns damaged
        coEvery { dataRepository.resolveStoredProjection(any()) } answers { firstArg() }
        coEvery { dataRepository.getOverride(damaged.id) } returns null
        every { repositoryFactory.create(KomiicSource) } returns parserRepository
        coEvery { dataRepository.updateProjectionSnapshotAtAnchor(repaired, damaged.id) } returns repaired
        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        val first = useCase(ContentIntent.of(damaged.id), force = false).first().toContent()

        assertEquals(repaired, first)
        coVerify(exactly = 1) { dataRepository.updateProjectionSnapshotAtAnchor(repaired, damaged.id) }
    }

    @Test
    fun `Komiic repair never builds a public url from a value that is not a comic id`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = KomiicSource).let {
            it.copy(url = "/comic/12345", publicUrl = "")
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.resolveStoredProjection(damaged) } returns damaged
        coEvery { dataRepository.getOverride(damaged.id) } returns null
        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        assertEquals(damaged, useCase(ContentIntent.of(damaged.id), force = false).first().toContent())
        coVerify(exactly = 0) { dataRepository.updateProjectionSnapshotAtAnchor(any(), any()) }
    }

    @Test
    fun `Komiic repair leaves other sources alone`() = runTest {
        val damaged = content(id = 42L, title = "Saved comic", source = TestMangaSource).let {
            it.copy(url = "", publicUrl = "")
        }
        coEvery { dataRepository.resolveIntent(any(), withChapters = true) } returns damaged
        coEvery { dataRepository.resolveStoredProjection(damaged) } returns damaged
        coEvery { dataRepository.getOverride(damaged.id) } returns null
        coEvery { localContentRepository.findSavedContent(any(), withDetails = true) } coAnswers { awaitCancellation() }

        assertEquals(damaged, useCase(ContentIntent.of(damaged.id), force = false).first().toContent())
        coVerify(exactly = 0) { dataRepository.updateProjectionSnapshotAtAnchor(any(), any()) }
    }

    private data object KomiicSource : ContentSource {
        override val name = "KOMIIC"
        override val locale = "zh"
        override val contentType = ContentType.MANGA
    }

	private fun content(
		id: Long,
		title: String,
		description: String? = null,
		coverUrl: String? = null,
		source: ContentSource = TestContentSource,
	): Content {
		return Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = 0f,
			contentRating = null,
			coverUrl = coverUrl,
			largeCoverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			description = description,
			chapters = listOf(
				ContentChapter(
					id = id * 10,
					title = "Chapter 1",
					number = 1f,
					volume = 0,
					url = "/chapter/$id",
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			),
			source = source,
		)
	}

	private data object TestMangaSource : ContentSource {
		override val name = "TEST_MANGA"
		override val locale = "en"
		override val contentType = ContentType.MANGA
	}
}
