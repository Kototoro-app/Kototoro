package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository

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
		val viewModel = SpaceNavigationSessionViewModel(
			repository,
			PassThroughValidator,
			TestSpaceCatalogRepository(),
			flags,
		)

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
			TestSpaceCatalogRepository(),
			FakeSessionFlagsRepository(enabled = false),
		)

		advanceUntilIdle()
		viewModel.save(snapshot(BuiltInSpaces.Manga, "home"))
		advanceUntilIdle()

		repository.loads shouldBe emptyList()
		repository.saved shouldBe emptyList()
	}

	@Test
	fun `latest snapshot wins when an older save is still running`() = runTest {
		val firstSaveStarted = CompletableDeferred<Unit>()
		val releaseFirstSave = CompletableDeferred<Unit>()
		val repository = FakeSessionRepository(
			onSave = { savedCount ->
				if (savedCount == 1) {
					firstSaveStarted.complete(Unit)
					releaseFirstSave.await()
				}
			},
		)
		val viewModel = SpaceNavigationSessionViewModel(
			repository,
			PassThroughValidator,
			TestSpaceCatalogRepository(),
			FakeSessionFlagsRepository(enabled = true),
		)
		advanceUntilIdle()

		val older = snapshot(BuiltInSpaces.Manga, "explore")
		val latest = snapshot(BuiltInSpaces.Manga, "home")
		viewModel.save(older)
		firstSaveStarted.await()
		viewModel.save(latest)
		releaseFirstSave.complete(Unit)
		advanceUntilIdle()

		repository.saved shouldBe listOf(older, latest)
		viewModel.uiState.value.sessions[BuiltInSpaces.Manga] shouldBe latest
	}

	@Test
	fun `unchanged navigation snapshot is not saved again when only timestamps differ`() = runTest {
		val initial = snapshot(BuiltInSpaces.Manga, "home")
		val repository = FakeSessionRepository(mapOf(BuiltInSpaces.Manga to initial))
		val viewModel = SpaceNavigationSessionViewModel(
			repository,
			PassThroughValidator,
			TestSpaceCatalogRepository(),
			FakeSessionFlagsRepository(enabled = true),
		)
		advanceUntilIdle()

		viewModel.save(initial.copy(lastAccessed = 2L, updatedAt = 2L))
		advanceUntilIdle()

		repository.saved shouldBe emptyList()
	}

	@Test
	fun `unchanged navigation snapshot is coalesced while the first save is running`() = runTest {
		val firstSaveStarted = CompletableDeferred<Unit>()
		val releaseFirstSave = CompletableDeferred<Unit>()
		val repository = FakeSessionRepository(
			onSave = {
				firstSaveStarted.complete(Unit)
				releaseFirstSave.await()
			},
		)
		val viewModel = SpaceNavigationSessionViewModel(
			repository,
			PassThroughValidator,
			TestSpaceCatalogRepository(),
			FakeSessionFlagsRepository(enabled = true),
		)
		advanceUntilIdle()
		val snapshot = snapshot(BuiltInSpaces.Manga, "home")

		viewModel.save(snapshot)
		firstSaveStarted.await()
		viewModel.save(snapshot.copy(lastAccessed = 2L, updatedAt = 2L))
		releaseFirstSave.complete(Unit)
		advanceUntilIdle()

		repository.saved shouldBe listOf(snapshot)
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
	private val onSave: suspend (savedCount: Int) -> Unit = {},
) : SpaceSessionRepository {
	val loads = mutableListOf<SpaceId>()
	val saved = mutableListOf<SpaceSessionSnapshot>()

	override suspend fun load(spaceId: SpaceId): SpaceSessionSnapshot? {
		loads += spaceId
		return stored[spaceId]
	}

	override suspend fun save(snapshot: SpaceSessionSnapshot) {
		saved += snapshot
		onSave(saved.size)
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
