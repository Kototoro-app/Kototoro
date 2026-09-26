package org.skepsun.kototoro.favourites.ui.container

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.favourites.domain.library.FavouriteContentResolver
import org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshot
import org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshotStore
import org.skepsun.kototoro.favourites.domain.library.FavouritesCardMapper
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.ui.SpaceBrowseBinding
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.tracker.work.UpdateCheckRequest

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesContainerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `manual update check does not expose page loading state`() = runTest {
        val checkStarted = CompletableDeferred<Unit>()
        val checkFinished = CompletableDeferred<Unit>()
        val scheduler = mockk<TrackWorker.Scheduler> {
            coEvery { requestCheckNow() } coAnswers {
                checkStarted.complete(Unit)
                UpdateCheckRequest.Started
            }
            coEvery { awaitOneShot(any()) } coAnswers {
                checkFinished.await()
                true
            }
        }
        val viewModel = createViewModel(scheduler)
        val loadingCollectorStarted = CompletableDeferred<Unit>()
        val loadingCollector = launch(Dispatchers.Default) {
            viewModel.isLoading
                .onStart { loadingCollectorStarted.complete(Unit) }
                .collect { }
        }
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { loadingCollectorStarted.await() }
        }

        viewModel.checkForUpdates()

        withContext(Dispatchers.Default) {
            withTimeout(2_000) { checkStarted.await() }
        }
        assertFalse(viewModel.isLoading.value)

        checkFinished.complete(Unit)
        loadingCollector.cancel()
    }

    private fun createViewModel(scheduler: TrackWorker.Scheduler): FavouritesContainerViewModel {
        val settings = mockk<AppSettings>(relaxed = true) {
            every { activeSourcePresetId } returns -1L
            every { observeChanges() } returns flowOf(null)
        }
        val globalFavoritesState = mockk<GlobalFavoritesState>(relaxed = true) {
            every { selectedGroupTab } returns MutableStateFlow(BrowseGroupTab.All)
            every { selectedSourceTags } returns MutableStateFlow(emptySet())
            every { appliedFilter } returns MutableStateFlow(emptySet())
        }
        val favouritesRepository = mockk<FavouritesRepository>(relaxed = true) {
            every { observeCategories() } returns flowOf(emptyList())
            every { observeCategoriesForLibrary() } returns flowOf(emptyList<FavouriteCategory>())
        }
        val snapshotStore = mockk<FavouriteLibrarySnapshotStore> {
            every { observe() } returns flowOf(FavouriteLibrarySnapshot.Empty)
        }
        val spaceBrowseScope = mockk<SpaceBrowseScope> {
            every { createBinding(any()) } returns SpaceBrowseBinding(
                mutableSpaceId = MutableStateFlow(null),
                groupTab = MutableStateFlow(null),
            )
        }

        return FavouritesContainerViewModel(
            appContext = mockk(relaxed = true),
            settings = settings,
            favouritesRepository = favouritesRepository,
            sourcesRepository = mockk(relaxed = true),
            mangaRepositoryFactory = mockk<ContentRepository.Factory>(relaxed = true),
            mangaDataRepository = mockk<ContentDataRepository>(relaxed = true),
            localStorageChanges = MutableSharedFlow<LocalContent?>(),
            networkState = mockk<NetworkState> { every { value } returns true },
            globalFavoritesState = globalFavoritesState,
            sourceGroupManager = mockk<SourceGroupManager>(relaxed = true),
            spaceBrowseScope = spaceBrowseScope,
            db = mockk<MangaDatabase>(relaxed = true),
            favouriteLibrarySnapshotStore = snapshotStore,
            spaceContentPolicy = mockk<SpaceContentPolicy>(relaxed = true),
            sourcePresetsRepository = mockk<SourcePresetsRepository>(relaxed = true),
            cardMapper = mockk<FavouritesCardMapper>(relaxed = true),
            contentResolver = mockk<FavouriteContentResolver>(relaxed = true),
            quickFilterFactory = mockk<FavoritesListQuickFilter.Factory>(relaxed = true),
            markAsReadUseCase = mockk<MarkAsReadUseCase>(relaxed = true),
            trackingRepository = mockk<TrackingRepository>(relaxed = true),
            trackWorkerScheduler = scheduler,
        )
    }
}
