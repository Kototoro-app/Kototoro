package org.skepsun.kototoro.reader.image

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderPageTransformation
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
    private val isCropEnabled: Boolean = false,
    private val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pageLookup: (PageId) -> ReaderPage? = { null },
) : ReaderImagePipeline {

    private val cachedAssets = ConcurrentHashMap<PageId, ReaderImageAsset>()
    private val inFlightLoads = ConcurrentHashMap<PageId, Deferred<ReaderImageAsset?>>()
    private val inFlightSourceLoads = ConcurrentHashMap<PageId, Job>()
    @Volatile
    private var retainedPageIds: Set<PageId>? = null
    private val mutableAssets = MutableStateFlow<Map<PageId, ReaderImageAsset>>(emptyMap())
    override val assets = mutableAssets.asStateFlow()
    private val mutableLoadStates = MutableStateFlow<Map<PageId, ReaderImageLoadState>>(emptyMap())
    override val loadStates = mutableLoadStates.asStateFlow()

    private fun setLoadState(pageId: PageId, state: ReaderImageLoadState) {
        mutableLoadStates.update { it + (pageId to state) }
    }

    private fun storeAsset(pageId: PageId, asset: ReaderImageAsset): Boolean {
        if (retainedPageIds?.contains(pageId) == false) return false
        cachedAssets[pageId] = asset
        if (asset !is ReaderImageAsset.Encoded) {
            mutableAssets.update { it + (pageId to asset) }
            setLoadState(pageId, ReaderImageLoadState.Ready)
        }
        return true
    }

    var onAssetLoaded: ((PageId, ReaderImageAsset) -> Unit)? = null

    override fun getCachedAsset(pageId: PageId): ReaderImageAsset? {
        val inMemory = cachedAssets[pageId]
        if (inMemory != null) return inMemory

        val state = composePipeline.cachedState(pageId.value)
        val uri = when (state) {
            is ComposeReaderImageState.OriginalReady -> state.original
            is ComposeReaderImageState.EnhancedReady -> state.enhanced
            else -> null
        } ?: return null

        val memoryImage = imageLoader.memoryCache?.get(MemoryCache.Key(uri.toString()))?.image
        if (memoryImage != null) {
            val bmp = runCatching { memoryImage.toBitmap() }.getOrNull()
            if (bmp != null) {
                val composeAsset = ReaderImageAsset.ComposeImage(pageId, bmp.asImageBitmap())
                storeAsset(pageId, composeAsset)
                return composeAsset
            }
        }

        val encoded = ReaderImageAsset.Encoded(pageId, uri.toString())
        storeAsset(pageId, encoded)
        return encoded
    }

    override suspend fun acquireAsset(pageId: PageId): ReaderImageAsset? = acquireAsset(pageId, force = false)

    override suspend fun retryAsset(pageId: PageId): ReaderImageAsset? = acquireAsset(pageId, force = true)

    private suspend fun acquireAsset(pageId: PageId, force: Boolean): ReaderImageAsset? {
        val deferred = synchronized(inFlightLoads) {
            inFlightLoads[pageId]?.let { existing ->
                if (!existing.isCompleted) return@synchronized existing
                inFlightLoads.remove(pageId, existing)
            }
            if (!force) {
                if (loadStates.value[pageId] is ReaderImageLoadState.Failed) return null
                val cached = cachedAssets[pageId]
                if (cached is ReaderImageAsset.ComposeImage) return cached
            }
            val page = pageLookup(pageId) ?: return null
            scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    setLoadState(pageId, ReaderImageLoadState.Loading())
                    val readyState = composePipeline.observe(page, force).onEach {
                        if (it is ComposeReaderImageState.Downloading) {
                            setLoadState(pageId, ReaderImageLoadState.Loading(it.progress))
                        }
                    }.firstOrNull {
                        it is ComposeReaderImageState.OriginalReady || it is ComposeReaderImageState.EnhancedReady ||
                            it is ComposeReaderImageState.Failed
                    } ?: error("Image source completed without an image")

                    if (readyState is ComposeReaderImageState.Failed) {
                        setLoadState(pageId, ReaderImageLoadState.Failed(readyState.cause))
                        return@async null
                    }

                    val uri = when (readyState) {
                        is ComposeReaderImageState.OriginalReady -> readyState.original
                        is ComposeReaderImageState.EnhancedReady -> readyState.enhanced
                        else -> error("Image source did not produce a display URI")
                    }
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .apply {
                            if (bitmapConfig == Bitmap.Config.RGB_565) {
                                allowHardware(false)
                            }
                            transformations(ComposeReaderPageTransformation(isCropEnabled, page.split))
                        }
                        .build()
                    val result = imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val bmp = result.image.toBitmap()
                        val composeAsset = ReaderImageAsset.ComposeImage(pageId, bmp.asImageBitmap())
                        val retained = storeAsset(pageId, composeAsset)
                        composePipeline.onImageDecoded(page, bmp.width, bmp.height)
                        if (retained) {
                            withContext(Dispatchers.Main) {
                                onAssetLoaded?.invoke(pageId, composeAsset)
                            }
                        }
                        composeAsset
                    } else {
                        throw (result as ErrorResult).throwable
                    }
                } catch (cancelled: CancellationException) {
                    mutableLoadStates.update { it - pageId }
                    throw cancelled
                } catch (error: Exception) {
                    setLoadState(pageId, ReaderImageLoadState.Failed(error))
                    null
                }
            }.also { task ->
                inFlightLoads[pageId] = task
                task.invokeOnCompletion { inFlightLoads.remove(pageId, task) }
            }
        }
        deferred.start()
        return deferred.await()
    }

    override fun updateResourceWindow(window: ReaderResourceWindow) {
        val retainedPageIds = HashSet<PageId>(window.requests.size)
        for (request in window.requests) {
            retainedPageIds.add(request.pageId)
        }
        this.retainedPageIds = retainedPageIds
        evictOutside(retainedPageIds)

        for (request in window.requests) {
            val pageId = request.pageId
            val page = pageLookup(pageId) ?: continue

            if (loadStates.value[pageId] is ReaderImageLoadState.Failed) {
                continue
            }

            if (request.readiness == PrefetchReadiness.PRESENTATION_READY) {
                if (cachedAssets[pageId] is ReaderImageAsset.ComposeImage || inFlightLoads.containsKey(pageId)) {
                    continue
                }
                inFlightSourceLoads.remove(pageId)?.cancel()
                // UNDISPATCHED registers the in-flight Deferred before another window update can race it.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    acquireAsset(pageId)
                }
            } else {
                if (cachedAssets.containsKey(pageId) ||
                    inFlightLoads.containsKey(pageId) ||
                    inFlightSourceLoads.containsKey(pageId)
                ) {
                    continue
                }
                val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                    try {
                        val readyState = composePipeline.observe(page).firstOrNull {
                            it is ComposeReaderImageState.OriginalReady || it is ComposeReaderImageState.EnhancedReady ||
                                it is ComposeReaderImageState.Failed
                        }
                        // Foreground requests own visible state; a speculative failure must not overwrite their result.
                        if (readyState is ComposeReaderImageState.Failed && !inFlightLoads.containsKey(pageId)) {
                            setLoadState(pageId, ReaderImageLoadState.Failed(readyState.cause))
                        }
                        val uri = when (readyState) {
                            is ComposeReaderImageState.OriginalReady -> readyState.original
                            is ComposeReaderImageState.EnhancedReady -> readyState.enhanced
                            else -> null
                        }
                        if (uri != null) {
                            storeAsset(pageId, ReaderImageAsset.Encoded(pageId, uri.toString()))
                        }
                    } finally {
                        inFlightSourceLoads.remove(pageId, currentCoroutineContext().job)
                    }
                }
                val existing = inFlightSourceLoads.putIfAbsent(pageId, job)
                if (existing == null) {
                    job.start()
                } else {
                    job.cancel()
                }
            }
        }
    }

    override fun observeAsset(pageId: PageId): Flow<ReaderImageAsset?> = flow {
        val cached = cachedAssets[pageId]
        if (cached != null) {
            emit(cached)
            if (cached is ReaderImageAsset.ComposeImage) return@flow
        }
        val acquired = acquireAsset(pageId)
        if (acquired != null && acquired != cached) {
            emit(acquired)
        }
    }

    private fun evictOutside(retainedPageIds: Set<PageId>) {
        val evictedPageIds = HashSet<PageId>()
        for (pageId in cachedAssets.keys) {
            if (pageId !in retainedPageIds) evictedPageIds.add(pageId)
        }
        for (pageId in inFlightLoads.keys) {
            if (pageId !in retainedPageIds) evictedPageIds.add(pageId)
        }
        for (pageId in inFlightSourceLoads.keys) {
            if (pageId !in retainedPageIds) evictedPageIds.add(pageId)
        }
        for (pageId in evictedPageIds) {
            evictAsset(pageId)
        }
    }

    private fun evictAsset(pageId: PageId) {
        inFlightLoads.remove(pageId)?.cancel()
        cachedAssets.remove(pageId)
        inFlightSourceLoads.remove(pageId)?.cancel()
        mutableAssets.update { it - pageId }
        mutableLoadStates.update { it - pageId }
    }
}
