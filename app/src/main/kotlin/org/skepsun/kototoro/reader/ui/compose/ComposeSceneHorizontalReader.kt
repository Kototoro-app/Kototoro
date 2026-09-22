package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.HorizontalReaderScene
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderCameraSnapshot
import org.skepsun.kototoro.reader.core.ReaderPrediction
import org.skepsun.kototoro.reader.core.ReaderPredictionConfig
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.SceneResourceWindowRequest
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.ReaderImageLoadState
import org.skepsun.kototoro.reader.image.ReaderImagePipeline
import org.skepsun.kototoro.reader.render.arr.AdaptiveRefreshRateHelper
import org.skepsun.kototoro.reader.render.compose.ComposeHorizontalSceneRenderer
import org.skepsun.kototoro.reader.render.compose.SceneImagePresentationCoordinator
import org.skepsun.kototoro.reader.render.compose.rememberComposeScenePrimaryScrollState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.math.roundToInt

private const val HORIZONTAL_PULL_THRESHOLD = 0.18f

internal data class HorizontalPullState(
    val startDistancePx: Float = 0f,
    val endDistancePx: Float = 0f,
)

/**
 * End-to-end Horizontal Continuous reader composable driven by the decoupled Reader Scene Engine (ADR 0002).
 *
 * Integrates:
 * - [HorizontalReaderScene] for pure geometric layout, anchored correction, LTR/RTL reading direction.
 * - [ReaderPrediction] for lookahead prefetching based on [ViewportMotion].
 * - [KototoroImagePipelineAdapter] for resource fetching and deduplicated image decoding.
 * - [ComposeHorizontalSceneRenderer] for Draw-phase rendering bypassing Composition/Layout.
 */
