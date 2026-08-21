package org.skepsun.kototoro.entitygraph.domain

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource

interface EntityGraphSourceAdapter {

    suspend fun findContentForEntity(
        entity: Entity,
        allowedSourceNames: Set<String> = emptySet(),
        sourceLimit: Int = 8,
        resultLimitPerSource: Int = 5,
    ): List<SourceResult>
}

data class SourceResult(
    val entity: Entity,
    val source: ContentSource,
    val content: Content,
    val confidence: Float,
)
