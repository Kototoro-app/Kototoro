package org.skepsun.kototoro.reader.image

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderResourceWindow

/**
 * High-level image pipeline contract consumed by the Reader architecture.
 *
 * Conforms to ADR 0002 Principle:
 * "ReaderCore owns semantics & prediction; ImagePipeline owns execution and resource scheduling."
 */
interface ReaderImagePipeline {

    /** Renderer-ready assets currently retained by the pipeline resource window. */
    val assets: StateFlow<Map<PageId, ReaderImageAsset>>

    /** Resource readiness is separate from scene geometry and encoded source availability. */
    val loadStates: StateFlow<Map<PageId, ReaderImageLoadState>>

    /**
     * Synchronously queries whether an asset is already cached in memory for the given page.
     */
    fun getCachedAsset(pageId: PageId): ReaderImageAsset?

    /**
     * Probes cached dimensions for page geometry hints without promoting unverified bitmaps
     * into authoritative presentation assets.
     */
    fun probeCachedDimensions(pageId: PageId): org.skepsun.kototoro.reader.core.IntSize? = null

    /**
     * Replaces the desired resource window. The pipeline owns acquisition, retention, and eviction.
     */
    fun updateResourceWindow(window: ReaderResourceWindow)

    /**
     * Observes the asset state for a page as a Flow.
     */
    fun observeAsset(pageId: PageId): Flow<ReaderImageAsset?>

    /**
     * Actively acquires the asset for the page, loading and decoding it if not already cached.
     */
    suspend fun acquireAsset(pageId: PageId): ReaderImageAsset? = null

    /** Retries a failed request explicitly, bypassing the source's failed/cached result. */
    suspend fun retryAsset(pageId: PageId): ReaderImageAsset?

    /**
     * Requests decode residency for the lattice tiles intersecting [visibleRegion] (image-space
     * logical pixels). This is the tile path of the resource contract (improvement plan §8.1:
     * the contract names the required region); implementations without tiled assets make it a
     * no-op rather than dropping the method, so callers never branch on the pipeline kind.
     */
    fun requestTiles(pageId: PageId, visibleRegion: IntRect, lookaheadRegion: IntRect? = null)
}

sealed interface ReaderImageLoadState {
    data class Loading(val progress: Float? = null) : ReaderImageLoadState
    data object Ready : ReaderImageLoadState
    data class Failed(val cause: Throwable) : ReaderImageLoadState
}
