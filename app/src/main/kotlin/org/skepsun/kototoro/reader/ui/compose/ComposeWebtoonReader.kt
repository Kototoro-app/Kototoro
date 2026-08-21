package org.skepsun.kototoro.reader.ui.compose


import android.graphics.Bitmap
import android.net.Uri
import android.graphics.drawable.Animatable
import android.util.Log
import android.view.ViewConfiguration
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.DrawableImage
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import coil3.request.allowHardware
import coil3.request.transformations
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.image.AvifAnimatedDrawable
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeWebtoonReader(
    pages: List<ReaderPage>,
    initialPage: Int,
    initialScroll: Int,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    onPagesChanged: (lowerPageKey: Long, upperPageKey: Long, activePageKey: Long) -> Unit,
    onInternalScrollChanged: (ReaderPage, Int) -> Unit,
    requestedPage: Int? = null,
    requestedPageSmooth: Boolean = false,
    webtoonScrollRequest: ComposeReaderScrollRequest? = null,
    zoomCommand: ComposeReaderZoomCommand? = null,
    webtoonZoomCommand: ComposeWebtoonZoomCommand? = null,
    isZoomEnabled: Boolean = false,
    defaultScale: Float = 1f,
    isGapsEnabled: Boolean = false,
    isPullGestureEnabled: Boolean = false,
    canGoPreviousChapter: Boolean = true,
    canGoNextChapter: Boolean = true,
    onPullChapter: (Int) -> Unit = {},
    onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
    onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
    resolveErrorStringId: (Throwable) -> Int = { 0 },
    isAnimationEnabled: Boolean = true,
    readerBackgroundColor: Int = android.graphics.Color.BLACK,
    imageColorFilter: ColorFilter? = null,
    bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    isReaderOptimizationEnabled: Boolean = false,
    isPreloadReductionEnabled: Boolean = false,
    isCropEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val initialPosition = initialPage.coerceIn(pages.indices)
    val listState = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(
            aheadFraction = resolveWebtoonAheadCacheFraction(isPreloadReductionEnabled),
            behindFraction = 0f,
        ),
        initialFirstVisibleItemIndex = initialPosition,
    )
    val visiblePageRange by remember(listState) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) IntRange.EMPTY else visibleItems.first().index..visibleItems.last().index
        }
    }
    val pageKeys = remember(pages) { pages.map(ReaderPage::readerKey) }
    val configuration = LocalConfiguration.current
    val viewportConfiguration = WebtoonViewportConfiguration(
        orientation = configuration.orientation,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
    var appliedViewportConfiguration by remember { mutableStateOf(viewportConfiguration) }
    val viewportConfigurationChanged = appliedViewportConfiguration != viewportConfiguration
    val viewportConfigurationChangedState = rememberUpdatedState(viewportConfigurationChanged)
    val currentPages by rememberUpdatedState(pages)
    val currentPageKeys by rememberUpdatedState(pageKeys)
    val currentOnPagesChanged by rememberUpdatedState(onPagesChanged)
    val currentOnInternalScrollChanged by rememberUpdatedState(onInternalScrollChanged)
    val stableViewportAnchor = remember {
        WebtoonViewportAnchorState(
            pageKey = pageKeys[initialPosition],
            offsetPx = 0,
        )
    }
    var hasAppliedInitialPosition by remember { mutableStateOf(false) }
    var isAnchorRestorePending by remember { mutableStateOf(true) }
    var appliedPageKeys by remember { mutableStateOf(pageKeys) }
    val isPageWindowAnchorShifted = requiresWebtoonAnchorRestore(
        previousPageKeys = appliedPageKeys,
        pageKeys = pageKeys,
        anchorPageKey = stableViewportAnchor.pageKey,
    )
    val isPageWindowAnchorShiftedState = rememberUpdatedState(isPageWindowAnchorShifted)
    // Keep dimensions outside individual lazy items. When an item is recycled and later returns
    // from Coil's cache, its height is known before the bitmap is drawn, preventing scroll jumps.
    val imageSizes = remember { mutableStateMapOf<Long, WebtoonImageSize>() }
    val initialPageKey = pageKeys[initialPosition]
    val initialPageSize = imageSizes[initialPageKey]
    var pendingAnchor by remember { mutableStateOf<WebtoonListAnchor?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var pullState by remember { mutableStateOf(WebtoonPullState()) }
    var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(0.5f, 1f)) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }
    val zoomAnimationScope = rememberCoroutineScope()
    val webtoonNavigationScope = rememberCoroutineScope()
    val context = LocalContext.current
    val doubleTapSlop = remember(context) {
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }
    val webtoonDecay = FloatExponentialDecaySpec()
    var webtoonZoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var webtoonFlingJob by remember { mutableStateOf<Job?>(null) }
    var webtoonNavigationJob by remember { mutableStateOf<Job?>(null) }
    var wasZoomEnabled by remember { mutableStateOf(isZoomEnabled) }
    var hasAppliedInitialScroll by remember { mutableStateOf(initialScroll <= 0) }
    val shiftedAnchorPosition = if (isPageWindowAnchorShifted) {
        resolveWebtoonAnchorPosition(pageKeys, stableViewportAnchor.pageKey)
    } else {
        -1
    }
    SideEffect {
        val canRequestShiftedAnchor =
            shiftedAnchorPosition >= 0 &&
            hasAppliedInitialPosition &&
            hasAppliedInitialScroll &&
            viewportWidthPx > 0 &&
            viewportHeightPx > 0
        if (canRequestShiftedAnchor) {
            Log.d(
                READER_WINDOW_LOG_TAG,
                "request anchor key=${stableViewportAnchor.pageKey} " +
                    "from=${appliedPageKeys.indexOf(stableViewportAnchor.pageKey)} to=$shiftedAnchorPosition " +
                    "offset=${stableViewportAnchor.offsetPx} windowSize=${pageKeys.size}",
            )
            listState.requestScrollToItem(shiftedAnchorPosition, stableViewportAnchor.offsetPx)
        } else if (
            hasAppliedInitialPosition &&
            hasAppliedInitialScroll &&
            viewportWidthPx > 0 &&
            viewportHeightPx > 0 &&
            requiresWebtoonWindowReplacement(
                appliedPageKeys,
                pageKeys,
                stableViewportAnchor.pageKey,
            ) && initialPosition in pageKeys.indices
        ) {
            // The window no longer contains the anchored page: a chapter switch replaced it
            // (as opposed to expanding it in place). Without this reset the LazyColumn keeps
            // its previous scroll index, which maps to an arbitrary page in the new chapter
            // and corrupts the persisted progress. Jump to the authoritative target page
            // carried by ReaderState instead.
            Log.d(
                READER_WINDOW_LOG_TAG,
                "replace window anchor=${stableViewportAnchor.pageKey} " +
                    "target=${pageKeys[initialPosition]} position=$initialPosition " +
                    "previousWindowSize=${appliedPageKeys.size} windowSize=${pageKeys.size}",
            )
            stableViewportAnchor.pageKey = pageKeys[initialPosition]
            stableViewportAnchor.offsetPx = 0
            listState.requestScrollToItem(initialPosition, 0)
        }
        if (!isPageWindowAnchorShifted || canRequestShiftedAnchor) appliedPageKeys = pageKeys
    }
    fun measurementFor(position: Int): WebtoonViewportMeasurement {
        val size = pages.getOrNull(position)?.let { page -> imageSizes[page.readerKey] }
        return measureWebtoonViewport(
            viewportHeightPx = viewportHeightPx,
            availableWidthPx = viewportWidthPx,
            imageWidthPx = size?.width,
            imageHeightPx = size?.height,
        )
    }
    fun dispatchWebtoonScroll(deltaPx: Float) {
        if (deltaPx.isFinite() && deltaPx != 0f) listState.dispatchRawDelta(deltaPx)
    }
    fun clampCanvasOffset(scale: Float, x: Float, y: Float): Offset {
        val bounds = resolveWebtoonCanvasOffsetBounds(viewportWidthPx, viewportHeightPx, scale)
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
        val previousLayoutHeight = resolveWebtoonLayoutViewportHeight(viewportHeightPx, previousScale)
        val nextLayoutHeight = resolveWebtoonLayoutViewportHeight(viewportHeightPx, nextScale)
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
            layoutSize = viewportWidthPx,
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

        // When the scale boundary removes translation room, preserve the focused content by
        // handing the equivalent displacement back to the scroll container.
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
    LaunchedEffect(webtoonZoomCommand, isAnimationEnabled) {
        webtoonZoomCommand?.let { command ->
            val animationJob = currentCoroutineContext().job
            webtoonZoomAnimationJob = animationJob
            try {
                animateWebtoonScaleTo(
                    (canvasScale * command.factor).coerceIn(0.5f, READER_WEBTOON_MAX_ZOOM_SCALE),
                )
            } finally {
                if (webtoonZoomAnimationJob === animationJob) webtoonZoomAnimationJob = null
            }
        }
    }
    LaunchedEffect(initialPosition, pageKeys, viewportWidthPx, viewportHeightPx) {
        if (!hasAppliedInitialPosition && viewportWidthPx > 0 && viewportHeightPx > 0) {
            val targetPage = pages[initialPosition]
            isAnchorRestorePending = true
            pendingAnchor = null
            stableViewportAnchor.pageKey = targetPage.readerKey
            stableViewportAnchor.offsetPx = 0
            listState.scrollToItem(initialPosition)
            snapshotFlow { listState.firstVisibleItemIndex }
                .first { actualPosition -> actualPosition == initialPosition }
            appliedViewportConfiguration = viewportConfiguration
            hasAppliedInitialPosition = true
            isAnchorRestorePending = !hasAppliedInitialScroll
        }
    }

    LaunchedEffect(initialPageSize, initialPosition, viewportWidthPx, viewportHeightPx, hasAppliedInitialPosition) {
        if (initialScroll <= 0 || hasAppliedInitialScroll || !hasAppliedInitialPosition || initialPageSize == null) {
            return@LaunchedEffect
        }
        val restoredOffset = initialScroll.coerceIn(
            0,
            (measurementFor(initialPosition).itemHeightPx - viewportHeightPx).coerceAtLeast(0),
        )
        isAnchorRestorePending = true
        pendingAnchor = null
        stableViewportAnchor.pageKey = initialPageKey
        stableViewportAnchor.offsetPx = restoredOffset
        listState.scrollToItem(initialPosition, restoredOffset)
        hasAppliedInitialScroll = true
        isAnchorRestorePending = false
    }

    LaunchedEffect(pageKeys, viewportWidthPx, viewportHeightPx, hasAppliedInitialPosition, hasAppliedInitialScroll) {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return@LaunchedEffect
        if (!hasAppliedInitialPosition || !hasAppliedInitialScroll) return@LaunchedEffect
        val anchorPosition = resolveWebtoonAnchorPosition(pageKeys, stableViewportAnchor.pageKey)
        if ((isAnchorRestorePending || viewportConfigurationChanged) && anchorPosition >= 0) {
            isAnchorRestorePending = true
            Log.d(
                READER_WINDOW_LOG_TAG,
                "restore anchor key=${stableViewportAnchor.pageKey} " +
                    "to=$anchorPosition offset=${stableViewportAnchor.offsetPx} windowSize=${pageKeys.size}",
            )
            listState.scrollToItem(anchorPosition, stableViewportAnchor.offsetPx)
            Log.d(
                READER_WINDOW_LOG_TAG,
                "restored anchor key=${stableViewportAnchor.pageKey} position=${listState.firstVisibleItemIndex} " +
                    "actualKey=${listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key}",
            )
        }
        appliedViewportConfiguration = viewportConfiguration
        isAnchorRestorePending = false
    }
    LaunchedEffect(listState) {
        var reportedPageKeys: Triple<Long, Long, Long>? = null
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val isViewportLayoutReady = isWebtoonViewportLayoutReady(
                visibleItemSizesPx = visibleItems.map { it.size },
                viewportHeightPx = viewportHeightPx,
            )
            val activePageKey = resolveLastEndVisibleWebtoonPageKey(
                items = visibleItems.mapNotNull { item ->
                    (item.key as? Long)?.let { key -> WebtoonVisibleItem(key, item.offset, item.size) }
                },
                viewportStartPx = layoutInfo.viewportStartOffset,
                viewportEndPx = layoutInfo.viewportEndOffset,
            )
            WebtoonViewportUpdate(
                lowerPageKey = visibleItems.firstOrNull()?.key as? Long,
                upperPageKey = visibleItems.lastOrNull()?.key as? Long,
                activePageKey = activePageKey,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                shouldTrackViewport = shouldTrackWebtoonViewport(
                    isAnchorRestorePending = isAnchorRestorePending,
                    isPageWindowAnchorShifted = isPageWindowAnchorShiftedState.value,
                    viewportConfigurationChanged = viewportConfigurationChangedState.value,
                    isViewportLayoutReady = isViewportLayoutReady,
                ),
            )
        }
            .distinctUntilChanged()
            .collect { viewport ->
                if (!viewport.shouldTrackViewport) {
                    return@collect
                }
                val lowerPageKey = viewport.lowerPageKey ?: return@collect
                val upperPageKey = viewport.upperPageKey ?: return@collect
                val activePageKey = viewport.activePageKey ?: return@collect
                val pagesSnapshot = currentPages
                val visibleRange = resolveWebtoonVisiblePageRange(
                    pageKeys = currentPageKeys,
                    lowerPageKey = lowerPageKey,
                    upperPageKey = upperPageKey,
                ) ?: return@collect
                val page = pagesSnapshot[visibleRange.first]
                stableViewportAnchor.pageKey = lowerPageKey
                stableViewportAnchor.offsetPx = viewport.firstVisibleItemScrollOffset
                val visiblePageKeys = Triple(lowerPageKey, upperPageKey, activePageKey)
                if (reportedPageKeys != visiblePageKeys) {
                    reportedPageKeys = visiblePageKeys
                    currentOnPagesChanged(lowerPageKey, upperPageKey, activePageKey)
                }
                currentOnInternalScrollChanged(page, viewport.firstVisibleItemScrollOffset)
            }
    }
    LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled, isAnchorRestorePending) {
        if (isAnchorRestorePending) return@LaunchedEffect
        val position = requestedPage?.takeIf { it in pages.indices } ?: return@LaunchedEffect
        webtoonNavigationJob?.cancel()
        webtoonNavigationJob = webtoonNavigationScope.launch {
            if (requestedPageSmooth && isAnimationEnabled) {
                listState.animateScrollToItem(position)
            } else {
                listState.scrollToItem(position)
            }
        }
    }
    var previousWebtoonScrollRequest by remember { mutableStateOf<ComposeReaderScrollRequest?>(null) }
    LaunchedEffect(webtoonScrollRequest) {
        webtoonScrollRequest?.let { request ->
            val requestDelta = resolveScrollRequestDelta(previousWebtoonScrollRequest, request)
            previousWebtoonScrollRequest = request
            fun dispatchScroll(delta: Float) = dispatchWebtoonScroll(delta)
            if (request.smooth) {
                var previousValue = 0f
                animate(
                    initialValue = 0f,
                    targetValue = requestDelta.toFloat(),
                ) { value, _ ->
                    dispatchScroll(value - previousValue)
                    previousValue = value
                }
            } else {
                dispatchScroll(requestDelta.toFloat())
            }
        }
    }
    LaunchedEffect(pendingAnchor, isAnchorRestorePending, pageKeys) {
        pendingAnchor?.let { anchor ->
            if (isAnchorRestorePending) return@let
            val anchorPosition = resolveWebtoonAnchorPosition(pageKeys, anchor.pageKey)
            if (!listState.isScrollInProgress && anchorPosition >= 0) {
                listState.scrollToItem(anchorPosition, anchor.offsetPx)
            }
            pendingAnchor = null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerBackgroundColor))
            .onSizeChanged { size ->
                // Keep the viewport independent from the zoomed-out LazyColumn layout.
                if (size.width != viewportWidthPx || size.height != viewportHeightPx) {
                    isAnchorRestorePending = true
                }
                viewportWidthPx = size.width
                viewportHeightPx = size.height
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
            if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return@BoxWithConstraints
            val pullThresholdPx = viewportHeightPx * WEBTOON_PULL_THRESHOLD
        val nestedScrollConnection = remember(
            listState,
            pages,
            viewportWidthPx,
            viewportHeightPx,
            isPullGestureEnabled,
            canGoPreviousChapter,
            canGoNextChapter,
        ) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && isPullGestureEnabled) {
                        val retracted = pullState.retract(available.y)
                        if (retracted != pullState) {
                            val consumed = when {
                                pullState.topDistancePx > retracted.topDistancePx ->
                                    -(pullState.topDistancePx - retracted.topDistancePx)
                                else -> pullState.bottomDistancePx - retracted.bottomDistancePx
                            }
                            pullState = retracted
                            return Offset(0f, consumed)
                        }
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || !isPullGestureEnabled || available.y == 0f) {
                        return Offset.Zero
                    }
                    pullState = pullState.pullAtBoundary(
                        availableY = available.y,
                        canScrollBackward = listState.canScrollBackward,
                        canScrollForward = listState.canScrollForward,
                        maxDistancePx = viewportHeightPx.toFloat(),
                    )
                    return Offset(x = 0f, y = available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    releasePull()
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    releasePull()
                    return Velocity.Zero
                }

                private fun releasePull() {
                    when (pullState.release(pullThresholdPx)) {
                        WebtoonPullDirection.PREVIOUS -> if (canGoPreviousChapter) onPullChapter(-1)
                        WebtoonPullDirection.NEXT -> if (canGoNextChapter) onPullChapter(1)
                        null -> Unit
                    }
                    pullState = WebtoonPullState()
                }
            }
        }
        val pageGap = if (isGapsEnabled) dimensionResource(R.dimen.webtoon_pages_gap) else 0.dp
        // Keep the scroll container inside a separate scaled canvas. This mirrors the legacy
        // WebtoonScalingFrame and keeps content outside the current list window in the same
        // transform coordinate space.
        Box(
            modifier = Modifier
                .requiredSize(
                    width = maxWidth,
                    height = if (canvasScale < 1f) maxHeight / canvasScale else maxHeight,
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
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(pageGap),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
            ) {
                items(
                    count = pages.size,
                    key = { pages[it].readerKey },
                    contentType = { WEBTOON_PAGE_CONTENT_TYPE },
                ) { position ->
                    val measurement = measurementFor(position)
                    ComposeWebtoonPage(
                        page = pages[position],
                        imageLoader = imageLoader,
                        imagePipeline = imagePipeline,
                        measurement = measurement,
                        decodeWidthPx = viewportWidthPx,
                        decodeHeightPx = viewportHeightPx,
                        bitmapConfig = bitmapConfig,
                        onImageSizeResolved = { width, height ->
                            if (width > 0 && height > 0) {
                                val pageKey = pages[position].readerKey
                                val newSize = WebtoonImageSize(width, height)
                                if (imageSizes[pageKey] != newSize) {
                                    if (hasAppliedInitialPosition && !listState.isScrollInProgress) {
                                        val visiblePosition = listState.firstVisibleItemIndex
                                    pendingAnchor = WebtoonListAnchor(
                                        pageKey = if (isAnchorRestorePending) {
                                            stableViewportAnchor.pageKey
                                        } else {
                                            pages.getOrNull(visiblePosition)?.readerKey
                                                ?: stableViewportAnchor.pageKey
                                        },
                                            offsetPx = if (isAnchorRestorePending) {
                                                0
                                            } else {
                                                listState.firstVisibleItemScrollOffset
                                            },
                                        )
                                    }
                                    imageSizes[pageKey] = newSize
                                }
                            }
                        },
                        onShowErrorDetails = onShowErrorDetails,
                        onRetryError = onRetryError,
                        resolveErrorStringId = resolveErrorStringId,
                        readerBackgroundColor = readerBackgroundColor,
                        imageColorFilter = imageColorFilter,
                        isCropEnabled = isCropEnabled,
                        isReaderOptimizationEnabled = isReaderOptimizationEnabled,
                        isPageVisible = position in visiblePageRange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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

@Composable
private fun WebtoonPullFeedback(progress: Float, text: String, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { alpha = progress.coerceIn(0.25f, 1f) },
    )
}

@Composable
private fun ComposeWebtoonPage(
    page: ReaderPage,
    imageLoader: ImageLoader,
    imagePipeline: ComposeReaderImagePipeline,
    measurement: WebtoonViewportMeasurement,
    onImageSizeResolved: (width: Int, height: Int) -> Unit,
    onShowErrorDetails: (Throwable, String?) -> Unit,
    onRetryError: (Throwable, retry: () -> Unit) -> Unit,
    resolveErrorStringId: (Throwable) -> Int,
    readerBackgroundColor: Int,
    imageColorFilter: ColorFilter?,
    isCropEnabled: Boolean,
    isReaderOptimizationEnabled: Boolean,
    decodeWidthPx: Int,
    decodeHeightPx: Int,
    bitmapConfig: Bitmap.Config,
    isPageVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    var retryKey by remember(page.readerKey) { mutableIntStateOf(0) }
    var renderError by remember(page.readerKey) { mutableStateOf<Throwable?>(null) }
    var forceCoil by remember(page.readerKey) { mutableStateOf(false) }
    val state by produceState<ComposeReaderImageState>(
        initialValue = imagePipeline.cachedState(page.readerKey) ?: ComposeReaderImageState.LoadingOriginal,
        key1 = page.readerKey,
        key2 = retryKey,
    ) {
        imagePipeline.observe(page, force = retryKey > 0).collect { value = it }
    }
    LaunchedEffect(retryKey) {
        renderError = null
    }
    val displayUri = when (val value = state) {
        is ComposeReaderImageState.OriginalReady -> value.original
        is ComposeReaderImageState.Enhancing -> value.original
        is ComposeReaderImageState.EnhancedReady -> value.enhanced
        else -> null
    }
    val cropBoundsState = rememberReaderCropBounds(displayUri, isCropEnabled, imagePipeline)
    val cropBounds = (cropBoundsState as? ReaderCropBoundsState.Ready)?.bounds
    LaunchedEffect(displayUri) {
        forceCoil = false
        renderError = null
    }
    val itemHeight = with(LocalDensity.current) { measurement.itemHeightPx.toDp() }
    val canUseSubsampling = !forceCoil

    Box(
        modifier = modifier
            .height(itemHeight)
            .clipToBounds()
            .background(Color(readerBackgroundColor)),
        contentAlignment = Alignment.Center,
    ) {
        if (cropBoundsState == ReaderCropBoundsState.Loading) {
            ReaderPageLoading(progress = null)
        } else if (renderError != null) {
            ReaderPageError(
                cause = renderError!!,
                onRetry = { onRetryError(renderError!!) { retryKey++ } },
                resolveStringId = resolveErrorStringId(renderError!!),
                onShowDetails = { onShowErrorDetails(renderError!!, page.url) },
            )
        } else when (val value = state) {
            ComposeReaderImageState.LoadingOriginal -> ReaderPageLoading(progress = null)
            is ComposeReaderImageState.Downloading -> ReaderPageLoading(progress = value.progress)
            is ComposeReaderImageState.PreviewReady -> ReaderPreviewImage(
                page = page,
                previewUrl = value.previewUrl,
                imageLoader = imageLoader,
                colorFilter = imageColorFilter,
                isCropEnabled = isCropEnabled,
                isReaderOptimizationEnabled = isReaderOptimizationEnabled,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(unbounded = true),
            )
            is ComposeReaderImageState.OriginalReady -> if (canUseSubsampling && !value.isAnimated) {
                ComposeWebtoonStaticSubsamplingImage(
                    uri = value.original,
                    split = page.split,
                    cropBounds = cropBounds,
                    bitmapConfig = bitmapConfig,
                    colorFilter = imageColorFilter,
                    onImageSizeResolved = { width, height ->
                        imagePipeline.onImageDecoded(page, width, height)
                        onImageSizeResolved(width, height)
                    },
                    onImageError = { forceCoil = true },
                    placeholder = {
                        if (isPageVisible && page.split == ReaderPageSplit.NONE) {
                            WebtoonTelephotoPlaceholder(
                                uri = value.original,
                                pageKey = page.readerKey,
                                decodeWidthPx = decodeWidthPx,
                                decodeHeightPx = decodeHeightPx,
                                imageLoader = imageLoader,
                                colorFilter = imageColorFilter,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else WebtoonImage(
                uri = value.original,
                imageLoader = imageLoader,
                pageKey = page.readerKey,
                split = page.split,
                decodeWidthPx = decodeWidthPx,
                decodeHeightPx = decodeHeightPx,
                onImageSizeResolved = { width, height ->
                    imagePipeline.onImageDecoded(page, width, height)
                    onImageSizeResolved(width, height)
                },
                colorFilter = imageColorFilter,
                isCropEnabled = isCropEnabled,
                isAnimated = value.isAnimated,
                isPageVisible = isPageVisible,
                isReaderOptimizationEnabled = isReaderOptimizationEnabled,
                onImageError = { renderError = it },
            )
            is ComposeReaderImageState.Enhancing -> if (canUseSubsampling) {
                ComposeWebtoonStaticSubsamplingImage(
                    uri = value.original,
                    split = page.split,
                    cropBounds = cropBounds,
                    bitmapConfig = bitmapConfig,
                    colorFilter = imageColorFilter,
                    onImageSizeResolved = { width, height ->
                        imagePipeline.onImageDecoded(page, width, height)
                        onImageSizeResolved(width, height)
                    },
                    onImageError = { forceCoil = true },
                    placeholder = {
                        if (isPageVisible && page.split == ReaderPageSplit.NONE) {
                            WebtoonTelephotoPlaceholder(
                                uri = value.original,
                                pageKey = page.readerKey,
                                decodeWidthPx = decodeWidthPx,
                                decodeHeightPx = decodeHeightPx,
                                imageLoader = imageLoader,
                                colorFilter = imageColorFilter,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else WebtoonImage(
                uri = value.original,
                imageLoader = imageLoader,
                pageKey = page.readerKey,
                split = page.split,
                decodeWidthPx = decodeWidthPx,
                decodeHeightPx = decodeHeightPx,
                onImageSizeResolved = { width, height ->
                    imagePipeline.onImageDecoded(page, width, height)
                    onImageSizeResolved(width, height)
                },
                colorFilter = imageColorFilter,
                isCropEnabled = isCropEnabled,
                isAnimated = false,
                isPageVisible = isPageVisible,
                isReaderOptimizationEnabled = isReaderOptimizationEnabled,
                onImageError = { renderError = it },
            )
            is ComposeReaderImageState.EnhancedReady -> if (canUseSubsampling) {
                ComposeWebtoonStaticSubsamplingImage(
                    uri = value.enhanced,
                    split = page.split,
                    cropBounds = cropBounds,
                    bitmapConfig = bitmapConfig,
                    colorFilter = imageColorFilter,
                    onImageSizeResolved = onImageSizeResolved,
                    onImageError = { forceCoil = true },
                    placeholder = {
                        if (isPageVisible && page.split == ReaderPageSplit.NONE) {
                            WebtoonTelephotoPlaceholder(
                                uri = value.enhanced,
                                pageKey = page.readerKey,
                                decodeWidthPx = decodeWidthPx,
                                decodeHeightPx = decodeHeightPx,
                                imageLoader = imageLoader,
                                colorFilter = imageColorFilter,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else WebtoonImage(
                uri = value.enhanced,
                imageLoader = imageLoader,
                pageKey = page.readerKey,
                split = page.split,
                decodeWidthPx = decodeWidthPx,
                decodeHeightPx = decodeHeightPx,
                onImageSizeResolved = onImageSizeResolved,
                colorFilter = imageColorFilter,
                isCropEnabled = isCropEnabled,
                isAnimated = false,
                isPageVisible = isPageVisible,
                isReaderOptimizationEnabled = isReaderOptimizationEnabled,
                onImageError = { renderError = it },
            )
            is ComposeReaderImageState.Failed -> ReaderPageError(
                cause = value.cause,
                onRetry = { onRetryError(value.cause) { retryKey++ } },
                resolveStringId = resolveErrorStringId(value.cause),
                onShowDetails = { onShowErrorDetails(value.cause, page.url) },
            )
        }
    }
}

@Composable
private fun WebtoonImage(
    uri: Uri,
    imageLoader: ImageLoader,
    onImageSizeResolved: (width: Int, height: Int) -> Unit,
    colorFilter: ColorFilter?,
    pageKey: Long,
    split: ReaderPageSplit,
    decodeWidthPx: Int,
    decodeHeightPx: Int,
    isCropEnabled: Boolean,
    isAnimated: Boolean,
    isPageVisible: Boolean,
    isReaderOptimizationEnabled: Boolean,
    onImageError: (Throwable) -> Unit,
) {
    if (isAnimated) {
        TelephotoCoilReaderImage(
            uri = uri,
            imageLoader = imageLoader,
            pageKey = pageKey,
            zoomMode = null,
            zoomCommand = null,
            isZoomEnabled = false,
            isAnimationEnabled = false,
            colorFilter = colorFilter,
            isPageVisible = isPageVisible,
            isReaderOptimizationEnabled = isReaderOptimizationEnabled,
            onImageSizeResolved = onImageSizeResolved,
            onImageError = onImageError,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val context = LocalContext.current
    var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
    AnimatedDrawableLifecycle(animatable, isPageVisible)
    val useSampledDecode = !isAnimated && !isCropEnabled && split == ReaderPageSplit.NONE &&
        decodeWidthPx > 0 && decodeHeightPx > 0
    AsyncImage(
        model = remember(
            uri,
            pageKey,
            split,
            isCropEnabled,
            isAnimated,
            decodeWidthPx,
            decodeHeightPx,
            isReaderOptimizationEnabled,
        ) {
            ImageRequest.Builder(context)
                .data(uri)
                .apply {
                    if (useSampledDecode) {
                        // Keep the full aspect ratio while allowing Coil's decoder to sample large strips.
                        size(Size(decodeWidthPx, decodeHeightPx))
                        .precision(Precision.INEXACT)
                    } else {
                        size(Size.ORIGINAL)
                    }
                }
                .allowHardware(!isAnimated)
                .apply {
                    if (!isAnimated) transformations(ComposeReaderPageTransformation(isCropEnabled, split))
                    if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
                }
                .build()
        },
        imageLoader = imageLoader,
        contentDescription = null,
        alignment = Alignment.TopCenter,
        contentScale = ContentScale.FillWidth,
        colorFilter = colorFilter,
        onSuccess = { result ->
            animatable = (result.result.image as? DrawableImage)?.drawable as? Animatable
            onImageSizeResolved(result.result.image.width, result.result.image.height)
        },
        onError = { result -> onImageError(result.result.throwable) },
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(unbounded = true)
    )
}

@Composable
private fun WebtoonTelephotoPlaceholder(
    uri: Uri,
    pageKey: Long,
    decodeWidthPx: Int,
    decodeHeightPx: Int,
    imageLoader: ImageLoader,
    colorFilter: ColorFilter?,
) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(uri, pageKey, decodeWidthPx, decodeHeightPx) {
            ImageRequest.Builder(context)
                .data(uri)
                .apply {
                    if (decodeWidthPx > 0 && decodeHeightPx > 0) {
                        size(Size(decodeWidthPx, decodeHeightPx))
                        precision(Precision.INEXACT)
                    }
                }
                .allowHardware(true)
                .build()
        },
        imageLoader = imageLoader,
        contentDescription = null,
        alignment = Alignment.TopCenter,
        contentScale = ContentScale.FillWidth,
        colorFilter = colorFilter,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun AnimatedDrawableLifecycle(animatable: Animatable?, isPageVisible: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(animatable, isPageVisible, isResumed) {
        if (isPageVisible && isResumed) animatable?.start() else animatable?.stop()
    }
    DisposableEffect(animatable) {
        onDispose {
            animatable?.stop()
            (animatable as? AvifAnimatedDrawable)?.release()
        }
    }
}

