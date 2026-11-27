package org.skepsun.kototoro.history.domain

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * "Mark as read" reaches the same status rule as reading the last page: it stores percent = 1f through
 * [HistoryRepository.addOrUpdate], and the repository upgrades an untouched reading status from there.
 */
class MarkAsReadUseCaseTest {

    private var storedStatus: ScrobblingStatus? = null
    private var autoMarkEnabled = true
    private val percentSlot = slot<Float>()

    private val historyRepository = mockk<HistoryRepository>(relaxed = true) {
        coEvery {
            addOrUpdate(
                manga = any(),
                chapterId = any(),
                page = any(),
                scroll = any(),
                percent = capture(percentSlot),
                force = any(),
            )
        } answers {
            storedStatus = readingStatusAfterCompletion(
                current = storedStatus,
                percent = percentSlot.captured,
                enabled = autoMarkEnabled,
            ) ?: storedStatus
        }
    }

    private val contentRepository = mockk<ContentRepository>(relaxed = true) {
        coEvery { getPages(any(), any()) } returns listOf(page(1L), page(2L))
    }

    private val contentRepositoryFactory = mockk<ContentRepository.Factory> {
        every { create(any()) } returns contentRepository
    }

    private val useCase = MarkAsReadUseCase(historyRepository, contentRepositoryFactory)

    @Test
    fun `mark as read completes an untouched reading status`() = runTest {
        for (initial in listOf(null, ScrobblingStatus.PLANNED, ScrobblingStatus.READING)) {
            storedStatus = initial

            useCase(content())

            assertEquals(ScrobblingStatus.COMPLETED, storedStatus, "initial status $initial")
            coVerify { historyRepository.addOrUpdate(any(), any(), any(), any(), 1f, true) }
        }
    }

    @Test
    fun `mark as read keeps statuses the user picked`() = runTest {
        for (initial in listOf(
            ScrobblingStatus.ON_HOLD,
            ScrobblingStatus.DROPPED,
            ScrobblingStatus.RE_READING,
            ScrobblingStatus.COMPLETED,
        )) {
            storedStatus = initial

            useCase(content())

            assertEquals(initial, storedStatus, "initial status $initial must be kept")
        }
    }

    @Test
    fun `mark as read keeps the stored status when the switch is off`() = runTest {
        autoMarkEnabled = false

        for (initial in listOf(null, ScrobblingStatus.PLANNED, ScrobblingStatus.READING)) {
            storedStatus = initial

            useCase(content())

            assertEquals(initial, storedStatus, "initial status $initial must be kept")
        }
    }

    private fun content() = Content(
        id = 1L,
        title = "Title",
        altTitles = emptySet(),
        url = "/title",
        publicUrl = "https://example.com/title",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        chapters = listOf(chapter(1L), chapter(2L)),
        source = TestContentSource,
    )

    private fun chapter(id: Long) = ContentChapter(
        id = id,
        title = "Chapter $id",
        number = id.toFloat(),
        volume = 0,
        url = "/chapter/$id",
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = TestContentSource,
    )

    private fun page(id: Long) = ContentPage(
        id = id,
        url = "/page/$id",
        preview = null,
        source = TestContentSource,
    )
}
