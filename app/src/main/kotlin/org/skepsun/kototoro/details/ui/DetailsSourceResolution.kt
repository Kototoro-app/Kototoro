package org.skepsun.kototoro.details.ui

import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.parsers.model.ContentSource

internal fun selectResolvedDetailsSource(
	original: ContentSource,
	enabledSources: List<ContentSourceInfo>,
	pipelineResolved: ContentSource,
): ContentSource {
	enabledSources.firstOrNull { it.mangaSource.name == original.name }?.mangaSource?.let { return it }
	return pipelineResolved.takeIf {
		it.resolvedContentTypeForSnapshot() != null || it.locale.isNotBlank()
	} ?: original
}
