package org.skepsun.kototoro.space.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.ContentScopePolicy
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceKind

/**
 * 真实构建只剩一个内置漫画空间，但“多空间隔离”这类语义仍需用例覆盖，
 * 因此用第二个自定义漫画空间来表达：它和内置空间一样只收漫画内容。
 */
internal val SecondTestSpace = SpaceContext(
    id = SpaceId("custom:second-manga"),
    kind = SpaceKind.MANGA,
    allowedContentTypes = ContentScopePolicy.spaceTypes,
    title = "Second manga space",
    isBuiltIn = false,
    sortKey = 1,
)

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
