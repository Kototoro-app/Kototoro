package org.skepsun.kototoro.settings.sources.unified

import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.explore.data.classifyAvailability
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

internal data class SourceTestOutcome(
    val availability: ContentSourceAvailability,
    val failure: String? = null,
) {
    val isAvailable: Boolean get() = availability == ContentSourceAvailability.AVAILABLE
}

internal suspend fun probeSourceAvailability(
    source: ContentSource,
    createRepository: () -> ContentRepository,
    timeoutMs: Long = 45_000L,
): SourceTestOutcome {
    // A missing homepage says nothing about a provider's ability to search or load content.
    if (source is CloudstreamSource && !source.api.hasMainPage) {
        return SourceTestOutcome(ContentSourceAvailability.UNKNOWN)
    }
    val result = source.runCatchingCancellable {
        withTimeoutOrNull(timeoutMs) {
            val repository = createRepository()
            repository.getList(0, repository.defaultSortOrder, ContentListFilter.EMPTY).isNotEmpty()
        }
    }
    val available = result.getOrDefault(false) == true
    val exception = result.exceptionOrNull()
    return SourceTestOutcome(
        availability = when {
            available -> ContentSourceAvailability.AVAILABLE
            exception != null -> classifyAvailability(exception)
            else -> ContentSourceAvailability.EMPTY
        },
        failure = when {
            exception != null -> exception.javaClass.simpleName
            result.getOrNull() == null -> "timeout"
            !available -> "empty"
            else -> null
        },
    )
}
