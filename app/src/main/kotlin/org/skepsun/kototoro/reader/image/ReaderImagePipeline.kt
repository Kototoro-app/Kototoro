package org.skepsun.kototoro.reader.image

import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchRequest

/**
 * High-level image pipeline contract consumed by the Reader architecture.
 *
 * Conforms to ADR 0002 Principle:
 * "ReaderCore owns semantics & prediction; ImagePipeline owns execution and resource scheduling."
 */
interface ReaderImagePipeline {

    /**
     * Synchronously queries whether an asset is already cached in memory for the given page.
     */
    fun getCachedAsset(pageId: PageId): ReaderImageAsset?

    /**
     * Submits predictive prefetch requests from ReaderPrediction to the pipeline.
     */
    fun schedulePrefetch(requests: List<PrefetchRequest>)

    /**
     * Observes the asset state for a page as a Flow.
     */
    fun observeAsset(pageId: PageId): Flow<ReaderImageAsset?>

    /**
     * Releases or evicts memory cached assets when a page leaves the active cache window.
     */
    fun evictAsset(pageId: PageId) {}
}
