package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlags
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceSessionRepository
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionValidator

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceNavigationSessionViewModelTest {

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() = Dispatchers.setMain(dispatcher)

	@AfterEach
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `enabled gate loads built in sessions and saves updates`() = runTest {
		val initial = snapshot(BuiltInSpaces.Novel, "history")
		val repository = FakeSessionRepository(mapOf(BuiltInSpaces.Novel to initial))
		val flags = FakeSessionFlagsRepository(enabled = true)
		val viewModel = SpaceNavigationSessionViewModel(repository, PassThroughValidator, flags)

		advanceUntilIdle()

		viewModel.uiState.value.restorationReady shouldBe true
		viewModel.uiState.value.sessions shouldBe mapOf(BuiltInSpaces.Novel to initial)

		val updated = snapshot(BuiltInSpaces.Novel, "favorites")
		viewModel.save(updated)
		advanceUntilIdle()

		repository.saved shouldBe listOf(updated)
		viewModel.uiState.value.sessions[BuiltInSpaces.Novel] shouldBe updated
	}

	@Test
	fun `disabled gate neither loads nor saves`() = runTest {
		val repository = FakeSessionRepository()
		val viewModel = SpaceNavigationSessionViewModel(
			repository,
			PassThroughValidator,
			FakeSessionFlagsRepository(enabled = false),
		)

		advanceUntilIdle()
		viewModel.save(snapshot(BuiltInSpaces.Manga, "home"))
		advanceUntilIdle()

		repository.loads shouldBe emptyList()
		repository.saved shouldBe emptyList()
	}

	private fun snapshot(spaceId: SpaceId, selected: String) = SpaceSessionSnapshot(
		spaceId = spaceId,
		selectedTopLevel = selected,
		resumeRoute = null,
		stacks = emptyMap(),
		lastAccessed = 1L,
		updatedAt = 1L,
	)
}

private object PassThroughValidator : SpaceSessionValidator {
	override suspend fun validate(snapshot: SpaceSessionSnapshot): SpaceSessionSnapshot = snapshot
}

private class FakeSessionRepository(
	private val stored: Map<SpaceId, SpaceSessionSnapshot> = emptyMap(),
) : SpaceSessionRepository {
	val loads = mutableListOf<SpaceId>()
	val saved = mutableListOf<SpaceSessionSnapshot>()

	override suspend fun load(spaceId: SpaceId): SpaceSessionSnapshot? {
		loads += spaceId
		return stored[spaceId]
	}

	override suspend fun save(snapshot: SpaceSessionSnapshot) {
		saved += snapshot
	}

	override suspend fun delete(spaceId: SpaceId) = Unit
}

private class FakeSessionFlagsRepository(enabled: Boolean) : SpaceFeatureFlagsRepository {
	override val flags = MutableStateFlow(
		SpaceFeatureFlags(
			entitySpaceEnabled = true,
			spaceSwitcherEnabled = true,
			spacePersistentNavigationEnabled = enabled,
			spaceImmersiveSwitchEnabled = false,
			spaceRoutePreferencesEnabled = false,
		),
	)
}
