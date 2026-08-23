package org.skepsun.kototoro.settings.sources.unified

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.extensions.install.ExtensionInstallDownloadState
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.recovery.RecoveryActionCoordinator
import org.skepsun.kototoro.extensions.recovery.SourceRecoveryStatus
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSourcesRecoveryViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val coordinator = mockk<RecoveryActionCoordinator>(relaxed = true)
    private val coordinatorSnapshot = MutableStateFlow(RecoveryUiState())

    // Mirrors of the viewModel's stateIn-flows, kept hot by a persistent collector below.
    private val latestUi = MutableStateFlow<UnifiedSourcesUiState>(UnifiedSourcesUiState.Loading)
    private val latestRecovery = MutableStateFlow(RecoveryUiState())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RecoveryBadgeProvider.updateMissingCount(0)
        every { coordinator.snapshot } returns coordinatorSnapshot
        coEvery { coordinator.refresh() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggling the recovery filter narrows visible sources to missing ones`() = runTest {
        val viewModel = buildViewModel()
        observeState(viewModel)
        coordinatorSnapshot.value = RecoveryUiState(
            missingCount = 1,
            total = 2,
            perSource = mapOf(
                "MIHON_1" to SourceRecoveryStatus.MISSING,
                "MIHON_2" to SourceRecoveryStatus.RESOLVED,
            ),
        )

        val before = awaitReady { it.sources.size == 2 }
        assertEquals(2, before.sources.size)
        assertEquals(2, before.allSources.size)

        viewModel.toggleRecoveryFilter()

        val after = awaitReady { it.sources.size == 1 }
        assertEquals(listOf("MIHON_1"), after.sources.map { it.id })
        awaitTrue("recovery filter active") { latestRecovery.value.recoveryFilterActive }

        viewModel.toggleRecoveryFilter()
        assertEquals(2, awaitReady { it.sources.size == 2 }.sources.size)
    }

    @Test
    fun `deep link with the recovery tab enables the filter and applies package query`() = runTest {
        val viewModel = buildViewModel()
        observeState(viewModel)

        viewModel.applyDeepLink(
            UnifiedSourcesDeepLink(
                initialTab = "recovery",
                packageFilter = "com.example",
                sourceKey = null,
            ),
        )

        val ready = awaitReady { it.filters.query == "com.example" }
        assertEquals("com.example", ready.filters.query)
        awaitTrue("recovery filter active") { latestRecovery.value.recoveryFilterActive }
    }

    @Test
    fun `deep link source key is highlighted and drives the recovery action`() = runTest {
        val received = mutableListOf<String>()
        coEvery { coordinator.run(any()) } coAnswers {
            val key: String = firstArg()
            received.add(key)
            RecoveryActionResult(key, ok = true, message = null)
        }
        val viewModel = buildViewModel()
        observeState(viewModel)

        viewModel.applyDeepLink(UnifiedSourcesDeepLink(initialTab = null, packageFilter = null, sourceKey = "MIHON_1"))
        assertEquals("MIHON_1", viewModel.highlightedSourceKey.value)

        viewModel.runHighlightedRecoveryAction()

        val deadline = System.currentTimeMillis() + 3_000
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        assertEquals(listOf("MIHON_1"), received)
    }

    @Test
    fun `saved state restores the deep link across process recreation`() = runTest {
        val handle = SavedStateHandle(
            mapOf(
                "dl_initialTab" to "recovery",
                "dl_package" to "com.example",
                "dl_sourceKey" to "MIHON_1",
            ),
        )
        val viewModel = buildViewModel(handle)
        observeState(viewModel)

        val ready = awaitReady { it.filters.query == "com.example" }
        assertEquals("com.example", ready.filters.query)
        assertEquals("MIHON_1", viewModel.highlightedSourceKey.value)
        awaitTrue("recovery filter active") { latestRecovery.value.recoveryFilterActive }
    }

    @Test
    fun `badge provider is refreshed from the coordinator snapshot`() = runTest {
        buildViewModel().also { observeState(it) }
        coordinatorSnapshot.update { it.copy(missingCount = 2, total = 5) }

        awaitTrue("badge count") { RecoveryBadgeProvider.count() == 2 }
    }

    @Test
    fun `migrateAffected delegates to the pure preselection`() = runTest {
        val viewModel = buildViewModel()
        val works = mapOf(
            "TSUNDOKU_9001" to listOf(11L, 12L, 11L),
            "MIHON_1" to listOf(42L),
        )

        assertEquals(listOf(11L, 12L), viewModel.migrateAffected("TSUNDOKU_9001", works))
        assertTrue(viewModel.migrateAffected("MIHON_absent", works).isEmpty())
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    private suspend fun awaitReady(
        predicate: (UnifiedSourcesUiState.Ready) -> Boolean,
    ): UnifiedSourcesUiState.Ready {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val state = latestUi.value
            if (state is UnifiedSourcesUiState.Ready && predicate(state)) {
                return state
            }
            delay(10)
        }
        error("timeout waiting for a Ready uiState matching the predicate")
    }

    private suspend fun awaitTrue(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return
            }
            delay(10)
        }
        error("timeout waiting for $description")
    }

    private fun buildViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
    ): UnifiedSourcesViewModel {
        val catalogState = UnifiedSourceCatalogState(
            repositories = emptyList(),
            packages = emptyList(),
            sources = listOf(
                sourceItem("MIHON_1"),
                sourceItem("MIHON_2"),
            ),
        )
        val catalogRepository = mockk<UnifiedSourceCatalogRepository>(relaxed = true)
        every { catalogRepository.observeState() } returns flowOf(catalogState)
        val downloadStates = MutableStateFlow<Map<String, ExtensionInstallDownloadState>>(emptyMap())
        val installService = mockk<ExtensionInstallService>(relaxed = true)
        every { installService.downloadStates } returns downloadStates

        val viewModel = UnifiedSourcesViewModel(
            appContext = mockk(relaxed = true),
            catalogRepository = catalogRepository,
            contentSourcesRepository = mockk<ContentSourcesRepository>(relaxed = true),
            sourceAvailabilityRepository = mockk<SourceAvailabilityRepository>(relaxed = true),
            contentRepositoryFactory = mockk<ContentRepository.Factory>(relaxed = true),
            jsonSourceManager = mockk<JsonSourceManager>(relaxed = true),
            legadoHttpClient = mockk<LegadoHttpClient>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            extensionRepoRepository = mockk<ExternalExtensionRepoRepository>(relaxed = true),
            installService = installService,
            signatureValidator = mockk<InstalledExtensionSignatureValidator>(relaxed = true),
            settings = mockk<AppSettings>(relaxed = true),
            mihonExtensionManager = mockk<MihonExtensionManager>(relaxed = true),
            aniyomiExtensionManager = mockk<AniyomiExtensionManager>(relaxed = true),
            ireaderExtensionManager = mockk<IReaderExtensionManager>(relaxed = true),
            cloudstreamRuntimeManager = mockk<CloudstreamRuntimeManager>(relaxed = true),
            recoveryCoordinator = coordinator,
            savedStateHandle = handle,
        )
        return viewModel
    }

    /** Keeps the stateIn-flows hot so `.value` reflects post-change recomputations. */
    private fun TestScope.observeState(viewModel: UnifiedSourcesViewModel) {
        backgroundScope.launch {
            viewModel.uiState.collect { latestUi.value = it }
        }
        backgroundScope.launch {
            viewModel.recoveryState.collect { latestRecovery.value = it }
        }
    }
}

private fun sourceItem(id: String): UnifiedSourceItem {
    return UnifiedSourceItem(
        id = id,
        kind = UnifiedSourceKind.MIHON,
        source = mockk<ContentSource>(relaxed = true),
        title = "Source $id",
        language = "en",
        contentType = ContentType.MANGA,
        repositoryId = null,
        repositoryName = null,
        packageId = null,
        packageName = null,
        isEnabled = true,
        isPinned = false,
        isAvailable = true,
        isInstalled = false,
        isNsfw = false,
        isBroken = false,
    )
}
