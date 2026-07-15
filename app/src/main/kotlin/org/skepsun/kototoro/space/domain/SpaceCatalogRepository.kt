package org.skepsun.kototoro.space.domain

import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.parsers.model.ContentType

interface SpaceCatalogRepository {

    val spaces: StateFlow<List<SpaceContext>>

    val allSpaces: StateFlow<List<SpaceContext>>

    fun find(spaceId: SpaceId): SpaceContext? = spaces.value.firstOrNull { it.id == spaceId }

    suspend fun create(
        title: String,
        contentTypes: Set<ContentType>,
        sourceLanguages: Set<String>,
        sourceKinds: Set<SourceType>,
    ): SpaceContext

    suspend fun update(space: SpaceContext)

    suspend fun delete(spaceId: SpaceId)
}

const val MAX_CUSTOM_SPACES = 16
