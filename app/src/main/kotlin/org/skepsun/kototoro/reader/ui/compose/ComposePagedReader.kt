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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.DrawableImage
import coil3.request.SuccessResult
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderImageScalingQuality
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.image.AvifAnimatedDrawable
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.ui.resolvePagedReaderAnchorPosition
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

private data class WebtoonImageSize(
	val width: Int,
	val height: Int,
)

private data class DoublePageTransform(
	val scale: Float = 1f,
	val offsetX: Float = 0f,
	val offsetY: Float = 0f,
)

private data class ReaderPagerAnimationDebugState(
	val scrolling: Boolean,
	val currentPage: Int,
	val settledPage: Int,
	val targetPage: Int,
	val offsetPercent: Int,
	val anchorPage: Int,
)

private data class WebtoonListAnchor(
	val pageKey: Long,
	val offsetPx: Int,
)

private class WebtoonViewportAnchorState(
	var pageKey: Long,
	var offsetPx: Int,
)

private data class WebtoonViewportConfiguration(
	val orientation: Int,
	val screenWidthDp: Int,
	val screenHeightDp: Int,
)

private data class WebtoonViewportUpdate(
	val lowerPageKey: Long?,
	val upperPageKey: Long?,
	val activePageKey: Long?,
	val firstVisibleItemScrollOffset: Int,
	val shouldTrackViewport: Boolean,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposePagedReader(
	pages: List<ReaderPage>,
	initialPage: Int,
	mode: ReaderMode,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	onPageChanged: (ReaderPage) -> Unit,
	requestedPage: Int? = null,
	requestedPageSmooth: Boolean = false,
	zoomCommand: ComposeReaderZoomCommand? = null,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = { 0 },
	isAnimationEnabled: Boolean = true,
	pageAnimation: ReaderAnimation = ReaderAnimation.DEFAULT,
	readerBackground: ReaderBackground = ReaderBackground.BLACK,
	readerBackgroundColor: Int = android.graphics.Color.BLACK,
	bookBackgroundTint: Int? = null,
	imageColorFilter: ColorFilter? = null,
	bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
	isReaderOptimizationEnabled: Boolean = false,
	isPreloadReductionEnabled: Boolean = false,
	zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
	isCropEnabled: Boolean = false,
	pageOverlay: @Composable BoxScope.() -> Unit = {},
	modifier: Modifier = Modifier,
) {
	if (pages.isEmpty()) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			KototoroLoadingIndicator()
		}
		return
	}

	var displayedPages by remember { mutableStateOf(pages) }
	val reverseLayout = mode == ReaderMode.REVERSED
	val isVertical = mode == ReaderMode.VERTICAL
	val pagerState = rememberPagerState(
		initialPage = initialPage.coerceIn(displayedPages.indices),
		pageCount = { displayedPages.size },
	)
	var isRestoringPageAnchor by remember { mutableStateOf(false) }
	var advancedAnchorPage by remember(pagerState) { mutableIntStateOf(pagerState.currentPage) }
	LaunchedEffect(pagerState, pageAnimation) {
		snapshotFlow {
			Triple(
				pagerState.isScrollInProgress,
				pagerState.currentPage,
				pagerState.currentPageOffsetFraction,
			)
		}.collect { (isScrolling, currentPage, offsetFraction) ->
			if (pageAnimation == ReaderAnimation.ADVANCED) {
				advancedAnchorPage = resolveAdvancedAnimationAnchor(
					anchorPage = advancedAnchorPage,
					currentPage = currentPage,
					currentPageOffsetFraction = offsetFraction,
					isScrollInProgress = isScrolling,
				)
			}
		}
	}
	LaunchedEffect(pagerState, pageAnimation, advancedAnchorPage) {
		if (pageAnimation != ReaderAnimation.ADVANCED) return@LaunchedEffect
		snapshotFlow {
			ReaderPagerAnimationDebugState(
				scrolling = pagerState.isScrollInProgress,
				currentPage = pagerState.currentPage,
				settledPage = pagerState.settledPage,
				targetPage = pagerState.targetPage,
				offsetPercent = (pagerState.currentPageOffsetFraction * 100f).roundToInt(),
				anchorPage = advancedAnchorPage,
			)
		}.distinctUntilChanged().collect { state ->
			Log.d(READER_ANIMATION_DEBUG_TAG, "advanced single $state")
		}
	}
	var hasAppliedInitialPosition by remember { mutableStateOf(false) }
	LaunchedEffect(displayedPages) {
		if (!hasAppliedInitialPosition) {
			val target = initialPage.coerceIn(displayedPages.indices)
			if (pagerState.currentPage != target) pagerState.scrollToPage(target)
			hasAppliedInitialPosition = true
		}
	}
	LaunchedEffect(pages, pagerState.isScrollInProgress) {
		if (!pagerState.isScrollInProgress && displayedPages !== pages) {
			val anchorPageKey = displayedPages.getOrNull(pagerState.settledPage)?.readerKey
			val anchorPosition = resolvePagedReaderAnchorPosition(
				pageKeys = pages.map(ReaderPage::readerKey),
				anchorPageKey = anchorPageKey,
				fallbackPosition = pagerState.settledPage,
			) ?: return@LaunchedEffect
			isRestoringPageAnchor = true
			try {
				displayedPages = pages
				withFrameNanos { }
				if (pagerState.currentPage != anchorPosition) {
					pagerState.scrollToPage(anchorPosition)
				}
			} finally {
				isRestoringPageAnchor = false
			}
		}
	}
	LaunchedEffect(pagerState, displayedPages, isRestoringPageAnchor) {
		snapshotFlow {
			Triple(
				pagerState.isScrollInProgress,
				pagerState.settledPage,
				isRestoringPageAnchor,
			)
		}
			.distinctUntilChanged()
			.collect { (isScrolling, position, restoringAnchor) ->
				if (shouldReportReaderSettledPage(isScrolling, restoringAnchor)) {
					displayedPages.getOrNull(position)?.let(onPageChanged)
				}
			}
	}

	LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled, displayedPages) {
		requestedPage?.takeIf { it in displayedPages.indices && it != pagerState.currentPage }?.let {
			if (shouldAnimatePageNavigation(pagerState.currentPage, it, requestedPageSmooth, isAnimationEnabled)) {
				pagerState.animateScrollToPage(it)
			} else {
				pagerState.scrollToPage(it)
			}
		}
	}
	val pageCurlState = rememberComposeReaderPageCurlState()
	LaunchedEffect(pagerState.isScrollInProgress) {
		if (!pagerState.isScrollInProgress) pageCurlState.resetDrag()
	}
	val isSimulationCurlUnfolding = resolvePageCurlUnfolding(
		settledPage = pagerState.settledPage,
		targetPage = pagerState.targetPage,
		horizontalDragFraction = pageCurlState.horizontalDragFraction,
		isReadingReversed = reverseLayout && !isVertical,
		verticalDragFraction = pageCurlState.verticalDragFraction,
		isVertical = isVertical,
	)

	val pageContent: @Composable PagerScope.(Int) -> Unit = { position ->
		val page = displayedPages[position]
		val effectiveAdvancedAnchorPage = if (
			!pagerState.isScrollInProgress &&
			abs(pagerState.currentPageOffsetFraction) < ADVANCED_PAGE_EPSILON
		) {
			pagerState.currentPage
		} else {
			advancedAnchorPage
		}
		val advancedNavigationProgress = if (pageAnimation == ReaderAnimation.ADVANCED) {
			resolveAdvancedNavigationProgress(
				anchorPage = effectiveAdvancedAnchorPage,
				currentPage = pagerState.currentPage,
				currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
			)
		} else {
			0f
		}
		val advancedIncomingPage = when {
			advancedNavigationProgress > ADVANCED_PAGE_EPSILON -> effectiveAdvancedAnchorPage + 1
			advancedNavigationProgress < -ADVANCED_PAGE_EPSILON -> effectiveAdvancedAnchorPage - 1
			else -> null
		}
		val transform = if (pageAnimation == ReaderAnimation.DEFAULT) {
			ComposeReaderPageTransform()
		} else {
			val pageOffset = if (pageAnimation == ReaderAnimation.ADVANCED) {
				// Keep the settled page as the anchor so the animated and static
				// pages do not swap when currentPage changes at the midpoint.
				(effectiveAdvancedAnchorPage - position) + advancedNavigationProgress
			} else {
				val logicalOffset = (position - pagerState.currentPage) - pagerState.currentPageOffsetFraction
				if (reverseLayout && !isVertical) -logicalOffset else logicalOffset
			}
			resolveComposeReaderPageTransform(
				animation = pageAnimation,
				pageOffset = pageOffset,
				isVertical = isVertical,
				isReversed = reverseLayout,
				navigationProgress = advancedNavigationProgress,
				isSettledPage = pageAnimation == ReaderAnimation.ADVANCED &&
					effectiveAdvancedAnchorPage == position,
				isIncomingPage = pageAnimation == ReaderAnimation.ADVANCED &&
					advancedIncomingPage == position,
				isCurlUnfolding = isSimulationCurlUnfolding,
			)
		}
		Box(
			modifier = Modifier
				.fillMaxSize()
				.zIndex(transform.zIndex)
				.graphicsLayer {
					alpha = transform.alpha
					translationX = if (isVertical) 0f else transform.translationFactor * size.width
					translationY = if (isVertical) transform.translationFactor * size.height else 0f
				}
				.composeReaderPageCurl(transform, isVertical, reverseLayout, pageCurlState),
		) {
			ComposeReaderPage(
				page = page,
				imageLoader = imageLoader,
				imagePipeline = imagePipeline,
				zoomCommand = zoomCommand,
				onShowErrorDetails = onShowErrorDetails,
				onRetryError = onRetryError,
				resolveErrorStringId = resolveErrorStringId,
				isAnimationEnabled = isAnimationEnabled,
				readerBackground = readerBackground,
				readerBackgroundColor = readerBackgroundColor,
				bookBackgroundTint = bookBackgroundTint,
				imageColorFilter = imageColorFilter,
				bitmapConfig = bitmapConfig,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				zoomMode = zoomMode,
				isCropEnabled = isCropEnabled,
				isPageVisible = pagerState.settledPage == position,
				isPageCurlEnabled = pageAnimation == ReaderAnimation.SIMULATION,
				modifier = Modifier.fillMaxSize(),
			)
			pageOverlay()
			when (pageAnimation) {
				ReaderAnimation.SIMULATION -> ComposeReaderSimulationPageShadow(transform)
				else -> Unit
			}
		}
	}

	if (isVertical) {
		VerticalPager(
			state = pagerState,
			beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
			modifier = modifier
				.fillMaxSize()
				.trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION),
			key = { displayedPages[it].readerKey },
			pageContent = pageContent,
		)
	} else {
		HorizontalPager(
			state = pagerState,
			beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
			modifier = modifier
				.fillMaxSize()
				.trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION),
			reverseLayout = reverseLayout,
			key = { displayedPages[it].readerKey },
			pageContent = pageContent,
		)
	}
}

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
				animateWebtoonScaleTo((canvasScale * command.factor).coerceIn(0.5f, 2.5f))
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
	LaunchedEffect(webtoonScrollRequest) {
		webtoonScrollRequest?.let { request ->
			fun dispatchScroll(delta: Float) = dispatchWebtoonScroll(delta)
			if (request.smooth) {
				var previousValue = 0f
				animate(
					initialValue = 0f,
					targetValue = request.delta.toFloat(),
				) { value, _ ->
					dispatchScroll(value - previousValue)
					previousValue = value
				}
			} else {
				dispatchScroll(request.delta.toFloat())
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
									val nextScale = (previousScale * zoom).coerceIn(0.5f, 2.5f)
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
						if (shouldFlingWebtoonCanvas(singlePointerTransformed, hadMultiplePointers)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeDoublePageReader(
	pages: List<ReaderPage>,
	initialPage: Int,
	reverseLayout: Boolean,
	coverPage: Boolean = false,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	onPagesChanged: (ReaderPage, ReaderPage) -> Unit,
	requestedPage: Int? = null,
	requestedPageSmooth: Boolean = false,
	zoomCommand: ComposeReaderZoomCommand? = null,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = { 0 },
	isAnimationEnabled: Boolean = true,
	pageAnimation: ReaderAnimation = ReaderAnimation.DEFAULT,
	readerBackground: ReaderBackground = ReaderBackground.BLACK,
	readerBackgroundColor: Int = android.graphics.Color.BLACK,
	bookBackgroundTint: Int? = null,
	imageColorFilter: ColorFilter? = null,
	bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
	isReaderOptimizationEnabled: Boolean = false,
	isPreloadReductionEnabled: Boolean = false,
	zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
	isCropEnabled: Boolean = false,
	pageOverlay: @Composable BoxScope.() -> Unit = {},
	modifier: Modifier = Modifier,
) {
	var displayedPages by remember { mutableStateOf(pages) }
	val displayItems = remember(displayedPages, coverPage) {
		buildDoublePageDisplayItems(displayedPages, coverPage = coverPage)
	}
	val displayPagePositions = remember(displayItems) { displayItems.map { it.originalPosition } }
	val spreadModel = remember(displayItems.size) { DoublePageSpreadModel.create(displayItems.size) }
	val spreads = spreadModel.spreads
	val pageKeys = displayItems.map { it.page?.readerKey ?: DoublePageSpreadModel.SPACER_KEY }
	val initialDisplayPosition = displayPagePositions.indexOf(initialPage).takeIf { it >= 0 } ?: 0
	var anchorPageKey by remember { mutableStateOf(pageKeys[initialDisplayPosition.coerceIn(pageKeys.indices)]) }
	val retainedAnchorPageKey = anchorPageKey
	var isRestoringAnchor by remember { mutableStateOf(false) }
	val spreadTransforms = remember(displayItems) { mutableStateMapOf<Int, DoublePageTransform>() }
	var spreadZoomJob by remember { mutableStateOf<Job?>(null) }
	var spreadFlingJob by remember { mutableStateOf<Job?>(null) }
	val spreadGestureScope = rememberCoroutineScope()
	val context = LocalContext.current
	val doubleTapSlop = remember(context) {
		ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
	}
	val spreadDecay = FloatExponentialDecaySpec()
	val pagerState = rememberPagerState(
		initialPage = spreadModel.spreadIndexForPage(initialDisplayPosition),
		pageCount = spreads::size,
	)
	var advancedAnchorSpread by remember(pagerState) { mutableIntStateOf(pagerState.currentPage) }
	LaunchedEffect(pagerState, pageAnimation) {
		snapshotFlow {
			Triple(
				pagerState.isScrollInProgress,
				pagerState.currentPage,
				pagerState.currentPageOffsetFraction,
			)
		}.collect { (isScrolling, currentPage, offsetFraction) ->
			if (pageAnimation == ReaderAnimation.ADVANCED) {
				advancedAnchorSpread = resolveAdvancedAnimationAnchor(
					anchorPage = advancedAnchorSpread,
					currentPage = currentPage,
					currentPageOffsetFraction = offsetFraction,
					isScrollInProgress = isScrolling,
				)
			}
		}
	}
	LaunchedEffect(pagerState, pageAnimation, advancedAnchorSpread) {
		if (pageAnimation != ReaderAnimation.ADVANCED && pageAnimation != ReaderAnimation.SIMULATION) {
			return@LaunchedEffect
		}
		snapshotFlow {
			ReaderPagerAnimationDebugState(
				scrolling = pagerState.isScrollInProgress,
				currentPage = pagerState.currentPage,
				settledPage = pagerState.settledPage,
				targetPage = pagerState.targetPage,
				offsetPercent = (pagerState.currentPageOffsetFraction * 100f).roundToInt(),
				anchorPage = advancedAnchorSpread,
			)
		}.distinctUntilChanged().collect { state ->
			Log.d(READER_ANIMATION_DEBUG_TAG, "${pageAnimation.name.lowercase()} double $state")
		}
	}
	var hasAppliedInitialPosition by remember { mutableStateOf(false) }
	LaunchedEffect(displayItems) {
		if (!hasAppliedInitialPosition) {
			val target = spreadModel.spreadIndexForPage(initialDisplayPosition)
			if (pagerState.currentPage != target) pagerState.scrollToPage(target)
			hasAppliedInitialPosition = true
		}
	}
	fun clampSpreadOffset(scale: Float, x: Float, y: Float): Offset {
		val maxX = (pagerState.layoutInfo.viewportSize.width * (scale - 1f) / 2f).coerceAtLeast(0f)
		val maxY = (pagerState.layoutInfo.viewportSize.height * (scale - 1f) / 2f).coerceAtLeast(0f)
		return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
	}
	fun spreadTransform(spreadIndex: Int): DoublePageTransform =
		spreadTransforms[spreadIndex] ?: DoublePageTransform()

	fun applySpreadTransform(
		spreadIndex: Int,
		nextScale: Float,
		pan: Offset,
		focus: Offset,
	): DoublePageTransform {
		val previous = spreadTransform(spreadIndex)
		val boundedScale = nextScale.coerceIn(1f, 2.5f)
		val factor = if (previous.scale > 0f) boundedScale / previous.scale else 1f
		val center = Offset(
			pagerState.layoutInfo.viewportSize.width / 2f,
			pagerState.layoutInfo.viewportSize.height / 2f,
		)
		val focusedTranslation = (focus - center) * (1f - factor)
		val bounded = clampSpreadOffset(
			boundedScale,
			previous.offsetX + pan.x + focusedTranslation.x,
			previous.offsetY + pan.y + focusedTranslation.y,
		)
		return DoublePageTransform(
			scale = boundedScale,
			offsetX = bounded.x,
			offsetY = bounded.y,
		).also { spreadTransforms[spreadIndex] = it }
	}

	suspend fun animateSpreadScaleTo(spreadIndex: Int, targetScale: Float, focus: Offset) {
		val initialScale = spreadTransform(spreadIndex).scale
		val boundedTarget = targetScale.coerceIn(1f, 2.5f)
		if (!isAnimationEnabled) {
			applySpreadTransform(spreadIndex, boundedTarget, Offset.Zero, focus)
			return
		}
		animate(
			initialValue = initialScale,
			targetValue = boundedTarget,
			animationSpec = tween(ZOOM_ANIMATION_DURATION_MS),
		) { value, _ ->
			applySpreadTransform(spreadIndex, value, Offset.Zero, focus)
		}
	}

	suspend fun flingSpread(spreadIndex: Int, velocity: Velocity) {
		if (spreadTransform(spreadIndex).scale <= 1f ||
			maxOf(kotlin.math.abs(velocity.x), kotlin.math.abs(velocity.y)) < 50f
		) return
		coroutineScope {
			launch {
				animateDecay(spreadTransform(spreadIndex).offsetX, velocity.x, spreadDecay) { value, _ ->
					val current = spreadTransform(spreadIndex)
					val bounded = clampSpreadOffset(current.scale, value, current.offsetY)
					spreadTransforms[spreadIndex] = current.copy(offsetX = bounded.x)
				}
			}
			launch {
				animateDecay(spreadTransform(spreadIndex).offsetY, velocity.y, spreadDecay) { value, _ ->
					val current = spreadTransform(spreadIndex)
					val bounded = clampSpreadOffset(current.scale, current.offsetX, value)
					spreadTransforms[spreadIndex] = current.copy(offsetY = bounded.y)
				}
			}
		}
	}
	val autoBackgroundColors = remember { mutableStateMapOf<Long, Int>() }
	val pageCurlState = rememberComposeReaderPageCurlState()
	LaunchedEffect(pagerState.isScrollInProgress) {
		if (!pagerState.isScrollInProgress) pageCurlState.resetDrag()
	}
	val isSimulationCurlUnfolding = resolvePageCurlUnfolding(
		settledPage = pagerState.settledPage,
		targetPage = pagerState.targetPage,
		horizontalDragFraction = pageCurlState.horizontalDragFraction,
		isReadingReversed = reverseLayout,
	)
	LaunchedEffect(pages, pagerState.isScrollInProgress) {
		if (!pagerState.isScrollInProgress && displayedPages !== pages) {
			val currentSpread = spreads.getOrNull(pagerState.settledPage)
			val visibleAnchorPageKey = currentSpread?.positions
				?.firstNotNullOfOrNull { displayItems[it].page?.readerKey }
				?: retainedAnchorPageKey
			val updatedDisplayItems = buildDoublePageDisplayItems(pages, coverPage = coverPage)
			val updatedPageKeys = updatedDisplayItems.map {
				it.page?.readerKey ?: DoublePageSpreadModel.SPACER_KEY
			}
			val updatedSpreadModel = DoublePageSpreadModel.create(updatedDisplayItems.size)
			val anchorSpreadIndex = updatedSpreadModel.resolveAnchorSpreadIndex(
				pageKeys = updatedPageKeys,
				anchorPageKey = visibleAnchorPageKey,
				fallbackPosition = pagerState.settledPage * 2,
			)
			isRestoringAnchor = true
			try {
				anchorPageKey = visibleAnchorPageKey
				displayedPages = pages
				withFrameNanos { }
				if (pagerState.currentPage != anchorSpreadIndex) {
					pagerState.scrollToPage(anchorSpreadIndex)
				}
			} finally {
				isRestoringAnchor = false
			}
		}
	}

	LaunchedEffect(pageKeys, requestedPage) {
		if (requestedPage == null && !isRestoringAnchor) {
			val anchorSpreadIndex = spreadModel.resolveAnchorSpreadIndex(
				pageKeys = pageKeys,
				anchorPageKey = retainedAnchorPageKey,
				fallbackPosition = pagerState.currentPage * 2,
			)
			if (anchorSpreadIndex != pagerState.currentPage) {
				isRestoringAnchor = true
				try {
					pagerState.scrollToPage(anchorSpreadIndex)
				} finally {
					isRestoringAnchor = false
				}
			}
		}
	}
	LaunchedEffect(pagerState, spreads, isRestoringAnchor) {
		snapshotFlow {
			Triple(
				pagerState.isScrollInProgress,
				pagerState.settledPage,
				isRestoringAnchor,
			)
		}
			.distinctUntilChanged()
			.collect { (isScrolling, spreadIndex, restoringAnchor) ->
				if (!shouldReportReaderSettledPage(isScrolling, restoringAnchor)) return@collect
				val spread = spreads[spreadIndex]
				val visiblePages = spread.positions.mapNotNull {
					displayItems[it].page
				}
				if (visiblePages.isNotEmpty()) {
					anchorPageKey = visiblePages.first().readerKey
					onPagesChanged(visiblePages.first(), visiblePages.last())
				}
			}
	}
	LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled, displayPagePositions) {
		requestedPage?.let { position ->
			val displayPosition = displayPagePositions.indexOf(position).takeIf { it >= 0 }
				?: return@LaunchedEffect
			val spreadIndex = spreadModel.spreadIndexForPage(displayPosition)
			displayItems[displayPosition].page?.let { anchorPageKey = it.readerKey }
			if (spreadIndex != pagerState.currentPage) {
				if (shouldAnimatePageNavigation(
						pagerState.currentPage,
						spreadIndex,
						requestedPageSmooth,
						isAnimationEnabled,
					)) {
					pagerState.animateScrollToPage(spreadIndex)
				} else {
					pagerState.scrollToPage(spreadIndex)
				}
			}
		}
	}
	LaunchedEffect(zoomCommand, pagerState.currentPage, isAnimationEnabled) {
		val spread = spreads.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
		val command = zoomCommand ?: return@LaunchedEffect
		if (command.pageKey !in spread.positions.mapNotNull { displayItems[it].page?.readerKey }) return@LaunchedEffect
		spreadZoomJob?.cancel()
		val job = currentCoroutineContext().job
		spreadZoomJob = job
		try {
			val current = spreadTransform(pagerState.currentPage)
			val target = (current.scale * command.factor).coerceIn(1f, 2.5f)
			if (!isAnimationEnabled) {
				applySpreadTransform(
					spreadIndex = pagerState.currentPage,
					nextScale = target,
					pan = Offset.Zero,
					focus = Offset(
						pagerState.layoutInfo.viewportSize.width / 2f,
						pagerState.layoutInfo.viewportSize.height / 2f,
					),
				)
			} else {
				animate(
					initialValue = current.scale,
					targetValue = target,
					animationSpec = tween(ZOOM_ANIMATION_DURATION_MS),
				) { value, _ ->
					applySpreadTransform(
						spreadIndex = pagerState.currentPage,
						nextScale = value,
						pan = Offset.Zero,
						focus = Offset(
							pagerState.layoutInfo.viewportSize.width / 2f,
							pagerState.layoutInfo.viewportSize.height / 2f,
						),
					)
				}
			}
		} finally {
			if (spreadZoomJob === job) spreadZoomJob = null
		}
	}

	fun resolveSpreadBackground(spreadIndex: Int): Int {
		val spread = spreads[spreadIndex]
		val firstPageKey = displayItems[spread.lowerPosition].page?.readerKey
		val secondPageKey = displayItems.getOrNull(spread.upperPosition)
			?.takeIf { spread.upperPosition != spread.lowerPosition }
			?.page?.readerKey
		val rawBackground = resolveDoublePageBackground(
			background = readerBackground,
			configuredColor = readerBackgroundColor,
			firstAutoColor = autoBackgroundColors[firstPageKey],
			secondAutoColor = secondPageKey?.let(autoBackgroundColors::get),
		)
		return if (readerBackground == ReaderBackground.AUTO) {
			applyAutomaticBookBackgroundTint(rawBackground, bookBackgroundTint)
		} else {
			rawBackground
		}
	}

	@Composable
	fun DoublePageSpreadContent(
		spreadIndex: Int,
		isPageVisible: Boolean,
		modifier: Modifier = Modifier,
	) {
		val spread = spreads[spreadIndex]
		val spreadBackground = resolveSpreadBackground(spreadIndex)
		val orderedPositions = spread.orderedPositions(reverseLayout)
		Row(
			modifier = modifier.background(Color(spreadBackground)),
		) {
			orderedPositions.forEach { position ->
				val page = displayItems[position].page
				if (page == null) {
					Box(modifier = Modifier.weight(1f).fillMaxSize())
				} else {
					ComposeReaderPage(
						page = page,
						imageLoader = imageLoader,
						imagePipeline = imagePipeline,
						zoomCommand = null,
						isZoomEnabled = false,
						onShowErrorDetails = onShowErrorDetails,
						onRetryError = onRetryError,
						resolveErrorStringId = resolveErrorStringId,
						isAnimationEnabled = isAnimationEnabled,
						readerBackground = readerBackground,
						readerBackgroundColor = readerBackgroundColor,
						bookBackgroundTint = bookBackgroundTint,
						imageColorFilter = imageColorFilter,
						bitmapConfig = bitmapConfig,
						isReaderOptimizationEnabled = isReaderOptimizationEnabled,
						zoomMode = zoomMode,
						isCropEnabled = isCropEnabled,
						isPageVisible = isPageVisible,
						isPageCurlEnabled = pageAnimation == ReaderAnimation.SIMULATION,
						applyPageBackground = false,
						pageBackgroundColorOverride = spreadBackground,
						onAutoBackgroundResolved = { color ->
							autoBackgroundColors[page.readerKey] = color
						},
						modifier = Modifier
							.weight(1f)
							.fillMaxSize(),
					)
				}
			}
			if (spread.lowerPosition == spread.upperPosition) {
				Box(modifier = Modifier.weight(1f).fillMaxSize())
			}
		}
	}

	fun Modifier.doublePageSpreadGestures(spreadIndex: Int): Modifier =
		pointerInput(isAnimationEnabled, spreadIndex) {
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
				spreadFlingJob?.cancel()
				val velocityTracker = VelocityTracker()
				var transformed = false
				var moved = false
				var eventTime = down.uptimeMillis
				do {
					val event = awaitPointerEvent(PointerEventPass.Initial)
					if (event.changes.any { it.isConsumed }) {
						moved = true
						continue
					}
					event.changes.maxByOrNull { it.uptimeMillis }?.let { eventTime = it.uptimeMillis }
					event.changes.filter { it.pressed }.forEach {
						velocityTracker.addPosition(it.uptimeMillis, it.position)
					}
					val pressedCount = event.changes.count { it.pressed }
					if (pressedCount >= 2) {
						moved = true
					} else if (event.changes.any { it.pressed }) {
						val currentPosition = event.changes.firstOrNull { it.pressed }?.position
						if (currentPosition != null && hasExceededWebtoonTapSlop(
								start = down.position,
								current = currentPosition,
								touchSlop = viewConfiguration.touchSlop,
							)
						) {
							moved = true
						}
					}
					if (pressedCount >= 2 || spreadTransform(spreadIndex).scale > 1f) {
						spreadZoomJob?.cancel()
						val centroid = event.calculateCentroid(useCurrent = false)
						val pan = event.calculatePan()
						val zoom = event.calculateZoom()
						val previous = spreadTransform(spreadIndex)
						val previousScale = previous.scale
						val nextScale = (previousScale * zoom).coerceIn(1f, 2.5f)
						val updated = applySpreadTransform(spreadIndex, nextScale, pan, centroid)
						val consumedPanX = updated.offsetX - previous.offsetX
						val consumedPanY = updated.offsetY - previous.offsetY
						val consumed = abs(nextScale - previousScale) > 0.001f ||
							abs(consumedPanX) > 0.001f || abs(consumedPanY) > 0.001f
						if (consumed) {
							event.changes.forEach { it.consume() }
							transformed = true
						}
					}
				} while (event.changes.any { it.pressed })
				if (transformed) {
					spreadFlingJob = spreadGestureScope.launch {
						flingSpread(spreadIndex, velocityTracker.calculateVelocity())
					}
					lastTapPosition = null
				} else if (!moved && eventTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
					if (isDoubleTapCandidate) {
						lastTapPosition = null
						spreadZoomJob?.cancel()
						spreadZoomJob = spreadGestureScope.launch {
							animateSpreadScaleTo(
								spreadIndex,
								if (spreadTransform(spreadIndex).scale > 1f) 1f else 2f,
								down.position,
							)
						}
					} else {
						lastTapPosition = down.position
						lastTapUpAt = eventTime
					}
				} else {
					lastTapPosition = null
				}
			}
		}

	HorizontalPager(
		state = pagerState,
		beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
		reverseLayout = reverseLayout,
		modifier = modifier
			.fillMaxSize()
			.trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION),
		key = { spreadIndex ->
			spreads[spreadIndex].positions.joinToString(separator = ":") {
				displayItems[it].page?.readerKey?.toString() ?: DoublePageSpreadModel.SPACER_KEY.toString()
			}
		},
	) { spreadIndex ->
		val spread = spreads[spreadIndex]
		val effectiveAdvancedAnchorSpread = if (
			!pagerState.isScrollInProgress &&
			abs(pagerState.currentPageOffsetFraction) < ADVANCED_PAGE_EPSILON
		) {
			pagerState.currentPage
		} else {
			advancedAnchorSpread
		}
		val advancedNavigationProgress = if (pageAnimation == ReaderAnimation.ADVANCED) {
			resolveAdvancedNavigationProgress(
				anchorPage = effectiveAdvancedAnchorSpread,
				currentPage = pagerState.currentPage,
				currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
			)
		} else {
			0f
		}
		val advancedIncomingSpread = when {
			advancedNavigationProgress > ADVANCED_PAGE_EPSILON -> effectiveAdvancedAnchorSpread + 1
			advancedNavigationProgress < -ADVANCED_PAGE_EPSILON -> effectiveAdvancedAnchorSpread - 1
			else -> null
		}
		val transform = if (pageAnimation == ReaderAnimation.DEFAULT) {
			ComposeReaderPageTransform()
		} else {
			val pageOffset = if (pageAnimation == ReaderAnimation.ADVANCED) {
				(effectiveAdvancedAnchorSpread - spreadIndex) + advancedNavigationProgress
			} else {
				val logicalOffset = (spreadIndex - pagerState.currentPage) - pagerState.currentPageOffsetFraction
				if (reverseLayout) -logicalOffset else logicalOffset
			}
			resolveComposeReaderPageTransform(
				animation = pageAnimation,
				pageOffset = pageOffset,
				isVertical = false,
				isReversed = reverseLayout,
				navigationProgress = advancedNavigationProgress,
				isSettledPage = pageAnimation == ReaderAnimation.ADVANCED &&
					effectiveAdvancedAnchorSpread == spreadIndex,
				isIncomingPage = pageAnimation == ReaderAnimation.ADVANCED &&
					advancedIncomingSpread == spreadIndex,
				isCurlUnfolding = isSimulationCurlUnfolding,
			)
			}
			val spreadBackground = resolveSpreadBackground(spreadIndex)
			val orderedPositions = spread.orderedPositions(reverseLayout)
			val currentTransform = spreadTransform(spreadIndex)
			val simulationTargetSpreadIndex = if (
				pageAnimation == ReaderAnimation.SIMULATION && pagerState.isScrollInProgress
			) {
				pagerState.targetPage.takeIf { it != pagerState.settledPage } ?: run {
					val drag = pageCurlState.horizontalDragFraction
					val delta = when {
						drag < -SIMULATION_PAGE_EPSILON -> if (reverseLayout) -1 else 1
						drag > SIMULATION_PAGE_EPSILON -> if (reverseLayout) 1 else -1
						else -> 0
					}
					(pagerState.settledPage + delta).takeIf { delta != 0 && it in spreads.indices }
				}
			} else {
				null
			}
			val simulationRevealSpreadIndex = simulationTargetSpreadIndex?.takeIf {
				transform.foldProgress > 0f
			}?.let { targetSpreadIndex ->
				if (spreadIndex == pagerState.settledPage) {
					targetSpreadIndex
				} else {
					pagerState.settledPage
				}
			}
			Box(
				modifier = Modifier
					.fillMaxSize()
					.zIndex(transform.zIndex)
					.graphicsLayer {
						scaleX = currentTransform.scale
						scaleY = currentTransform.scale
						translationX = currentTransform.offsetX + transform.translationFactor * size.width
						translationY = currentTransform.offsetY
					},
			) {
				simulationRevealSpreadIndex?.let { revealSpreadIndex ->
					DoublePageSpreadContent(
						spreadIndex = revealSpreadIndex,
						isPageVisible = true,
						modifier = Modifier
							.fillMaxSize()
							.zIndex(0f),
					)
				}
				Box(
					modifier = Modifier
						.fillMaxSize()
						.zIndex(1f)
						.graphicsLayer { alpha = transform.alpha }
						.background(Color(spreadBackground))
						.composeReaderPageCurl(
							transform = transform,
							isVertical = false,
							isReadingReversed = reverseLayout,
							state = pageCurlState,
						),
				) {
				Row(
				modifier = Modifier
					.fillMaxSize()
				.pointerInput(isAnimationEnabled) {
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
						spreadFlingJob?.cancel()
						val velocityTracker = VelocityTracker()
						var transformed = false
						var moved = false
						var eventTime = down.uptimeMillis
						do {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							if (event.changes.any { it.isConsumed }) {
								moved = true
								continue
							}
							event.changes.maxByOrNull { it.uptimeMillis }?.let { eventTime = it.uptimeMillis }
							event.changes.filter { it.pressed }.forEach {
								velocityTracker.addPosition(it.uptimeMillis, it.position)
							}
							val pressedCount = event.changes.count { it.pressed }
							if (pressedCount >= 2) {
								moved = true
							} else if (event.changes.any { it.pressed }) {
								val currentPosition = event.changes.firstOrNull { it.pressed }?.position
								if (currentPosition != null && hasExceededWebtoonTapSlop(
										start = down.position,
										current = currentPosition,
										touchSlop = viewConfiguration.touchSlop,
									)
								) {
									moved = true
								}
							}
							if (pressedCount >= 2 || spreadTransform(spreadIndex).scale > 1f) {
								spreadZoomJob?.cancel()
								val centroid = event.calculateCentroid(useCurrent = false)
								val pan = event.calculatePan()
								val zoom = event.calculateZoom()
								val previous = spreadTransform(spreadIndex)
								val previousScale = previous.scale
								val nextScale = (previousScale * zoom).coerceIn(1f, 2.5f)
								val updated = applySpreadTransform(spreadIndex, nextScale, pan, centroid)
								val consumedPanX = updated.offsetX - previous.offsetX
								val consumedPanY = updated.offsetY - previous.offsetY
								val consumed = abs(nextScale - previousScale) > 0.001f ||
									abs(consumedPanX) > 0.001f || abs(consumedPanY) > 0.001f
								if (consumed) {
									event.changes.forEach { it.consume() }
									transformed = true
								}
							}
						} while (event.changes.any { it.pressed })
						if (transformed) {
							spreadFlingJob = spreadGestureScope.launch {
								flingSpread(spreadIndex, velocityTracker.calculateVelocity())
							}
							lastTapPosition = null
						} else if (!moved && eventTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
							if (isDoubleTapCandidate) {
								lastTapPosition = null
								spreadZoomJob?.cancel()
								spreadZoomJob = spreadGestureScope.launch {
									animateSpreadScaleTo(
										spreadIndex,
										if (spreadTransform(spreadIndex).scale > 1f) 1f else 2f,
										down.position,
									)
								}
							} else {
								lastTapPosition = down.position
								lastTapUpAt = eventTime
							}
						} else {
							lastTapPosition = null
						}
					}
				},
				) {
					orderedPositions.forEach { position ->
						val page = displayItems[position].page
						if (page == null) {
							Box(modifier = Modifier.weight(1f).fillMaxSize())
						} else {
							ComposeReaderPage(
								page = page,
								imageLoader = imageLoader,
								imagePipeline = imagePipeline,
								zoomCommand = null,
								isZoomEnabled = false,
								onShowErrorDetails = onShowErrorDetails,
								onRetryError = onRetryError,
								resolveErrorStringId = resolveErrorStringId,
								isAnimationEnabled = isAnimationEnabled,
								readerBackground = readerBackground,
								readerBackgroundColor = readerBackgroundColor,
								bookBackgroundTint = bookBackgroundTint,
								imageColorFilter = imageColorFilter,
								bitmapConfig = bitmapConfig,
								isReaderOptimizationEnabled = isReaderOptimizationEnabled,
								zoomMode = zoomMode,
								isCropEnabled = isCropEnabled,
								isPageVisible = pagerState.settledPage == spreadIndex,
								isPageCurlEnabled = pageAnimation == ReaderAnimation.SIMULATION,
								applyPageBackground = false,
								pageBackgroundColorOverride = spreadBackground,
								onAutoBackgroundResolved = { color ->
									autoBackgroundColors[page.readerKey] = color
								},
								modifier = Modifier
									.weight(1f)
									.fillMaxSize(),
							)
						}
					}
					if (spread.lowerPosition == spread.upperPosition) {
					Box(modifier = Modifier.weight(1f).fillMaxSize())
				}
			}
			pageOverlay()
			when (pageAnimation) {
				ReaderAnimation.SIMULATION -> ComposeReaderSimulationPageShadow(transform)
				else -> Unit
			}
			}
		}
	}
	}

