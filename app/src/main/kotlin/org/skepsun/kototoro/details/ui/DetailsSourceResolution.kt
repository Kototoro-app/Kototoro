package org.skepsun.kototoro.details.ui

import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.parsers.model.ContentSource

internal inline fun selectResolvedDetailsSource(
    original: ContentSource,
    enabledSource: ContentSource?,
    resolvePipeline: () -> ContentSource,
): ContentSource {
    enabledSource?.let { return it }
    return resolvePipeline().takeIf {
        it.resolvedContentTypeForSnapshot() != null || it.locale.isNotBlank()
    } ?: original
}

internal inline fun selectResolvedDetailsSource(
    original: ContentSource,
    enabledSources: List<ContentSourceInfo>,
    resolvePipeline: () -> ContentSource,
): ContentSource {
    val enabled = enabledSources.firstOrNull { it.mangaSource.name == original.name }?.mangaSource
    return selectResolvedDetailsSource(
        original = original,
        enabledSource = enabled,
        resolvePipeline = resolvePipeline,
    )
}

internal fun selectResolvedDetailsSource(
    original: ContentSource,
    enabledSources: List<ContentSourceInfo>,
    pipelineResolved: ContentSource,
): ContentSource = selectResolvedDetailsSource(
    original = original,
    enabledSources = enabledSources,
    resolvePipeline = { pipelineResolved },
)

/**
 * Symmetric structural equality for [ContentSource] instances.
 *
 * Avoids two equality pitfalls in source models:
 * - `AnonymousContentSource.equals` compares only `name` against any `ContentSource`, which would
 *   make an unresolved shell appear equal to a newly-resolved live source and suppress updates.
 * - `JsonContentSource.equals` compares only `entity.id`, which would ignore metadata/enabled updates.
 */
internal fun ContentSource?.isStructurallySameSource(other: ContentSource?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    if (this::class.java != other::class.java) return false
    if (name != other.name || locale != other.locale || contentType != other.contentType) return false
    if (this is JsonContentSource && other is JsonContentSource) {
        return entity == other.entity
    }
    if (this is ContentSourceInfo && other is ContentSourceInfo) {
        return isEnabled == other.isEnabled &&
            isPinned == other.isPinned &&
            availability == other.availability &&
            mangaSource.isStructurallySameSource(other.mangaSource)
    }
    return this == other
}

internal fun ContentSource?.structuralSourceHashCode(): Int {
    if (this == null) return 0
    var result = this::class.java.hashCode()
    result = 31 * result + name.hashCode()
    result = 31 * result + locale.hashCode()
    result = 31 * result + contentType.hashCode()
    result = when (this) {
        is JsonContentSource -> 31 * result + entity.hashCode()
        is ContentSourceInfo -> {
            var infoResult = 31 * result + isEnabled.hashCode()
            infoResult = 31 * infoResult + isPinned.hashCode()
            infoResult = 31 * infoResult + availability.hashCode()
            31 * infoResult + mangaSource.structuralSourceHashCode()
        }
        else -> 31 * result + hashCode()
    }
    return result
}

internal fun areSourceInfoListsEquivalent(
    old: List<ContentSourceInfo>,
    new: List<ContentSourceInfo>,
): Boolean {
    if (old === new) return true
    if (old.size != new.size) return false
    for (i in old.indices) {
        if (!old[i].isStructurallySameSource(new[i])) {
            return false
        }
    }
    return true
}
