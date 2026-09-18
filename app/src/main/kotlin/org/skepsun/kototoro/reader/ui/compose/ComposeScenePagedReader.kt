package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import org.skepsun.kototoro.reader.core.PagedPanBoundsResolver
import org.skepsun.kototoro.reader.core.PagedSlot
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PageSegment
import org.skepsun.kototoro.reader.core.PagedPageSpec
import org.skepsun.kototoro.reader.core.PagedReaderScene
import org.skepsun.kototoro.reader.core.PagedDragState
import org.skepsun.kototoro.reader.core.PagedMotionSnapshot
import org.skepsun.kototoro.reader.core.PagedSpreadConfig
import org.skepsun.kototoro.reader.core.PagedTransitionResolver
import org.skepsun.kototoro.reader.core.PrefetchPriority
import org.skepsun.kototoro.reader.core.PrefetchReadiness
import org.skepsun.kototoro.reader.core.PrefetchRequest
import org.skepsun.kototoro.reader.core.ReaderResourceWindow
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.SpreadBehavior
import org.skepsun.kototoro.reader.core.VisibleNode
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.ReaderImageLoadState
import org.skepsun.kototoro.reader.image.ReaderImagePipeline
import org.skepsun.kototoro.reader.render.compose.AnimatedDrawBridge
import org.skepsun.kototoro.reader.render.compose.PageSeamPolicy
import org.skepsun.kototoro.reader.render.compose.SceneImagePresentationCoordinator
import org.skepsun.kototoro.reader.render.compose.ScenePageTransform
import org.skepsun.kototoro.reader.render.compose.ScenePageTransitionRenderer
import org.skepsun.kototoro.reader.render.compose.ScenePageTransition
import org.skepsun.kototoro.reader.render.compose.animatedDrawBridge
import org.skepsun.kototoro.reader.render.compose.drawFrameNodes
import org.skepsun.kototoro.reader.render.compose.rememberComposeScenePrimaryScrollState
import org.skepsun.kototoro.reader.render.compose.resolveScenePageTransition
import org.skepsun.kototoro.reader.render.compose.tileDrawBridge
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val PAGED_PULL_THRESHOLD_FRACTION = 0.18f

/** Quiet period after the camera stops moving before multi-LOD replacement is requested. */
private const val CAMERA_SETTLE_DEBOUNCE_MS = 150L

/**
 * Identity of one camera resting position, used to debounce settle work without keying a
 * [LaunchedEffect] on values that change every frame.
 */
private data class CameraSettleKey(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scrollOffset: Float,
)

internal fun createInitialPagedPageSpecs(
    pages: List<ReaderPage>,
    adapter: KototoroImagePipelineAdapter? = null,
    defaultRatio: Float = 0.707f,
): List<PagedPageSpec> {
    return pages.map { page ->
        val pageId = PageId(page.readerKey)
        val cachedDims = adapter?.probeCachedDimensions(pageId)
        val hint = if (cachedDims != null) {
            PageGeometryHint.Exact(cachedDims.width, cachedDims.height)
        } else {
            PageGeometryHint.Estimated(ratio = defaultRatio)
        }
        val segment = when (page.split) {
            ReaderPageSplit.NONE -> PageSegment.FULL
            ReaderPageSplit.LEFT -> PageSegment.LEFT_HALF
            ReaderPageSplit.RIGHT -> PageSegment.RIGHT_HALF
        }
        PagedPageSpec(
            pageId = pageId,
            geometryHint = hint,
            chapterId = page.chapterId,
            chapterPageIndex = page.index,
            segment = segment,
            spreadBehavior = SpreadBehavior.AUTO,
        )
    }
}

/**
 * Modern Paged Scene Reader Composable driven by the decoupled Reader Scene Engine (ADR 0002 Phase 3C).
 *
 * Unifies Single-Page (LTR, RTL Manga, Vertical Paged) and Double-Page Spreads:
 * - Mathematical slot resolution via [PagedReaderScene] with zero Compose layout overhead.
 * - Draw-phase rendering via [drawFrameNodes], bypassing Composition and Layout on drag & snap.
 * - [PagedDragState] for content-pan handoff and velocity- and threshold-aware slot snapping.
 * - Full resource windowing via [KototoroImagePipelineAdapter] (slot-scoped lookahead & eviction).
 * - High-precision pinch-to-zoom (1x..5x) and single-slot pan.
 * - Pull gestures for previous/next chapter boundaries.
 */
