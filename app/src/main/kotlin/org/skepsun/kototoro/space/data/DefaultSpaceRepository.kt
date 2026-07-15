package org.skepsun.kototoro.space.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSpaceRepository @Inject constructor(
	private val localDataSource: SpaceLocalDataSource,
	private val diagnostics: SpaceDiagnostics,
	private val catalogRepository: SpaceCatalogRepository,
) : SpaceRepository {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val initialSpace = SpaceId(localDataSource.readActiveSpaceId()).takeIf {
		it.value.startsWith("builtin:") || it.value.startsWith("custom:")
	}
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
		scope.launch {
			catalogRepository.spaces.collectLatest { spaces ->
				if (spaces.none { it.id == mutableActiveSpace.value }) {
					localDataSource.writeActiveSpaceId(BuiltInSpaces.Manga.value)
					mutableActiveSpace.value = BuiltInSpaces.Manga
				}
			}
		}
	}

	override suspend fun activate(spaceId: SpaceId) {
		if (catalogRepository.find(spaceId) == null) {
			diagnostics.record(
				SpaceDiagnosticEvent(
					stage = SpaceDiagnosticStage.REJECTED,
					activeSpaceId = activeSpace.value.value,
					targetSpaceId = spaceId.value,
					reason = "unknown_space",
				),
			)
			throw IllegalArgumentException("Unknown SpaceId: ${spaceId.value}")
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