@Composable
internal fun ComposeReaderPage(
	page: ReaderPage,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	aspectRatio: Float? = null,
	placeholderMinHeight: Dp? = null,
	onImageSizeResolved: (width: Int, height: Int) -> Unit = { _, _ -> },
	zoomCommand: ComposeReaderZoomCommand? = null,
	isZoomEnabled: Boolean = true,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = { 0 },
	isAnimationEnabled: Boolean = true,
	readerBackground: ReaderBackground = ReaderBackground.BLACK,
	readerBackgroundColor: Int = android.graphics.Color.BLACK,
	bookBackgroundTint: Int? = null,
	imageColorFilter: ColorFilter? = null,
	bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
	isCropEnabled: Boolean = false,
	zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
	isPageVisible: Boolean = true,
	isReaderOptimizationEnabled: Boolean = false,
	isPageCurlEnabled: Boolean = false,
	applyPageBackground: Boolean = true,
	pageBackgroundColorOverride: Int? = null,
	onAutoBackgroundResolved: (Int) -> Unit = {},
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
	val displayUri = when (val value = state) {
		is ComposeReaderImageState.OriginalReady -> value.original
		is ComposeReaderImageState.Enhancing -> value.original
		is ComposeReaderImageState.EnhancedReady -> value.enhanced
		else -> null
	}
	LaunchedEffect(displayUri) {
		renderError = null
	}
	var autoBackgroundColor by remember(page.readerKey) { mutableStateOf<Int?>(null) }
	val context = LocalContext.current
	LaunchedEffect(page.readerKey, displayUri, readerBackground) {
		if (readerBackground != ReaderBackground.AUTO || displayUri == null) return@LaunchedEffect
		val result = imageLoader.execute(
			ImageRequest.Builder(context)
				.data(displayUri)
				.size(AUTO_BACKGROUND_SAMPLE_SIZE, AUTO_BACKGROUND_SAMPLE_SIZE)
				.allowHardware(false)
				.build(),
		) as? SuccessResult ?: return@LaunchedEffect
		val resolved = withContext(Dispatchers.Default) {
			ReaderAutoBackground.resolve(result.image.toBitmap())
		}
		autoBackgroundColor = resolved
		onAutoBackgroundResolved(resolved)
	}
	val rawPageBackgroundColor = if (readerBackground == ReaderBackground.AUTO) {
		autoBackgroundColor ?: readerBackgroundColor
	} else {
		readerBackgroundColor
	}
	val pageBackgroundColor = if (readerBackground == ReaderBackground.AUTO) {
		applyAutomaticBookBackgroundTint(rawPageBackgroundColor, bookBackgroundTint)
	} else {
		rawPageBackgroundColor
	}
	val effectivePageBackgroundColor = pageBackgroundColorOverride ?: pageBackgroundColor

	Box(
		modifier = modifier
			.then(aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier)
			.then(
				if (aspectRatio == null && placeholderMinHeight != null) {
					Modifier.heightIn(min = placeholderMinHeight)
				} else {
					Modifier
				},
			)
			.background(
				if (applyPageBackground || pageBackgroundColorOverride != null) {
					Color(effectivePageBackgroundColor)
				} else {
					Color.Transparent
				},
			),
		contentAlignment = Alignment.Center,
	) {
		Box(
			modifier = Modifier
				.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			if (renderError != null) {
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
					contentScale = ContentScale.Fit,
					modifier = Modifier.fillMaxSize(),
				)
			is ComposeReaderImageState.OriginalReady -> PagedReaderImage(
				uri = value.original,
				imageLoader = imageLoader,
					onImageSizeResolved = { width, height ->
						imagePipeline.onImageDecoded(page, width, height)
						onImageSizeResolved(width, height)
				},
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isZoomEnabled = isZoomEnabled,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				bitmapConfig = bitmapConfig,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = value.isAnimated,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				isPageCurlEnabled = isPageCurlEnabled,
				forceCoil = forceCoil,
				onSubsamplingError = { forceCoil = true },
				onImageError = { renderError = it },
					modifier = Modifier.fillMaxSize(),
			)
			is ComposeReaderImageState.Enhancing -> PagedReaderImage(
				uri = value.original,
				imageLoader = imageLoader,
					onImageSizeResolved = { width, height ->
						imagePipeline.onImageDecoded(page, width, height)
						onImageSizeResolved(width, height)
				},
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isZoomEnabled = isZoomEnabled,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				bitmapConfig = bitmapConfig,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = false,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				isPageCurlEnabled = isPageCurlEnabled,
				forceCoil = forceCoil,
				onSubsamplingError = { forceCoil = true },
				onImageError = { renderError = it },
					modifier = Modifier.fillMaxSize(),
			)
				is ComposeReaderImageState.EnhancedReady -> PagedReaderImage(
				uri = value.enhanced,
				imageLoader = imageLoader,
					onImageSizeResolved = onImageSizeResolved,
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isZoomEnabled = isZoomEnabled,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				bitmapConfig = bitmapConfig,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = false,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				isPageCurlEnabled = isPageCurlEnabled,
				forceCoil = forceCoil,
				onSubsamplingError = { forceCoil = true },
				onImageError = { renderError = it },
					modifier = Modifier.fillMaxSize(),
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
	val imageScalingQuality = LocalReaderImageScalingQuality.current
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
	LaunchedEffect(displayUri) {
		forceCoil = false
		renderError = null
	}
	val itemHeight = with(LocalDensity.current) { measurement.itemHeightPx.toDp() }
	val canUseSubsampling = imageScalingQuality.usesTelephoto() &&
		!forceCoil && !isCropEnabled && page.split == ReaderPageSplit.NONE

	Box(
		modifier = modifier
			.height(itemHeight)
			.clipToBounds()
			.background(Color(readerBackgroundColor)),
		contentAlignment = Alignment.Center,
	) {
		if (renderError != null) {
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
					bitmapConfig = bitmapConfig,
					colorFilter = imageColorFilter,
					onImageSizeResolved = { width, height ->
						imagePipeline.onImageDecoded(page, width, height)
						onImageSizeResolved(width, height)
					},
					onImageError = { forceCoil = true },
					placeholder = {
						if (isPageVisible) {
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
					bitmapConfig = bitmapConfig,
					colorFilter = imageColorFilter,
					onImageSizeResolved = { width, height ->
						imagePipeline.onImageDecoded(page, width, height)
						onImageSizeResolved(width, height)
					},
					onImageError = { forceCoil = true },
					placeholder = {
						if (isPageVisible) {
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
					bitmapConfig = bitmapConfig,
					colorFilter = imageColorFilter,
					onImageSizeResolved = onImageSizeResolved,
					onImageError = { forceCoil = true },
					placeholder = {
						if (isPageVisible) {
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
private fun ReaderPageError(
	cause: Throwable,
	onRetry: () -> Unit,
	onShowDetails: () -> Unit,
	resolveStringId: Int,
) {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = Modifier.padding(16.dp),
	) {
		Text(
			text = cause.localizedMessage.orEmpty(),
			color = MaterialTheme.colorScheme.error,
		)
		TextButton(onClick = onRetry) {
			Text(stringResource(resolveReaderErrorActionStringId(resolveStringId)))
		}
		TextButton(onClick = onShowDetails) {
			Text(stringResource(R.string.error_details))
		}
	}
}

@Composable
private fun ReaderPageLoading(progress: Float?) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		if (progress == null) {
			KototoroLoadingIndicator()
			Text(
				text = stringResource(R.string.loading_),
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(top = 8.dp),
			)
		} else {
			KototoroLoadingIndicator(progress = { progress })
			Text(
				text = "${(progress * 100).toInt()}%",
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(top = 8.dp),
			)
		}
	}
}

internal fun resolveReaderErrorActionStringId(resolveStringId: Int): Int =
	resolveStringId.takeIf { it != 0 } ?: R.string.try_again

@Composable
private fun ReaderPreviewImage(
	page: ReaderPage,
	previewUrl: String,
	imageLoader: ImageLoader,
	contentScale: ContentScale,
	colorFilter: ColorFilter?,
	isCropEnabled: Boolean,
	isReaderOptimizationEnabled: Boolean,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val imageScalingQuality = LocalReaderImageScalingQuality.current
	val request = remember(page.readerKey, previewUrl, isCropEnabled, isReaderOptimizationEnabled) {
		ImageRequest.Builder(context)
			.data(previewUrl)
			.mangaSourceExtra(page.source)
			.transformations(ComposeReaderPageTransformation(isCropEnabled, page.split))
			.apply {
				if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.READ_ONLY)
			}
			.build()
	}
	AsyncImage(
		model = request,
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = contentScale,
		colorFilter = colorFilter,
		filterQuality = imageScalingQuality.toComposeFilterQuality(),
		modifier = modifier,
	)
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
	val context = LocalContext.current
	val imageScalingQuality = LocalReaderImageScalingQuality.current
	var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
	AnimatedDrawableLifecycle(animatable, isPageVisible)
	val useLanczos = imageScalingQuality == ReaderImageScalingQuality.LANCZOS &&
		decodeWidthPx > 0 && decodeHeightPx > 0
	val useSampledDecode = !isAnimated && !isCropEnabled && split == ReaderPageSplit.NONE &&
		decodeWidthPx > 0 && decodeHeightPx > 0 && !useLanczos
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
			imageScalingQuality,
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
					if (!isAnimated) {
						val pageTransformation = ComposeReaderPageTransformation(isCropEnabled, split)
						if (useLanczos) {
							transformations(
								pageTransformation,
								ReaderLanczosTransformation(decodeWidthPx, decodeHeightPx),
							)
						} else {
							transformations(pageTransformation)
						}
					}
					if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
				}
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		alignment = Alignment.TopCenter,
		contentScale = ContentScale.FillWidth,
		colorFilter = colorFilter,
		filterQuality = imageScalingQuality.toComposeFilterQuality(),
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
	val imageScalingQuality = LocalReaderImageScalingQuality.current
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
		filterQuality = imageScalingQuality.toComposeFilterQuality(),
		modifier = Modifier.fillMaxSize(),
	)
}

@Composable
private fun PagedReaderImage(
	uri: Uri,
	imageLoader: ImageLoader,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	pageKey: Long,
	split: ReaderPageSplit,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	colorFilter: ColorFilter?,
	bitmapConfig: Bitmap.Config,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
	isReaderOptimizationEnabled: Boolean,
	isPageCurlEnabled: Boolean,
	zoomMode: ZoomMode,
	forceCoil: Boolean,
	onSubsamplingError: () -> Unit,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	val imageScalingQuality = LocalReaderImageScalingQuality.current
	if (imageScalingQuality.usesTelephoto() && !isPageCurlEnabled && !forceCoil && !isAnimated && !isCropEnabled &&
		split == ReaderPageSplit.NONE && zoomMode != ZoomMode.KEEP_START
	) {
		ComposePagedTelephotoImage(
			uri = uri,
			pageKey = pageKey,
			bitmapConfig = bitmapConfig,
			colorFilter = colorFilter,
			zoomMode = zoomMode,
			zoomCommand = zoomCommand,
			isZoomEnabled = isZoomEnabled,
			isAnimationEnabled = isAnimationEnabled,
			onImageSizeResolved = onImageSizeResolved,
			onImageError = { onSubsamplingError() },
			modifier = modifier,
		)
	} else {
		ZoomableReaderImage(
			uri = uri,
			imageLoader = imageLoader,
			onImageSizeResolved = onImageSizeResolved,
			pageKey = pageKey,
			split = split,
			zoomCommand = zoomCommand,
			isZoomEnabled = isZoomEnabled,
			isAnimationEnabled = isAnimationEnabled,
			colorFilter = colorFilter,
			isCropEnabled = isCropEnabled,
			isAnimated = isAnimated,
			isPageVisible = isPageVisible,
			isReaderOptimizationEnabled = isReaderOptimizationEnabled,
			zoomMode = zoomMode,
			onImageError = onImageError,
			modifier = modifier,
		)
	}
}

@Composable
private fun ZoomableReaderImage(
	uri: Uri,
	imageLoader: ImageLoader,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	pageKey: Long,
	split: ReaderPageSplit,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	colorFilter: ColorFilter?,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
	isReaderOptimizationEnabled: Boolean,
	zoomMode: ZoomMode,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val imageScalingQuality = LocalReaderImageScalingQuality.current
	val zoomState = rememberSaveable(pageKey, zoomMode, saver = ReaderZoomState.Saver) { ReaderZoomState() }
	var viewportWidth by remember(pageKey) { mutableIntStateOf(0) }
	var viewportHeight by remember(pageKey) { mutableIntStateOf(0) }
	var imageWidth by remember(pageKey) { mutableIntStateOf(0) }
	var imageHeight by remember(pageKey) { mutableIntStateOf(0) }
	var initialScaleApplied by remember(pageKey, zoomMode) { mutableStateOf(false) }
	var transformVersion by remember(pageKey) { mutableIntStateOf(0) }
	val zoomAnimationScope = rememberCoroutineScope()
	var zoomAnimationJob by remember(pageKey) { mutableStateOf<Job?>(null) }
	var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
	AnimatedDrawableLifecycle(animatable, isPageVisible)

	fun updateGeometry() {
		zoomState.updateGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight, zoomMode)
		if (!initialScaleApplied && imageWidth > 0 && imageHeight > 0) {
			zoomState.zoomTo(initialReaderScale(zoomMode, viewportWidth, viewportHeight, imageWidth, imageHeight))
			initialScaleApplied = true
			transformVersion++
		}
	}

	suspend fun animateZoomTo(targetScale: Float) {
		if (!isAnimationEnabled) {
			zoomState.zoomTo(targetScale)
			transformVersion++
			return
		}
		animate(
			initialValue = zoomState.scale,
			targetValue = targetScale,
			animationSpec = tween(ZOOM_ANIMATION_DURATION_MS),
		) { value, _ ->
			zoomState.zoomTo(value)
			transformVersion++
		}
	}

	LaunchedEffect(zoomCommand, isAnimationEnabled) {
		if (zoomCommand?.pageKey == pageKey) {
			val animationJob = currentCoroutineContext().job
			zoomAnimationJob = animationJob
			try {
				animateZoomTo(zoomState.targetScaleForFactor(zoomCommand.factor))
			} finally {
				if (zoomAnimationJob === animationJob) zoomAnimationJob = null
			}
		}
	}

	AsyncImage(
		model = remember(
			uri,
			pageKey,
			split,
			isCropEnabled,
			isAnimated,
			isReaderOptimizationEnabled,
			imageScalingQuality,
			viewportWidth,
			viewportHeight,
		) {
			val useLanczos = imageScalingQuality == ReaderImageScalingQuality.LANCZOS &&
				viewportWidth > 0 && viewportHeight > 0 && !isAnimated
			ImageRequest.Builder(context)
				.data(uri)
				.allowHardware(!isAnimated)
				.apply {
					if (!isAnimated) {
						val pageTransformation = ComposeReaderPageTransformation(isCropEnabled, split)
						if (useLanczos) {
							size(Size.ORIGINAL)
							transformations(
								pageTransformation,
								ReaderLanczosTransformation(viewportWidth, viewportHeight),
							)
						} else {
							transformations(pageTransformation)
						}
					}
					if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
				}
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = when (zoomMode) {
			ZoomMode.FIT_CENTER,
			ZoomMode.KEEP_START -> ContentScale.Fit
			ZoomMode.FIT_HEIGHT -> ContentScale.FillHeight
			ZoomMode.FIT_WIDTH -> ContentScale.FillWidth
		},
		colorFilter = colorFilter,
		filterQuality = imageScalingQuality.toComposeFilterQuality(),
		onSuccess = { result ->
			animatable = (result.result.image as? DrawableImage)?.drawable as? Animatable
			imageWidth = result.result.image.width
			imageHeight = result.result.image.height
			updateGeometry()
			onImageSizeResolved(imageWidth, imageHeight)
		},
		onError = { result -> onImageError(result.result.throwable) },
		modifier = modifier
			.onSizeChanged { size ->
				viewportWidth = size.width
				viewportHeight = size.height
				updateGeometry()
			}
			.graphicsLayer {
				transformVersion
				scaleX = zoomState.scale
				scaleY = zoomState.scale
				translationX = zoomState.offsetX
				translationY = zoomState.offsetY
			}
			.then(
				if (isZoomEnabled) {
					Modifier
						.pointerInput(uri) {
							detectTapGestures(
								onDoubleTap = {
									zoomAnimationJob?.cancel()
									zoomAnimationJob = zoomAnimationScope.launch {
										animateZoomTo(zoomState.doubleTapTargetScale())
									}
								},
							)
						}
						.pointerInput(uri) {
							awaitEachGesture {
								awaitFirstDown(requireUnconsumed = false)
								zoomAnimationJob?.cancel()
								do {
									val event = awaitPointerEvent()
									val pressedCount = event.changes.count { it.pressed }
									if (pressedCount >= 2 || zoomState.scale > 1f) {
										val pan = event.calculatePan()
										val consumption = zoomState.transform(pan.x, pan.y, event.calculateZoom())
										if (consumption.consumed) {
											event.changes.forEach { it.consume() }
											transformVersion++
										}
									}
								} while (event.changes.any { it.pressed })
							}
						}
				} else {
					Modifier
				},
			),
	)
}

@Composable
private fun AnimatedDrawableLifecycle(animatable: Animatable?, isPageVisible: Boolean) {
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

internal fun resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled: Boolean): Int =
	if (isPreloadReductionEnabled) 0 else 1

internal fun resolveWebtoonAheadCacheFraction(isPreloadReductionEnabled: Boolean): Float =
	if (isPreloadReductionEnabled) 0f else WEBTOON_AHEAD_CACHE_FRACTION

internal fun resolveWebtoonGestureBoundaryHandoff(
	scale: Float,
	desiredY: Float,
	boundedY: Float,
	isTransformGesture: Boolean,
): Int = if (isTransformGesture) 0 else resolveWebtoonBoundaryHandoff(scale, desiredY, boundedY)

internal fun shouldReportReaderSettledPage(isScrollInProgress: Boolean, isRestoringAnchor: Boolean): Boolean =
	!isScrollInProgress && !isRestoringAnchor

internal fun shouldFlingWebtoonCanvas(singlePointerTransformed: Boolean, hadMultiplePointers: Boolean): Boolean =
	singlePointerTransformed && !hadMultiplePointers

private const val ZOOM_ANIMATION_DURATION_MS = 220
private const val ADVANCED_PAGE_EPSILON = 0.001f
private const val SIMULATION_PAGE_EPSILON = 0.001f
private const val READER_ANIMATION_DEBUG_TAG = "ReaderPageAnimation"
private const val WEBTOON_AHEAD_CACHE_FRACTION = 2f
private const val WEBTOON_PAGE_CONTENT_TYPE = "webtoon_page"
private const val WEBTOON_PULL_THRESHOLD = 0.3f
private const val READER_WINDOW_LOG_TAG = "ReaderWindow"
private const val AUTO_BACKGROUND_SAMPLE_SIZE = 64
