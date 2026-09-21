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
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
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

    override fun requestTiles(pageId: PageId, visibleRegion: IntRect, lookaheadRegion: IntRect?) {
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

    /**
     * Limits reported by the renderer that will draw these bitmaps.
     *
     * The policy ceiling is only an upper bound: without this the planner assumes it on every device,
     * which is optimistic on hardware whose texture limit is lower. The host reports the real value
     * from the canvas it draws into.
     */
    @Volatile
    private var rendererCapabilities: RendererCapabilities = RendererCapabilities.Unknown

    fun setRendererCapabilities(capabilities: RendererCapabilities) {
        rendererCapabilities = capabilities
    }

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
        val updated = mutableLoadStates.updateAndGet { it + (pageId to state) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            // How many pages the pipeline is telling the reader to show a spinner for. A turn over an
            // already-decoded page must not move this counter; it exists because that regression was
            // invisible in frame timings and could only be seen in the UI.
            Trace.setCounter("Reader.LoadingPages", updated.values.count { it is ReaderImageLoadState.Loading }.toLong())
        }
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
                // A decode that hits the memory cache finishes in a few milliseconds, so publishing a
                // loading state up front made every warm page turn flash the "加载中…" overlay over a
                // page that was already decoded (the paged window only prefetches neighbours as
                // SOURCE_READY, so the decode starts exactly at the turn). The state is therefore
                // published only once the wait becomes perceptible; a genuinely slow page still gets
                // its indicator, just not before it is worth showing.
                // Progress reports go through the same gate as the initial publish. `observe` emits
                // Downloading for every page whose source is not in its state cache yet - the normal
                // case while turning page after page - and publishing those immediately put the
                // "加载中…" overlay back on screen for the few frames a cached page needs to decode.
                val loadingAnnounced = AtomicBoolean(false)
                val loadingPublish = scope.launch {
                    delay(LOADING_STATE_DELAY_MS)
                    loadingAnnounced.set(true)
                    // Only announce loading while the page is still unresolved, and atomically: the
                    // acquisition can resolve (or fail) on another thread at any moment, and that
                    // outcome has to win. A read-then-write here lost that race and turned a failed
                    // page back into a loading one.
                    mutableLoadStates.update { states ->
                        if (states.containsKey(pageId)) states else states + (pageId to ReaderImageLoadState.Loading())
                    }
                }
                try {
                    val readyState = composePipeline.observe(page, force).onEach {
                        if (it is ComposeReaderImageState.Downloading && loadingAnnounced.get()) {
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
                                    capabilities = rendererCapabilities,
                                    format = if (bitmapConfig == Bitmap.Config.RGB_565) RasterFormat.RGB_565 else RasterFormat.ARGB_8888,
                                )
                                if (plan is DecodePlan.Tiled) {
                                    regionSources[pageId] = initialSource
                                    val split = page.split.toTileSplit()
                                    // Both rungs are built once, here: the base is the level this page
                                    // needs at fit scale (what a neighbour shows) and the target the
                                    // level the camera already demands. Nothing rebuilds them later
                                    // except a genuine level change - see updateTileLadders.
                                    val ladder = resolveTileLadder(
                                        sourceContentWidthPx = geometry.logicalSize.width,
                                        viewportWidthPx = vpSize.width.coerceAtLeast(100),
                                        cameraScale = cameraScale,
                                    )
                                    val grid = TileGrid(
                                        pageId = pageId,
                                        geometry = geometry,
                                        split = split,
                                        tileDimension = plan.tileDimension,
                                        sampleSize = ladder.baseSampleSize,
                                    )
                                    val targetLayer = ladder.targetSampleSize?.let { targetSampleSize ->
                                        ReaderImageAsset.TileLayer(
                                            grid = TileGrid(
                                                pageId = pageId,
                                                geometry = geometry,
                                                split = split,
                                                tileDimension = plan.tileDimension,
                                                sampleSize = targetSampleSize,
                                            ),
                                        )
                                    }
                                    actualTileManager.requestOverview(grid, plan.overviewLod.sampleSize)
                                    val overviewKey = grid.overviewTile(plan.overviewLod.sampleSize).key
                                    val asset = ReaderImageAsset.Tiled(
                                        pageId = pageId,
                                        base = ReaderImageAsset.TileLayer(grid = grid, overviewKey = overviewKey),
                                        target = targetLayer,
                                        tileStore = actualTileManager,
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
                } finally {
                    // A pending "loading" must never land after the page resolved, failed, or was
                    // cancelled - including the early returns above.
                    loadingPublish.cancel()
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
    /**
     * Brings every tiled page's ladder in line with the camera.
     *
     * Replaces the old per-settle re-plan (which asked [DecodePlanner] for each cached page on every
     * settle and rebuilt the base grid whenever the answer stepped a level): the level is now a pure
     * function of the page size, the viewport and the camera, so a layer is only built, replaced or
     * dropped when that level actually changes. Same intent, without rebuilding grids under a moving
     * camera - the experiment that did rebuild per frame launched 26k decodes for zero frame gain.
     */
    private fun updateTileLadders(scale: Float) {
        val vpWidth = viewportSizeProvider().width.coerceAtLeast(100)
        for ((pageId, asset) in cachedAssets) {
            if (asset !is ReaderImageAsset.Tiled) continue
            val currentSampleSize = (asset.target ?: asset.base).sampleSize
            val ladder = resolveTileLadder(
                sourceContentWidthPx = asset.grid.pageSize.width,
                viewportWidthPx = vpWidth,
                cameraScale = scale,
                currentSampleSize = currentSampleSize,
            )
            var updated = asset

            if (asset.base.sampleSize != ladder.baseSampleSize) {
                actualTileManager.releaseTiles { it.pageId == pageId && it.sampleSize == asset.base.sampleSize }
                updated = updated.copy(
                    base = ReaderImageAsset.TileLayer(
                        grid = asset.grid.withSampleSize(ladder.baseSampleSize),
                        overviewKey = asset.base.overviewKey,
                    ),
                )
            }

            if (updated.target?.sampleSize != ladder.targetSampleSize) {
                updated.target?.let { stale ->
                    actualTileManager.releaseTiles { it.pageId == pageId && it.sampleSize == stale.sampleSize }
                }
                updated = updated.copy(
                    target = ladder.targetSampleSize?.let { targetSampleSize ->
                        ReaderImageAsset.TileLayer(
                            grid = asset.grid.withSampleSize(targetSampleSize),
                        )
                    },
                )
            }

            if (updated !== asset) {
                cachedAssets[pageId] = updated
                updateAssets { it + (pageId to updated) }
            }
        }
    }

    fun onCameraSettled(snapshot: ReaderCameraSnapshot, scene: ReaderScene? = null) {
        val scale = snapshot.scale
        val visibleBounds = snapshot.visibleBoundsInScene
        // The camera feeds the next decode plan (sampled pages re-acquire at their own LOD) and the
        // tile ladder, so a zoomed page is painted from its own level instead of the fit one.
        cameraScale = scale
        updateTileLadders(scale)

        // Sharp tiles for the magnified band start decoding at settle instead of waiting for the next
        // debounced visible-frame tick. Both layers are requested for every visible frame anyway
        // (KototoroImagePipelineAdapter.requestTiles), so this only shortens the first sharp frame.
        val targetLayers = cachedAssets.mapNotNull { (pageId, asset) ->
            (asset as? ReaderImageAsset.Tiled)?.target?.let { pageId to it }
        }
        if (targetLayers.isNotEmpty() && scene != null) {
            for ((pageId, target) in targetLayers) {
                val pageSceneBounds = scene.pageGeometries.firstOrNull { it.pageId == pageId }?.sceneBounds
                    ?: continue
                if (pageSceneBounds.width <= 0f || pageSceneBounds.height <= 0f) continue
                val intersection = pageSceneBounds.intersectionOrNull(visibleBounds) ?: continue
                actualTileManager.requestTiles(target.grid, intersection.toPageLogical(pageSceneBounds, target.grid))
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

/** Same grid at another level: the lattice, geometry and gutters describe the page, not the LOD. */
private fun TileGrid.withSampleSize(sampleSize: Int): TileGrid = TileGrid(
    pageId = pageId,
    geometry = geometry,
    split = split,
    tileDimension = tileDimension,
    sampleSize = sampleSize,
    outputGutterPx = outputGutterPx,
    seamPaddingPx = seamPaddingPx,
)

/** Maps a scene-space rectangle into page-logical pixels of [grid]. */
private fun FloatRect.toPageLogical(sceneBounds: FloatRect, grid: TileGrid): IntRect {
    val scaleX = grid.pageSize.width.toFloat() / sceneBounds.width
    val scaleY = grid.pageSize.height.toFloat() / sceneBounds.height
    return IntRect(
        left = ((left - sceneBounds.left) * scaleX).toInt().coerceIn(0, grid.pageSize.width),
        top = ((top - sceneBounds.top) * scaleY).toInt().coerceIn(0, grid.pageSize.height),
        right = kotlin.math.ceil((right - sceneBounds.left) * scaleX).toInt().coerceIn(0, grid.pageSize.width),
        bottom = kotlin.math.ceil((bottom - sceneBounds.top) * scaleY).toInt().coerceIn(0, grid.pageSize.height),
    )
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
 * How long an acquisition may run before it reports itself as loading.
 *
 * Warm decodes (memory-cache hits) finish well inside this window, so they never flash a loading
 * overlay over a page that is already there; slow pages still get their indicator, just not before
 * the wait is worth showing.
 */
private const val LOADING_STATE_DELAY_MS = 120L

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
