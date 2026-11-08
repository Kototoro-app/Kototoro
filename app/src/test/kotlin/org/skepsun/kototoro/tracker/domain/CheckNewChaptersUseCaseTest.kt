package org.skepsun.kototoro.tracker.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository.DetailsFetchMode
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates

class CheckNewChaptersUseCaseTest : StringSpec({

    fun chapter(id: Long) = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "https://example.com/manga/chapter/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = TestContentSource,
    )

    fun manga(chapterIds: List<Long>?, state: ContentState? = null) = Content(
        id = 24L,
        title = "Manga",
        altTitles = emptySet(),
        url = "https://example.com/manga",
        publicUrl = "https://example.com/manga",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = state,
        authors = emptySet(),
        chapters = chapterIds?.map(::chapter),
        source = TestContentSource,
    )

    fun track(lastChapterId: Long) = ContentTracking(
        anchorMangaId = 24L,
        entityId = null,
        preferredLocalMangaId = null,
        manga = manga(chapterIds = null),
        lastChapterId = lastChapterId,
        lastCheck = null,
        lastChapterDate = null,
        newChapters = 0,
    )

    fun useCase(repository: ContentRepository): CheckNewChaptersUseCase {
        val factory = mockk<ContentRepository.Factory> { every { create(any()) } returns repository }
        val history = mockk<HistoryRepository> { coEvery { getOne(any()) } returns null }
        return CheckNewChaptersUseCase(
            repository = mockk(relaxed = true),
            historyRepository = history,
            mangaRepositoryFactory = factory,
            localContentRepository = mockk(relaxed = true),
        )
    }

    "a check without new chapters only asks for the chapter list" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) } returns manga(listOf(1L, 2L))
        }

        val updates = useCase(repository)(track(lastChapterId = 2L))

        updates.shouldBeInstanceOf<MangaUpdates.Success>().newChapters shouldHaveSize 0
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.FORCE_REFRESH) }
    }

    "new chapters refresh the details too, so a finished series is noticed" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) } returns manga(listOf(1L, 2L, 3L))
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns
                manga(listOf(1L, 2L, 3L), state = ContentState.FINISHED)
        }

        val updates = useCase(repository)(track(lastChapterId = 2L))

        val success = updates.shouldBeInstanceOf<MangaUpdates.Success>()
        success.newChapters.map { it.id } shouldBe listOf(3L)
        success.manga.state shouldBe ContentState.FINISHED
    }

    "a source that cannot fetch chapters alone is refreshed once" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns false
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns manga(listOf(1L, 2L, 3L))
        }

        val updates = useCase(repository)(track(lastChapterId = 2L))

        updates.shouldBeInstanceOf<MangaUpdates.Success>().newChapters.map { it.id } shouldBe listOf(3L)
        coVerify(exactly = 1) { repository.getDetails(any(), DetailsFetchMode.FORCE_REFRESH) }
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) }
    }

    "the first check of a title fetches its details" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns manga(listOf(1L))
        }

        useCase(repository)(track(lastChapterId = 0L)).shouldBeInstanceOf<MangaUpdates.Success>()
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) }
    }
})