@Composable
fun ComposeSceneHorizontalReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    initialScroll: Int,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    readingDirection: SceneReadingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
    onPagesChanged: (lowerKey: Long, upperKey: Long, activeKey: Long) -> Unit,
    onInternalScrollChanged: (page: ReaderPage, scroll: Int) -> Unit,
    requestedPage: Int? = null,
    requestedPageSmooth: Boolean = false,
    horizontalScrollRequest: ComposeReaderScrollRequest? = null,
    zoomCommand: ComposeReaderZoomCommand? = null,
    isZoomEnabled: Boolean = false,
    defaultScale: Float = 1f,
    isGapsEnabled: Boolean = false,
    isPullGestureEnabled: Boolean = false,
    canGoPreviousChapter: Boolean = true,
    canGoNextChapter: Boolean = true,
    onPullChapter: (delta: Int) -> Unit = {},
    onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
    onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
    resolveErrorStringId: (Throwable) -> Int = ExceptionResolver::getResolveStringId,
    isAnimationEnabled: Boolean = true,
    isReaderOptimizationEnabled: Boolean = false,
    isPreloadReductionEnabled: Boolean = false,
    isCropEnabled: Boolean = false,
    bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    imageColorFilter: ColorFilter? = null,
    modifier: Modifier = Modifier,
    readerBackgroundColor: Int = android.graphics.Color.BLACK,
) {
    if (pages.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val pageMap = remember(pages) {
        pages.associateBy { it.readerKey }
    }
    val currentPageMap = rememberUpdatedState(pageMap)
    val pageLookup: (PageId) -> ReaderPage? = remember {
        { id -> currentPageMap.value[id.value] }
    }

    val density = LocalDensity.current
    val pageGapPx = if (isGapsEnabled) {
        with(density) { dimensionResource(R.dimen.webtoon_pages_gap).roundToPx() }
    } else {
        0
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

    val resourceWindowPlanner = remember(isPreloadReductionEnabled) {
        val config = if (isPreloadReductionEnabled) {
            ReaderPredictionConfig(
                staticAheadFraction = 1.0f,
                staticBehindFraction = 0.5f,
                lookaheadHorizonSeconds = 0.4f,
                maxLookaheadExtraPx = 3000f,
            )
        } else {
            ReaderPredictionConfig(
                staticAheadFraction = 3.0f,
                staticBehindFraction = 1.5f,
                lookaheadHorizonSeconds = 0.8f,
                maxLookaheadExtraPx = 8000f,
            )
        }
        ReaderPrediction(config)
    }

    val scene = remember(viewportWidthPx, viewportHeightPx, pageGapPx, readingDirection) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) null
        else {
            HorizontalReaderScene(
                availableHeight = viewportHeightPx.toInt(),
                defaultViewportWidth = viewportWidthPx.toInt().coerceAtLeast(1000),
                initialPages = createInitialScenePageHints(pages, adapter),
                pageSpacingPx = pageGapPx,
                readingDirection = readingDirection,
            )
        }
    }

    val initialPosition = remember(pages, initialPage) {
        initialPage.coerceIn(pages.indices)
    }
    val initialTargetScrollX = remember(scene, initialPosition, initialScroll, viewportWidthPx) {
        if (scene != null && initialPosition in pages.indices && viewportWidthPx > 0f) {
            val targetPage = pages[initialPosition]
            scene.resolveViewportOriginForPage(
                pageId = PageId(targetPage.readerKey),
                viewportExtent = viewportWidthPx,
                intraPageOffset = initialScroll.toFloat(),
            ) ?: 0f
        } else {
            0f
        }
    }
    val initialMaxScroll = remember(scene, viewportWidthPx) {
        if (scene != null && viewportWidthPx > 0f) {
            (scene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
        } else {
            Float.MAX_VALUE
        }
    }
    val scrollState = rememberComposeScenePrimaryScrollState(
        initialOffset = initialTargetScrollX,
        maxOffset = initialMaxScroll,
        key = scene,
    )
    var hasAppliedInitialPosition by remember { mutableStateOf(false) }
    val retainedAssets by adapter.assets.collectAsStateWithLifecycle()
    var currentMotion by remember { mutableStateOf(ViewportMotion.Idle) }
    var lastReportedPages by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var statusPageId by remember { mutableStateOf(PageId(pages[initialPosition].readerKey)) }

    var pullState by remember { mutableStateOf(HorizontalPullState()) }
    var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(0.5f, 1f)) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }
    val zoomAnimationScope = rememberCoroutineScope()
    val doubleTapSlop = remember(context) {
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }
    val flingDecay = FloatExponentialDecaySpec()
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var zoomFlingJob by remember { mutableStateOf<Job?>(null) }

    // Chapter-wide ratio of the decoded pages: sizes the still-loading placeholders so they
    // match the real image size once the chapter's ratio has converged. Keyed by chapter so
    // boundary-loading window expansions keep the converged estimate.
    val ratioEstimator = remember(pages.firstOrNull()?.chapterId) { ReaderChapterRatioEstimator() }

    val activeScene = scene

    fun updateResourceWindow(
        currentScene: HorizontalReaderScene,
        currentX: Float,
        motion: ViewportMotion,
    ) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
        val vp = ReaderViewport(FloatRect.fromLtwh(currentX, 0f, viewportWidthPx, viewportHeightPx))
        val frame = currentScene.resolve(vp)

        val progress = frame.progress
        progress.activePageId?.let { statusPageId = it }
        val lowerId = progress.lowerPageId
        val upperId = progress.upperPageId
        val activeId = progress.activePageId
        if (lowerId != null && upperId != null && activeId != null && hasAppliedInitialPosition) {
            val lowerKey = lowerId.value
            val upperKey = upperId.value
            val activeKey = activeId.value

            val newReported = Triple(lowerKey, upperKey, activeKey)
            if (lastReportedPages != newReported) {
                lastReportedPages = newReported
                onPagesChanged(lowerKey, upperKey, activeKey)
            }

            val activePage = pageLookup(activeId) ?: pages.firstOrNull()
            if (activePage != null) {
                val pageRelativeScroll = progress.intraPageOffsetPx.roundToInt()
                onInternalScrollChanged(activePage, pageRelativeScroll)
            }
        }

        resourceWindowPlanner.plan(
            SceneResourceWindowRequest(scene = currentScene, frame = frame, motion = motion),
        )?.let {
            adapter.updateResourceWindow(it)
        }

        SceneImagePresentationCoordinator.coordinateVisibleTiles(frame, adapter)
    }

    LaunchedEffect(pages, scene) {
        if (scene != null && hasAppliedInitialPosition) {
            val currentVp = ReaderViewport(
                FloatRect.fromLtwh(scrollState.offset, 0f, viewportWidthPx, viewportHeightPx),
            )
            val newHints = createInitialScenePageHints(pages, adapter, unknownPageRatio = ratioEstimator.convergedRatio)
            val compensation = scene.updatePages(newHints, currentVp)
            if (viewportWidthPx > 0f) {
                scrollState.maxOffset = (scene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
            }
            if (compensation != null) {
                if (compensation.deltaX != 0f) {
                    scrollState.snapBy(compensation.deltaX)
                }
            } else if (initialPosition in pages.indices) {
                val targetPage = pages[initialPosition]
                val newOrigin = scene.resolveViewportOriginForPage(
                    PageId(targetPage.readerKey),
                    viewportExtent = viewportWidthPx,
                    intraPageOffset = initialScroll.toFloat(),
                ) ?: 0f
                val targetScroll = newOrigin.coerceIn(0f, scrollState.maxOffset)
                scrollState.snapTo(targetScroll)
            }
            updateResourceWindow(scene, scrollState.offset, currentMotion)
        }
    }

    /**
     * Applies a chapter-average aspect ratio to the still-unknown pages: their placeholder
     * geometry then matches the real image size, and [HorizontalReaderScene.updatePages] keeps
     * the exact geometries and the active page's visual anchor stable.
     */
    fun applyRatioRelayout(ratio: Float) {
        val currentScene = activeScene ?: return
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || !hasAppliedInitialPosition) return
        val currentVp = ReaderViewport(
            FloatRect.fromLtwh(scrollState.offset, 0f, viewportWidthPx, viewportHeightPx),
        )
        val newHints = createInitialScenePageHints(pages, adapter, unknownPageRatio = ratio)
        val compensation = currentScene.updatePages(newHints, currentVp)
        scrollState.maxOffset = (currentScene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
        if (compensation != null && compensation.deltaX != 0f) {
            scrollState.snapBy(compensation.deltaX)
        }
        updateResourceWindow(currentScene, scrollState.offset, currentMotion)
    }

    fun dispatchHorizontalScroll(deltaPx: Float) {
        if (deltaPx.isFinite() && deltaPx != 0f && activeScene != null && viewportWidthPx > 0f) {
            val maxScroll = (activeScene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
            scrollState.maxOffset = maxScroll
            val newScroll = (scrollState.offset + deltaPx).coerceIn(0f, maxScroll)
            scrollState.snapTo(newScroll)
            updateResourceWindow(activeScene, newScroll, currentMotion)
        }
    }

    fun clampCanvasOffset(scale: Float, x: Float, y: Float): Offset {
        val bounds = resolveWebtoonCanvasOffsetBounds(viewportWidthPx.toInt(), viewportHeightPx.toInt(), scale)
        return Offset(
            x.coerceIn(bounds.minX, bounds.maxX),
            y.coerceIn(bounds.minY, bounds.maxY),
        )
    }

    fun applyCanvasPan(pan: Offset, isTransformGesture: Boolean) {
        if (!pan.x.isFinite() || !pan.y.isFinite()) return
        val desiredX = canvasOffsetX + pan.x
        val desiredY = canvasOffsetY + pan.y
        val bounded = clampCanvasOffset(canvasScale, desiredX, desiredY)
        canvasOffsetX = bounded.x
        canvasOffsetY = bounded.y
        dispatchHorizontalScroll(
            resolveWebtoonGestureBoundaryHandoff(
                scale = canvasScale,
                desiredY = desiredX,
                boundedY = bounded.x,
                isTransformGesture = isTransformGesture,
            ).toFloat(),
        )
    }

    fun applyCanvasScaleAtFocus(
        nextScale: Float,
        focus: Offset,
        isTransformGesture: Boolean = false,
    ) {
        if (!nextScale.isFinite() || !focus.x.isFinite() || !focus.y.isFinite()) return
        val previousScale = canvasScale
        val safeScale = previousScale.coerceAtLeast(0.01f)
        val centerX = viewportWidthPx / 2f
        val centerY = viewportHeightPx / 2f
        val focusedContentX = centerX + (focus.x - canvasOffsetX - centerX) / safeScale
        val focusedContentY = centerY + (focus.y - canvasOffsetY - centerY) / safeScale

        val desiredOffset = Offset(
            x = focus.x - (centerX + nextScale * (focusedContentX - centerX)),
            y = focus.y - (centerY + nextScale * (focusedContentY - centerY)),
        )
        canvasScale = nextScale
        val bounded = clampCanvasOffset(nextScale, desiredOffset.x, desiredOffset.y)
        canvasOffsetX = bounded.x
        canvasOffsetY = bounded.y
    }

    suspend fun animateHorizontalScaleTo(
        targetScale: Float,
        focus: Offset = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f),
    ) {
        if (!isAnimationEnabled) {
            applyCanvasScaleAtFocus(targetScale, focus)
            return
        }
        animate(
            initialValue = canvasScale,
            targetValue = targetScale,
            animationSpec = tween(ZOOM_ANIMATION_DURATION_MS),
        ) { value, _ ->
            applyCanvasScaleAtFocus(value, focus)
        }
    }

    suspend fun flingCanvas(velocity: Velocity) {
        if (canvasScale <= 1f || maxOf(kotlin.math.abs(velocity.x), kotlin.math.abs(velocity.y)) < 50f) return
        coroutineScope {
            launch {
                var previousValue = canvasOffsetX
                animateDecay(canvasOffsetX, velocity.x, flingDecay) { value, _ ->
                    val desiredX = canvasOffsetX + (value - previousValue)
                    val bounded = clampCanvasOffset(canvasScale, desiredX, canvasOffsetY)
                    canvasOffsetX = bounded.x
                    dispatchHorizontalScroll(resolveWebtoonBoundaryHandoff(canvasScale, desiredX, bounded.x).toFloat())
                    previousValue = value
                }
            }
            launch {
                animateDecay(canvasOffsetY, velocity.y, flingDecay) { value, _ ->
                    canvasOffsetY = clampCanvasOffset(canvasScale, canvasOffsetX, value).y
                }
            }
        }
    }

    /**
     * The single programmatic page navigation entry: scrolls (or animates) to [position]'s
     * page origin. Shared by the `requestedPage` prop effect and the viewport's accessibility
     * actions (improvement plan §5.2: reuse the existing navigation entry).
     */
    fun navigateToPagePosition(position: Int, smooth: Boolean) {
        val scene = activeScene ?: return
        if (viewportWidthPx <= 0f || position !in pages.indices) return
        val targetPage = pages[position]
        val targetOrigin = scene.resolveViewportOriginForPage(
            pageId = PageId(targetPage.readerKey),
            viewportExtent = viewportWidthPx,
        ) ?: 0f
        val clampedTarget = targetOrigin.coerceIn(0f, scrollState.maxOffset)
        zoomAnimationJob?.cancel()
        zoomAnimationJob = zoomAnimationScope.launch {
            if (smooth && isAnimationEnabled) {
                var previousValue = scrollState.offset
                animate(
                    initialValue = scrollState.offset,
                    targetValue = clampedTarget,
                ) { value, _ ->
                    val delta = value - previousValue
                    scrollState.snapTo(value)
                    previousValue = value
                    updateResourceWindow(scene, value, ViewportMotion(velocityX = delta * 60f, velocityY = 0f))
                }
                updateResourceWindow(scene, clampedTarget, ViewportMotion.Idle)
            } else {
                scrollState.snapTo(clampedTarget)
                updateResourceWindow(scene, clampedTarget, ViewportMotion.Idle)
            }
        }
    }

    // Programmatic page navigation
    var previousRequestedPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(requestedPage, activeScene) {
        if (requestedPage != null && requestedPage != previousRequestedPage && activeScene != null && viewportWidthPx > 0f) {
            previousRequestedPage = requestedPage
            val targetPage = pages.getOrNull(requestedPage)
            if (targetPage != null) {
                navigateToPagePosition(requestedPage!!, smooth = requestedPageSmooth)
            }
        }
    }

    // Programmatic zoom (the reader chrome's zoom in/out controls).
    LaunchedEffect(zoomCommand, isAnimationEnabled) {
        val command = zoomCommand ?: return@LaunchedEffect
        val animationJob = currentCoroutineContext().job
        zoomAnimationJob = animationJob
        try {
            animateHorizontalScaleTo(
                (canvasScale * command.factor).coerceIn(0.5f, READER_WEBTOON_MAX_ZOOM_SCALE),
            )
        } finally {
            if (zoomAnimationJob === animationJob) zoomAnimationJob = null
        }
    }

    val pullThresholdPx = viewportWidthPx * HORIZONTAL_PULL_THRESHOLD
    fun handleOverScroll(overscrollDeltaX: Float) {
        if (!isPullGestureEnabled || viewportWidthPx <= 0f) return
        val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
        // In LTR: overscrollDeltaX < 0 is pulling at start (prev), > 0 is pulling at end (next)
        // In RTL: overscrollDeltaX > 0 is pulling at start (prev), < 0 is pulling at end (next)
        val pullDelta = if (isRtl) overscrollDeltaX else -overscrollDeltaX
        pullState = if (pullDelta > 0f) {
            pullState.copy(startDistancePx = (pullState.startDistancePx + pullDelta).coerceAtMost(viewportWidthPx), endDistancePx = 0f)
        } else if (pullDelta < 0f) {
            pullState.copy(startDistancePx = 0f, endDistancePx = (pullState.endDistancePx - pullDelta).coerceAtMost(viewportWidthPx))
        } else {
            pullState
        }
    }

    fun handleReleaseOverScroll() {
        if (!isPullGestureEnabled) return
        if (pullState.startDistancePx >= pullThresholdPx) {
            if (canGoPreviousChapter) onPullChapter(-1)
        } else if (pullState.endDistancePx >= pullThresholdPx) {
            if (canGoNextChapter) onPullChapter(1)
        }
        pullState = HorizontalPullState()
    }

    DisposableEffect(adapter, activeScene) {
        if (activeScene != null) {
            adapter.onAssetLoaded = { pageId, asset ->
                val exactSize = when (asset) {
                    is ReaderImageAsset.ComposeImage -> IntSize(asset.imageBitmap.width, asset.imageBitmap.height)
                    is ReaderImageAsset.AndroidBitmap -> IntSize(asset.bitmap.width, asset.bitmap.height)
                    is ReaderImageAsset.Animated -> IntSize(asset.width, asset.height)
                    is ReaderImageAsset.Tiled -> asset.grid.pageSize
                    else -> null
                }
                if (exactSize != null) {
                    val vp = ReaderViewport(
                        FloatRect.fromLtwh(scrollState.offset, 0f, viewportWidthPx, viewportHeightPx),
                    )
                    val compensation = activeScene.updatePageHint(
                        pageId = pageId,
                        newHint = PageGeometryHint.Exact(exactSize.width, exactSize.height),
                        currentViewport = vp,
                    )
                    if (viewportWidthPx > 0f) {
                        scrollState.maxOffset = (activeScene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
                    }
                    if (compensation != null && compensation.deltaX != 0f) {
                        scrollState.snapBy(compensation.deltaX)
                    }
                    // Let the chapter-average ratio converge the still-loading placeholders
                    // toward their real size.
                    ratioEstimator.onDecoded(exactSize.width, exactSize.height)?.let(::applyRatioRelayout)
                    updateResourceWindow(activeScene, scrollState.offset, currentMotion)
                }
            }
        }
        onDispose {
            adapter.onAssetLoaded = null
        }
    }

    // 150ms Zoom settle signal to trigger multi-LOD progressive replacement
    LaunchedEffect(canvasScale, canvasOffsetX, canvasOffsetY, scrollState.offset, activeScene) {
        if (activeScene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
            delay(150)
            val visibleBounds = SceneImagePresentationCoordinator.computeVisibleBounds(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                scrollOffset = scrollState.offset,
                isHorizontal = true,
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                totalSceneExtent = activeScene.totalSceneWidth,
                totalCrossExtent = activeScene.availableHeight.toFloat(),
            )
            adapter.onCameraSettled(
                SceneImagePresentationCoordinator.createCameraSnapshot(canvasScale, visibleBounds),
                scene = activeScene,
            )
        }
    }

    // Initial positioning and viewport priming
    LaunchedEffect(activeScene, viewportWidthPx, viewportHeightPx) {
        if (activeScene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
            if (!hasAppliedInitialPosition) {
                val maxScroll = (activeScene.totalSceneWidth - viewportWidthPx).coerceAtLeast(0f)
                scrollState.maxOffset = maxScroll
                val targetX = initialTargetScrollX.coerceIn(0f, maxScroll)
                scrollState.snapTo(targetX)
                hasAppliedInitialPosition = true
            }
            updateResourceWindow(activeScene, scrollState.offset, ViewportMotion.Idle)
        }
    }

    // Stable viewport semantics (improvement plan §5.2): the node describes the settled page
    // window — never the per-frame scroll offset — and offers previous/next page (plus
    // chapter, where supported) custom actions reusing [navigateToPagePosition] and
    // [onPullChapter]. The first/last page simply does not offer the impossible action.
    val settledPages = remember(lastReportedPages) {
        lastReportedPages?.let { (lowerKey, upperKey, _) ->
            val lowerIndex = pages.indexOfFirst { it.readerKey == lowerKey }
            val upperIndex = pages.indexOfFirst { it.readerKey == upperKey }
            if (lowerIndex >= 0 && upperIndex >= 0) SceneSettledPages(lowerIndex, upperIndex) else null
        }
    }

    fun settledLowerIndex(): Int = lastReportedPages?.first?.let { key ->
        pages.indexOfFirst { it.readerKey == key }
    } ?: -1

    fun settledUpperIndex(): Int = lastReportedPages?.second?.let { key ->
        pages.indexOfFirst { it.readerKey == key }
    } ?: -1

    val viewportActions = SceneReaderViewportActions(
        canPreviousPage = settledLowerIndex() > 0,
        canNextPage = settledUpperIndex() in 0 until pages.lastIndex,
        onPreviousPage = {
            val lower = settledLowerIndex()
            if (lower > 0) navigateToPagePosition(lower - 1, smooth = true)
        },
        onNextPage = {
            val upper = settledUpperIndex()
            if (upper in 0 until pages.lastIndex) navigateToPagePosition(upper + 1, smooth = true)
        },
        canPreviousChapter = canGoPreviousChapter,
        canNextChapter = canGoNextChapter,
        onPreviousChapter = { onPullChapter(-1) },
        onNextChapter = { onPullChapter(1) },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .sceneReaderViewportSemantics(
                testTag = SceneReaderViewportSemantics.HORIZONTAL_VIEWPORT_TEST_TAG,
                totalPages = pages.size,
                settled = settledPages,
                actions = viewportActions,
            )
            .background(Color(readerBackgroundColor))
            .onSizeChanged { size ->
                viewportWidthPx = size.width.toFloat()
                viewportHeightPx = size.height.toFloat()
            }
            .pointerInput(isZoomEnabled) {
                if (isZoomEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        zoomFlingJob?.cancel()
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var singlePointerTransformed = false
                        var hadMultiplePointers = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2) {
                                hadMultiplePointers = true
                                event.changes.forEach { it.consume() }
                                zoomAnimationJob?.cancel()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                if (centroid.x.isFinite() && centroid.y.isFinite() &&
                                    pan.x.isFinite() && pan.y.isFinite() && zoom.isFinite()
                                ) {
                                    val previousScale = canvasScale
                                    val nextScale = (previousScale * zoom).coerceIn(0.5f, 5.0f)
                                    applyCanvasScaleAtFocus(nextScale, centroid, isTransformGesture = true)
                                    applyCanvasPan(pan, isTransformGesture = true)
                                }
                            } else if (pressedCount == 1 && canvasScale > 1f) {
                                if (event.changes.any { it.isConsumed }) continue
                                val change = event.changes.first { it.pressed }
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val pan = event.calculatePan()
                                if (pan.x.isFinite() && pan.y.isFinite()) {
                                    applyCanvasPan(pan, isTransformGesture = false)
                                    event.changes.forEach { it.consume() }
                                    singlePointerTransformed = true
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (shouldFlingAfterTransform(singlePointerTransformed, hadMultiplePointers)) {
                            zoomFlingJob = zoomAnimationScope.launch {
                                flingCanvas(velocityTracker.calculateVelocity())
                            }
                        }
                    }
                }
            }
            .pointerInput(isZoomEnabled, defaultScale) {
                if (isZoomEnabled) {
                    var lastTapUpAt = 0L
                    var lastTapPosition: Offset? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val isDoubleTapCandidate = isTapGridDoubleTapCandidate(
                            previousPosition = lastTapPosition,
                            previousTapAt = lastTapUpAt,
                            position = down.position,
                            now = down.uptimeMillis,
                            minTimeMillis = viewConfiguration.doubleTapMinTimeMillis,
                            timeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
                            doubleTapSlop = doubleTapSlop,
                        )
                        if (isDoubleTapCandidate) down.consume()
                        var moved = false
                        var eventTime = down.uptimeMillis
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.isConsumed }) {
                                moved = true
                            }
                            event.changes.maxByOrNull { it.uptimeMillis }?.let { eventTime = it.uptimeMillis }
                            if (event.changes.count { it.pressed } >= 2) {
                                moved = true
                            } else if (event.changes.any { it.pressed }) {
                                val currentPosition = event.changes.firstOrNull { it.pressed }?.position
                                if (currentPosition != null &&
                                    hasExceededWebtoonTapSlop(
                                        start = down.position,
                                        current = currentPosition,
                                        touchSlop = viewConfiguration.touchSlop,
                                    )
                                ) {
                                    moved = true
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val heldTooLong =
                            eventTime - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                        if (moved || heldTooLong) {
                            lastTapPosition = null
                            return@awaitEachGesture
                        }
                        if (isDoubleTapCandidate) {
                            lastTapPosition = null
                            val targetScale = if (kotlin.math.abs(canvasScale - defaultScale) > 0.001f) {
                                defaultScale.coerceIn(0.5f, 1f)
                            } else {
                                2f
                            }
                            zoomAnimationJob?.cancel()
                            zoomAnimationJob = zoomAnimationScope.launch {
                                animateHorizontalScaleTo(targetScale, focus = down.position)
                            }
                        } else {
                            lastTapPosition = down.position
                            lastTapUpAt = eventTime
                        }
                    }
                }
            },
    ) {
        val canvasWidth = if (canvasScale < 1f) maxWidth / canvasScale else maxWidth
        val canvasHeight = if (canvasScale < 1f) maxHeight / canvasScale else maxHeight
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(
                    width = canvasWidth,
                    height = canvasHeight,
                )
                .graphicsLayer {
                    alpha = if (hasAppliedInitialPosition) 1f else 0f
                    scaleX = canvasScale
                    scaleY = canvasScale
                    translationX = canvasOffsetX
                    translationY = canvasOffsetY
                    transformOrigin = TransformOrigin.Center
                },
        ) {
            if (activeScene != null) {
                val pageLabelFormat = stringResource(R.string.reader_page_label)
                ComposeHorizontalSceneRenderer(
                    scene = activeScene,
                    scrollState = scrollState,
                    placeholderColor = Color.DarkGray,
                    pageLabelProvider = { pageId ->
                        pageLookup(pageId)?.index?.let { pageLabelFormat.format(it + 1) } ?: ""
                    },
                    imageColorFilter = imageColorFilter,
                    readerAssetProvider = { id ->
                        retainedAssets[id]
                    },
                    tileStore = adapter.tileStore,
                    onScrollProgressChanged = { currentX, _ ->
                        updateResourceWindow(activeScene, currentX, currentMotion)
                    },
                    onMotionChanged = { motion ->
                        currentMotion = motion
                        AdaptiveRefreshRateHelper.applyPreference(view, motion)
                        if (motion.isIdle) {
                            updateResourceWindow(activeScene, scrollState.offset, motion)
                        }
                    },
                    onOverScroll = ::handleOverScroll,
                    onReleaseOverScroll = ::handleReleaseOverScroll,
                    modifier = Modifier.fillMaxSize(),
                )
                val horizontalLoadingOverlays = remember(
                    activeScene,
                    lastReportedPages,
                    viewportWidthPx,
                    viewportHeightPx,
                    retainedAssets,
                ) {
                    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
                        AnchoredLoadingOverlays(0f, emptyList())
                    } else {
                        // Anchor snapshot taken WITHOUT observing the scroll offset: composition
                        // stays unsubscribed from per-frame scroll (the renderer scrolls in the
                        // draw phase); the overlays track it at the layer phase instead, and the
                        // anchor is re-taken when the settled window or retained assets change.
                        val anchorX = Snapshot.withoutReadObservation { scrollState.offset }
                        val vWidth = viewportWidthPx
                        val vHeight = viewportHeightPx
                        val verticalOffset = ((vHeight - activeScene.availableHeight) / 2f).coerceAtLeast(0f)
                        // Resolve with leading/trailing margin so pages scrolled into view
                        // mid-flight already carry an anchored overlay that stays glued to
                        // their placeholder rect.
                        val margin = vWidth * LOADING_OVERLAY_MARGIN_VIEWPORTS
                        val viewport = ReaderViewport(
                            bounds = FloatRect.fromLtwh(
                                anchorX - margin,
                                0f,
                                vWidth + margin * 2f,
                                activeScene.availableHeight.toFloat(),
                            ),
                        )
                        val frame = activeScene.resolve(viewport)
                        val items = mutableListOf<Triple<PageId, Float, Float>>()
                        for (node in frame.visibleNodes) {
                            if (retainedAssets[node.pageId] == null) {
                                val screenLeft = node.sceneBounds.left - anchorX
                                val screenRight = node.sceneBounds.right - anchorX
                                val screenTop = verticalOffset + node.sceneBounds.top
                                val screenBottom = verticalOffset + node.sceneBounds.bottom
                                val visibleLeft = maxOf(screenLeft, 0f)
                                val visibleRight = minOf(screenRight, vWidth)
                                val visibleTop = maxOf(screenTop, 0f)
                                val visibleBottom = minOf(screenBottom, vHeight)
                                val (cx, cy) = if (visibleRight > visibleLeft && visibleBottom > visibleTop) {
                                    (visibleLeft + visibleRight) / 2f to (visibleTop + visibleBottom) / 2f
                                } else {
                                    // Page within the margin but off-screen: anchor at the
                                    // page's own center; the overlay glides in glued to the
                                    // placeholder when the page scrolls into view.
                                    (screenLeft + screenRight) / 2f to (screenTop + screenBottom) / 2f
                                }
                                items.add(Triple(node.pageId, cx, cy))
                            }
                        }
                        AnchoredLoadingOverlays(anchorX, items)
                    }
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clipToBounds(),
                ) {
                    for ((pageId, centerX, centerY) in horizontalLoadingOverlays.items) {
                        key(pageId) {
                            CenteredOverlay(
                                centerX = centerX,
                                centerY = centerY,
                                modifier = Modifier.loadingOverlayHorizontalAnchor(
                                    horizontalLoadingOverlays.anchorScroll,
                                    scrollState,
                                ),
                            ) {
                                SceneReaderPageLoadOverlay(
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
                }
            }
        }
        val isRtl = readingDirection == SceneReadingDirection.RIGHT_TO_LEFT
        val prevAlignment = if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
        val nextAlignment = if (isRtl) Alignment.CenterStart else Alignment.CenterEnd
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullState.startDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoPreviousChapter) R.string.pull_to_prev_chapter else R.string.pull_top_no_prev),
            modifier = Modifier.align(prevAlignment),
        )
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullState.endDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoNextChapter) R.string.pull_to_next_chapter else R.string.pull_bottom_no_next),
            modifier = Modifier.align(nextAlignment),
        )
    }
}

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

