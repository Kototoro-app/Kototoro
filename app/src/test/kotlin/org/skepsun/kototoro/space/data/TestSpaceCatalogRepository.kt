package org.skepsun.kototoro.space.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId

internal class TestSpaceCatalogRepository(
    initial: List<SpaceContext> = BuiltInSpaces.contexts,
) : SpaceCatalogRepository {
    private val state = MutableStateFlow(initial)
    override val spaces: StateFlow<List<SpaceContext>> = state
    override val allSpaces: StateFlow<List<SpaceContext>> = state

    override suspend fun create(
        title: String,
        contentTypes: Set<ContentType>,
        sourceLanguages: Set<String>,
        sourceKinds: Set<SourceType>,
    ): SpaceContext = error("Not used")

    override suspend fun update(space: SpaceContext) = Unit
    override suspend fun delete(spaceId: SpaceId) = Unit
}