@Composable
fun ComposeScenePagedReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    isDoublePage: Boolean = false,
    coverPage: Boolean = false,
    readingDirection: SceneReadingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    onPagesChanged: (lowerKey: Long, upperKey: Long, activeKey: Long) -> Unit,
    requestedPage: Int? = null,
    requestedPageSmooth: Boolean = false,
    zoomCommand: ComposeReaderZoomCommand? = null,
    isZoomEnabled: Boolean = true,
    defaultScale: Float = 1f,
    isPullGestureEnabled: Boolean = false,
    canGoPreviousChapter: Boolean = true,
    canGoNextChapter: Boolean = true,
    onPullChapter: (delta: Int) -> Unit = {},
    onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
    onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
    resolveErrorStringId: (Throwable) -> Int = ExceptionResolver::getResolveStringId,
    isAnimationEnabled: Boolean = true,
    pageAnimation: ReaderAnimation = ReaderAnimation.DEFAULT,
    readerBackground: ReaderBackground = ReaderBackground.BLACK,
    readerBackgroundColor: Int = android.graphics.Color.BLACK,
    bookBackgroundTint: Int? = null,
    zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
    isReaderOptimizationEnabled: Boolean = false,
    isPreloadReductionEnabled: Boolean = false,
    isCropEnabled: Boolean = false,
    bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    imageColorFilter: ColorFilter? = null,
    pageOverlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val pageMap = remember(pages) { pages.associateBy { it.readerKey } }
    val currentPageMap = rememberUpdatedState(pageMap)
    val pageLookup: (PageId) -> ReaderPage? = remember {
        { id -> currentPageMap.value[id.value] }
    }

    val adapter = remember(imagePipeline, imageLoader, isCropEnabled, bitmapConfig, isReaderOptimizationEnabled) {
        KototoroImagePipelineAdapter(
            context = context,
            composePipeline = imagePipeline,
            imageLoader = imageLoader,
            scope = coroutineScope,
            pageLookup = pageLookup,
            isCropEnabled = isCropEnabled,
            bitmapConfig = bitmapConfig,
            isReaderOptimizationEnabled = isReaderOptimizationEnabled,
            viewportSizeProvider = {
                IntSize(
                    viewportWidthPx.toInt().coerceAtLeast(100),
                    viewportHeightPx.toInt().coerceAtLeast(100),
                )
            },
        )
    }

    val scene = remember(viewportWidthPx, viewportHeightPx, isDoublePage, coverPage, readingDirection, zoomMode) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) null
        else {
            PagedReaderScene(
                viewportWidth = viewportWidthPx.toInt(),
                viewportHeight = viewportHeightPx.toInt(),
                config = PagedSpreadConfig(
                    isDoublePage = isDoublePage,
                    isCoverOffset = coverPage,
                    readingDirection = readingDirection,
                    pageSpacingPx = 0,
                    zoomMode = zoomMode,
                ),
                initialSpecs = createInitialPagedPageSpecs(pages, adapter),
            )
        }
    }

    val animatedBridge = remember { AnimatedDrawBridge(autoUpdateVisiblePages = false) }
    DisposableEffect(animatedBridge) {
        onDispose {
            animatedBridge.stopAll()
        }
    }

    val shouldAnimate = isAnimationEnabled && pageAnimation != ReaderAnimation.NONE

    val primaryExtent = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
    val initialPosition = remember(pages, initialPage) { initialPage.coerceIn(pages.indices) }

    val scrollState = rememberComposeScenePrimaryScrollState(
        initialOffset = 0f,
        maxOffset = Float.MAX_VALUE,
        // Scale modes rebuild placements, but do not change slot navigation coordinates.
        key = remember(viewportWidthPx, viewportHeightPx, isDoublePage, coverPage, readingDirection) { Any() },
    )
    var hasAppliedInitialPosition by remember { mutableStateOf(false) }
    var sceneRevision by remember { mutableLongStateOf(0L) }
    val retainedAssets by adapter.assets.collectAsStateWithLifecycle()

    val transitionStyle = remember(pageAnimation) { resolveScenePageTransition(pageAnimation) }
    val pageCurlState = rememberComposeReaderPageCurlState()
    // Slot the in-flight page transition is anchored on: the slot the current drag started from,
    // or the nearest slot while idle. Cover keeps this page in place; curl folds it away.
    var transitionAnchorSlot by remember { mutableIntStateOf(initialPosition) }

    val autoBgColors = remember { mutableStateMapOf<PageId, Int>() }
    LaunchedEffect(retainedAssets, readerBackground) {
        if (readerBackground != ReaderBackground.AUTO) return@LaunchedEffect
        for ((pageId, asset) in retainedAssets) {
            if (!autoBgColors.containsKey(pageId)) {
                val bmp = when (asset) {
                    is ReaderImageAsset.ComposeImage -> asset.imageBitmap.asAndroidBitmap()
                    is ReaderImageAsset.AndroidBitmap -> asset.bitmap
                    is ReaderImageAsset.Tiled -> asset.overviewKey?.let { asset.tileStore.tile(it)?.payload as? Bitmap }
                    is ReaderImageAsset.Preview -> asset.imageBitmap.asAndroidBitmap()
                    else -> null
                }
                if (bmp != null) {
                    val resolved = withContext(Dispatchers.Default) {
                        ReaderAutoBackground.resolve(bmp)
                    }
                    autoBgColors[pageId] = resolved
                }
            }
        }
    }

    fun resolveSlotBackgroundColor(slot: PagedSlot): Int {
        if (readerBackground != ReaderBackground.AUTO) {
            return resolveScenePagedBackground(readerBackground, readerBackgroundColor, null, bookBackgroundTint)
        }
        val placements = slot.placements
        if (placements.isEmpty()) {
            return resolveScenePagedBackground(readerBackground, readerBackgroundColor, null, bookBackgroundTint)
        }
        val firstAuto = autoBgColors[placements.first().pageId]
        val secondAuto = placements.getOrNull(1)?.let { autoBgColors[it.pageId] }
        val mergedAuto = resolveDoublePageBackground(
            background = readerBackground,
            configuredColor = readerBackgroundColor,
            firstAutoColor = firstAuto,
            secondAutoColor = secondAuto,
        ).takeIf { firstAuto != null || secondAuto != null }
        return resolveScenePagedBackground(readerBackground, readerBackgroundColor, mergedAuto, bookBackgroundTint)
    }

    val fallbackBackgroundColor = remember(readerBackground, readerBackgroundColor, bookBackgroundTint) {
        resolveScenePagedBackground(readerBackground, readerBackgroundColor, null, bookBackgroundTint)
    }

    var lastReportedPages by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var statusPageId by remember { mutableStateOf(PageId(pages[initialPosition].readerKey)) }

    var pullStartDistancePx by remember { mutableFloatStateOf(0f) }
    var pullEndDistancePx by remember { mutableFloatStateOf(0f) }
    val pullThresholdPx = primaryExtent * PAGED_PULL_THRESHOLD_FRACTION

    var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(1f, 5f)) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }
    var zoomedSlotIndex by remember { mutableIntStateOf(0) }

    var snapAnimationJob by remember { mutableStateOf<Job?>(null) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var canvasFlingJob by remember { mutableStateOf<Job?>(null) }
    val flingDecay = remember { FloatExponentialDecaySpec() }

    fun getSlotContentBounds(slotIndex: Int): FloatRect? {
        val currentScene = scene ?: return null
        val slot = currentScene.allSlots.getOrNull(slotIndex) ?: return null
        val placements = slot.placements
        if (placements.isEmpty()) return null
        return FloatRect(
            left = placements.minOf { it.boundsInSlot.left },
            top = placements.minOf { it.boundsInSlot.top },
            right = placements.maxOf { it.boundsInSlot.right },
            bottom = placements.maxOf { it.boundsInSlot.bottom },
        )
    }

    fun getSlotPanRanges(slotIndex: Int, scale: Float): Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>> {
        val bounds = getSlotContentBounds(slotIndex)
        val contentLeft = bounds?.left ?: 0f
        val contentRight = bounds?.right ?: viewportWidthPx
        val contentTop = bounds?.top ?: 0f
        val contentBottom = bounds?.bottom ?: viewportHeightPx

        val panX = PagedPanBoundsResolver.resolvePanRange(
            contentMin = contentLeft,
            contentMax = contentRight,
            viewportSize = viewportWidthPx,
            scale = scale,
        )
        val panY = PagedPanBoundsResolver.resolvePanRange(
            contentMin = contentTop,
            contentMax = contentBottom,
            viewportSize = viewportHeightPx,
            scale = scale,
        )
        return panX to panY
    }

    /**
     * Whether single-pointer dragging at the current transform is owned by the content canvas
     * instead of page navigation — i.e. the content is enlarged and has room to pan.
     *
     * Mirrors the legacy reader where enlarged content pans with inertia on release: the canvas
     * owns the drag whenever there is pan room in any direction — from user zoom (scale > 1) or
     * from the layout itself overflowing the viewport at baseline scale (FIT_HEIGHT / FIT_WIDTH
     * widen or heighten content past the screen, native-size KEEP_START surpasses it). Fitting
     * pages resolve degenerate ranges and keep plain swipe-to-flip with no canvas inertia.
     */
    fun isSlotPannable(slotIndex: Int, scale: Float): Boolean {
        if (scale > 1f) return true
        val (panX, panY) = getSlotPanRanges(slotIndex, scale)
        return panX.endInclusive > panX.start + 0.5f || panY.endInclusive > panY.start + 0.5f
    }

    fun resolveSlotInitialOffsets(slotIndex: Int): Pair<Float, Float> {
        val bounds = getSlotContentBounds(slotIndex) ?: return 0f to 0f
        val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
        val initX = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = bounds.left,
            contentMax = bounds.right,
            viewportSize = viewportWidthPx,
            scale = 1f,
            isStartReversed = isRtl,
        )
        val initY = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = bounds.top,
            contentMax = bounds.bottom,
            viewportSize = viewportHeightPx,
            scale = 1f,
            isStartReversed = false,
        )
        return initX to initY
    }

    val slotZoomMap = remember(zoomMode) { HashMap<PageId, PagedSlotZoom>() }

    var appliedZoomMode by remember { mutableStateOf(zoomMode) }
    LaunchedEffect(zoomMode, scene) {
        if (scene == null || appliedZoomMode == zoomMode) return@LaunchedEffect
        snapAnimationJob?.cancel()
        zoomAnimationJob?.cancel()
        canvasFlingJob?.cancel()
        val currentSlot = (scrollState.offset / primaryExtent.coerceAtLeast(1f)).roundToInt()
            .coerceIn(0, (scene.slotCount - 1).coerceAtLeast(0))
        canvasScale = 1f
        val (initialX, initialY) = resolveSlotInitialOffsets(currentSlot)
        canvasOffsetX = initialX
        canvasOffsetY = initialY
        zoomedSlotIndex = currentSlot
        transitionAnchorSlot = currentSlot
        scrollState.snapTo(currentSlot * primaryExtent)
        scrollState.isDragging = false
        scrollState.isFlinging = false
        pullStartDistancePx = 0f
        pullEndDistancePx = 0f
        appliedZoomMode = zoomMode
    }

    fun saveSlotZoom(slotIndex: Int, scale: Float, offsetX: Float, offsetY: Float) {
        val currentScene = scene ?: return
        val key = currentScene.allSlots.getOrNull(slotIndex)?.progressAnchorPageId ?: return
        slotZoomMap[key] = PagedSlotZoom(scale, offsetX, offsetY)
    }

    fun getSlotZoom(slotIndex: Int): PagedSlotZoom? {
        val currentScene = scene ?: return null
        val key = currentScene.allSlots.getOrNull(slotIndex)?.progressAnchorPageId ?: return null
        return slotZoomMap[key]
    }

    fun resolveSlotTransform(slotIndex: Int): PagedSlotZoom {
        if (slotIndex == zoomedSlotIndex) return PagedSlotZoom(canvasScale, canvasOffsetX, canvasOffsetY)
        val saved = getSlotZoom(slotIndex)
        if (saved != null) {
            val (panX, panY) = getSlotPanRanges(slotIndex, saved.scale)
            return saved.copy(offsetX = saved.offsetX.coerceIn(panX), offsetY = saved.offsetY.coerceIn(panY))
        }
        val (initialX, initialY) = resolveSlotInitialOffsets(slotIndex)
        return PagedSlotZoom(1f, initialX, initialY)
    }

    fun updateResourceWindow(
        currentScene: PagedReaderScene,
        currentOffset: Float,
    ) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
        val pe = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
        if (pe <= 0f) return

        val vp = if (readingDirection.isHorizontal) {
            ReaderViewport(FloatRect.fromLtwh(currentOffset, 0f, viewportWidthPx, viewportHeightPx))
        } else {
            ReaderViewport(FloatRect.fromLtwh(0f, currentOffset, viewportWidthPx, viewportHeightPx))
        }
        val frame = currentScene.resolve(vp)
        val progress = frame.progress
        val activeId = progress.activePageId
        if (activeId != null) {
            statusPageId = activeId
        }
        val lowerId = progress.lowerPageId
        val upperId = progress.upperPageId

        val activeSlotIndex = (currentOffset / pe).roundToInt().coerceIn(0, (currentScene.slotCount - 1).coerceAtLeast(0))
        val requests = mutableListOf<PrefetchRequest>()

        // 1. Current slot pages: IMMEDIATE presentation
        val activeSlot = currentScene.allSlots.getOrNull(activeSlotIndex)
        activeSlot?.pageIds?.forEach { id ->
            requests.add(PrefetchRequest(id, PrefetchPriority.IMMEDIATE, PrefetchReadiness.PRESENTATION_READY))
        }

        // 2. Additional visible nodes in frame: HIGH presentation
        frame.visibleNodes.forEach { node ->
            if (requests.none { it.pageId == node.pageId }) {
                requests.add(PrefetchRequest(node.pageId, PrefetchPriority.HIGH, PrefetchReadiness.PRESENTATION_READY))
            }
        }

        // 3. Lookahead slots: SOURCE_READY prefetch
        val lookaheadSlots = if (isPreloadReductionEnabled) 1 else 2
        for (step in 1..lookaheadSlots) {
            currentScene.allSlots.getOrNull(activeSlotIndex - step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.MEDIUM, PrefetchReadiness.SOURCE_READY))
                }
            }
            currentScene.allSlots.getOrNull(activeSlotIndex + step)?.pageIds?.forEach { id ->
                if (requests.none { it.pageId == id }) {
                    requests.add(PrefetchRequest(id, PrefetchPriority.HIGH, PrefetchReadiness.SOURCE_READY))
                }
            }
        }
        adapter.updateResourceWindow(ReaderResourceWindow(requests))

        // 4. For visible Tiled pages, request intersecting lattice tiles
        val contentNodes = currentScene.allSlots
            .filter { it.bounds.intersects(vp.bounds) }
            .flatMap { slot ->
                val transform = resolveSlotTransform(slot.slotIndex)
                slot.visibleContentNodes(transform.scale, transform.offsetX, transform.offsetY)
            }
        SceneImagePresentationCoordinator.coordinateVisibleTiles(
            frame.copy(visibleNodes = contentNodes), retainedAssets, adapter,
        )
    }

    LaunchedEffect(scrollState.offset, scene, retainedAssets) {
        if (scene != null) {
            updateResourceWindow(scene, scrollState.offset)
        }
    }

    // Settle-only page reporting: prevents mid-gesture chapter reload collisions
    LaunchedEffect(hasAppliedInitialPosition, scene) {
        if (!hasAppliedInitialPosition || scene == null) return@LaunchedEffect
        snapshotFlow {
            val isIdle = !scrollState.isDragging && !scrollState.isFlinging
            if (isIdle) Pair(scrollState.offset, sceneRevision) else null
        }
            .filterNotNull()
            .mapNotNull { (offset, _) ->
                if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return@mapNotNull null
                val vp = if (readingDirection.isHorizontal) {
                    ReaderViewport(FloatRect.fromLtwh(offset, 0f, viewportWidthPx, viewportHeightPx))
                } else {
                    ReaderViewport(FloatRect.fromLtwh(0f, offset, viewportWidthPx, viewportHeightPx))
                }
                val progress = scene.resolve(vp).progress
                val lowerId = progress.lowerPageId ?: return@mapNotNull null
                val upperId = progress.upperPageId ?: return@mapNotNull null
                val activeId = progress.activePageId ?: return@mapNotNull null
                Triple(lowerId.value, upperId.value, activeId.value)
            }
            .distinctUntilChanged()
            .collect { (lowerKey, upperKey, activeKey) ->
                lastReportedPages = Triple(lowerKey, upperKey, activeKey)
                onPagesChanged(lowerKey, upperKey, activeKey)
            }
    }

    // 150ms Zoom settle signal to trigger multi-LOD progressive replacement.
    // The camera is a snapshotFlow with a real debounce rather than effect keys, because keying on
    // a scroll offset restarts (cancels and relaunches) the coroutine on every frame of a turn,
    // which is pure overhead next to the settle work this exists to perform.
    LaunchedEffect(scene, sceneRevision) {
        if (scene == null) return@LaunchedEffect
        snapshotFlow { CameraSettleKey(canvasScale, canvasOffsetX, canvasOffsetY, scrollState.offset) }
            .debounce(CAMERA_SETTLE_DEBOUNCE_MS)
            .collect {
                if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return@collect
                val slot = scene.allSlots.getOrNull(zoomedSlotIndex) ?: return@collect
                val visibleBounds = slot.contentViewport(canvasScale, canvasOffsetX, canvasOffsetY)
                // Decode LOD is relative to fit-to-screen, whereas canvasScale is relative
                // to the selected layout. Native-size and fill modes can already exceed fit.
                val layoutScale = slot.placements.maxOfOrNull {
                    maxOf(it.boundsInSlot.width / viewportWidthPx, it.boundsInSlot.height / viewportHeightPx)
                } ?: 1f
                adapter.onCameraSettled(
                    SceneImagePresentationCoordinator.createCameraSnapshot(canvasScale * layoutScale, visibleBounds),
                    scene = scene,
                )
                val frame = scene.resolve(ReaderViewport(slot.bounds))
                SceneImagePresentationCoordinator.coordinateVisibleTiles(
                    frame.copy(visibleNodes = slot.visibleContentNodes(canvasScale, canvasOffsetX, canvasOffsetY)),
                    retainedAssets, adapter,
                )
            }
    }

    // Zero-CLS anchor compensation on image decode
    DisposableEffect(adapter, scene) {
        if (scene != null) {
            adapter.onAssetLoaded = { pageId, asset ->
                val exactSize = when (asset) {
                    is ReaderImageAsset.ComposeImage -> IntSize(asset.imageBitmap.width, asset.imageBitmap.height)
                    is ReaderImageAsset.AndroidBitmap -> IntSize(asset.bitmap.width, asset.bitmap.height)
                    is ReaderImageAsset.Animated -> IntSize(asset.width, asset.height)
                    is ReaderImageAsset.Tiled -> asset.grid.pageSize
                    else -> null
                }
                if (exactSize != null) {
                    val vp = if (readingDirection.isHorizontal) {
                        ReaderViewport(FloatRect.fromLtwh(scrollState.offset, 0f, viewportWidthPx, viewportHeightPx))
                    } else {
                        ReaderViewport(FloatRect.fromLtwh(0f, scrollState.offset, viewportWidthPx, viewportHeightPx))
                    }
                    val compensation = scene.updatePageHint(
                        pageId = pageId,
                        newHint = PageGeometryHint.Exact(exactSize.width, exactSize.height),
                        currentViewport = vp,
                    )
                    sceneRevision = scene.revision
                    if (viewportWidthPx > 0f && viewportHeightPx > 0f) {
                        val pe = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
                        scrollState.maxOffset = ((scene.slotCount - 1) * pe).coerceAtLeast(0f)
                    }
                    if (compensation != null) {
                        val delta = if (readingDirection.isHorizontal) compensation.deltaX else compensation.deltaY
                        if (delta != 0f) {
                            scrollState.snapBy(delta)
                        }
                    }
                    updateResourceWindow(scene, scrollState.offset)
                }
            }
        }
        onDispose {
            adapter.onAssetLoaded = null
        }
    }

    // Initial positioning
    LaunchedEffect(scene, viewportWidthPx, viewportHeightPx) {
        if (scene != null && viewportWidthPx > 0f && viewportHeightPx > 0f && !hasAppliedInitialPosition) {
            val pe = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
            val initialPos = initialPage.coerceIn(pages.indices)
            val targetPage = pages.getOrNull(initialPos)
            val initialSlot = if (targetPage != null) {
                scene.slotIndexOf(PageId(targetPage.readerKey)).coerceAtLeast(0)
            } else 0
            val maxScroll = ((scene.slotCount - 1) * pe).coerceAtLeast(0f)
            scrollState.maxOffset = maxScroll
            val targetScroll = (initialSlot * pe).coerceIn(0f, maxScroll)
            scrollState.snapTo(targetScroll)
            val (initX, initY) = resolveSlotInitialOffsets(initialSlot)
            canvasOffsetX = initX
            canvasOffsetY = initY
            zoomedSlotIndex = initialSlot
            sceneRevision = scene.revision
            hasAppliedInitialPosition = true
            updateResourceWindow(scene, targetScroll)
        }
    }

    // Page updates
    LaunchedEffect(pages, scene) {
        if (scene != null && hasAppliedInitialPosition) {
            val pe = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
            val currentVp = if (readingDirection.isHorizontal) {
                ReaderViewport(FloatRect.fromLtwh(scrollState.offset, 0f, viewportWidthPx, viewportHeightPx))
            } else {
                ReaderViewport(FloatRect.fromLtwh(0f, scrollState.offset, viewportWidthPx, viewportHeightPx))
            }
            val newSpecs = createInitialPagedPageSpecs(pages, adapter)
            val compensation = scene.updatePagedPages(newSpecs, currentVp)
            sceneRevision = scene.revision
            if (pe > 0f) {
                scrollState.maxOffset = ((scene.slotCount - 1) * pe).coerceAtLeast(0f)
            }
            if (compensation != null) {
                val delta = if (readingDirection.isHorizontal) compensation.deltaX else compensation.deltaY
                if (delta != 0f) {
                    scrollState.snapBy(delta)
                }
            } else if (initialPosition in pages.indices) {
                val targetPage = pages[initialPosition]
                val targetSlot = scene.slotIndexOf(PageId(targetPage.readerKey))
                if (targetSlot >= 0 && pe > 0f) {
                    val targetOffset = (targetSlot * pe).coerceIn(0f, scrollState.maxOffset)
                    scrollState.snapTo(targetOffset)
                }
            }
            updateResourceWindow(scene, scrollState.offset)
        }
    }

    // Programmatic page request
    var previousRequestedPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(requestedPage, scene) {
        if (requestedPage != null && requestedPage != previousRequestedPage && scene != null && primaryExtent > 0f) {
            previousRequestedPage = requestedPage
            val targetPage = pages.getOrNull(requestedPage)
            if (targetPage != null) {
                val targetSlot = scene.slotIndexOf(PageId(targetPage.readerKey))
                if (targetSlot >= 0) {
                    val targetOffset = (targetSlot * primaryExtent).coerceIn(0f, scrollState.maxOffset)
                    saveSlotZoom(zoomedSlotIndex, canvasScale, canvasOffsetX, canvasOffsetY)
                    val updateToTargetSlot = {
                        val saved = getSlotZoom(targetSlot)
                        if (saved != null) {
                            val (panX, panY) = getSlotPanRanges(targetSlot, saved.scale)
                            canvasScale = saved.scale
                            canvasOffsetX = saved.offsetX.coerceIn(panX)
                            canvasOffsetY = saved.offsetY.coerceIn(panY)
                        } else {
                            val (initX, initY) = resolveSlotInitialOffsets(targetSlot)
                            canvasScale = 1f
                            canvasOffsetX = initX
                            canvasOffsetY = initY
                        }
                        zoomedSlotIndex = targetSlot
                    }
                    if (requestedPageSmooth && shouldAnimate) {
                        snapAnimationJob?.cancel()
                        snapAnimationJob = coroutineScope.launch {
                            scrollState.isFlinging = true
                            try {
                                animate(
                                    initialValue = scrollState.offset,
                                    targetValue = targetOffset,
                                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                ) { value, _ ->
                                    scrollState.snapTo(value)
                                    updateResourceWindow(scene, value)
                                }
                                updateToTargetSlot()
                            } finally {
                                scrollState.isFlinging = false
                            }
                        }
                    } else {
                        scrollState.snapTo(targetOffset)
                        updateResourceWindow(scene, targetOffset)
                        updateToTargetSlot()
                    }
                }
            }
        }
    }

    // Programmatic zoom command
    LaunchedEffect(zoomCommand, scene) {
        val command = zoomCommand ?: return@LaunchedEffect
        if (pages.none { it.readerKey == command.pageKey }) return@LaunchedEffect
        val currentSlot = if (primaryExtent > 0f && scene != null && scene.slotCount > 0) {
            (scrollState.offset / primaryExtent).roundToInt().coerceIn(0, scene.slotCount - 1)
        } else 0
        zoomedSlotIndex = currentSlot
        val targetScale = (canvasScale * command.factor).coerceIn(1f, 5f)
        zoomAnimationJob?.cancel()
        if (shouldAnimate) {
            zoomAnimationJob = coroutineScope.launch {
                animate(
                    initialValue = canvasScale,
                    targetValue = targetScale,
                    animationSpec = tween(200),
                ) { value, _ ->
                    canvasScale = value
                    if (value <= 1f) {
                        val (initX, initY) = resolveSlotInitialOffsets(currentSlot)
                        canvasOffsetX = initX
                        canvasOffsetY = initY
                    }
                    saveSlotZoom(currentSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                }
            }
        } else {
            canvasScale = targetScale
            if (targetScale <= 1f) {
                val (initX, initY) = resolveSlotInitialOffsets(currentSlot)
                canvasOffsetX = initX
                canvasOffsetY = initY
            }
            saveSlotZoom(currentSlot, canvasScale, canvasOffsetX, canvasOffsetY)
        }
    }

    fun handlePull(dragDelta: Float) {
        if (!isPullGestureEnabled || primaryExtent <= 0f) return
        if (scrollState.offset <= 0f && dragDelta < 0f) {
            pullStartDistancePx = (pullStartDistancePx - dragDelta).coerceAtMost(primaryExtent)
            pullEndDistancePx = 0f
        } else if (scrollState.offset >= scrollState.maxOffset && dragDelta > 0f) {
            pullEndDistancePx = (pullEndDistancePx + dragDelta).coerceAtMost(primaryExtent)
            pullStartDistancePx = 0f
        }
    }

    fun handleReleasePull() {
        if (!isPullGestureEnabled) return
        if (pullStartDistancePx >= pullThresholdPx) {
            if (canGoPreviousChapter) onPullChapter(-1)
        } else if (pullEndDistancePx >= pullThresholdPx) {
            if (canGoNextChapter) onPullChapter(1)
        }
        pullStartDistancePx = 0f
        pullEndDistancePx = 0f
    }

    val doubleTapSlop = remember(context) {
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .tileDrawBridge(adapter.tileStore)
            .animatedDrawBridge(animatedBridge)
            .trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION)
            .background(Color(fallbackBackgroundColor))
            .onSizeChanged { size ->
                viewportWidthPx = size.width.toFloat()
                viewportHeightPx = size.height.toFloat()
            }
            .pointerInput(isZoomEnabled, readingDirection, scene) {
                var lastTapUpAt = 0L
                var lastTapPosition: Offset? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    snapAnimationJob?.cancel()
                    canvasFlingJob?.cancel()
                    pullStartDistancePx = 0f
                    pullEndDistancePx = 0f
                    val pe = primaryExtent.coerceAtLeast(1f)
                    val startOffset = scrollState.offset
                    val currentSlot = (startOffset / pe).roundToInt().coerceIn(0, (scene?.slotCount ?: 1) - 1)
                    if (currentSlot != zoomedSlotIndex) {
                        saveSlotZoom(zoomedSlotIndex, canvasScale, canvasOffsetX, canvasOffsetY)
                        val saved = getSlotZoom(currentSlot)
                        if (saved != null) {
                            val (panX, panY) = getSlotPanRanges(currentSlot, saved.scale)
                            canvasScale = saved.scale
                            canvasOffsetX = saved.offsetX.coerceIn(panX)
                            canvasOffsetY = saved.offsetY.coerceIn(panY)
                        } else {
                            canvasScale = 1f
                            val (newInitX, newInitY) = resolveSlotInitialOffsets(currentSlot)
                            canvasOffsetX = newInitX
                            canvasOffsetY = newInitY
                        }
                        zoomedSlotIndex = currentSlot
                    }
                    val initialSlot = currentSlot

                    val isDoubleTap = isZoomEnabled && isTapGridDoubleTapCandidate(
                        previousPosition = lastTapPosition,
                        previousTapAt = lastTapUpAt,
                        position = down.position,
                        now = down.uptimeMillis,
                        minTimeMillis = viewConfiguration.doubleTapMinTimeMillis,
                        timeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
                        doubleTapSlop = doubleTapSlop,
                    )

                    if (isDoubleTap) {
                        down.consume()
                        lastTapPosition = null
                        lastTapUpAt = 0L
                        zoomedSlotIndex = initialSlot
                        val targetScale = if (canvasScale > 1f) 1f else 2.5f
                        val startScale = canvasScale
                        val (startPanX, startPanY) = canvasOffsetX to canvasOffsetY
                        val (targetPanRangeX, targetPanRangeY) = getSlotPanRanges(initialSlot, targetScale)
                        val center = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f)
                        val factor = if (startScale > 0f) targetScale / startScale else 1f
                        val focusedTranslation = (down.position - center) * (1f - factor)
                        val targetOffsetX = if (targetScale <= 1f) {
                            resolveSlotInitialOffsets(initialSlot).first
                        } else {
                            (startPanX * factor + focusedTranslation.x).coerceIn(targetPanRangeX)
                        }
                        val targetOffsetY = if (targetScale <= 1f) {
                            resolveSlotInitialOffsets(initialSlot).second
                        } else {
                            (startPanY * factor + focusedTranslation.y).coerceIn(targetPanRangeY)
                        }

                        coroutineScope.launch {
                            if (shouldAnimate) {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(200),
                                ) { progress, _ ->
                                    val curScale = startScale + (targetScale - startScale) * progress
                                    canvasScale = curScale
                                    val (curPanX, curPanY) = getSlotPanRanges(initialSlot, curScale)
                                    canvasOffsetX = (startPanX + (targetOffsetX - startPanX) * progress).coerceIn(curPanX)
                                    canvasOffsetY = (startPanY + (targetOffsetY - startPanY) * progress).coerceIn(curPanY)
                                    saveSlotZoom(initialSlot, curScale, canvasOffsetX, canvasOffsetY)
                                }
                            } else {
                                canvasScale = targetScale
                                canvasOffsetX = targetOffsetX
                                canvasOffsetY = targetOffsetY
                                saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                            }
                        }
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        return@awaitEachGesture
                    }

                    val pageVelocityTracker = VelocityTracker()
                    val canvasVelocityTracker = VelocityTracker()
                    canvasVelocityTracker.addPosition(down.uptimeMillis, down.position)
                    var velocityPosition = 0f
                    val dragState = PagedDragState(readingDirection)
                    var isZoomGesture = false
                    var moved = false
                    var eventTime = down.uptimeMillis

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.maxByOrNull { it.uptimeMillis }?.let { eventTime = it.uptimeMillis }
                        val pressedCount = event.changes.count { it.pressed }
                        if (isZoomEnabled && pressedCount >= 2) {
                            moved = true
                            if (!isZoomGesture) {
                                dragState.cancel()
                                pageVelocityTracker.resetTracking()
                                canvasVelocityTracker.resetTracking()
                                scrollState.isDragging = false
                                scrollState.snapTo((initialSlot * pe).coerceIn(0f, scrollState.maxOffset))
                                scene?.let { updateResourceWindow(it, scrollState.offset) }
                                pullStartDistancePx = 0f
                                pullEndDistancePx = 0f
                            }
                            isZoomGesture = true
                            zoomedSlotIndex = initialSlot
                            event.changes.forEach { it.consume() }
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (pan.x.isFinite() && pan.y.isFinite() && zoom.isFinite()) {
                                val nextScale = (canvasScale * zoom).coerceIn(1f, 5f)
                                canvasScale = nextScale
                                val (nextPanX, nextPanY) = getSlotPanRanges(initialSlot, nextScale)
                                canvasOffsetX = (canvasOffsetX + pan.x).coerceIn(nextPanX)
                                canvasOffsetY = (canvasOffsetY + pan.y).coerceIn(nextPanY)
                                saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                            }
                        } else if (pressedCount == 1 && !isZoomGesture) {
                            val change = event.changes.first { it.pressed }
                            val pan = event.calculatePan()
                            val isVertical = readingDirection.isVertical

                            if (hypot(change.position.x - down.position.x, change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                                moved = true
                            }

                            val (panX, panY) = getSlotPanRanges(initialSlot, canvasScale)
                            val primaryDelta = if (isVertical) pan.y else pan.x
                            val crossDelta = if (isVertical) pan.x else pan.y
                            // Content that overflows the viewport in the reading axis — whether from the
                            // layout fit (FIT_HEIGHT widens a page past the screen, native-size mode
                            // exceeds it) or from user zoom — pans within the page first. The drag only
                            // hands off to page navigation once it reaches the content edge:
                            // dragState.dragBy clamps the in-bounds portion into the pan and converts
                            // the leftover past the pan range into a page turn. Content that fits the
                            // viewport resolves a degenerate pan range, so the whole drag feeds
                            // navigation, preserving plain swipe-to-flip for fit pages.
                            val movement = dragState.dragBy(
                                delta = primaryDelta,
                                contentOffset = if (isVertical) canvasOffsetY else canvasOffsetX,
                                range = if (isVertical) panY else panX,
                            )
                            if (isVertical) {
                                canvasOffsetY = movement.contentOffset
                                if (crossDelta.isFinite()) {
                                    canvasOffsetX = (canvasOffsetX + crossDelta).coerceIn(panX)
                                }
                            } else {
                                canvasOffsetX = movement.contentOffset
                                if (crossDelta.isFinite()) {
                                    canvasOffsetY = (canvasOffsetY + crossDelta).coerceIn(panY)
                                }
                            }
                            if (isSlotPannable(initialSlot, canvasScale)) {
                                saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                            }

                            if (movement.pageDelta != 0f) {
                                // The page the gesture started from anchors cover/curl visuals until
                                // the release snap settles on its target slot.
                                transitionAnchorSlot = initialSlot
                                if (movement.resetVelocity) {
                                    pageVelocityTracker.resetTracking()
                                    velocityPosition = 0f
                                } else {
                                    velocityPosition += movement.pageDelta
                                }
                                pageVelocityTracker.addPosition(change.uptimeMillis, Offset(velocityPosition, 0f))
                                scrollState.isDragging = true
                                val desiredOffset = startOffset + dragState.pageOffset
                                val maxScroll = scrollState.maxOffset
                                val newOffset = desiredOffset.coerceIn(0f, maxScroll)
                                scrollState.snapTo(newOffset)
                                scene?.let { updateResourceWindow(it, newOffset) }
                                pullStartDistancePx = 0f
                                pullEndDistancePx = 0f
                                if (desiredOffset < 0f) {
                                    handlePull(desiredOffset)
                                } else if (desiredOffset > maxScroll) {
                                    handlePull(desiredOffset - maxScroll)
                                }
                            } else if (isSlotPannable(initialSlot, canvasScale)) {
                                canvasVelocityTracker.addPosition(change.uptimeMillis, change.position)
                            }

                            if ((primaryDelta.isFinite() && primaryDelta != 0f) ||
                                (crossDelta.isFinite() && crossDelta != 0f)
                            ) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    val heldTooLong = eventTime - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                    if (!moved && !heldTooLong && !isZoomGesture && dragState.pageOffset == 0f) {
                        lastTapPosition = down.position
                        lastTapUpAt = eventTime
                    } else {
                        lastTapPosition = null
                        lastTapUpAt = 0L
                    }

                    scrollState.isDragging = false
                    handleReleasePull()

                    if (!isZoomGesture && scene != null && scene.slotCount > 0) {
                        if (dragState.pageOffset != 0f) {
                            val targetSlot = dragState.resolveTargetSlot(
                                currentSlot = initialSlot,
                                pageExtent = pe,
                                forwardVelocity = pageVelocityTracker.calculateVelocity().x,
                                totalSlots = scene.slotCount,
                            )
                            val targetOffset = (targetSlot * pe).coerceIn(0f, scrollState.maxOffset)
                            snapAnimationJob = coroutineScope.launch {
                                scrollState.isFlinging = true
                                try {
                                    if (shouldAnimate) {
                                        animate(
                                            initialValue = scrollState.offset,
                                            targetValue = targetOffset,
                                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                        ) { value, _ ->
                                            scrollState.snapTo(value)
                                            updateResourceWindow(scene, value)
                                        }
                                    } else {
                                        scrollState.snapTo(targetOffset)
                                        updateResourceWindow(scene, targetOffset)
                                    }
                                    if (targetSlot != initialSlot) {
                                        saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                                        val saved = getSlotZoom(targetSlot)
                                        if (saved != null) {
                                            val (panX, panY) = getSlotPanRanges(targetSlot, saved.scale)
                                            canvasScale = saved.scale
                                            canvasOffsetX = saved.offsetX.coerceIn(panX)
                                            canvasOffsetY = saved.offsetY.coerceIn(panY)
                                        } else {
                                            canvasScale = 1f
                                            val (newInitX, newInitY) = resolveSlotInitialOffsets(targetSlot)
                                            canvasOffsetX = newInitX
                                            canvasOffsetY = newInitY
                                        }
                                        zoomedSlotIndex = targetSlot
                                        transitionAnchorSlot = targetSlot
                                    }
                                } finally {
                                    scrollState.isFlinging = false
                                }
                            }
                        } else if (isSlotPannable(initialSlot, canvasScale)) {
                            val vel = canvasVelocityTracker.calculateVelocity()
                            if (maxOf(abs(vel.x), abs(vel.y)) >= 50f) {
                                canvasFlingJob = coroutineScope.launch {
                                    val (panRangeX, panRangeY) = getSlotPanRanges(initialSlot, canvasScale)
                                    if (abs(vel.x) >= 50f) {
                                        launch {
                                            animateDecay(canvasOffsetX, vel.x, flingDecay) { value, _ ->
                                                canvasOffsetX = value.coerceIn(panRangeX)
                                                saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                                            }
                                        }
                                    }
                                    if (abs(vel.y) >= 50f) {
                                        launch {
                                            animateDecay(canvasOffsetY, vel.y, flingDecay) { value, _ ->
                                                canvasOffsetY = value.coerceIn(panRangeY)
                                                saveSlotZoom(initialSlot, canvasScale, canvasOffsetX, canvasOffsetY)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (hasAppliedInitialPosition) 1f else 0f
                }
                .drawWithContent {
                    // Header/decode geometry can change after the asset snapshot was published.
                    sceneRevision
                    if (scene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
                        val currentOffset = scrollState.offset
                        val vp = if (readingDirection.isHorizontal) {
                            ReaderViewport(FloatRect.fromLtwh(currentOffset, 0f, viewportWidthPx, viewportHeightPx))
                        } else {
                            ReaderViewport(FloatRect.fromLtwh(0f, currentOffset, viewportWidthPx, viewportHeightPx))
                        }
                        val frame = scene.resolve(vp)

                        val allVisiblePageIds = HashSet<PageId>(frame.visibleNodes.size)
                        for (node in frame.visibleNodes) {
                            allVisiblePageIds.add(node.pageId)
                        }
                        animatedBridge.updateVisiblePages(allVisiblePageIds)

                        val isVerticalAxis = readingDirection.isVertical
                        val isMirrored = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
                        val primaryExtentPx = if (isVerticalAxis) viewportHeightPx else viewportWidthPx
                        val motion = resolveSceneTransitionMotion(
                            currentOffset = currentOffset,
                            primaryExtentPx = primaryExtentPx,
                            anchorSlot = transitionAnchorSlot,
                            isScrollInProgress = scrollState.isScrollInProgress,
                        )
                        val isCurlUnfolding = resolvePageCurlUnfolding(
                            settledPage = transitionAnchorSlot,
                            targetPage = motion.currentSlot,
                            horizontalDragFraction = pageCurlState.horizontalDragFraction,
                            isReadingReversed = isMirrored,
                            verticalDragFraction = pageCurlState.verticalDragFraction,
                            isVertical = isVerticalAxis,
                        )

                        // Slots are drawn back to front so the cover and curl layering reads correctly.
                        // The slide style resolves zIndex 0 everywhere, which keeps plain slot order.
                        val drawableSlots = scene.allSlots
                            .filter { slot ->
                                val intersect = slot.bounds.intersectionOrNull(vp.bounds)
                                intersect != null && intersect.width > 0f && intersect.height > 0f
                            }
                            .map { slot ->
                                slot to resolveSceneSlotTransition(
                                    slotIndex = slot.slotIndex,
                                    motion = motion,
                                    style = transitionStyle,
                                    readingDirection = readingDirection,
                                    isCurlUnfolding = isCurlUnfolding,
                                )
                            }
                            .sortedBy { (_, transition) -> transition.zIndex }

                        for ((slot, transition) in drawableSlots) {
                            val slotIndex = slot.slotIndex
                            val slotColor = resolveSlotBackgroundColor(slot)
                            // Translation is a fraction of the slot's own size, exactly as in the legacy
                            // pager; the slide style resolves 0 here because the offset already moves.
                            val translationPx = transition.translationFactor * primaryExtentPx
                            val (baseScreenX, baseScreenY) = when (readingDirection) {
                                SceneReadingDirection.LEFT_TO_RIGHT -> {
                                    (slotIndex * viewportWidthPx - currentOffset) to 0f
                                }
                                SceneReadingDirection.RIGHT_TO_LEFT -> {
                                    (currentOffset - slotIndex * viewportWidthPx) to 0f
                                }
                                SceneReadingDirection.TOP_TO_BOTTOM -> {
                                    0f to (slotIndex * viewportHeightPx - currentOffset)
                                }
                            }
                            val slotScreenX = baseScreenX + if (isVerticalAxis) 0f else translationPx
                            val slotScreenY = baseScreenY + if (isVerticalAxis) translationPx else 0f
                            val (slotScale, slotPanX, slotPanY) = resolveSlotTransform(slotIndex)
                            val slotCenter = Offset(
                                slotScreenX + viewportWidthPx / 2f,
                                slotScreenY + viewportHeightPx / 2f,
                            )
                            val slotNodes = slot.visibleContentNodes(slotScale, slotPanX, slotPanY)
                            val slotRect = Rect(
                                left = slotScreenX,
                                top = slotScreenY,
                                right = slotScreenX + viewportWidthPx,
                                bottom = slotScreenY + viewportHeightPx,
                            )
                            val curlGeometry = ScenePageTransitionRenderer.resolveCurlGeometry(
                                size = Size(viewportWidthPx, viewportHeightPx),
                                transform = transition,
                                downFraction = pageCurlState.downFraction,
                                horizontalDragFraction = pageCurlState.horizontalDragFraction,
                                isVertical = isVerticalAxis,
                                isReversed = isMirrored,
                            )

                            fun DrawScope.drawSlotBody() {
                                drawRect(
                                    color = Color(slotColor),
                                    topLeft = Offset(slotScreenX, slotScreenY),
                                    size = Size(viewportWidthPx, viewportHeightPx),
                                )
                                if (slotNodes.isNotEmpty()) {
                                    withTransform({
                                        translate(slotPanX, slotPanY)
                                        scale(slotScale, slotScale, pivot = slotCenter)
                                    }) {
                                        drawFrameNodes(
                                            frame = frame.copy(visibleNodes = slotNodes),
                                            seamPolicy = PageSeamPolicy.Zero,
                                            viewportScrollX = if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) currentOffset else 0f,
                                            viewportScrollY = if (readingDirection.isVertical) currentOffset else 0f,
                                            placeholderColor = Color.DarkGray,
                                            imageColorFilter = imageColorFilter,
                                            readerAssetProvider = { id: PageId -> retainedAssets[id] },
                                            animatedBridge = animatedBridge,
                                            screenPositionProvider = if (isMirrored) {
                                                { node: VisibleNode ->
                                                    val placement = slot.placements.firstOrNull { it.pageId == node.pageId }
                                                    val boundsInSlot = placement?.boundsInSlot ?: node.sceneBounds
                                                    val screenX = currentOffset - slotIndex * viewportWidthPx + boundsInSlot.left
                                                    val screenY = boundsInSlot.top
                                                    Offset(screenX, screenY)
                                                }
                                            } else null,
                                        )
                                    }
                                }
                            }

                            val layerPaint = if (transition.alpha < 1f) {
                                Paint().apply { alpha = transition.alpha }
                            } else {
                                null
                            }
                            if (layerPaint != null) {
                                drawIntoCanvas { canvas -> canvas.saveLayer(slotRect, layerPaint) }
                            }
                            withTransform({
                                clipRect(
                                    left = slotScreenX,
                                    top = slotScreenY,
                                    right = slotScreenX + viewportWidthPx,
                                    bottom = slotScreenY + viewportHeightPx,
                                )
                            }) {
                                if (curlGeometry != null) {
                                    // Fold paths are slot-local; shift them into screen space so the body
                                    // (which draws in scene-derived screen coordinates) can be clipped
                                    // and mirrored without a second coordinate convention.
                                    val origin = Offset(slotScreenX, slotScreenY)
                                    val frontPath = curlGeometry.frontPath.toPath().apply { translate(origin) }
                                    val backPath = curlGeometry.backPath.toPath().apply { translate(origin) }
                                    val curlPivot = curlGeometry.bottomCurlOffset + origin
                                    val shadowProgress = (
                                        1f - abs(curlGeometry.curlLineVector.y) / viewportHeightPx.coerceAtLeast(1f)
                                        ).coerceIn(0f, 1f)
                                    clipPath(frontPath) {
                                        drawSlotBody()
                                    }
                                    withTransform({
                                        if (isVerticalAxis) {
                                            scale(1f, -1f, pivot = curlPivot)
                                            rotateRad(-curlGeometry.angle, pivot = curlPivot)
                                        } else {
                                            scale(-1f, 1f, pivot = curlPivot)
                                            rotateRad(curlGeometry.angle, pivot = curlPivot)
                                        }
                                    }) {
                                        drawPath(
                                            path = backPath,
                                            color = Color.Black.copy(alpha = 0.12f + shadowProgress * 0.12f),
                                            style = Stroke(width = 18f),
                                        )
                                        clipPath(backPath) {
                                            drawSlotBody()
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.1f),
                                                topLeft = Offset(slotScreenX, slotScreenY),
                                                size = Size(viewportWidthPx, viewportHeightPx),
                                            )
                                        }
                                    }
                                } else {
                                    drawSlotBody()
                                }
                                if (transition.revealedPageShade > 0f) {
                                    drawRect(
                                        color = Color.Black.copy(alpha = transition.revealedPageShade),
                                        topLeft = Offset(slotScreenX, slotScreenY),
                                        size = Size(viewportWidthPx, viewportHeightPx),
                                    )
                                }
                            }
                            if (layerPaint != null) {
                                drawIntoCanvas { canvas -> canvas.restore() }
                            }
                        }
                    }
                    drawContent()
                },
        )

        // The overlay positions track the paging offset, which changes every frame during a turn.
        // Reading it in composition only while some page still lacks an asset keeps the settled,
        // fully-loaded case free of per-frame recomposition - the common case while reading.
        val hasPendingPageAssets = remember(scene, retainedAssets) {
            scene?.allSlots?.any { slot ->
                slot.placements.any { retainedAssets[it.pageId] == null }
            } == true
        }
        val observedPagingOffset = if (hasPendingPageAssets) scrollState.offset else 0f

        val loadingPlacements = remember(
            scene,
            sceneRevision,
            observedPagingOffset,
            viewportWidthPx,
            viewportHeightPx,
            retainedAssets,
            canvasScale,
            canvasOffsetX,
            canvasOffsetY,
            zoomedSlotIndex,
        ) {
            if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || scene == null) {
                emptyList()
            } else {
                val currentOffset = observedPagingOffset
                val vp = if (readingDirection.isHorizontal) {
                    ReaderViewport(FloatRect.fromLtwh(currentOffset, 0f, viewportWidthPx, viewportHeightPx))
                } else {
                    ReaderViewport(FloatRect.fromLtwh(0f, currentOffset, viewportWidthPx, viewportHeightPx))
                }
                val items = mutableListOf<Triple<PageId, Float, Float>>()
                for (slot in scene.allSlots) {
                    val slotIntersect = slot.bounds.intersectionOrNull(vp.bounds)
                    if (slotIntersect != null && slotIntersect.width > 0f && slotIntersect.height > 0f) {
                        val slotIndex = slot.slotIndex
                        val (baseScreenX, baseScreenY) = when (readingDirection) {
                            SceneReadingDirection.LEFT_TO_RIGHT -> (slotIndex * viewportWidthPx - currentOffset) to 0f
                            SceneReadingDirection.RIGHT_TO_LEFT -> (currentOffset - slotIndex * viewportWidthPx) to 0f
                            SceneReadingDirection.TOP_TO_BOTTOM -> 0f to (slotIndex * viewportHeightPx - currentOffset)
                        }
                        // Loading and error overlays follow the page while a cover or curl transition
                        // moves it, so a slow page does not report progress from the wrong position.
                        val loadingTransition = resolveSceneSlotTransition(
                            slotIndex = slotIndex,
                            motion = resolveSceneTransitionMotion(
                                currentOffset = currentOffset,
                                primaryExtentPx = if (readingDirection.isVertical) viewportHeightPx else viewportWidthPx,
                                anchorSlot = transitionAnchorSlot,
                                isScrollInProgress = scrollState.isScrollInProgress,
                            ),
                            style = transitionStyle,
                            readingDirection = readingDirection,
                        )
                        val translationPx = loadingTransition.translationFactor *
                            (if (readingDirection.isVertical) viewportHeightPx else viewportWidthPx)
                        val slotScreenX = baseScreenX + if (readingDirection.isVertical) 0f else translationPx
                        val slotScreenY = baseScreenY + if (readingDirection.isVertical) translationPx else 0f
                        val (slotScale, slotPanX, slotPanY) = resolveSlotTransform(slotIndex)
                        val slotCenter = Offset(
                            slotScreenX + viewportWidthPx / 2f,
                            slotScreenY + viewportHeightPx / 2f,
                        )
                        for (placement in slot.placements) {
                            if (retainedAssets[placement.pageId] == null) {
                                val unscaledCenterX = slotScreenX + placement.boundsInSlot.left + placement.boundsInSlot.width / 2f
                                val unscaledCenterY = slotScreenY + placement.boundsInSlot.top + placement.boundsInSlot.height / 2f
                                val pageCenterX = slotCenter.x + (unscaledCenterX - slotCenter.x) * slotScale + slotPanX
                                val pageCenterY = slotCenter.y + (unscaledCenterY - slotCenter.y) * slotScale + slotPanY
                                if (pageCenterX in -viewportWidthPx..(viewportWidthPx * 2f) &&
                                    pageCenterY in -viewportHeightPx..(viewportHeightPx * 2f)
                                ) {
                                    items.add(Triple(placement.pageId, pageCenterX, pageCenterY))
                                }
                            }
                        }
                    }
                }
                items
            }
        }

        // Per-page Loading & Error UI positioned at the center of each loading page
        for ((pageId, centerX, centerY) in loadingPlacements) {
            key(pageId) {
                CenteredOverlay(
                    centerX = centerX,
                    centerY = centerY,
                ) {
                    PagedSceneReaderLoadStatus(
                        pipeline = adapter,
                        pageId = pageId,
                        page = pageLookup(pageId),
                        onRetryError = onRetryError,
                        onShowErrorDetails = onShowErrorDetails,
                        resolveErrorStringId = resolveErrorStringId,
                        onRetry = { coroutineScope.launch { adapter.retryAsset(pageId) } },
                    )
                }
            }
        }

        // Chapter Pull feedback
        val isVertical = readingDirection.isVertical
        val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
        val prevAlignment = if (isVertical) Alignment.TopCenter else if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
        val nextAlignment = if (isVertical) Alignment.BottomCenter else if (isRtl) Alignment.CenterStart else Alignment.CenterEnd
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullStartDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoPreviousChapter) R.string.pull_to_prev_chapter else R.string.pull_top_no_prev),
            modifier = Modifier.align(prevAlignment),
        )
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullEndDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoNextChapter) R.string.pull_to_next_chapter else R.string.pull_bottom_no_next),
            modifier = Modifier.align(nextAlignment),
        )

        pageOverlay()
    }
}

