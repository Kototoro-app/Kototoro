package org.skepsun.kototoro.history.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventBus
import javax.inject.Provider

class HistoryRepositoryAutoCompletedStatusTest {

    private val mangaRepository = mockk<ContentDataRepository>(relaxed = true)
    private val settings = mockk<AppSettings>(relaxed = true)
    private val repository = HistoryRepository(
        db = mockk<MangaDatabase>(relaxed = true),
        settings = settings,
        scrobblers = emptySet<Scrobbler>(),
        mangaRepository = mangaRepository,
        localObserver = mockk<HistoryLocalObserver>(relaxed = true),
        newChaptersUseCaseProvider = mockk<Provider<CheckNewChaptersUseCase>>(relaxed = true),
        spaceContentPolicy = mockk<SpaceContentPolicy>(relaxed = true),
        sourceTrackerEvents = SourceTrackerEventBus,
    )

    private fun givenStatus(status: ScrobblingStatus?) {
        coEvery { mangaRepository.getReadingStatus(MANGA_ID) } returns status
    }

    private fun givenSwitch(enabled: Boolean) {
        every { settings.isAutoMarkCompletedEnabled } returns enabled
    }

    @Test
    fun `read through marks empty planned and reading titles completed`() = runTest {
        givenSwitch(true)
        val initialStatuses = listOf(null, ScrobblingStatus.PLANNED, ScrobblingStatus.READING)

        for (initial in initialStatuses) {
            givenStatus(initial)

            repository.applyAutoCompletedStatus(MANGA_ID, 1f)
        }

        coVerify(exactly = initialStatuses.size) {
            mangaRepository.setReadingStatus(MANGA_ID, ScrobblingStatus.COMPLETED)
        }
    }

    @Test
    fun `read through keeps statuses the user picked`() = runTest {
        givenSwitch(true)

        for (initial in listOf(
            ScrobblingStatus.ON_HOLD,
            ScrobblingStatus.DROPPED,
            ScrobblingStatus.RE_READING,
            ScrobblingStatus.COMPLETED,
        )) {
            givenStatus(initial)

            repository.applyAutoCompletedStatus(MANGA_ID, 1f)

            coVerify(exactly = 0) { mangaRepository.setReadingStatus(any(), any()) }
        }
    }

    @Test
    fun `read through writes nothing when the switch is off`() = runTest {
        givenSwitch(false)

        for (initial in listOf(null, ScrobblingStatus.PLANNED, ScrobblingStatus.READING)) {
            givenStatus(initial)

            repository.applyAutoCompletedStatus(MANGA_ID, 1f)

            coVerify(exactly = 0) { mangaRepository.setReadingStatus(any(), any()) }
        }
    }

    @Test
    fun `partial progress writes nothing`() = runTest {
        givenSwitch(true)
        givenStatus(ScrobblingStatus.READING)

        repository.applyAutoCompletedStatus(MANGA_ID, 0.42f)

        coVerify(exactly = 0) { mangaRepository.setReadingStatus(any(), any()) }
    }

    @Test
    fun `progress within float noise of a hundred percent still completes a reading title`() = runTest {
        givenSwitch(true)
        givenStatus(ScrobblingStatus.READING)

        repository.applyAutoCompletedStatus(MANGA_ID, 0.99999f)

        coVerify(exactly = 1) { mangaRepository.setReadingStatus(MANGA_ID, ScrobblingStatus.COMPLETED) }
    }

    @Test
    fun `the stored status is read back rather than assumed`() = runTest {
        givenSwitch(true)
        givenStatus(ScrobblingStatus.ON_HOLD)

        repository.applyAutoCompletedStatus(MANGA_ID, 1f)

        coVerify(exactly = 1) { mangaRepository.getReadingStatus(MANGA_ID) }
        assertEquals(ScrobblingStatus.ON_HOLD, mangaRepository.getReadingStatus(MANGA_ID))
    }

    private companion object {
        const val MANGA_ID = 42L
    }
}
