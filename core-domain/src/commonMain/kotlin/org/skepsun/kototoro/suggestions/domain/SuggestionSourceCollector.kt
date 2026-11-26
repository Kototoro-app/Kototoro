package org.skepsun.kototoro.suggestions.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

const val SUGGESTIONS_SOURCE_TIMEOUT_MS = 45_000L
const val SUGGESTIONS_GLOBAL_TIMEOUT_MS = 5 * 60 * 1_000L
const val SUGGESTIONS_MAX_PARALLELISM = 3

/**
 * Fetches every source with bounded parallelism and per-source/global timeouts; a failing or slow source only
 * contributes nothing. Every source that answers in time is kept whole, however many answered before it. Shared by Android's suggestions worker and the Windows host.
 */
suspend fun <S, T> collectSourceResults(
    sources: Iterable<S>,
    maxParallelism: Int = SUGGESTIONS_MAX_PARALLELISM,
    sourceTimeoutMillis: Long = SUGGESTIONS_SOURCE_TIMEOUT_MS,
    globalTimeoutMillis: Long = SUGGESTIONS_GLOBAL_TIMEOUT_MS,
    fetch: suspend (S) -> List<T>,
): List<T> {
    require(maxParallelism > 0) { "maxParallelism must be positive" }
    require(sourceTimeoutMillis > 0) { "sourceTimeoutMillis must be positive" }
    require(globalTimeoutMillis > 0) { "globalTimeoutMillis must be positive" }
    val semaphore = Semaphore(maxParallelism)
    val producer = channelFlow {
        for (source in sources) {
            launch {
                val result = withTimeoutOrNull(sourceTimeoutMillis) {
                    try {
                        semaphore.withPermit { fetch(source) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                if (result != null) {
                    send(result)
                }
            }
        }
    }
    val results = ArrayList<T>()
    withTimeoutOrNull(globalTimeoutMillis) {
        producer.collect { results += it }
    }
    return results
}
