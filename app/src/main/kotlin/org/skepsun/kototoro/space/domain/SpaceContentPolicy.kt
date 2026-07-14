package org.skepsun.kototoro.space.domain

import org.skepsun.kototoro.parsers.model.ContentType

interface SpaceContentPolicy {

    fun allowedTypes(spaceId: SpaceId): Set<ContentType>

    fun spaceFor(contentType: ContentType?): SpaceId?

    fun accepts(spaceId: SpaceId, contentType: ContentType?): Boolean
}

class DefaultSpaceContentPolicy : SpaceContentPolicy {

    override fun allowedTypes(spaceId: SpaceId): Set<ContentType> {
        return BuiltInSpaces.contexts.firstOrNull { it.id == spaceId }?.allowedContentTypes.orEmpty()
    }

    override fun spaceFor(contentType: ContentType?): SpaceId? {
        if (contentType == null || contentType == ContentType.OTHER) {
            return null
        }
        return BuiltInSpaces.contexts.firstOrNull { contentType in it.allowedContentTypes }?.id
    }

    override fun accepts(spaceId: SpaceId, contentType: ContentType?): Boolean {
        return contentType != null && contentType in allowedTypes(spaceId)
    }
}
