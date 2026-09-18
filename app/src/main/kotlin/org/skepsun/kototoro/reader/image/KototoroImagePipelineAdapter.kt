package org.skepsun.kototoro.reader.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Trace
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.asDrawable
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.CachePolicy
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
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.ReaderCameraSnapshot
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.core.ReaderScene
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImagePipeline
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderImageState
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderPageTransformation
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
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
    private val isReaderOptimizationEnabled: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    regionDecoderFactory: RegionDecoderFactory? = null,
    private val decodePlanner: DecodePlanner = DecodePlanner(),
    tileManager: ReaderTileManager? = null,
    private val viewportSizeProvider: () -> IntSize = { IntSize(1080, 2400) },
    private val pageLookup: (PageId) -> ReaderPage? = { null },
) : ReaderImagePipeline {

    private val cachedAssets = ConcurrentHashMap<PageId, ReaderImageAsset>()
    private val inFlightLoads = ConcurrentHashMap<PageId, Deferred<ReaderImageAsset?>>()
    private val inFlightSourceLoads = ConcurrentHashMap<PageId, Job>()
    private val regionSources = ConcurrentHashMap<PageId, RegionDecodeSource>()

    val actualRegionDecoderFactory: RegionDecoderFactory? by lazy {
        regionDecoderFactory ?: runCatching {
            AndroidRegionDecoderFactory(context, ioDispatcher, preferredBitmapConfig = bitmapConfig)
        }.getOrNull()
    }

    val actualTileManager: ReaderTileManager by lazy {
        tileManager ?: ReaderTileManager(
            scope = scope,
            decodeDispatcher = ioDispatcher,
            sourceFactory = { id -> regionSources[id] },
            costOf = { payload ->
                when (payload) {
                    is Bitmap -> payload.allocationByteCount.toLong()
                    else -> 0L
                }
            },
            payloadReleaser = { payload ->
                if (payload is Bitmap && !payload.isRecycled) {
                    payload.recycle()
                }
            },
        )
    }

    val tileStore: TileStore get() = actualTileManager

    fun requestTiles(pageId: PageId, visibleRegion: IntRect, lookaheadRegion: IntRect? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.CachedAssetCount", cachedAssets.size.toLong())
            Trace.setCounter("Reader.RegionSourceCount", regionSources.size.toLong())
        }
        val asset = cachedAssets[pageId] as? ReaderImageAsset.Tiled ?: return
        actualTileManager.requestTiles(asset.base.grid, visibleRegion, lookaheadRegion)
        asset.target?.let { target ->
            actualTileManager.requestTiles(target.grid, visibleRegion, lookaheadRegion)
        }
    }

    @Volatile
    private var desiredReadiness: Map<PageId, PrefetchReadiness>? = null

    /** Camera scale of the last settle, fed into the decode plan so zoom resolves its own LOD. */
    @Volatile
    private var cameraScale: Float = 1f

    /** Last zoom target width requested per page, so a repeated settle is not decoded again. */
    private val zoomReacquireTargets = ConcurrentHashMap<PageId, Int>()
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

    private fun publishPreview(pageId: PageId, previewUrl: String) {
        val existing = cachedAssets[pageId]
        if (existing?.isAuthoritativePresentation == true) return

        scope.launch(ioDispatcher) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(previewUrl)
                    .build()
                val res = imageLoader.execute(req)
                if (res is SuccessResult) {
                    val previewBmp = res.image.toBitmap()
                    synchronized(cachedAssets) {
                        val current = cachedAssets[pageId]
                        if (current?.isAuthoritativePresentation == true) return@launch
                        val asset = ReaderImageAsset.Preview(pageId, previewBmp.asImageBitmap())
                        cachedAssets[pageId] = asset
                        updateAssets { it + (pageId to asset) }
                    }
                }
            } catch (_: Throwable) {
                // Non-fatal preview failure; original load proceeds
            }
        }
    }

    private fun storeAsset(pageId: PageId, asset: ReaderImageAsset): Boolean {
        val readinessMap = desiredReadiness
        if (readinessMap != null) {
            val targetReadiness = readinessMap[pageId] ?: return false
            if (asset !is ReaderImageAsset.Encoded && targetReadiness != PrefetchReadiness.PRESENTATION_READY) {
                return false
            }
        }
        val existing = cachedAssets[pageId]
        if (existing?.isAuthoritativePresentation == true && !asset.isAuthoritativePresentation) {
            return false
        }
        cachedAssets[pageId] = asset
        if (asset !is ReaderImageAsset.Encoded) {
            updateAssets { it + (pageId to asset) }
            if (asset.isAuthoritativePresentation) {
                setLoadState(pageId, ReaderImageLoadState.Ready)
            }
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
        if (inMemory is ReaderImageAsset.Animated) {
            return IntSize(inMemory.width, inMemory.height)
        }
        if (inMemory is ReaderImageAsset.Tiled) {
            return inMemory.grid.pageSize
        }
        if (inMemory is ReaderImageAsset.Preview) {
            return null
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
                if (cached is ReaderImageAsset.ComposeImage || cached is ReaderImageAsset.Tiled || cached is ReaderImageAsset.Animated) {
                    return cached
                }
            }
            val page = pageLookup(pageId) ?: return null
            scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    setLoadState(pageId, ReaderImageLoadState.Loading())
                    val readyState = composePipeline.observe(page, force).onEach {
                        if (it is ComposeReaderImageState.Downloading) {
                            setLoadState(pageId, ReaderImageLoadState.Loading(it.progress))
                        }
                        if (it is ComposeReaderImageState.PreviewReady) {
                            publishPreview(pageId, it.previewUrl)
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
                    val isAnimatedHint = when (readyState) {
                        is ComposeReaderImageState.OriginalReady -> readyState.isAnimated
                        is ComposeReaderImageState.EnhancedReady -> readyState.isAnimated
                        else -> false
                    }

                    // Attempt region decoding and planning for non-animated images
                    val factory = actualRegionDecoderFactory
                    // The plan's LOD has to survive into the request: without a size constraint Coil
                    // decodes the original resolution, which turns a 6000x9000 page into a ~216MB
                    // bitmap even though the planner resolved a ~13.5MB sampled decode for it.
                    var plannedDecodeSize: IntSize? = null
                    val tiledAsset: ReaderImageAsset.Tiled? = if (factory != null && !isAnimatedHint) {
                        runCatching {
                            val cropBounds = if (isCropEnabled) composePipeline.getTrimmedBounds(uri) else null
                            val initialSource = factory.create(uri, isAnimatedHint = false)
                            if (!initialSource.metadata.isAnimated) {
                                val contentRect = if (cropBounds != null) {
                                    IntRect(cropBounds.left, cropBounds.top, cropBounds.right, cropBounds.bottom)
                                        .intersectionOrNull(IntRect.fromLtwh(0, 0, initialSource.metadata.size.width, initialSource.metadata.size.height))
                                        ?: IntRect.fromLtwh(0, 0, initialSource.metadata.size.width, initialSource.metadata.size.height)
                                } else {
                                    IntRect.fromLtwh(0, 0, initialSource.metadata.size.width, initialSource.metadata.size.height)
                                }
                                val geometry = ImageSourceGeometry(
                                    encodedSize = initialSource.metadata.size,
                                    contentRect = contentRect,
                                    orientationDegrees = initialSource.geometry.orientationDegrees,
                                )
                                val vpSize = viewportSizeProvider()
                                val plan = decodePlanner.plan(
                                    pageId = pageId,
                                    metadata = initialSource.metadata,
                                    geometry = geometry,
                                    viewportWidth = vpSize.width.coerceAtLeast(100),
                                    viewportHeight = vpSize.height.coerceAtLeast(100),
                                    cameraScale = cameraScale,
                                    format = if (bitmapConfig == Bitmap.Config.RGB_565) RasterFormat.RGB_565 else RasterFormat.ARGB_8888,
                                )
                                if (plan is DecodePlan.Tiled) {
                                    regionSources[pageId] = initialSource
                                    val split = page.split.toTileSplit()
                                    val grid = TileGrid(
                                        pageId = pageId,
                                        geometry = geometry,
                                        split = split,
                                        tileDimension = plan.tileDimension,
                                        sampleSize = plan.lod.sampleSize,
                                    )
                                    actualTileManager.requestOverview(grid, plan.overviewLod.sampleSize)
                                    val overviewKey = grid.overviewTile(plan.overviewLod.sampleSize).key
                                    val asset = ReaderImageAsset.Tiled(
                                        pageId = pageId,
                                        grid = grid,
                                        tileStore = actualTileManager,
                                        overviewKey = overviewKey,
                                    )
                                    val retained = storeAsset(pageId, asset)
                                    composePipeline.onImageDecoded(page, grid.pageSize.width, grid.pageSize.height)
                                    if (retained) {
                                        withContext(Dispatchers.Main) {
                                            onAssetLoaded?.invoke(pageId, asset)
                                        }
                                    }
                                    asset
                                } else {
                                    plannedDecodeSize = plan.requestedDecodeSize()
                                    null
                                }
                            } else {
                                null
                            }
                        }.getOrNull()
                    } else {
                        null
                    }

                    if (tiledAsset != null) {
                        return@async tiledAsset
                    }
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .apply {
                            plannedDecodeSize?.let { size(it.width, it.height) }
                            if (bitmapConfig == Bitmap.Config.RGB_565) {
                                allowHardware(false)
                            }
                            if (isReaderOptimizationEnabled) {
                                memoryCachePolicy(CachePolicy.DISABLED)
                            }
                            transformations(ComposeReaderPageTransformation(isCropEnabled, page.split))
                        }
                        .build()
                    val result = imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val drawable = result.image.asDrawable(context.resources)
                        val isAnimatedDrawable = isAnimatedHint || (drawable is Animatable)
                        val presentationAsset: ReaderImageAsset = if (isAnimatedDrawable) {
                            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
                            ReaderImageAsset.Animated(
                                pageId = pageId,
                                drawable = drawable,
                                width = width,
                                height = height,
                            )
                        } else {
                            val bmp = result.image.toBitmap()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
                                Trace.setCounter("Reader.PresentationWidthPx", bmp.width.toLong())
                            }
                            ReaderImageAsset.ComposeImage(pageId, bmp.asImageBitmap())
                        }

                        val retained = storeAsset(pageId, presentationAsset)
                        val width = when (presentationAsset) {
                            is ReaderImageAsset.Animated -> presentationAsset.width
                            is ReaderImageAsset.ComposeImage -> presentationAsset.imageBitmap.width
                            else -> 1080
                        }
                        val height = when (presentationAsset) {
                            is ReaderImageAsset.Animated -> presentationAsset.height
                            is ReaderImageAsset.ComposeImage -> presentationAsset.imageBitmap.height
                            else -> 1920
                        }
                        composePipeline.onImageDecoded(page, width, height)
                        if (retained) {
                            withContext(Dispatchers.Main) {
                                onAssetLoaded?.invoke(pageId, presentationAsset)
                            }
                        }
                        presentationAsset
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
                val current = cachedAssets[pageId]
                if (current is ReaderImageAsset.ComposeImage || current is ReaderImageAsset.Tiled || current is ReaderImageAsset.Animated || inFlightLoads.containsKey(pageId)) {
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
                if (current is ReaderImageAsset.Tiled) {
                    actualTileManager.releasePage(pageId)
                } else if (current is ReaderImageAsset.Animated) {
                    (current.drawable as? Animatable)?.stop()
                }
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
            if (cached is ReaderImageAsset.ComposeImage || cached is ReaderImageAsset.Tiled || cached is ReaderImageAsset.Animated) return@flow
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
        val current = cachedAssets.remove(pageId)
        if (current is ReaderImageAsset.Animated) {
            (current.drawable as? Animatable)?.stop()
        }
        inFlightSourceLoads.remove(pageId)?.cancel()
        actualTileManager.releasePage(pageId)
        regionSources.remove(pageId)
        // A page that comes back later deserves its own zoom decision, not the stale one.
        zoomReacquireTargets.remove(pageId)
        updateAssets { it - pageId }
        mutableLoadStates.update { it - pageId }
    }

    /**
     * Responds to camera / zoom settle by evaluating multi-LOD tile requests
     * for visible pages and releasing over-sampled tiles upon zoom-out.
     */
    fun onCameraSettled(snapshot: ReaderCameraSnapshot, scene: ReaderScene? = null) {
        val scale = snapshot.scale
        val visibleBounds = snapshot.visibleBoundsInScene
        // The camera feeds the next decode plan, so a zoomed page asks for its own LOD instead of
        // reusing the fit-to-screen target resolved when the page was first acquired.
        cameraScale = scale

        if (scale <= 1.0f) {
            // Zoom-out: drop target layers and release over-sampled tiles
            for ((pageId, asset) in cachedAssets) {
                if (asset !is ReaderImageAsset.Tiled) continue
                if (asset.target != null) {
                    val targetSampleSize = asset.target.sampleSize
                    actualTileManager.releaseTiles { it.pageId == pageId && it.sampleSize == targetSampleSize }
                    val downgraded = ReaderImageAsset.Tiled(
                        pageId = pageId,
                        base = asset.base,
                        target = null,
                        tileStore = actualTileManager,
                    )
                    cachedAssets[pageId] = downgraded
                    updateAssets { it + (pageId to downgraded) }
                }
            }
            return
        }

        // Zoom-in: find visible tiled pages that intersect visible bounds
        val visibleFloatRect = snapshot.visibleBoundsInScene

        for ((pageId, asset) in cachedAssets) {
            if (asset !is ReaderImageAsset.Tiled) continue
            val source = regionSources[pageId] ?: continue
            val page = pageLookup(pageId) ?: continue

            var pageSceneBounds: FloatRect? = null
            if (scene != null) {
                val pageGeom = scene.pageGeometries.firstOrNull { it.pageId == pageId }
                if (pageGeom != null) {
                    if (!pageGeom.sceneBounds.intersects(visibleFloatRect)) {
                        continue
                    }
                    pageSceneBounds = pageGeom.sceneBounds
                }
            }

            val vpSize = viewportSizeProvider()
            val plan = decodePlanner.plan(
                pageId = pageId,
                metadata = source.metadata,
                geometry = asset.grid.geometry,
                viewportWidth = vpSize.width.coerceAtLeast(100),
                viewportHeight = vpSize.height.coerceAtLeast(100),
                cameraScale = scale,
                currentLod = LodSpec(
                    level = ReaderLodPolicy.calculateLodLevel(asset.base.sampleSize),
                    sampleSize = asset.base.sampleSize,
                    targetPixelScale = 1.0f / asset.base.sampleSize,
                ),
                format = if (bitmapConfig == Bitmap.Config.RGB_565) RasterFormat.RGB_565 else RasterFormat.ARGB_8888,
            )

            if (plan is DecodePlan.Tiled && plan.lod.sampleSize < asset.base.sampleSize) {
                val targetSampleSize = plan.lod.sampleSize
                if (asset.target?.sampleSize != targetSampleSize) {
                    val targetGrid = TileGrid(
                        pageId = pageId,
                        geometry = asset.grid.geometry,
                        split = page.split.toTileSplit(),
                        tileDimension = plan.tileDimension,
                        sampleSize = targetSampleSize,
                    )
                    val targetLayer = ReaderImageAsset.TileLayer(grid = targetGrid, sampleSize = targetSampleSize)
                    val updated = ReaderImageAsset.Tiled(
                        pageId = pageId,
                        base = asset.base,
                        target = targetLayer,
                        tileStore = actualTileManager,
                    )
                    cachedAssets[pageId] = updated
                    updateAssets { it + (pageId to updated) }

                    // Request high-LOD target tiles
                    if (pageSceneBounds != null && pageSceneBounds.width > 0f && pageSceneBounds.height > 0f) {
                        val intersection = pageSceneBounds.intersectionOrNull(visibleFloatRect)
                        if (intersection != null) {
                            val scaleX = targetGrid.pageSize.width.toFloat() / pageSceneBounds.width
                            val scaleY = targetGrid.pageSize.height.toFloat() / pageSceneBounds.height
                            val pageLogical = IntRect(
                                left = ((intersection.left - pageSceneBounds.left) * scaleX).toInt().coerceIn(0, targetGrid.pageSize.width),
                                top = ((intersection.top - pageSceneBounds.top) * scaleY).toInt().coerceIn(0, targetGrid.pageSize.height),
                                right = kotlin.math.ceil((intersection.right - pageSceneBounds.left) * scaleX).toInt().coerceIn(0, targetGrid.pageSize.width),
                                bottom = kotlin.math.ceil((intersection.bottom - pageSceneBounds.top) * scaleY).toInt().coerceIn(0, targetGrid.pageSize.height),
                            )
                            actualTileManager.requestTiles(targetGrid, pageLogical)
                        }
                    }
                }
            }
        }

        // Sampled pages are not tiles: they were decoded for the fit scale, so a zoomed reader
        // would be shown an upscaled bitmap unless the page is decoded again for this camera.
        // Each page is re-decoded once per target width; a repeated settle at the same zoom is not
        // worth another multi-megabyte decode.
        val viewportWidth = viewportSizeProvider().width
        for ((pageId, asset) in cachedAssets) {
            val decodedWidth = when (asset) {
                is ReaderImageAsset.ComposeImage -> asset.imageBitmap.width
                is ReaderImageAsset.AndroidBitmap -> asset.bitmap.width
                else -> continue
            }
            val lastAttempt = zoomReacquireTargets[pageId]
            if (shouldAttemptZoomReacquire(scale, decodedWidth, viewportWidth, lastAttempt)) {
                zoomReacquireTargets[pageId] = (viewportWidth * scale).toInt()
                scope.launch(ioDispatcher) { acquireAsset(pageId, force = true) }
            }
        }
    }
}

internal fun ReaderPageSplit.toTileSplit(): TileSplit = when (this) {
    ReaderPageSplit.NONE -> TileSplit.NONE
    ReaderPageSplit.LEFT -> TileSplit.LEFT
    ReaderPageSplit.RIGHT -> TileSplit.RIGHT
}

/**
 * Decode size the image request must carry for [this] plan, or `null` to leave it unconstrained.
 *
 * A sampled plan resolved its target from the viewport and the source size, and dropping that
 * decision makes the loader decode the original resolution instead - a 6000x9000 page becomes a
 * ~216MB bitmap where the plan asked for ~13.5MB. Tiled plans draw from the tile store and single
 * plans are already exactly one bitmap at the source size, so neither needs a constraint.
 */
internal fun DecodePlan?.requestedDecodeSize(): IntSize? = when (this) {
    is DecodePlan.SampledSingle -> targetSize
    else -> null
}

/** Shortfall below which a sampled page is left as it is rather than decoded again. */
private const val ZOOM_REACQUIRE_THRESHOLD = 1.25f

/**
 * Whether a sampled page must be decoded again for the current camera.
 *
 * A page decoded to [decodedWidthPx] and shown across a [viewportWidthPx] viewport is only
 * upscaled once the zoomed demand passes the decoded width by [threshold]; anything smaller is
 * within the resampling headroom and not worth the memory churn.
 */
internal fun shouldReacquireForZoom(
    scale: Float,
    decodedWidthPx: Int,
    viewportWidthPx: Int,
    threshold: Float = ZOOM_REACQUIRE_THRESHOLD,
): Boolean {
    if (scale <= 1f || decodedWidthPx <= 0 || viewportWidthPx <= 0) return false
    return decodedWidthPx * threshold < viewportWidthPx * scale
}

/**
 * Whether a zoom re-decode should be attempted at all, given the width already requested for that
 * page. Settles repeat while panning, and retrying an identical target only churns decode memory.
 */
internal fun shouldAttemptZoomReacquire(
    scale: Float,
    decodedWidthPx: Int,
    viewportWidthPx: Int,
    lastAttemptWidthPx: Int?,
): Boolean {
    if (!shouldReacquireForZoom(scale, decodedWidthPx, viewportWidthPx)) return false
    val targetWidthPx = viewportWidthPx * scale
    val previous = lastAttemptWidthPx ?: return true
    return targetWidthPx > previous * 1.1f
}
