package org.skepsun.kototoro.suggestions.domain

import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.util.ext.flatten
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

internal const val SUGGESTIONS_SOURCE_TIMEOUT_MS = 45_000L
internal const val SUGGESTIONS_GLOBAL_TIMEOUT_MS = 5 * 60 * 1_000L
internal const val SUGGESTIONS_MAX_RAW_RESULTS = 280
internal const val SUGGESTIONS_MAX_PARALLELISM = 3

internal suspend fun <S, T> collectSourceResults(
    sources: Iterable<S>,
    maxParallelism: Int = SUGGESTIONS_MAX_PARALLELISM,
    sourceTimeoutMillis: Long = SUGGESTIONS_SOURCE_TIMEOUT_MS,
    globalTimeoutMillis: Long = SUGGESTIONS_GLOBAL_TIMEOUT_MS,
    maxRawResults: Int = SUGGESTIONS_MAX_RAW_RESULTS,
    fetch: suspend (S) -> List<T>,
): List<T> {
    require(maxParallelism > 0) { "maxParallelism must be positive" }
    require(sourceTimeoutMillis > 0) { "sourceTimeoutMillis must be positive" }
    require(globalTimeoutMillis > 0) { "globalTimeoutMillis must be positive" }
    if (maxRawResults <= 0) {
        return emptyList()
    }

    val semaphore = Semaphore(maxParallelism)
    val producer = channelFlow {
        for (source in sources) {
            launch {
                val result = withTimeoutOrNull(sourceTimeoutMillis) {
                    runCatchingCancellable { semaphore.withPermit { fetch(source) } }
                        .getOrDefault(emptyList())
                }
                if (result != null) {
                    send(result)
                }
            }
        }
    }
    val results = ArrayList<T>(maxRawResults)
    withTimeoutOrNull(globalTimeoutMillis) {
        producer
            .flatten()
            .take(maxRawResults)
            .collect { results += it }
    }
    return results
}
