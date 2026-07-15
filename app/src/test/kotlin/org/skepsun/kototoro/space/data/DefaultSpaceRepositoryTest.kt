package org.skepsun.kototoro.space.data

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlags
import org.skepsun.kototoro.space.domain.SpaceId

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSpaceRepositoryTest {

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() = Dispatchers.setMain(dispatcher)

	@AfterEach
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `invalid persisted id falls back to manga and repairs preference`() {
		val store = FakeSpaceLocalDataSource("invalid")
		val diagnostics = mockk<SpaceDiagnostics>(relaxed = true)

		val repository = DefaultSpaceRepository(store, diagnostics, TestSpaceCatalogRepository())

		assertEquals(BuiltInSpaces.Manga, repository.activeSpace.value)
		assertEquals(listOf(BuiltInSpaces.Manga.value), store.writes)
		verify {
			diagnostics.record(match { event ->
				event.stage == SpaceDiagnosticStage.INITIALIZED &&
					event.activeSpaceId == BuiltInSpaces.Manga.value
			})
		}
	}

	@Test
	fun `activate persists and publishes built in space once`() = runTest {
		val store = FakeSpaceLocalDataSource(BuiltInSpaces.Manga.value)
		val diagnostics = mockk<SpaceDiagnostics>(relaxed = true)
		val repository = DefaultSpaceRepository(store, diagnostics, TestSpaceCatalogRepository())

		repository.activate(BuiltInSpaces.Novel)
		repository.activate(BuiltInSpaces.Novel)

		assertEquals(BuiltInSpaces.Novel, repository.activeSpace.value)
		assertEquals(listOf(BuiltInSpaces.Novel.value), store.writes)
		verify(exactly = 1) {
			diagnostics.record(match { it.stage == SpaceDiagnosticStage.ACTIVATED })
		}
	}

	@Test
	fun `activate rejects unknown space without changing preference`() {
		val store = FakeSpaceLocalDataSource(BuiltInSpaces.Manga.value)
		val diagnostics = mockk<SpaceDiagnostics>(relaxed = true)
		val repository = DefaultSpaceRepository(store, diagnostics, TestSpaceCatalogRepository())

		assertThrows(IllegalArgumentException::class.java) {
			runTest { repository.activate(SpaceId("custom:unknown")) }
		}
		assertEquals(BuiltInSpaces.Manga, repository.activeSpace.value)
		assertTrue(store.writes.isEmpty())
		verify {
			diagnostics.record(match { it.stage == SpaceDiagnosticStage.REJECTED })
		}
	}

	@Test
	fun `feature gates cannot bypass entity space parent gate`() {
		val disabled = SpaceFeatureFlags(
			entitySpaceEnabled = false,
			spaceSwitcherEnabled = true,
			spacePersistentNavigationEnabled = true,
			spaceImmersiveSwitchEnabled = true,
			spaceRoutePreferencesEnabled = true,
		)
		assertFalse(disabled.effectiveSwitcherEnabled)
		assertFalse(disabled.effectivePersistentNavigationEnabled)
		assertFalse(disabled.effectiveImmersiveSwitchEnabled)
		assertFalse(disabled.effectiveRoutePreferencesEnabled)

		val enabled = disabled.copy(entitySpaceEnabled = true)
		assertTrue(enabled.effectiveSwitcherEnabled)
		assertTrue(enabled.effectivePersistentNavigationEnabled)
		assertTrue(enabled.effectiveImmersiveSwitchEnabled)
		assertTrue(enabled.effectiveRoutePreferencesEnabled)
	}

	private class FakeSpaceLocalDataSource(
		private var value: String,
	) : SpaceLocalDataSource {
		val writes = mutableListOf<String>()

		override fun readActiveSpaceId(): String = value

		override fun writeActiveSpaceId(value: String) {
			this.value = value
			writes += value
		}
	}
}
