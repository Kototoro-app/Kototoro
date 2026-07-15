package org.skepsun.kototoro.space.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlags
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceViewModelTest {

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(dispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `switcher opens and selection activates target space`() = runTest {
		val repository = FakeSpaceRepository()
		val flags = FakeFeatureFlagsRepository(enabledFlags())
		val viewModel = SpaceViewModel(repository, TestSpaceCatalogRepository(), flags)
		val collection = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

		viewModel.onAction(SpaceAction.OpenSwitcher)
		assertTrue(viewModel.uiState.value.switcherVisible)

		viewModel.onAction(SpaceAction.SelectSpace(BuiltInSpaces.Novel))
		advanceUntilIdle()

		assertEquals(BuiltInSpaces.Novel, viewModel.uiState.value.activeSpaceId)
		assertFalse(viewModel.uiState.value.switcherVisible)
		assertEquals(listOf(BuiltInSpaces.Novel), repository.activations)
		collection.cancel()
	}

	@Test
	fun `parent feature gate closes and blocks switcher`() = runTest {
		val repository = FakeSpaceRepository()
		val flags = FakeFeatureFlagsRepository(enabledFlags().copy(entitySpaceEnabled = false))
		val viewModel = SpaceViewModel(repository, TestSpaceCatalogRepository(), flags)
		val collection = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

		viewModel.onAction(SpaceAction.OpenSwitcher)
		viewModel.onAction(SpaceAction.SelectSpace(BuiltInSpaces.Anime))
		advanceUntilIdle()

		assertFalse(viewModel.uiState.value.switcherEnabled)
		assertFalse(viewModel.uiState.value.switcherVisible)
		assertTrue(repository.activations.isEmpty())
		collection.cancel()
	}

	@Test
	fun `awaited selection returns only after target space is active`() = runTest {
		val repository = FakeSpaceRepository()
		val viewModel = SpaceViewModel(
			repository,
			TestSpaceCatalogRepository(),
			FakeFeatureFlagsRepository(enabledFlags()),
		)
		val collection = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

		assertTrue(viewModel.selectSpaceAndAwait(BuiltInSpaces.Anime))

		assertEquals(BuiltInSpaces.Anime, repository.activeSpace.value)
		assertEquals(BuiltInSpaces.Anime, viewModel.uiState.value.activeSpaceId)
		assertFalse(viewModel.uiState.value.switchInProgress)
		collection.cancel()
	}

	@Test
	fun `persistent navigation requires its own gate and effective switcher`() = runTest {
		val repository = FakeSpaceRepository()
		val flags = FakeFeatureFlagsRepository(
			enabledFlags().copy(spacePersistentNavigationEnabled = true),
		)
		val viewModel = SpaceViewModel(repository, TestSpaceCatalogRepository(), flags)
		val collection = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

		assertTrue(viewModel.uiState.value.persistentNavigationEnabled)

		flags.flags.value = flags.flags.value.copy(spaceSwitcherEnabled = false)
		advanceUntilIdle()

		assertFalse(viewModel.uiState.value.persistentNavigationEnabled)
		collection.cancel()
	}

	private fun enabledFlags() = SpaceFeatureFlags(
		entitySpaceEnabled = true,
		spaceSwitcherEnabled = true,
		spacePersistentNavigationEnabled = false,
		spaceImmersiveSwitchEnabled = false,
		spaceRoutePreferencesEnabled = false,
	)

	private class FakeSpaceRepository : SpaceRepository {
		private val active = MutableStateFlow(BuiltInSpaces.Manga)
		override val activeSpace: StateFlow<SpaceId> = active
		val activations = mutableListOf<SpaceId>()

		override suspend fun activate(spaceId: SpaceId) {
			activations += spaceId
			active.value = spaceId
		}
	}

	private class FakeFeatureFlagsRepository(initial: SpaceFeatureFlags) : SpaceFeatureFlagsRepository {
		override val flags = MutableStateFlow(initial)
	}
}
