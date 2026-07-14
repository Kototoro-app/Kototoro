package org.skepsun.kototoro.space.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceRepository @Inject constructor(
	private val localDataSource: SpaceLocalDataSource,
	private val diagnostics: SpaceDiagnostics,
) : SpaceRepository {

	private val builtInIds = BuiltInSpaces.contexts.mapTo(LinkedHashSet()) { it.id }
	private val initialSpace = SpaceId(localDataSource.readActiveSpaceId()).takeIf { it in builtInIds }
		?: BuiltInSpaces.Manga
	private val mutableActiveSpace = MutableStateFlow(initialSpace)

	override val activeSpace: StateFlow<SpaceId> = mutableActiveSpace.asStateFlow()

	init {
		if (localDataSource.readActiveSpaceId() != initialSpace.value) {
			localDataSource.writeActiveSpaceId(initialSpace.value)
		}
		diagnostics.record(
			SpaceDiagnosticEvent(
				stage = SpaceDiagnosticStage.INITIALIZED,
				activeSpaceId = initialSpace.value,
			),
		)
	}

	override suspend fun activate(spaceId: SpaceId) {
		if (spaceId !in builtInIds) {
			diagnostics.record(
				SpaceDiagnosticEvent(
					stage = SpaceDiagnosticStage.REJECTED,
					activeSpaceId = activeSpace.value.value,
					targetSpaceId = spaceId.value,
					reason = "unknown_space",
				),
			)
			throw IllegalArgumentException("Unknown built-in SpaceId: ${spaceId.value}")
		}
		if (spaceId == activeSpace.value) return
		val previous = activeSpace.value
		localDataSource.writeActiveSpaceId(spaceId.value)
		mutableActiveSpace.value = spaceId
		diagnostics.record(
			SpaceDiagnosticEvent(
				stage = SpaceDiagnosticStage.ACTIVATED,
				activeSpaceId = previous.value,
				targetSpaceId = spaceId.value,
			),
		)
	}
}
