package org.skepsun.kototoro.space.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.domain.SpaceSwitchResult
import org.skepsun.kototoro.space.domain.SpaceSwitchState

class DefaultSpaceSwitchCoordinatorTest {

	@Test
	fun `save and switch flushes before activating target`() = runTest {
		val events = mutableListOf<String>()
		val repository = RecordingSpaceRepository(events)
		val coordinator = DefaultSpaceSwitchCoordinator(repository)

		val result = coordinator.requestSwitch(
			target = BuiltInSpaces.Novel,
			origin = SpaceSwitchOrigin.READER,
			availability = SpaceSwitchAvailability.SAVE_AND_SWITCH,
			progressFlusher = SpaceProgressFlusher { events += "flush" },
		)

		result shouldBe SpaceSwitchResult.Success(BuiltInSpaces.Novel)
		events shouldBe listOf("flush", "activate:builtin:novel")
		coordinator.state.value shouldBe SpaceSwitchState()
	}

	@Test
	fun `flush failure keeps active space unchanged`() = runTest {
		val repository = RecordingSpaceRepository()
		val coordinator = DefaultSpaceSwitchCoordinator(repository)
		val failure = IllegalStateException("save failed")

		val result = coordinator.requestSwitch(
			target = BuiltInSpaces.Anime,
			origin = SpaceSwitchOrigin.NOVEL_READER,
			availability = SpaceSwitchAvailability.SAVE_AND_SWITCH,
			progressFlusher = SpaceProgressFlusher { throw failure },
		)

		result shouldBe SpaceSwitchResult.Failed(failure)
		repository.activeSpace.value shouldBe BuiltInSpaces.Manga
		repository.activations shouldBe emptyList()
	}

	@Test
	fun `unavailable surface neither flushes nor activates`() = runTest {
		val repository = RecordingSpaceRepository()
		val coordinator = DefaultSpaceSwitchCoordinator(repository)
		var flushed = false

		val result = coordinator.requestSwitch(
			target = BuiltInSpaces.Anime,
			origin = SpaceSwitchOrigin.VIDEO_PLAYER,
			availability = SpaceSwitchAvailability.UNAVAILABLE,
			progressFlusher = SpaceProgressFlusher { flushed = true },
		)

		result shouldBe SpaceSwitchResult.Unavailable
		flushed shouldBe false
		repository.activations shouldBe emptyList()
	}

	private class RecordingSpaceRepository(
		private val events: MutableList<String> = mutableListOf(),
	) : SpaceRepository {
		override val activeSpace = MutableStateFlow<SpaceId>(BuiltInSpaces.Manga)
		val activations = mutableListOf<SpaceId>()

		override suspend fun activate(spaceId: SpaceId) {
			events += "activate:${spaceId.value}"
			activations += spaceId
			activeSpace.value = spaceId
		}
	}
}
