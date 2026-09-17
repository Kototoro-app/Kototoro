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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderPrediction
import org.skepsun.kototoro.reader.core.ReaderPredictionConfig
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.core.ViewportMotion
import org.skepsun.kototoro.reader.image.KototoroImagePipelineAdapter
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import org.skepsun.kototoro.reader.image.ReaderImageLoadState
import org.skepsun.kototoro.reader.image.ReaderImagePipeline
import org.skepsun.kototoro.reader.render.arr.AdaptiveRefreshRateHelper
import org.skepsun.kototoro.reader.render.compose.ComposeSceneRenderer
import org.skepsun.kototoro.reader.render.compose.rememberComposeSceneScrollState
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.math.roundToInt

/**
 * End-to-end Webtoon reader composable driven by the decoupled Reader Scene Engine (ADR 0002).
 *
 * Integrates:
 * - [VerticalReaderScene] for pure geometric layout, anchored correction, and active page detection.
 * - [ReaderPrediction] for lookahead prefetching based on [ViewportMotion].
 * - [KototoroImagePipelineAdapter] for resource fetching and deduplicated image decoding.
 * - [ComposeSceneRenderer] for Draw-phase rendering bypassing Composition/Layout.
 */
@Composable
fun ComposeSceneWebtoonReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    initialScroll: Int,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    onPagesChanged: (lowerKey: Long, upperKey: Long, activeKey: Long) -> Unit,
    onInternalScrollChanged: (page: ReaderPage, scroll: Int) -> Unit,
    requestedPage: Int? = null,
    requestedPageSmooth: Boolean = false,
    webtoonScrollRequest: ComposeReaderScrollRequest? = null,
    webtoonPageTurnRequest: ComposeWebtoonPageTurnRequest? = null,
    webtoonPageTurnDistanceFraction: Float = 0.9f,
    zoomCommand: ComposeReaderZoomCommand? = null,
    webtoonZoomCommand: ComposeWebtoonZoomCommand? = null,
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

    val pageLookup: (PageId) -> ReaderPage? = remember(pages) {
        val map = pages.associateBy { it.readerKey }
        val lookup: (PageId) -> ReaderPage? = { id -> map[id.value] }
        lookup
    }

    val adapter = remember(imagePipeline, imageLoader, pages, isCropEnabled, bitmapConfig) {
        KototoroImagePipelineAdapter(
            context = context,
            composePipeline = imagePipeline,
            imageLoader = imageLoader,
            scope = coroutineScope,
            pageLookup = pageLookup,
            isCropEnabled = isCropEnabled,
            bitmapConfig = bitmapConfig,
        )
    }

    val predictor = remember(isPreloadReductionEnabled) {
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

    // Scene is created once width is measured
    val scene = remember(pages, viewportWidthPx, adapter) {
        if (viewportWidthPx <= 0f) null
        else {
            VerticalReaderScene(
                availableWidth = viewportWidthPx.toInt(),
                defaultViewportHeight = viewportHeightPx.toInt().coerceAtLeast(1000),
                initialPages = createInitialScenePageHints(pages, adapter),
            )
        }
    }

    val initialPosition = remember(pages, initialPage) {
        initialPage.coerceIn(pages.indices)
    }
    val initialTargetScrollY = remember(scene, initialPosition, initialScroll) {
        if (scene != null && initialPosition in pages.indices) {
            val targetPage = pages[initialPosition]
            val pageTop = scene.resolvePageScrollPosition(PageId(targetPage.readerKey)) ?: 0f
            pageTop + initialScroll.toFloat()
        } else {
            0f
        }
    }
    val initialMaxScroll = remember(scene, viewportHeightPx) {
        if (scene != null && viewportHeightPx > 0f) {
            (scene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
        } else {
            Float.MAX_VALUE
        }
    }
    val scrollState = rememberComposeSceneScrollState(
        initialScrollY = initialTargetScrollY,
        maxScrollY = initialMaxScroll,
        key = scene,
    )
    var hasAppliedInitialPosition by remember(pages) { mutableStateOf(false) }

    // In-memory decoded image cache for visible and near-visible pages
    val decodedBitmaps = remember(pages, adapter) {
        val map = mutableStateMapOf<PageId, ImageBitmap>()
        for (page in pages) {
            val pageId = PageId(page.readerKey)
            val cached = adapter.getCachedAsset(pageId)
            if (cached is ReaderImageAsset.ComposeImage) {
                map[pageId] = cached.imageBitmap
            }
        }
        map
    }
    var currentMotion by remember { mutableStateOf(ViewportMotion.Idle) }
    var lastReportedPages by remember(pages) { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var statusPageId by remember(pages) { mutableStateOf(PageId(pages[initialPosition].readerKey)) }

    var pullState by remember { mutableStateOf(WebtoonPullState()) }
    var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(0.5f, 1f)) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }
    val zoomAnimationScope = rememberCoroutineScope()
    val webtoonNavigationScope = rememberCoroutineScope()
    val doubleTapSlop = remember(context) {
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }
    val webtoonDecay = FloatExponentialDecaySpec()
    var webtoonZoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var webtoonFlingJob by remember { mutableStateOf<Job?>(null) }
    var webtoonNavigationJob by remember { mutableStateOf<Job?>(null) }
    var wasZoomEnabled by remember { mutableStateOf(isZoomEnabled) }

    val activeScene = scene

    // Unified resource window update used by both initial prime, scroll events, and asset resolution
    fun updateResourceWindow(
        currentScene: VerticalReaderScene,
        currentY: Float,
        motion: ViewportMotion,
    ) {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, currentY, viewportWidthPx, viewportHeightPx))
        val frame = currentScene.resolve(vp)

        // 1. Semantic update (lowerKey, upperKey, activeKey) - only report once initial positioning is ready
        val visibleNodes = frame.visibleNodes
        currentScene.resolveActivePageId(vp)?.let { statusPageId = it }
        if (visibleNodes.isNotEmpty() && hasAppliedInitialPosition) {
            val lowerKey = visibleNodes.first().pageId.value
            val upperKey = visibleNodes.last().pageId.value
            val activeId = currentScene.resolveActivePageId(vp) ?: visibleNodes.first().pageId
            val activeKey = activeId.value

            val newReported = Triple(lowerKey, upperKey, activeKey)
            if (lastReportedPages != newReported) {
                lastReportedPages = newReported
                onPagesChanged(lowerKey, upperKey, activeKey)
            }

            val activePage = pageLookup(activeId) ?: pages.firstOrNull()
            if (activePage != null) {
                val pageRelativeScroll = resolveActivePageRelativeScroll(currentScene, vp, activeId)
                onInternalScrollChanged(activePage, pageRelativeScroll)
            }
        }

        // 2. Predict lookahead and trigger prefetch
        val requests = predictor.predict(currentScene, vp, motion)
        adapter.schedulePrefetch(requests)

        // 3. Ensure foreground acquire for all visible nodes
        for (node in visibleNodes) {
            val pageId = node.pageId
            if (!decodedBitmaps.containsKey(pageId)) {
                coroutineScope.launch {
                    adapter.acquireAsset(pageId)
                }
            }
        }

        // 4. Memory Cache Sliding Window Eviction:
        // Keep active visible nodes, plus lookahead (~7 ahead) and retention (~4 behind).
        // Evict out-of-range bitmaps from memory while preserving their exact geometry in VerticalReaderScene.
        if (visibleNodes.isNotEmpty()) {
            val visibleIndices = visibleNodes.mapNotNull { pageLookup(it.pageId)?.index }
            if (visibleIndices.isNotEmpty()) {
                val minIndex = visibleIndices.min()
                val maxIndex = visibleIndices.max()
                val minKeep = (minIndex - 4).coerceAtLeast(0)
                val maxKeep = (maxIndex + 7).coerceAtMost(pages.size - 1)

                val toEvict = mutableListOf<PageId>()
                for (cachedId in decodedBitmaps.keys) {
                    val idx = pageLookup(cachedId)?.index ?: continue
                    if (idx < minKeep || idx > maxKeep) {
                        toEvict.add(cachedId)
                    }
                }
                for (evictId in toEvict) {
                    decodedBitmaps.remove(evictId)
                    adapter.evictAsset(evictId)
                }
            }
        }
    }

    fun dispatchWebtoonScroll(deltaPx: Float) {
        if (deltaPx.isFinite() && deltaPx != 0f && activeScene != null && viewportHeightPx > 0f) {
            val maxScroll = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
            scrollState.maxScrollY = maxScroll
            val newScroll = (scrollState.scrollY + deltaPx).coerceIn(0f, maxScroll)
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
        dispatchWebtoonScroll(
            resolveWebtoonGestureBoundaryHandoff(
                scale = canvasScale,
                desiredY = desiredY,
                boundedY = bounded.y,
                isTransformGesture = isTransformGesture,
            ).toFloat(),
        )
    }

    fun contentCoordinateAtFocus(
        scale: Float,
        offset: Float,
        focus: Float,
        layoutSize: Int,
    ): Float {
        val safeScale = scale.coerceAtLeast(0.01f)
        val center = layoutSize / 2f
        return center + (focus - offset - center) / safeScale
    }

    fun applyCanvasScaleAtFocus(
        nextScale: Float,
        focus: Offset,
        isTransformGesture: Boolean = false,
    ) {
        if (!nextScale.isFinite() || !focus.x.isFinite() || !focus.y.isFinite()) return
        val previousScale = canvasScale
        val previousLayoutHeight = resolveWebtoonLayoutViewportHeight(viewportHeightPx.toInt(), previousScale)
        val nextLayoutHeight = resolveWebtoonLayoutViewportHeight(viewportHeightPx.toInt(), nextScale)
        val focusedContentY = contentCoordinateAtFocus(
            scale = previousScale,
            offset = canvasOffsetY,
            focus = focus.y,
            layoutSize = previousLayoutHeight,
        )
        val nextCenter = Offset(viewportWidthPx / 2f, nextLayoutHeight / 2f)
        val focusedContentX = contentCoordinateAtFocus(
            scale = previousScale,
            offset = canvasOffsetX,
            focus = focus.x,
            layoutSize = viewportWidthPx.toInt(),
        )
        val desiredOffset = Offset(
            x = focus.x - (nextCenter.x + nextScale * (focusedContentX - nextCenter.x)),
            y = focus.y - (nextCenter.y + nextScale * (focusedContentY - nextCenter.y)),
        )
        canvasScale = nextScale
        val bounded = clampCanvasOffset(
            nextScale,
            desiredOffset.x,
            desiredOffset.y,
        )
        canvasOffsetX = bounded.x
        canvasOffsetY = bounded.y

        val newFocusedContentY = contentCoordinateAtFocus(
            scale = nextScale,
            offset = bounded.y,
            focus = focus.y,
            layoutSize = nextLayoutHeight,
        )
        if (!isTransformGesture) dispatchWebtoonScroll(focusedContentY - newFocusedContentY)
    }

    suspend fun flingCanvas(velocity: Velocity) {
        if (canvasScale <= 1f || maxOf(kotlin.math.abs(velocity.x), kotlin.math.abs(velocity.y)) < 50f) return
        coroutineScope {
            launch {
                animateDecay(canvasOffsetX, velocity.x, webtoonDecay) { value, _ ->
                    canvasOffsetX = clampCanvasOffset(canvasScale, value, canvasOffsetY).x
                }
            }
            launch {
                var previousValue = canvasOffsetY
                animateDecay(canvasOffsetY, velocity.y, webtoonDecay) { value, _ ->
                    val desiredY = canvasOffsetY + (value - previousValue)
                    val bounded = clampCanvasOffset(canvasScale, canvasOffsetX, desiredY)
                    canvasOffsetY = bounded.y
                    dispatchWebtoonScroll(resolveWebtoonBoundaryHandoff(canvasScale, desiredY, bounded.y).toFloat())
                    previousValue = value
                }
            }
        }
    }

    suspend fun animateWebtoonScaleTo(
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

    LaunchedEffect(defaultScale, viewportHeightPx, viewportWidthPx) {
        val bounded = clampCanvasOffset(canvasScale, canvasOffsetX, canvasOffsetY)
        canvasOffsetX = bounded.x
        canvasOffsetY = bounded.y
    }

    LaunchedEffect(isZoomEnabled) {
        if (wasZoomEnabled && !isZoomEnabled) {
            webtoonZoomAnimationJob?.cancel()
            webtoonFlingJob?.cancel()
            canvasScale = 1f
            canvasOffsetX = 0f
            canvasOffsetY = 0f
        }
        wasZoomEnabled = isZoomEnabled
    }

    LaunchedEffect(webtoonZoomCommand, zoomCommand, isAnimationEnabled) {
        val command = webtoonZoomCommand ?: zoomCommand?.let { ComposeWebtoonZoomCommand(it.id, it.factor) }
        command?.let { cmd ->
            val animationJob = currentCoroutineContext().job
            webtoonZoomAnimationJob = animationJob
            try {
                animateWebtoonScaleTo(
                    (canvasScale * cmd.factor).coerceIn(0.5f, READER_WEBTOON_MAX_ZOOM_SCALE),
                )
            } finally {
                if (webtoonZoomAnimationJob === animationJob) webtoonZoomAnimationJob = null
            }
        }
    }

    LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled, hasAppliedInitialPosition) {
        if (!hasAppliedInitialPosition || activeScene == null) return@LaunchedEffect
        val position = requestedPage?.takeIf { it in pages.indices } ?: return@LaunchedEffect
        val page = pages[position]
        val targetY = activeScene.resolvePageScrollPosition(PageId(page.readerKey)) ?: return@LaunchedEffect
        webtoonNavigationJob?.cancel()
        webtoonNavigationJob = webtoonNavigationScope.launch {
            val maxScroll = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
            val clampedTargetY = targetY.coerceIn(0f, maxScroll)
            if (requestedPageSmooth && isAnimationEnabled) {
                var previousValue = scrollState.scrollY
                animate(
                    initialValue = scrollState.scrollY,
                    targetValue = clampedTargetY,
                ) { value, _ ->
                    dispatchWebtoonScroll(value - previousValue)
                    previousValue = value
                }
            } else {
                scrollState.snapTo(clampedTargetY)
                updateResourceWindow(activeScene, clampedTargetY, ViewportMotion.Idle)
            }
        }
    }

    var previousWebtoonPageTurnRequest by remember {
        mutableStateOf(webtoonPageTurnRequest)
    }
    LaunchedEffect(
        webtoonPageTurnRequest,
        viewportHeightPx,
        webtoonPageTurnDistanceFraction,
        isAnimationEnabled,
        hasAppliedInitialPosition,
    ) {
        val request = webtoonPageTurnRequest ?: return@LaunchedEffect
        if (!hasAppliedInitialPosition || viewportHeightPx <= 0) return@LaunchedEffect
        val requestDelta = resolveWebtoonPageTurnRequestDelta(previousWebtoonPageTurnRequest, request)
        previousWebtoonPageTurnRequest = request
        val scrollDelta = resolveWebtoonPageTurnDistance(
            viewportHeightPx = viewportHeightPx.toInt(),
            scale = canvasScale,
            delta = requestDelta,
            distanceFraction = webtoonPageTurnDistanceFraction,
        )
        if (!scrollDelta.isFinite() || scrollDelta == 0f) return@LaunchedEffect
        if (isAnimationEnabled) {
            var previousValue = 0f
            animate(
                initialValue = 0f,
                targetValue = scrollDelta,
            ) { value, _ ->
                dispatchWebtoonScroll(value - previousValue)
                previousValue = value
            }
        } else {
            dispatchWebtoonScroll(scrollDelta)
        }
    }

    var previousWebtoonScrollRequest by remember { mutableStateOf<ComposeReaderScrollRequest?>(null) }
    LaunchedEffect(webtoonScrollRequest) {
        webtoonScrollRequest?.let { request ->
            val requestDelta = resolveScrollRequestDelta(previousWebtoonScrollRequest, request)
            previousWebtoonScrollRequest = request
            if (request.smooth && isAnimationEnabled) {
                var previousValue = 0f
                animate(
                    initialValue = 0f,
                    targetValue = requestDelta.toFloat(),
                ) { value, _ ->
                    dispatchWebtoonScroll(value - previousValue)
                    previousValue = value
                }
            } else {
                dispatchWebtoonScroll(requestDelta.toFloat())
            }
        }
    }

    val pullThresholdPx = viewportHeightPx * WEBTOON_PULL_THRESHOLD
    fun handleOverScroll(overscrollDeltaY: Float) {
        if (!isPullGestureEnabled || viewportHeightPx <= 0f) return
        val availableY = -overscrollDeltaY
        val retracted = pullState.retract(availableY)
        pullState = if (retracted != pullState) {
            retracted
        } else {
            pullState.pullAtBoundary(
                availableY = availableY,
                canScrollBackward = scrollState.scrollY > 0f,
                canScrollForward = scrollState.scrollY < scrollState.maxScrollY,
                maxDistancePx = viewportHeightPx,
            )
        }
    }
    fun handleReleaseOverScroll() {
        if (!isPullGestureEnabled) return
        when (pullState.release(pullThresholdPx)) {
            WebtoonPullDirection.PREVIOUS -> if (canGoPreviousChapter) onPullChapter(-1)
            WebtoonPullDirection.NEXT -> if (canGoNextChapter) onPullChapter(1)
            null -> Unit
        }
        pullState = WebtoonPullState()
    }



    // Wire anchor compensation whenever an exact asset dimension is resolved
    DisposableEffect(adapter, activeScene) {
        if (activeScene != null) {
            adapter.onAssetLoaded = { pageId, asset ->
                if (asset is ReaderImageAsset.ComposeImage) {
                    val bmp = asset.imageBitmap
                    decodedBitmaps[pageId] = bmp
                    val vp = ReaderViewport(
                        FloatRect.fromLtwh(0f, scrollState.scrollY, viewportWidthPx, viewportHeightPx),
                    )
                    val compensation = activeScene.updatePageHint(
                        pageId = pageId,
                        newHint = PageGeometryHint.Exact(bmp.width, bmp.height),
                        currentViewport = vp,
                    )
                    if (viewportHeightPx > 0f) {
                        scrollState.maxScrollY = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
                    }
                    if (compensation != null && compensation.deltaY != 0f) {
                        scrollState.snapBy(compensation.deltaY)
                    }
                    // Immediately refresh resource window so newly visible pages from contraction are acquired
                    updateResourceWindow(activeScene, scrollState.scrollY, currentMotion)
                }
            }
        }
        onDispose {
            adapter.onAssetLoaded = null
        }
    }

    // Initial positioning and viewport priming
    LaunchedEffect(activeScene, viewportWidthPx, viewportHeightPx) {
        if (activeScene != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
            if (!hasAppliedInitialPosition) {
                val maxScroll = (activeScene.totalSceneHeight - viewportHeightPx).coerceAtLeast(0f)
                scrollState.maxScrollY = maxScroll
                val targetY = initialTargetScrollY.coerceIn(0f, maxScroll)
                scrollState.snapTo(targetY)
                hasAppliedInitialPosition = true
            }
            updateResourceWindow(activeScene, scrollState.scrollY, ViewportMotion.Idle)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerBackgroundColor))
            .onSizeChanged { size ->
                viewportWidthPx = size.width.toFloat()
                viewportHeightPx = size.height.toFloat()
            }
            .pointerInput(isZoomEnabled) {
                if (isZoomEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        webtoonFlingJob?.cancel()
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
                                webtoonZoomAnimationJob?.cancel()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                if (centroid.x.isFinite() && centroid.y.isFinite() &&
                                    pan.x.isFinite() && pan.y.isFinite() && zoom.isFinite()
                                ) {
                                    val previousScale = canvasScale
                                    val nextScale = (previousScale * zoom)
                                        .coerceIn(0.5f, READER_WEBTOON_MAX_ZOOM_SCALE)
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
                            webtoonFlingJob = zoomAnimationScope.launch {
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
                        val previousPosition = lastTapPosition
                        val isDoubleTapCandidate = isTapGridDoubleTapCandidate(
                            previousPosition = previousPosition,
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
                            webtoonZoomAnimationJob?.cancel()
                            webtoonZoomAnimationJob = zoomAnimationScope.launch {
                                animateWebtoonScaleTo(targetScale, focus = down.position)
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
                ComposeSceneRenderer(
                    scene = activeScene,
                    scrollState = scrollState,
                    placeholderColor = Color.DarkGray,
                    pageLabelProvider = { pageId ->
                        pageLookup(pageId)?.index?.let { "第 ${it + 1} 页" } ?: ""
                    },
                    imageColorFilter = imageColorFilter,
                    readerAssetProvider = { id ->
                        decodedBitmaps[id]?.let { ReaderImageAsset.ComposeImage(id, it) } ?: adapter.getCachedAsset(id)
                    },
                    onScrollProgressChanged = { currentY, _ ->
                        updateResourceWindow(activeScene, currentY, currentMotion)
                    },
                    onMotionChanged = { motion ->
                        currentMotion = motion
                        AdaptiveRefreshRateHelper.applyPreference(view, motion)
                    },
                    onOverScroll = ::handleOverScroll,
                    onReleaseOverScroll = ::handleReleaseOverScroll,
                    modifier = Modifier.fillMaxSize(),
                )
                SceneReaderLoadStatus(
                    pipeline = adapter,
                    pageId = statusPageId,
                    onRetry = { coroutineScope.launch { adapter.retryAsset(statusPageId) } },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullState.topDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoPreviousChapter) R.string.pull_to_prev_chapter else R.string.pull_top_no_prev),
            modifier = Modifier.align(Alignment.TopCenter),
        )
        WebtoonPullFeedback(
            progress = if (pullThresholdPx > 0f) pullState.bottomDistancePx / pullThresholdPx else 0f,
            text = stringResource(if (canGoNextChapter) R.string.pull_to_next_chapter else R.string.pull_bottom_no_next),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Only the active page's low-frequency resource state participates in this overlay's composition. */
@Composable
private fun SceneReaderLoadStatus(
    pipeline: ReaderImagePipeline,
    pageId: PageId,
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
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.cause.getDisplayMessage(LocalContext.current.resources),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        else -> Box(modifier) {
            ReaderPageLoading((state as? ReaderImageLoadState.Loading)?.progress)
        }
    }
}

internal fun createInitialScenePageHints(
    pages: List<ReaderPage>,
    adapter: KototoroImagePipelineAdapter? = null,
    defaultRatio: Float = 1.0f,
): List<Pair<PageId, PageGeometryHint>> {
    return pages.map { page ->
        val pageId = PageId(page.readerKey)
        val cached = adapter?.getCachedAsset(pageId)
        val hint = if (cached is ReaderImageAsset.ComposeImage) {
            PageGeometryHint.Exact(cached.imageBitmap.width, cached.imageBitmap.height)
        } else {
            PageGeometryHint.Estimated(ratio = defaultRatio)
        }
        pageId to hint
    }
}

internal fun resolveActivePageRelativeScroll(
    scene: VerticalReaderScene,
    viewport: ReaderViewport,
    activePageId: PageId?,
): Int {
    if (activePageId == null) return 0
    val frame = scene.resolve(viewport)
    val node = frame.visibleNodes.find { it.pageId == activePageId }
    return if (node != null) {
        (viewport.bounds.top - node.sceneBounds.top).roundToInt().coerceAtLeast(0)
    } else {
        0
    }
}
