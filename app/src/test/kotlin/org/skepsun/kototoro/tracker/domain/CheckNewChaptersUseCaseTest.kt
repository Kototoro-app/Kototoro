package org.skepsun.kototoro.tracker.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.skepsun.kototoro.core.db.entity.ChapterEntity
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

    fun useCase(
        repository: ContentRepository,
        tracking: TrackingRepository = mockk(relaxed = true),
    ): CheckNewChaptersUseCase {
        val factory = mockk<ContentRepository.Factory> { every { create(any()) } returns repository }
        val history = mockk<HistoryRepository> { coEvery { getOne(any()) } returns null }
        return CheckNewChaptersUseCase(
            repository = tracking,
            historyRepository = history,
            mangaRepositoryFactory = factory,
            localContentRepository = mockk(relaxed = true),
        )
    }

    fun entity(id: Long) = ChapterEntity(id, 24L, "Chapter $id", id.toFloat(), 0, "/c/$id", null, 0L, null, "TEST", 0)

    /** Storing reports [results] in turn, the way storeChapterSnapshot would for the lists given. */
    fun tracking(vararg results: ChapterSnapshotResult?): TrackingRepository {
        val queue = ArrayDeque(results.toList())
        return mockk(relaxed = true) {
            coEvery { storeCheckedSnapshot(any(), any()) } answers {
                firstArg<Content>().copy(id = secondArg()) to queue.removeFirstOrNull()
            }
        }
    }

    "a check without new chapters only asks for the chapter list" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) } returns manga(listOf(1L, 2L))
        }

        val updates = useCase(repository, tracking(ChapterSnapshotResult.Updated(emptyList())))(track(2L))

        updates.shouldBeInstanceOf<MangaUpdates.Success>().newChapters shouldHaveSize 0
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.FORCE_REFRESH) }
    }

    "the chapters storing the list recorded are the new ones, and the details are refreshed for them" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) } returns manga(listOf(1L, 2L, 3L))
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns
                manga(listOf(1L, 2L, 3L), state = ContentState.FINISHED)
        }
        val stores = tracking(ChapterSnapshotResult.Updated(listOf(entity(3))), ChapterSnapshotResult.Updated(emptyList()))

        val updates = useCase(repository, stores)(track(lastChapterId = 2L))

        val success = updates.shouldBeInstanceOf<MangaUpdates.Success>()
        success.newChapters.map { it.id } shouldBe listOf(3L)
        success.manga.state shouldBe ContentState.FINISHED
        success.manga.id shouldBe 24L
    }

    "a source that cannot fetch chapters alone is refreshed once" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns false
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns manga(listOf(1L, 2L, 3L))
        }

        val updates = useCase(repository, tracking(ChapterSnapshotResult.Updated(listOf(entity(3)))))(track(2L))

        updates.shouldBeInstanceOf<MangaUpdates.Success>().newChapters.map { it.id } shouldBe listOf(3L)
        coVerify(exactly = 1) { repository.getDetails(any(), DetailsFetchMode.FORCE_REFRESH) }
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) }
    }

    "the first check of a title fetches its details and only sets the baseline" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.FORCE_REFRESH) } returns manga(listOf(1L))
        }

        val updates = useCase(repository, tracking(ChapterSnapshotResult.Stored))(track(lastChapterId = 0L))

        updates.shouldBeInstanceOf<MangaUpdates.Success>().isValid shouldBe false
        coVerify(exactly = 0) { repository.getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) }
    }

    "a list too short to be complete fails the check" {
        val repository = mockk<ContentRepository> {
            every { refreshesChaptersAlone } returns true
            coEvery { getDetails(any(), DetailsFetchMode.CHAPTERS_REFRESH) } returns manga(listOf(1L))
        }

        val updates = useCase(repository, tracking(ChapterSnapshotResult.Incomplete))(track(lastChapterId = 2L))

        updates.shouldBeInstanceOf<MangaUpdates.Failure>().error.shouldBeInstanceOf<IncompleteChapterListException>()
    }

    "reading an older chapter keeps chapters the tracker has not seen after the anchor" {
        val checkedAt = java.time.Instant.ofEpochMilli(5_000L)
        val saved = slot<ContentTracking>()
        val tracking = mockk<TrackingRepository>(relaxed = true) {
            coEvery { updateReadChapter(any(), any(), any(), any()) } returns false
            coEvery { getTrackOrNull(any()) } returns track(lastChapterId = 2L).copy(lastCheck = checkedAt)
            coEvery { mergeWith(capture(saved)) } returns Unit
        }
        // The reader loaded chapters 3 and 4, which appeared after the last check.
        useCase(mockk(), tracking)(manga(listOf(1L, 2L, 3L, 4L)), currentChapterId = 1L)

        saved.captured.lastChapterId shouldBe 2L
        saved.captured.lastCheck shouldBe checkedAt
    }
})
