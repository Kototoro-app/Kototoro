package org.skepsun.kototoro.filter.ui

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.ViewModelLifecycle
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.PendingContentListNavigation
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.search.domain.ContentSearchRepository

/**
 * Regression test for the "empty filter sheet on Navigation 3 content lists" bug.
 *
 * Browse page → content source button → [MainNavigator.openContentList] hands the source
 * over via [PendingContentListNavigation] (Navigation 3 does not map route arguments into
 * the entry's SavedStateHandle). The list ViewModels already fall back to that hand-off;
 * FilterCoordinator must too, otherwise it builds its repository against an unknown source
 * and the filter sheet comes up empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterCoordinatorSourceResolutionTest {

	private val mainDispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(mainDispatcher)
		PendingContentListNavigation.clear()
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
		PendingContentListNavigation.clear()
	}

	@Test
	fun `source falls back to pending navigation source when saved state has none`() {
		PendingContentListNavigation.setSource("PENDING_SOURCE")

		val factory = mockk<ContentRepository.Factory>(relaxed = true)
		val capturedSource = slot<ParserContentSource>()
		every { factory.create(capture(capturedSource)) } returns mockRepository("PENDING_SOURCE")

		FilterCoordinator(
			savedStateHandle = SavedStateHandle(),
			mangaRepositoryFactory = factory,
			searchRepository = mockk(relaxed = true),
			savedFiltersRepository = mockSavedFilters(),
			settings = mockSettings(),
			lifecycle = mockk<ViewModelLifecycle>(relaxed = true),
		)

		assertEquals("PENDING_SOURCE", capturedSource.captured.name)
		// The hand-off must be peeked, not consumed: RemoteListViewModel still needs it.
		assertEquals("PENDING_SOURCE", PendingContentListNavigation.peekSourceName())
	}

	@Test
	fun `saved state source takes precedence over pending navigation source`() {
		PendingContentListNavigation.setSource("PENDING_SOURCE")
		val savedStateHandle = SavedStateHandle().apply {
			this[AppRouter.KEY_SOURCE] = "SAVED_SOURCE"
		}

		val factory = mockk<ContentRepository.Factory>(relaxed = true)
		val capturedSource = slot<ParserContentSource>()
		every { factory.create(capture(capturedSource)) } returns mockRepository("SAVED_SOURCE")

		FilterCoordinator(
			savedStateHandle = savedStateHandle,
			mangaRepositoryFactory = factory,
			searchRepository = mockk(relaxed = true),
			savedFiltersRepository = mockSavedFilters(),
			settings = mockSettings(),
			lifecycle = mockk<ViewModelLifecycle>(relaxed = true),
		)

		assertEquals("SAVED_SOURCE", capturedSource.captured.name)
	}

	@Test
	fun `unknown source when neither saved state nor pending navigation provides a name`() {
		val factory = mockk<ContentRepository.Factory>(relaxed = true)
		val capturedSource = slot<ParserContentSource>()
		every { factory.create(capture(capturedSource)) } returns mockRepository("UNKNOWN")

		FilterCoordinator(
			savedStateHandle = SavedStateHandle(),
			mangaRepositoryFactory = factory,
			searchRepository = mockk(relaxed = true),
			savedFiltersRepository = mockSavedFilters(),
			settings = mockSettings(),
			lifecycle = mockk<ViewModelLifecycle>(relaxed = true),
		)

		assertEquals("UNKNOWN", capturedSource.captured.name)
	}

	private fun mockRepository(sourceName: String): ContentRepository {
		val parserSource = mockk<ParserContentSource>(relaxed = true)
		every { parserSource.name } returns sourceName
		every { parserSource.locale } returns ""

		val repository = mockk<ContentRepository>(relaxed = true)
		every { repository.source } returns parserSource
		every { repository.sortOrders } returns emptySet()
		every { repository.defaultSortOrder } returns SortOrder.RELEVANCE
		return repository
	}

	private fun mockSavedFilters(): SavedFiltersRepository {
		val repository = mockk<SavedFiltersRepository>(relaxed = true)
		every { repository.observeAll(any()) } returns flowOf(emptyList())
		return repository
	}

	private fun mockSettings(): AppSettings {
		val settings = mockk<AppSettings>(relaxed = true)
		every { settings.globalTagBlacklist } returns emptySet()
		// A real, never-emitting flow: observeAsFlow collects it without blocking forever.
		every { settings.observeChanges() } returns MutableSharedFlow(extraBufferCapacity = 64)
		return settings
	}
}
