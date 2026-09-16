package org.skepsun.kototoro.reader.image

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.PrefetchRequest
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import java.util.concurrent.ConcurrentHashMap

/**
 * Production adapter bridging the decoupled [ReaderImagePipeline] contract
 * to Kototoro's existing, stable [ComposeReaderImagePipeline] and Coil3 [ImageLoader].
 *
 * Conforms to ADR 0002 Constraint 2 & 4:
 * 1. ImagePipeline owns resource scheduling and caching.
 * 2. Emits polymorphic [ReaderImageAsset] handles without forcing premature in-memory Bitmap copies.
 */
class KototoroImagePipelineAdapter(
    private val context: Context,
    private val composePipeline: ComposeReaderImagePipeline,
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
    private val pageLookup: (PageId) -> ReaderPage?,
) : ReaderImagePipeline {

    private val cachedAssets = ConcurrentHashMap<PageId, ReaderImageAsset>()

    override fun getCachedAsset(pageId: PageId): ReaderImageAsset? {
        val inMemory = cachedAssets[pageId]
        if (inMemory != null) return inMemory

        val state = composePipeline.cachedState(pageId.value)
        val uri = when (state) {
            is ComposeReaderImageState.OriginalReady -> state.original
            is ComposeReaderImageState.EnhancedReady -> state.enhanced
            else -> null
        } ?: return null

        val encoded = ReaderImageAsset.Encoded(pageId, uri.toString())
        cachedAssets[pageId] = encoded
        return encoded
    }

    override fun schedulePrefetch(requests: List<PrefetchRequest>) {
        for (request in requests) {
            val page = pageLookup(request.pageId) ?: continue
            val priority = request.priority

            // Skip low priority if we already have it cached
            if (priority == PrefetchPriority.LOW && cachedAssets.containsKey(request.pageId)) {
                continue
            }

            scope.launch(Dispatchers.IO) {
                val state = composePipeline.cachedState(page.readerKey)
                val uri = when (state) {
                    is ComposeReaderImageState.OriginalReady -> state.original
                    is ComposeReaderImageState.EnhancedReady -> state.enhanced
                    else -> null
                }
                if (uri != null) {
                    val req = ImageRequest.Builder(context)
                        .data(uri)
                        .build()
                    imageLoader.enqueue(req)
                }
            }
        }
    }

    override fun observeAsset(pageId: PageId): Flow<ReaderImageAsset?> {
        val page = pageLookup(pageId) ?: return flow { emit(null) }
        return composePipeline.observe(page).map { state ->
            when (state) {
                is ComposeReaderImageState.OriginalReady -> {
                    val asset = ReaderImageAsset.Encoded(pageId, state.original.toString())
                    cachedAssets[pageId] = asset
                    asset
                }
                is ComposeReaderImageState.EnhancedReady -> {
                    val asset = ReaderImageAsset.Encoded(pageId, state.enhanced.toString())
                    cachedAssets[pageId] = asset
                    asset
                }
                is ComposeReaderImageState.PreviewReady -> {
                    ReaderImageAsset.Encoded(pageId, state.previewUrl)
                }
                else -> cachedAssets[pageId]
            }
        }
    }

    override fun evictAsset(pageId: PageId) {
        cachedAssets.remove(pageId)
    }

    fun storeAsset(pageId: PageId, asset: ReaderImageAsset) {
        cachedAssets[pageId] = asset
    }
}
