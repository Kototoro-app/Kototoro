package org.skepsun.kototoro.space.domain

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.explore.data.SourceRule
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject

interface SpaceContentPolicy {

    fun allowedTypes(spaceId: SpaceId): Set<ContentType>

    fun spaceFor(contentType: ContentType?): SpaceId?

    fun accepts(spaceId: SpaceId, contentType: ContentType?): Boolean

    fun allowedSourceNames(spaceId: SpaceId): Set<String>?

    fun observeAllowedSourceNames(spaceId: SpaceId): Flow<Set<String>?>
}

@Reusable
class DefaultSpaceContentPolicy @Inject constructor(
    private val catalogRepository: SpaceCatalogRepository,
    private val sourceRuleResolver: SourceRuleResolver,
) : SpaceContentPolicy {

    override fun allowedTypes(spaceId: SpaceId): Set<ContentType> {
        return catalogRepository.find(spaceId)?.allowedContentTypes.orEmpty()
    }

    override fun spaceFor(contentType: ContentType?): SpaceId? {
        if (contentType == null || contentType == ContentType.OTHER) {
            return null
        }
        return catalogRepository.spaces.value.firstOrNull { contentType in it.allowedContentTypes }?.id
    }

    override fun accepts(spaceId: SpaceId, contentType: ContentType?): Boolean {
        return contentType != null && contentType in allowedTypes(spaceId)
    }

    override fun allowedSourceNames(spaceId: SpaceId): Set<String>? {
        val context = catalogRepository.find(spaceId) ?: return emptySet()
        if (context.sourceLanguages.isEmpty() && context.sourceKinds.isEmpty()) return null
        return sourceRuleResolver.resolveCurrentSourceNames(
            SourceRule(
                languages = context.sourceLanguages,
                contentTypes = context.allowedContentTypes,
                sourceTypes = context.sourceKinds,
            ),
        )
    }

    override fun observeAllowedSourceNames(spaceId: SpaceId): Flow<Set<String>?> {
        return catalogRepository.spaces.flatMapLatest { spaces ->
            val context = spaces.firstOrNull { it.id == spaceId } ?: return@flatMapLatest flowOf(emptySet())
            if (context.sourceLanguages.isEmpty() && context.sourceKinds.isEmpty()) {
                flowOf(null)
            } else {
                sourceRuleResolver.observeResolvedSourceNames(
                    SourceRule(
                        languages = context.sourceLanguages,
                        contentTypes = context.allowedContentTypes,
                        sourceTypes = context.sourceKinds,
                    ),
                )
            }
        }.distinctUntilChanged()
    }
}
