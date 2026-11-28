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

    private val initialSpace = SpaceId(localDataSource.readActiveSpaceId()).takeIf { candidate ->
        // 已移除的内置空间（小说/动画）不能再作为起始空间，否则首帧会落在空空间上。
        candidate.value !in REMOVED_BUILT_IN_SPACE_IDS &&
            (candidate.value.startsWith("builtin:") || candidate.value.startsWith("custom:"))
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
        // 旧路由、旧备份里仍可能带着已移除的小说/动画空间，落到这里就回退到漫画空间，
        // 让一次还原或一次深链跳转不至于把整个页面崩掉。
        val target = if (spaceId.value in REMOVED_BUILT_IN_SPACE_IDS) BuiltInSpaces.Manga else spaceId
        if (catalogRepository.find(target) == null) {
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
        if (target == activeSpace.value) return
        val previous = activeSpace.value
        localDataSource.writeActiveSpaceId(target.value)
        mutableActiveSpace.value = target
        diagnostics.record(
            SpaceDiagnosticEvent(
                stage = SpaceDiagnosticStage.ACTIVATED,
                activeSpaceId = previous.value,
                targetSpaceId = target.value,
            ),
        )
    }

    private companion object {
        /** 已从本构建移除的内置空间：小说与动画。 */
        val REMOVED_BUILT_IN_SPACE_IDS = setOf(BuiltInSpaces.Novel.value, BuiltInSpaces.Anime.value)
    }
}
