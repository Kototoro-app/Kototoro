package org.skepsun.kototoro.reader.ui.compose


import android.graphics.Bitmap
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import coil3.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.roundToInt
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

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
	val isPagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
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
		val safeScale = scale.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 1f
		val maxX = (pagerState.layoutInfo.viewportSize.width * (safeScale - 1f) / 2f).coerceAtLeast(0f)
		val maxY = (pagerState.layoutInfo.viewportSize.height * (safeScale - 1f) / 2f).coerceAtLeast(0f)
		return Offset(
			x.takeIf(Float::isFinite)?.coerceIn(-maxX, maxX) ?: 0f,
			y.takeIf(Float::isFinite)?.coerceIn(-maxY, maxY) ?: 0f,
		)
	}
	fun spreadTransform(spreadIndex: Int): DoublePageTransform =
		spreadTransforms[spreadIndex]?.takeIf(DoublePageTransform::isFinite) ?: DoublePageTransform()

	fun applySpreadTransform(
		spreadIndex: Int,
		nextScale: Float,
		pan: Offset,
		focus: Offset,
	): DoublePageTransform {
		val stored = spreadTransform(spreadIndex)
		val previous = stored.takeIf(DoublePageTransform::isFinite) ?: DoublePageTransform()
		val boundedScale = nextScale.takeIf(Float::isFinite)
			?.coerceIn(1f, READER_MAX_ZOOM_SCALE) ?: previous.scale
		val factor = if (previous.scale > 0f) boundedScale / previous.scale else 1f
		val center = Offset(
			pagerState.layoutInfo.viewportSize.width / 2f,
			pagerState.layoutInfo.viewportSize.height / 2f,
		)
		val safeFocus = focus.takeIf(Offset::isFinite) ?: center
		val safePan = pan.takeIf(Offset::isFinite) ?: Offset.Zero
		val focusedTranslation = (safeFocus - center) * (1f - factor)
		val scaledPreviousOffset = Offset(previous.offsetX, previous.offsetY) * factor
		val bounded = clampSpreadOffset(
			boundedScale,
			scaledPreviousOffset.x + safePan.x + focusedTranslation.x,
			scaledPreviousOffset.y + safePan.y + focusedTranslation.y,
		)
		return DoublePageTransform(
			scale = boundedScale,
			offsetX = bounded.x,
			offsetY = bounded.y,
		).also { spreadTransforms[spreadIndex] = it }
	}

	suspend fun animateSpreadScaleTo(spreadIndex: Int, targetScale: Float, focus: Offset) {
		val initialScale = spreadTransform(spreadIndex).scale
		val boundedTarget = targetScale.coerceIn(1f, READER_MAX_ZOOM_SCALE)
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
	LaunchedEffect(pagerState, spreads, isRestoringAnchor, requestedPage) {
		snapshotFlow {
			ReaderPagerReportState(
				isDragged = isPagerDragged,
				isScrollInProgress = pagerState.isScrollInProgress,
				settledPage = pagerState.settledPage,
				targetPage = pagerState.targetPage,
				isRestoringAnchor = isRestoringAnchor,
				isProgrammaticRequestPending = requestedPage != null,
			)
		}
			.mapNotNull { state ->
				resolveReaderPageToReport(
					isDragged = state.isDragged,
					isScrollInProgress = state.isScrollInProgress,
					settledPage = state.settledPage,
					targetPage = state.targetPage,
					isRestoringAnchor = state.isRestoringAnchor,
					isProgrammaticRequestPending = state.isProgrammaticRequestPending,
				)
			}
			.distinctUntilChanged()
			.collect { spreadIndex ->
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
			val target = (current.scale * command.factor).coerceIn(1f, READER_MAX_ZOOM_SCALE)
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
				var singlePointerTransformed = false
				var hadMultiplePointers = false
				var moved = false
				var eventTime = down.uptimeMillis
				do {
					val event = awaitPointerEvent(PointerEventPass.Initial)
					if (event.changes.any { it.isConsumed }) {
						moved = true
						continue
					}
					event.changes.maxByOrNull { it.uptimeMillis }?.let { eventTime = it.uptimeMillis }
					val pressedCount = event.changes.count { it.pressed }
					if (pressedCount >= 2) {
						hadMultiplePointers = true
						moved = true
					} else if (event.changes.any { it.pressed }) {
						val change = event.changes.firstOrNull { it.pressed }
						if (change != null) velocityTracker.addPosition(change.uptimeMillis, change.position)
						val currentPosition = change?.position
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
						val pan = if (pressedCount >= 2) Offset.Zero else event.calculatePan()
						val zoom = event.calculateZoom()
						val previous = spreadTransform(spreadIndex)
						val previousScale = previous.scale
						val nextScale = (previousScale * zoom).coerceIn(1f, READER_MAX_ZOOM_SCALE)
						val updated = applySpreadTransform(spreadIndex, nextScale, pan, centroid)
						val consumedPanX = updated.offsetX - previous.offsetX
						val consumedPanY = updated.offsetY - previous.offsetY
						val consumed = abs(nextScale - previousScale) > 0.001f ||
							abs(consumedPanX) > 0.001f || abs(consumedPanY) > 0.001f
						if (consumed) {
							event.changes.forEach { it.consume() }
							transformed = true
							if (pressedCount == 1) singlePointerTransformed = true
						}
					}
				} while (event.changes.any { it.pressed })
				if (transformed) {
					if (shouldFlingAfterTransform(singlePointerTransformed, hadMultiplePointers)) {
						spreadFlingJob = spreadGestureScope.launch {
							flingSpread(spreadIndex, velocityTracker.calculateVelocity())
						}
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

	Box(modifier = modifier.fillMaxSize()) {
		HorizontalPager(
			state = pagerState,
			beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
			reverseLayout = reverseLayout,
			userScrollEnabled = spreadTransform(pagerState.currentPage).scale <= 1f + PAGED_ZOOM_EPSILON,
			modifier = Modifier
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
					.doublePageSpreadGestures(spreadIndex)
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
					modifier = Modifier.fillMaxSize(),
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
				when (pageAnimation) {
					ReaderAnimation.SIMULATION -> ComposeReaderSimulationPageShadow(transform)
					else -> Unit
				}
				}
			}
		}
		pageOverlay()
	}
}

private fun DoublePageTransform.isFinite(): Boolean =
	scale.isFinite() && scale > 0f && offsetX.isFinite() && offsetY.isFinite()

private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()

