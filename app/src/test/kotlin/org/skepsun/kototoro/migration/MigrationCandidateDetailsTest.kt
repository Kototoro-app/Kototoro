package org.skepsun.kototoro.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationSettings
import org.skepsun.kototoro.migration.ui.list.MigrationItemStatus
import org.skepsun.kototoro.migration.ui.list.MigrationListActivity
import org.skepsun.kototoro.migration.ui.list.MigrationListViewModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.search.domain.SearchResults
import org.skepsun.kototoro.search.domain.SearchV2Helper

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationCandidateDetailsTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val repository = mockk<ContentRepository>()
    private val helper = mockk<SearchV2Helper>()
    private val migrate = mockk<MigrateUseCase>()
    private val candidates = (2L..11L).map(::content)
    private var fetch: suspend (Content) -> Content = { summary ->
        summary.copy(chapters = List(3) { chapter(summary.id * 100 + it, (it + 1).toFloat()) })
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.Default } returns dispatcher
        mockkStatic("androidx.lifecycle.ViewModelKt")
        every { any<ViewModel>().viewModelScope } returns scope
        coEvery { repository.getDetails(any()) } coAnswers { fetch(firstArg()) }
        val results = mockk<SearchResults> { every { manga } returns candidates }
        coEvery { helper.invoke(any(), any(), any()) } returns results
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        unmockkStatic("androidx.lifecycle.ViewModelKt")
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `preview hydrates a candidate without changing the selected target or migrating`() {
        val model = model()
        model.loadCandidateDetails(1, 3)
        scheduler.advanceUntilIdle()

        val item = model.state.value.items.single()
        assertEquals(3, item.candidates.first { it.content.id == 3L }.content.chapters?.size)
        assertEquals(2L, item.target?.id)
        assertEquals(MigrationItemStatus.MATCHED, item.status)
        unmockkStatic(Dispatchers::class)
        coVerify(exactly = 0) { migrate.invoke(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate previews share one request and hydrate all rows with the same candidate`() {
        val model = model(1, 10)
        val gate = CompletableDeferred<Unit>()
        fetch = { gate.await(); it.copy(chapters = emptyList()) }
        model.loadCandidateDetails(1, 3)
        model.loadCandidateDetails(10, 3)
        scheduler.runCurrent()
        verifyDetailsCalls(3, 1)
        gate.complete(Unit)
        scheduler.advanceUntilIdle()

        model.state.value.items.forEach { item ->
            assertEquals(emptyList<Any>(), item.candidates.first { it.content.id == 3L }.content.chapters)
        }
        model.loadCandidateDetails(1, 3)
        scheduler.advanceUntilIdle()
        verifyDetailsCalls(3, 1)
    }

    @Test
    fun `selected match details are reused and a known empty chapter list is not fetched again`() {
        val model = model()
        model.loadCandidateDetails(1, 2)
        scheduler.advanceUntilIdle()
        verifyDetailsCalls(2, 1)

        fetch = { it.copy(chapters = emptyList()) }
        model.loadCandidateDetails(1, 3)
        scheduler.advanceUntilIdle()
        // Even a click captured before the preview completed must reuse the now-loaded details.
        model.selectCandidate(1, org.skepsun.kototoro.migration.domain.MatchCandidate(candidates[1], 1.0))
        scheduler.advanceUntilIdle()

        assertEquals(0, model.state.value.items.single().targetChapters)
        assertEquals(3, model.state.value.items.single().candidates.first { it.content.id == 2L }.content.chapters?.size)
        verifyDetailsCalls(3, 1)
    }

    @Test
    fun `a failed preview preserves unknown chapters and the selected target and can be retried`() {
        val model = model()
        fetch = { error("source unavailable") }
        model.loadCandidateDetails(1, 3)
        scheduler.advanceUntilIdle()
        assertNull(model.state.value.items.single().candidates.first { it.content.id == 3L }.content.chapters)
        assertEquals(2L, model.state.value.items.single().target?.id)
        assertEquals(MigrationItemStatus.MATCHED, model.state.value.items.single().status)

        fetch = { it.copy(chapters = emptyList()) }
        model.loadCandidateDetails(1, 3)
        scheduler.advanceUntilIdle()
        verifyDetailsCalls(3, 2)
    }

    @Test
    fun `manual search preview requests respect the two request limit per source`() {
        val model = model()
        model.manualSearch(1, "Frieren")
        scheduler.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        fetch = {
            active++
            peak = maxOf(peak, active)
            try { gate.await(); it.copy(chapters = emptyList()) } finally { active-- }
        }
        candidates.drop(1).forEach { model.loadCandidateDetails(1, it.id) }
        scheduler.runCurrent()
        assertEquals(2, active)
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(2, peak)
        assertEquals(2L, model.state.value.items.single().target?.id)
    }

    private fun model(vararg ids: Long = longArrayOf(1)): MigrationListViewModel {
        val database = mockk<MangaDatabase>(relaxed = true)
        val data = mockk<ContentDataRepository>()
        coEvery { data.findContentById(any(), false) } answers {
            content(firstArg()).copy(source = LocalMangaSource)
        }
        val repositoryFactory = mockk<ContentRepository.Factory> {
            every { create(any()) } returns repository
        }
        val helperFactory = mockk<SearchV2Helper.Factory> { every { create(any()) } returns helper }
        val settings = mockk<MigrationSettings>(relaxed = true) {
            every { getTargetSourceNames(any()) } returns listOf(TestContentSource.name)
            every { matchMode } returns MatchMode.FIRST_HIT
            every { extraQuery } returns ""
        }
        every { Dispatchers.Default } returns dispatcher
        return MigrationListViewModel(
            SavedStateHandle(mapOf(MigrationListActivity.EXTRA_IDS to ids)),
            database, data, repositoryFactory, helperFactory, migrate, settings,
        ).also { scheduler.advanceUntilIdle() }
    }

    private fun verifyDetailsCalls(id: Long, count: Int) {
        // MockK's coVerify starts its own coroutine; keep that dispatcher lookup out of verification.
        unmockkStatic(Dispatchers::class)
        try {
            coVerify(exactly = count) { repository.getDetails(match { it.id == id }) }
        } finally {
            mockkStatic(Dispatchers::class)
            every { Dispatchers.Default } returns dispatcher
        }
    }

    private fun content(id: Long) = Content(
        id = id, title = "Frieren", altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = TestContentSource,
    )
}
