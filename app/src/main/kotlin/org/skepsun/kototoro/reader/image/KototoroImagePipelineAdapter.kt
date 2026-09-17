package org.skepsun.kototoro.reader.image

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Trace
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.IntSize
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
    private var desiredReadiness: Map<PageId, PrefetchReadiness>? = null
    private val mutableAssets = MutableStateFlow<Map<PageId, ReaderImageAsset>>(emptyMap())
    override val assets = mutableAssets.asStateFlow()
    private val mutableLoadStates = MutableStateFlow<Map<PageId, ReaderImageLoadState>>(emptyMap())
    override val loadStates = mutableLoadStates.asStateFlow()

    private fun updateAssets(transform: (Map<PageId, ReaderImageAsset>) -> Map<PageId, ReaderImageAsset>) {
        val updated = mutableAssets.updateAndGet(transform)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.ActivePresentationAssets", updated.size.toLong())
        }
    }

    private fun setLoadState(pageId: PageId, state: ReaderImageLoadState) {
        mutableLoadStates.update { it + (pageId to state) }
    }

    private fun storeAsset(pageId: PageId, asset: ReaderImageAsset): Boolean {
        val readinessMap = desiredReadiness
        if (readinessMap != null) {
            val targetReadiness = readinessMap[pageId] ?: return false
            if (asset !is ReaderImageAsset.Encoded && targetReadiness != PrefetchReadiness.PRESENTATION_READY) {
                return false
            }
        }
        cachedAssets[pageId] = asset
        if (asset !is ReaderImageAsset.Encoded) {
            updateAssets { it + (pageId to asset) }
            setLoadState(pageId, ReaderImageLoadState.Ready)
        }
        return true
    }

    var onAssetLoaded: ((PageId, ReaderImageAsset) -> Unit)? = null

    override fun probeCachedDimensions(pageId: PageId): IntSize? {
        val inMemory = cachedAssets[pageId]
        if (inMemory is ReaderImageAsset.ComposeImage) {
            return IntSize(inMemory.imageBitmap.width, inMemory.imageBitmap.height)
        }
        if (inMemory is ReaderImageAsset.AndroidBitmap) {
            return IntSize(inMemory.bitmap.width, inMemory.bitmap.height)
        }

        val state = composePipeline.cachedState(pageId.value)
        val uri = when (state) {
            is ComposeReaderImageState.OriginalReady -> state.original
            is ComposeReaderImageState.EnhancedReady -> state.enhanced
            else -> null
        } ?: return null

        val memoryImage = imageLoader.memoryCache?.get(MemoryCache.Key(uri.toString()))?.image ?: return null
        val bmp = runCatching { memoryImage.toBitmap() }.getOrNull() ?: return null
        return IntSize(bmp.width, bmp.height)
    }

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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
                            Trace.setCounter("Reader.PresentationWidthPx", bmp.width.toLong())
                        }
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
        val desired = HashMap<PageId, PrefetchReadiness>(window.requests.size)
        for (request in window.requests) {
            val existing = desired[request.pageId]
            if (existing == null || request.readiness == PrefetchReadiness.PRESENTATION_READY) {
                desired[request.pageId] = request.readiness
            }
        }
        this.desiredReadiness = desired

        // 1. Evict pages completely outside the window
        evictOutside(desired.keys)

        // 2. Downgrade presentation assets that are now only requested as SOURCE_READY
        downgradeToSource(desired)

        // 3. Process requests
        for (request in window.requests) {
            val pageId = request.pageId
            val page = pageLookup(pageId) ?: continue

            if (loadStates.value[pageId] is ReaderImageLoadState.Failed) {
                continue
            }

            val targetReadiness = desired[pageId] ?: request.readiness
            if (targetReadiness == PrefetchReadiness.PRESENTATION_READY) {
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

    private fun downgradeToSource(desired: Map<PageId, PrefetchReadiness>) {
        for ((pageId, readiness) in desired) {
            if (readiness != PrefetchReadiness.SOURCE_READY) continue

            // Cancel any speculative decode that is no longer within the presentation window
            inFlightLoads.remove(pageId)?.cancel()

            val current = cachedAssets[pageId]
            if (current != null && current !is ReaderImageAsset.Encoded) {
                // Remove presentation asset from renderer-facing state flow and ready state
                updateAssets { it - pageId }
                mutableLoadStates.update { it - pageId }

                // Downgrade in-memory cache to lightweight Encoded handle
                val uri = when (val state = composePipeline.cachedState(pageId.value)) {
                    is ComposeReaderImageState.OriginalReady -> state.original
                    is ComposeReaderImageState.EnhancedReady -> state.enhanced
                    else -> null
                }
                if (uri != null) {
                    cachedAssets[pageId] = ReaderImageAsset.Encoded(pageId, uri.toString())
                } else {
                    cachedAssets.remove(pageId)
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
        updateAssets { it - pageId }
        mutableLoadStates.update { it - pageId }
    }
}
