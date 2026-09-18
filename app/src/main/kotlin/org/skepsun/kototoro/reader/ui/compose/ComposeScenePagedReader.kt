package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.skepsun.kototoro.reader.core.PagedSnapResolver
import org.skepsun.kototoro.reader.core.PagedSpreadConfig
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
import org.skepsun.kototoro.reader.render.compose.SceneImagePresentationCoordinator
import org.skepsun.kototoro.reader.render.compose.animatedDrawBridge
import org.skepsun.kototoro.reader.render.compose.drawFrameNodes
import org.skepsun.kototoro.reader.render.compose.rememberComposeScenePrimaryScrollState
import org.skepsun.kototoro.reader.render.compose.tileDrawBridge
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import kotlin.math.roundToInt

private const val PAGED_PULL_THRESHOLD_FRACTION = 0.18f

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
 * - [PagedSnapResolver] for predictable, velocity- and threshold-aware slot snapping.
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
    isZoomEnabled: Boolean = false,
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

    val animatedBridge = remember { AnimatedDrawBridge() }
    DisposableEffect(animatedBridge) {
        onDispose {
            animatedBridge.stopAll()
        }
    }

    val resolvedBackgroundColor = remember(readerBackgroundColor, bookBackgroundTint) {
        applyAutomaticBookBackgroundTint(readerBackgroundColor, bookBackgroundTint)
    }
    val shouldAnimate = isAnimationEnabled && pageAnimation != ReaderAnimation.NONE

    val primaryExtent = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
    val initialPosition = remember(pages, initialPage) { initialPage.coerceIn(pages.indices) }

    val scrollState = rememberComposeScenePrimaryScrollState(
        initialOffset = 0f,
        maxOffset = Float.MAX_VALUE,
        key = scene,
    )
    var hasAppliedInitialPosition by remember { mutableStateOf(false) }
    val retainedAssets by adapter.assets.collectAsStateWithLifecycle()
    var lastReportedPages by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var statusPageId by remember { mutableStateOf(PageId(pages[initialPosition].readerKey)) }

    var pullStartDistancePx by remember { mutableFloatStateOf(0f) }
    var pullEndDistancePx by remember { mutableFloatStateOf(0f) }
    val pullThresholdPx = primaryExtent * PAGED_PULL_THRESHOLD_FRACTION

    var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(1f, 5f)) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }

    var snapAnimationJob by remember { mutableStateOf<Job?>(null) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }

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
        if (lowerId != null && upperId != null && activeId != null && hasAppliedInitialPosition) {
            val lowerKey = lowerId.value
            val upperKey = upperId.value
            val activeKey = activeId.value
            val newReported = Triple(lowerKey, upperKey, activeKey)
            if (lastReportedPages != newReported) {
                lastReportedPages = newReported
                onPagesChanged(lowerKey, upperKey, activeKey)
            }
        }

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
        SceneImagePresentationCoordinator.coordinateVisibleTiles(frame, retainedAssets, adapter)
    }

    LaunchedEffect(scrollState.offset, scene, retainedAssets) {
        if (scene != null) {
            updateResourceWindow(scene, scrollState.offset)
        }
    }

    // 150ms Zoom settle signal to trigger multi-LOD progressive replacement
    LaunchedEffect(canvasScale, canvasOffsetX, canvasOffsetY, scrollState.offset, scene) {
        if (scene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
            delay(150)
            val isHorizontal = readingDirection.isHorizontal
            val visibleBounds = SceneImagePresentationCoordinator.computeVisibleBounds(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                scrollOffset = scrollState.offset,
                isHorizontal = isHorizontal,
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                totalSceneExtent = scene.totalSceneExtent,
                totalCrossExtent = if (isHorizontal) viewportHeightPx else viewportWidthPx,
            )
            adapter.onCameraSettled(
                SceneImagePresentationCoordinator.createCameraSnapshot(canvasScale, visibleBounds),
                scene = scene,
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
            hasAppliedInitialPosition = true
            updateResourceWindow(scene, targetScroll)
        }
    }

    // Page updates
    LaunchedEffect(pages, scene) {
        if (scene != null && hasAppliedInitialPosition) {
            val pe = if (readingDirection.isHorizontal) viewportWidthPx else viewportHeightPx
            val newSpecs = createInitialPagedPageSpecs(pages, adapter)
            scene.setPagedPages(newSpecs)
            if (pe > 0f) {
                scrollState.maxOffset = ((scene.slotCount - 1) * pe).coerceAtLeast(0f)
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
                            } finally {
                                scrollState.isFlinging = false
                            }
                        }
                    } else {
                        scrollState.snapTo(targetOffset)
                        updateResourceWindow(scene, targetOffset)
                    }
                }
            }
        }
    }

    // Programmatic zoom command
    LaunchedEffect(zoomCommand, scene) {
        val command = zoomCommand ?: return@LaunchedEffect
        if (pages.none { it.readerKey == command.pageKey }) return@LaunchedEffect
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
                        canvasOffsetX = 0f
                        canvasOffsetY = 0f
                    }
                }
            }
        } else {
            canvasScale = targetScale
            if (targetScale <= 1f) {
                canvasOffsetX = 0f
                canvasOffsetY = 0f
            }
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
            .background(Color(resolvedBackgroundColor))
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
                    pullStartDistancePx = 0f
                    pullEndDistancePx = 0f

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
                        val targetScale = if (canvasScale > 1f) 1f else 2.5f
                        coroutineScope.launch {
                            if (shouldAnimate) {
                                animate(
                                    initialValue = canvasScale,
                                    targetValue = targetScale,
                                    animationSpec = tween(200),
                                ) { value, _ ->
                                    canvasScale = value
                                    if (value <= 1f) {
                                        canvasOffsetX = 0f
                                        canvasOffsetY = 0f
                                    }
                                }
                            } else {
                                canvasScale = targetScale
                                if (targetScale <= 1f) {
                                    canvasOffsetX = 0f
                                    canvasOffsetY = 0f
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var isZoomGesture = false
                    var dragAccumulator = 0f
                    val startOffset = scrollState.offset
                    val pe = primaryExtent.coerceAtLeast(1f)
                    val initialSlot = (startOffset / pe).roundToInt()

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedCount = event.changes.count { it.pressed }
                        if (isZoomEnabled && pressedCount >= 2) {
                            isZoomGesture = true
                            event.changes.forEach { it.consume() }
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (pan.x.isFinite() && pan.y.isFinite() && zoom.isFinite()) {
                                val nextScale = (canvasScale * zoom).coerceIn(1f, 5f)
                                canvasScale = nextScale
                                if (nextScale > 1f) {
                                    val maxPanX = (viewportWidthPx * (nextScale - 1f)) / 2f
                                    val maxPanY = (viewportHeightPx * (nextScale - 1f)) / 2f
                                    canvasOffsetX = (canvasOffsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                                    canvasOffsetY = (canvasOffsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                                } else {
                                    canvasOffsetX = 0f
                                    canvasOffsetY = 0f
                                }
                            }
                        } else if (pressedCount == 1) {
                            val change = event.changes.first { it.pressed }
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (canvasScale > 1f) {
                                val pan = event.calculatePan()
                                if (pan.x.isFinite() && pan.y.isFinite() && (pan.x != 0f || pan.y != 0f)) {
                                    event.changes.forEach { it.consume() }
                                    val maxPanX = (viewportWidthPx * (canvasScale - 1f)) / 2f
                                    val maxPanY = (viewportHeightPx * (canvasScale - 1f)) / 2f
                                    canvasOffsetX = (canvasOffsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                                    canvasOffsetY = (canvasOffsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                                }
                            } else if (!isZoomGesture) {
                                val pan = event.calculatePan()
                                val isVertical = readingDirection.isVertical
                                val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
                                val forwardDelta = if (isVertical) -pan.y else if (isRtl) pan.x else -pan.x

                                if (forwardDelta != 0f) {
                                    event.changes.forEach { it.consume() }
                                    scrollState.isDragging = true
                                    dragAccumulator += forwardDelta
                                    val desiredOffset = startOffset + dragAccumulator
                                    val maxScroll = scrollState.maxOffset
                                    val newOffset = desiredOffset.coerceIn(0f, maxScroll)
                                    scrollState.snapTo(newOffset)
                                    scene?.let { updateResourceWindow(it, newOffset) }

                                    if (desiredOffset < 0f) {
                                        handlePull(desiredOffset)
                                    } else if (desiredOffset > maxScroll) {
                                        handlePull(desiredOffset - maxScroll)
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    lastTapPosition = down.position
                    lastTapUpAt = System.currentTimeMillis()

                    scrollState.isDragging = false
                    handleReleasePull()

                    if (!isZoomGesture && canvasScale <= 1f && scene != null && scene.slotCount > 0) {
                        val velocity = velocityTracker.calculateVelocity()
                        val isVertical = readingDirection.isVertical
                        val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
                        val forwardVelocity = if (isVertical) -velocity.y else if (isRtl) velocity.x else -velocity.x
                        val normalizedVelocity = forwardVelocity / (pe * 0.5f)
                        val offsetFraction = (dragAccumulator / pe).coerceIn(-1f, 1f)

                        val targetSlot = PagedSnapResolver.resolveTargetSlot(
                            currentSlot = initialSlot,
                            offsetFraction = offsetFraction,
                            normalizedVelocity = normalizedVelocity,
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
                            } finally {
                                scrollState.isFlinging = false
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
                    scaleX = canvasScale
                    scaleY = canvasScale
                    translationX = canvasOffsetX
                    translationY = canvasOffsetY
                    transformOrigin = TransformOrigin.Center
                }
                .drawWithContent {
                    if (scene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
                        val currentOffset = scrollState.offset
                        val vp = if (readingDirection.isHorizontal) {
                            ReaderViewport(FloatRect.fromLtwh(currentOffset, 0f, viewportWidthPx, viewportHeightPx))
                        } else {
                            ReaderViewport(FloatRect.fromLtwh(0f, currentOffset, viewportWidthPx, viewportHeightPx))
                        }
                        val frame = scene.resolve(vp)
                        drawFrameNodes(
                            frame = frame,
                            viewportScrollX = if (readingDirection == SceneReadingDirection.LEFT_TO_RIGHT) currentOffset else 0f,
                            viewportScrollY = if (readingDirection.isVertical) currentOffset else 0f,
                            placeholderColor = Color.DarkGray,
                            imageColorFilter = imageColorFilter,
                            readerAssetProvider = { id: PageId -> retainedAssets[id] },
                            animatedBridge = animatedBridge,
                            screenPositionProvider = if (readingDirection == SceneReadingDirection.RIGHT_TO_LEFT) {
                                { node: VisibleNode ->
                                    val slotIndex = scene.slotIndexOf(node.pageId)
                                    val slot = scene.allSlots.getOrNull(slotIndex)
                                    val placement = slot?.placements?.firstOrNull { it.pageId == node.pageId }
                                    val boundsInSlot = placement?.boundsInSlot ?: node.sceneBounds
                                    val screenX = currentOffset - slotIndex * viewportWidthPx + boundsInSlot.left
                                    val screenY = boundsInSlot.top
                                    Offset(screenX, screenY)
                                }
                            } else null,
                        )
                    }
                    drawContent()
                },
        )

        // Loading & Error UI for active page
        PagedSceneReaderLoadStatus(
            pipeline = adapter,
            pageId = statusPageId,
            page = pageLookup(statusPageId),
            onRetryError = onRetryError,
            onShowErrorDetails = onShowErrorDetails,
            resolveErrorStringId = resolveErrorStringId,
            onRetry = { coroutineScope.launch { adapter.retryAsset(statusPageId) } },
            modifier = Modifier.align(Alignment.Center),
        )

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