@Composable
private fun PagedSceneReaderLoadStatus(
    pipeline: ReaderImagePipeline,
    pageId: PageId,
    page: ReaderPage?,
    onRetryError: (Throwable, retry: () -> Unit) -> Unit,
    onShowErrorDetails: (Throwable, String?) -> Unit,
    resolveErrorStringId: (Throwable) -> Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val states by pipeline.loadStates.collectAsStateWithLifecycle()
    when (val state = states[pageId]) {
        ReaderImageLoadState.Ready -> Unit
        is ReaderImageLoadState.Failed -> Surface(
            modifier = modifier.padding(24.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            ReaderPageError(
                cause = state.cause,
                onRetry = { onRetryError(state.cause, onRetry) },
                onShowDetails = { onShowErrorDetails(state.cause, page?.url) },
                resolveStringId = resolveErrorStringId(state.cause),
            )
        }
        else -> Box(modifier) {
            ReaderPageLoading((state as? ReaderImageLoadState.Loading)?.progress)
        }
    }
}

/**
 * Paging motion of the scene at [currentOffset], expressed in the slot-order convention the
 * transition seam understands: [PagedMotionSnapshot.offsetFraction] is positive while the reader
 * advances to the next slot in reading order, whatever the physical direction is.
 */
private fun resolveSceneTransitionMotion(
    currentOffset: Float,
    primaryExtentPx: Float,
    anchorSlot: Int,
    isScrollInProgress: Boolean,
): PagedMotionSnapshot {
    val position = if (primaryExtentPx > 0f) currentOffset / primaryExtentPx else 0f
    val nearestSlot = position.roundToInt()
    return PagedMotionSnapshot(
        currentSlot = nearestSlot,
        settledSlot = anchorSlot,
        targetSlot = nearestSlot,
        offsetFraction = position - nearestSlot,
        isScrollInProgress = isScrollInProgress,
    )
}

/** Resolves one slot's transition transform from scene state. */
private fun resolveSceneSlotTransition(
    slotIndex: Int,
    motion: PagedMotionSnapshot,
    style: ScenePageTransition,
    readingDirection: SceneReadingDirection,
    isCurlUnfolding: Boolean = false,
): ScenePageTransform = ScenePageTransitionRenderer.transformFor(
    transition = style,
    input = PagedTransitionResolver.resolve(
        snapshot = motion,
        slotIndex = slotIndex,
        direction = readingDirection,
        isCurlUnfolding = isCurlUnfolding,
    ),
    isVertical = readingDirection.isVertical,
    isReversed = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT,
)

private data class PagedSlotZoom(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

@Composable
private fun CenteredOverlay(
    centerX: Float,
    centerY: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(
            constraints.copy(minWidth = 0, minHeight = 0)
        )
        val w = placeable?.width ?: 0
        val h = placeable?.height ?: 0
        val left = (centerX - w / 2f).roundToInt()
        val top = (centerY - h / 2f).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable?.place(left, top)
        }
    }
}


