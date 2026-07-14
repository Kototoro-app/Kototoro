package org.skepsun.kototoro.space.domain

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ActiveSpaceScopeTest {

	@Test
	fun `scope is absent while switcher is disabled`() = runTest {
		val spaces = FakeSpaceRepository(BuiltInSpaces.Novel)
		val flags = FakeSpaceFeatureFlagsRepository(switcherEnabled = false)

		spaces.observeActiveSpaceScope(flags).first() shouldBe null
	}

	@Test
	fun `scope follows active space while switcher is enabled`() = runTest {
		val spaces = FakeSpaceRepository(BuiltInSpaces.Manga)
		val flags = FakeSpaceFeatureFlagsRepository(switcherEnabled = true)
		val scope = spaces.observeActiveSpaceScope(flags)

		scope.first() shouldBe BuiltInSpaces.Manga
		spaces.activate(BuiltInSpaces.Anime)
		scope.first() shouldBe BuiltInSpaces.Anime
	}

	@Test
	fun `enabling switcher exposes the current active space`() = runTest {
		val spaces = FakeSpaceRepository(BuiltInSpaces.Novel)
		val flags = FakeSpaceFeatureFlagsRepository(switcherEnabled = false)
		val scope = spaces.observeActiveSpaceScope(flags)

		scope.first() shouldBe null
		flags.setSwitcherEnabled(true)
		scope.first() shouldBe BuiltInSpaces.Novel
	}
}

private class FakeSpaceRepository(initialSpace: SpaceId) : SpaceRepository {
	override val activeSpace = MutableStateFlow(initialSpace)

	override suspend fun activate(spaceId: SpaceId) {
		activeSpace.value = spaceId
	}
}

private class FakeSpaceFeatureFlagsRepository(switcherEnabled: Boolean) : SpaceFeatureFlagsRepository {
	override val flags = MutableStateFlow(createFlags(switcherEnabled))

	fun setSwitcherEnabled(enabled: Boolean) {
		flags.value = createFlags(enabled)
	}

	private fun createFlags(switcherEnabled: Boolean) = SpaceFeatureFlags(
		entitySpaceEnabled = true,
		spaceSwitcherEnabled = switcherEnabled,
		spacePersistentNavigationEnabled = false,
		spaceImmersiveSwitchEnabled = false,
		spaceRoutePreferencesEnabled = false,
	)
}
