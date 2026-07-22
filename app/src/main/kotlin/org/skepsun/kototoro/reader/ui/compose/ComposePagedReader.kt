package org.skepsun.kototoro.reader.ui.compose

import android.net.Uri
import android.graphics.drawable.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.DrawableImage
import coil3.request.SuccessResult
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.image.AvifAnimatedDrawable
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

private data class WebtoonImageSize(
	val width: Int,
	val height: Int,
)

private data class WebtoonListAnchor(
	val index: Int,
	val offsetPx: Int,
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
	isCropEnabled: Boolean = false,
	modifier: Modifier = Modifier,
) {
	if (pages.isEmpty()) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator()
		}
		return
	}

	val reverseLayout = mode == ReaderMode.REVERSED
	val pagerState = rememberPagerState(
		initialPage = initialPage.coerceIn(pages.indices),
		pageCount = pages::size,
	)

	LaunchedEffect(pagerState, pages) {
		snapshotFlow { pagerState.settledPage }
			.distinctUntilChanged()
			.collect { position -> pages.getOrNull(position)?.let(onPageChanged) }
	}

	LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled) {
		requestedPage?.takeIf { it in pages.indices && it != pagerState.currentPage }?.let {
			if (shouldAnimatePageNavigation(pagerState.currentPage, it, requestedPageSmooth, isAnimationEnabled)) {
				pagerState.animateScrollToPage(it)
			} else {
				pagerState.scrollToPage(it)
			}
		}
	}

	val pageContent: @Composable PagerScope.(Int) -> Unit = { position ->
		val page = pages[position]
		val isVertical = mode == ReaderMode.VERTICAL
		val logicalOffset = (position - pagerState.currentPage) - pagerState.currentPageOffsetFraction
		val pageOffset = if (reverseLayout && !isVertical) -logicalOffset else logicalOffset
		val transform = resolveComposeReaderPageTransform(pageAnimation, pageOffset, isVertical, reverseLayout)
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
			isCropEnabled = isCropEnabled,
			isPageVisible = pagerState.settledPage == position,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer {
					alpha = transform.alpha
					translationX = if (isVertical) 0f else transform.translationFactor * size.width
					translationY = if (isVertical) transform.translationFactor * size.height else 0f
					rotationX = transform.rotationX
					rotationY = transform.rotationY
					transformOrigin = transform.transformOrigin
					cameraDistance = READER_PAGE_CAMERA_DISTANCE
				},
		)
	}

	if (mode == ReaderMode.VERTICAL) {
		VerticalPager(
			state = pagerState,
			modifier = modifier.fillMaxSize(),
			key = { pages[it].readerKey },
			pageContent = pageContent,
		)
	} else {
		HorizontalPager(
			state = pagerState,
			modifier = modifier.fillMaxSize(),
			reverseLayout = reverseLayout,
			key = { pages[it].readerKey },
			pageContent = pageContent,
		)
	}
}

@Composable
fun ComposeWebtoonReader(
	pages: List<ReaderPage>,
	initialPage: Int,
	initialScroll: Int,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	onPageChanged: (ReaderPage) -> Unit,
	onInternalScrollChanged: (ReaderPage, Int) -> Unit,
	requestedPage: Int? = null,
	requestedPageSmooth: Boolean = false,
	webtoonScrollRequest: ComposeReaderScrollRequest? = null,
	zoomCommand: ComposeReaderZoomCommand? = null,
	webtoonZoomCommand: ComposeWebtoonZoomCommand? = null,
	isZoomEnabled: Boolean = false,
	defaultScale: Float = 1f,
	isGapsEnabled: Boolean = false,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = { 0 },
	isAnimationEnabled: Boolean = true,
	readerBackgroundColor: Int = android.graphics.Color.BLACK,
	imageColorFilter: ColorFilter? = null,
	isCropEnabled: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(pages.indices))
	// Keep dimensions outside individual lazy items. When an item is recycled and later returns
	// from Coil's cache, its height is known before the bitmap is drawn, preventing scroll jumps.
	val imageSizes = remember { mutableStateMapOf<Long, WebtoonImageSize>() }
	val internalOffsets = remember {
		mutableStateMapOf<Long, Int>().apply {
			pages.getOrNull(initialPage)?.let { page -> put(page.readerKey, initialScroll.coerceAtLeast(0)) }
		}
	}
	var pendingAnchor by remember { mutableStateOf<WebtoonListAnchor?>(null) }
	var viewportWidthPx by remember { mutableIntStateOf(0) }
	var viewportHeightPx by remember { mutableIntStateOf(0) }
	fun measurementFor(position: Int): WebtoonViewportMeasurement {
		val size = pages.getOrNull(position)?.let { page -> imageSizes[page.readerKey] }
		return measureWebtoonViewport(viewportHeightPx, viewportWidthPx, size?.width, size?.height)
	}
	fun consumeVisibleInternalScroll(scrollDelta: Int): Int {
		if (scrollDelta == 0) return 0
		val visibleItems = listState.layoutInfo.visibleItemsInfo
		val position = if (scrollDelta > 0) {
			visibleItems.firstOrNull()?.index
		} else {
			visibleItems.lastOrNull()?.index
		} ?: return 0
		val page = pages.getOrNull(position) ?: return 0
		val measurement = measurementFor(position)
		if (measurement.internalScrollRangePx == 0) return 0
		val result = consumeWebtoonInternalScroll(
			offsetPx = internalOffsets[page.readerKey] ?: 0,
			scrollRangePx = measurement.internalScrollRangePx,
			deltaPx = scrollDelta,
		)
		internalOffsets[page.readerKey] = result.offsetPx
		onInternalScrollChanged(page, result.offsetPx)
		return result.consumedPx
	}
	var canvasScale by remember(defaultScale) { mutableFloatStateOf(defaultScale.coerceIn(0.5f, 1f)) }
	val zoomAnimationScope = rememberCoroutineScope()
	var webtoonZoomAnimationJob by remember { mutableStateOf<Job?>(null) }
	suspend fun animateWebtoonScaleTo(targetScale: Float) {
		if (!isAnimationEnabled) {
			canvasScale = targetScale
			return
		}
		animate(
			initialValue = canvasScale,
			targetValue = targetScale,
			animationSpec = tween(ZOOM_ANIMATION_DURATION_MS),
		) { value, _ -> canvasScale = value }
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

	LaunchedEffect(listState, pages) {
		snapshotFlow { listState.firstVisibleItemIndex }
			.distinctUntilChanged()
			.collect { position ->
				pages.getOrNull(position)?.let { page ->
					onPageChanged(page)
					onInternalScrollChanged(page, internalOffsets[page.readerKey] ?: 0)
				}
			}
	}
	LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled) {
		requestedPage?.takeIf { it in pages.indices }?.let { position ->
			if (shouldAnimatePageNavigation(
					listState.firstVisibleItemIndex,
					position,
					requestedPageSmooth,
					isAnimationEnabled,
				)) {
				listState.animateScrollToItem(position)
			} else {
				listState.scrollToItem(position)
			}
		}
	}
	LaunchedEffect(webtoonScrollRequest) {
		webtoonScrollRequest?.let { request ->
			fun dispatchScroll(delta: Float) {
				val requestedDelta = delta.toInt()
				val internallyConsumed = consumeVisibleInternalScroll(requestedDelta)
				listState.dispatchRawDelta((requestedDelta - internallyConsumed).toFloat())
			}
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
	LaunchedEffect(pendingAnchor) {
		pendingAnchor?.let { anchor ->
			if (!listState.isScrollInProgress && anchor.index in pages.indices) {
				listState.scrollToItem(anchor.index, anchor.offsetPx)
			}
			pendingAnchor = null
		}
	}

	BoxWithConstraints(
		modifier = modifier
			.fillMaxSize()
			.background(Color(readerBackgroundColor))
			.pointerInput(isZoomEnabled) {
				if (isZoomEnabled) {
					detectTransformGestures { _, _, zoom, _ ->
						webtoonZoomAnimationJob?.cancel()
						canvasScale = (canvasScale * zoom).coerceIn(0.5f, 2.5f)
					}
				}
			}
			.pointerInput(isZoomEnabled, defaultScale) {
				if (isZoomEnabled) {
					detectTapGestures(onDoubleTap = {
						val targetScale = if (kotlin.math.abs(canvasScale - defaultScale) > 0.001f) {
							defaultScale.coerceIn(0.5f, 1f)
						} else {
							2f
						}
						webtoonZoomAnimationJob?.cancel()
						webtoonZoomAnimationJob = zoomAnimationScope.launch {
							animateWebtoonScaleTo(targetScale)
						}
					})
				}
			},
	) {
		val nestedScrollConnection = remember(listState, pages, viewportWidthPx, viewportHeightPx) {
			object : NestedScrollConnection {
				override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
					val consumed = consumeVisibleInternalScroll((-available.y).toInt())
					return Offset(x = 0f, y = -consumed.toFloat())
				}
			}
		}
		val pageGap = if (isGapsEnabled) dimensionResource(R.dimen.webtoon_pages_gap) else 0.dp
		LazyColumn(
			state = listState,
			verticalArrangement = Arrangement.spacedBy(pageGap),
			modifier = Modifier
				.fillMaxSize()
				.onSizeChanged { size ->
					viewportWidthPx = size.width
					viewportHeightPx = size.height
				}
				.nestedScroll(nestedScrollConnection)
				.graphicsLayer {
				scaleX = canvasScale
				scaleY = canvasScale
				transformOrigin = TransformOrigin.Center
			},
		) {
			items(
			count = pages.size,
			key = { pages[it].readerKey },
		) { position ->
			ComposeWebtoonPage(
				page = pages[position],
				imageLoader = imageLoader,
				imagePipeline = imagePipeline,
				measurement = measurementFor(position),
				internalOffsetPx = restoreWebtoonInternalScroll(
					savedOffsetPx = internalOffsets[pages[position].readerKey] ?: 0,
					scrollRangePx = measurementFor(position).internalScrollRangePx,
				),
				onImageSizeResolved = { width, height ->
					if (width > 0 && height > 0) {
						val pageKey = pages[position].readerKey
						val newSize = WebtoonImageSize(width, height)
						if (imageSizes[pageKey] != newSize) {
							if (!listState.isScrollInProgress) {
								pendingAnchor = WebtoonListAnchor(
									index = listState.firstVisibleItemIndex,
									offsetPx = listState.firstVisibleItemScrollOffset,
								)
							}
							imageSizes[pageKey] = newSize
							val restoredOffset = restoreWebtoonInternalScroll(
								savedOffsetPx = internalOffsets[pageKey] ?: 0,
								scrollRangePx = measurementFor(position).internalScrollRangePx,
							)
							internalOffsets[pageKey] = restoredOffset
							onInternalScrollChanged(pages[position], restoredOffset)
						}
					}
				},
				onShowErrorDetails = onShowErrorDetails,
				onRetryError = onRetryError,
				resolveErrorStringId = resolveErrorStringId,
				readerBackgroundColor = readerBackgroundColor,
				imageColorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isPageVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == position },
				modifier = Modifier.fillMaxWidth(),
			)
		}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeDoublePageReader(
	pages: List<ReaderPage>,
	initialPage: Int,
	reverseLayout: Boolean,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	onPagesChanged: (Int, Int) -> Unit,
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
	isCropEnabled: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val spreadModel = remember(pages.size) { DoublePageSpreadModel.create(pages.size) }
	val spreads = spreadModel.spreads
	val pageKeys = pages.map(ReaderPage::readerKey)
	var anchorPageKey by remember { mutableStateOf(pages[initialPage.coerceIn(pages.indices)].readerKey) }
	val retainedAnchorPageKey = anchorPageKey
	var isRestoringAnchor by remember { mutableStateOf(false) }
	val pagerState = rememberPagerState(
		initialPage = spreadModel.spreadIndexForPage(initialPage),
		pageCount = spreads::size,
	)
	val autoBackgroundColors = remember { mutableStateMapOf<Long, Int>() }

	LaunchedEffect(pageKeys, requestedPage) {
		if (requestedPage == null) {
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
		snapshotFlow { pagerState.settledPage to isRestoringAnchor }
			.distinctUntilChanged()
			.collect { (spreadIndex, restoringAnchor) ->
				if (restoringAnchor) return@collect
				val spread = spreads[spreadIndex]
				anchorPageKey = pages[spread.lowerPosition].readerKey
				onPagesChanged(spread.lowerPosition, spread.upperPosition)
			}
	}
	LaunchedEffect(requestedPage, requestedPageSmooth, isAnimationEnabled) {
		requestedPage?.let { position ->
			val spreadIndex = spreadModel.spreadIndexForPage(position)
			pages.getOrNull(position)?.let { anchorPageKey = it.readerKey }
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

	HorizontalPager(
		state = pagerState,
		reverseLayout = reverseLayout,
		modifier = modifier.fillMaxSize(),
		key = { spreadIndex ->
			spreads[spreadIndex].positions.joinToString(separator = ":") { pages[it].readerKey.toString() }
		},
	) { spreadIndex ->
		val spread = spreads[spreadIndex]
		val logicalOffset = (spreadIndex - pagerState.currentPage) - pagerState.currentPageOffsetFraction
		val pageOffset = if (reverseLayout) -logicalOffset else logicalOffset
		val transform = resolveComposeReaderPageTransform(
			animation = pageAnimation,
			pageOffset = pageOffset,
			isVertical = false,
			isReversed = reverseLayout,
		)
		val firstPageKey = pages[spread.lowerPosition].readerKey
		val secondPageKey = pages.getOrNull(spread.upperPosition)
			?.takeIf { spread.upperPosition != spread.lowerPosition }
			?.readerKey
		val rawSpreadBackground = resolveDoublePageBackground(
			background = readerBackground,
			configuredColor = readerBackgroundColor,
			firstAutoColor = autoBackgroundColors[firstPageKey],
			secondAutoColor = secondPageKey?.let(autoBackgroundColors::get),
		)
		val spreadBackground = if (readerBackground == ReaderBackground.AUTO) {
			applyAutomaticBookBackgroundTint(rawSpreadBackground, bookBackgroundTint)
		} else {
			rawSpreadBackground
		}
		Row(
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer {
					alpha = transform.alpha
					translationX = transform.translationFactor * size.width
					rotationY = transform.rotationY
					transformOrigin = transform.transformOrigin
					cameraDistance = READER_PAGE_CAMERA_DISTANCE
				}
				.background(Color(spreadBackground)),
		) {
			spread.positions.forEach { position ->
				ComposeReaderPage(
					page = pages[position],
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
					isCropEnabled = isCropEnabled,
					isPageVisible = pagerState.settledPage == spreadIndex,
					applyPageBackground = false,
					onAutoBackgroundResolved = { color ->
						autoBackgroundColors[pages[position].readerKey] = color
					},
					modifier = Modifier.weight(1f).fillMaxSize(),
				)
			}
			if (spread.lowerPosition == spread.upperPosition) {
				Box(modifier = Modifier.weight(1f).fillMaxSize())
			}
		}
	}
}

@Composable
fun ComposeReaderPage(
	page: ReaderPage,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	aspectRatio: Float? = null,
	placeholderMinHeight: Dp? = null,
	onImageSizeResolved: (width: Int, height: Int) -> Unit = { _, _ -> },
	zoomCommand: ComposeReaderZoomCommand? = null,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = { 0 },
	isAnimationEnabled: Boolean = true,
	readerBackground: ReaderBackground = ReaderBackground.BLACK,
	readerBackgroundColor: Int = android.graphics.Color.BLACK,
	bookBackgroundTint: Int? = null,
	imageColorFilter: ColorFilter? = null,
	isCropEnabled: Boolean = false,
	isPageVisible: Boolean = true,
	applyPageBackground: Boolean = true,
	onAutoBackgroundResolved: (Int) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	var retryKey by remember(page.readerKey) { mutableIntStateOf(0) }
	val state by produceState<ComposeReaderImageState>(
		initialValue = ComposeReaderImageState.LoadingOriginal,
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
			.background(if (applyPageBackground) Color(pageBackgroundColor) else Color.Transparent),
		contentAlignment = Alignment.Center,
	) {
		when (val value = state) {
			ComposeReaderImageState.LoadingOriginal -> CircularProgressIndicator()
			is ComposeReaderImageState.PreviewReady -> ReaderPreviewImage(
				page = page,
				previewUrl = value.previewUrl,
				imageLoader = imageLoader,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				contentScale = ContentScale.Fit,
				modifier = Modifier.fillMaxSize(),
			)
			is ComposeReaderImageState.OriginalReady -> ZoomableReaderImage(
				uri = value.original,
				imageLoader = imageLoader,
				onImageSizeResolved = { width, height ->
					imagePipeline.onImageDecoded(page, width, height)
					onImageSizeResolved(width, height)
				},
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = value.isAnimated,
				isPageVisible = isPageVisible,
				modifier = Modifier.fillMaxSize(),
			)
			is ComposeReaderImageState.Enhancing -> ZoomableReaderImage(
				uri = value.original,
				imageLoader = imageLoader,
				onImageSizeResolved = { width, height ->
					imagePipeline.onImageDecoded(page, width, height)
					onImageSizeResolved(width, height)
				},
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = false,
				isPageVisible = isPageVisible,
				modifier = Modifier.fillMaxSize(),
			)
			is ComposeReaderImageState.EnhancedReady -> ZoomableReaderImage(
				uri = value.enhanced,
				imageLoader = imageLoader,
				onImageSizeResolved = onImageSizeResolved,
				pageKey = page.readerKey,
				split = page.split,
				zoomCommand = zoomCommand,
				isAnimationEnabled = isAnimationEnabled,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = false,
				isPageVisible = isPageVisible,
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

@Composable
private fun ComposeWebtoonPage(
	page: ReaderPage,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	measurement: WebtoonViewportMeasurement,
	internalOffsetPx: Int,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onShowErrorDetails: (Throwable, String?) -> Unit,
	onRetryError: (Throwable, retry: () -> Unit) -> Unit,
	resolveErrorStringId: (Throwable) -> Int,
	readerBackgroundColor: Int,
	imageColorFilter: ColorFilter?,
	isCropEnabled: Boolean,
	isPageVisible: Boolean,
	modifier: Modifier = Modifier,
) {
	var retryKey by remember(page.readerKey) { mutableIntStateOf(0) }
	val state by produceState<ComposeReaderImageState>(
		initialValue = ComposeReaderImageState.LoadingOriginal,
		key1 = page.readerKey,
		key2 = retryKey,
	) {
		imagePipeline.observe(page, force = retryKey > 0).collect { value = it }
	}
	val itemHeight = with(LocalDensity.current) { measurement.itemHeightPx.toDp() }

	Box(
		modifier = modifier
			.height(itemHeight)
			.clipToBounds()
			.background(Color(readerBackgroundColor)),
		contentAlignment = Alignment.Center,
	) {
		when (val value = state) {
			ComposeReaderImageState.LoadingOriginal -> CircularProgressIndicator()
			is ComposeReaderImageState.PreviewReady -> ReaderPreviewImage(
				page = page,
				previewUrl = value.previewUrl,
				imageLoader = imageLoader,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				contentScale = ContentScale.FillWidth,
				modifier = Modifier.fillMaxSize(),
			)
			is ComposeReaderImageState.OriginalReady -> WebtoonImage(
				uri = value.original,
				imageLoader = imageLoader,
				internalOffsetPx = internalOffsetPx,
				pageKey = page.readerKey,
				split = page.split,
				onImageSizeResolved = { width, height ->
					imagePipeline.onImageDecoded(page, width, height)
					onImageSizeResolved(width, height)
				},
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = value.isAnimated,
				isPageVisible = isPageVisible,
			)
			is ComposeReaderImageState.Enhancing -> WebtoonImage(
				uri = value.original,
				imageLoader = imageLoader,
				internalOffsetPx = internalOffsetPx,
				pageKey = page.readerKey,
				split = page.split,
				onImageSizeResolved = { width, height ->
					imagePipeline.onImageDecoded(page, width, height)
					onImageSizeResolved(width, height)
				},
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = false,
				isPageVisible = isPageVisible,
			)
			is ComposeReaderImageState.EnhancedReady -> WebtoonImage(
				uri = value.enhanced,
				imageLoader = imageLoader,
				internalOffsetPx = internalOffsetPx,
				pageKey = page.readerKey,
				split = page.split,
				onImageSizeResolved = onImageSizeResolved,
				colorFilter = imageColorFilter,
				isCropEnabled = isCropEnabled,
				isAnimated = false,
				isPageVisible = isPageVisible,
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
		if (cause.isSerializable()) {
			TextButton(onClick = onShowDetails) {
				Text(stringResource(R.string.error_details))
			}
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
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val request = remember(page.readerKey, previewUrl, isCropEnabled) {
		ImageRequest.Builder(context)
			.data(previewUrl)
			.mangaSourceExtra(page.source)
			.transformations(ComposeReaderPageTransformation(isCropEnabled, page.split))
			.build()
	}
	AsyncImage(
		model = request,
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = contentScale,
		colorFilter = colorFilter,
		modifier = modifier,
	)
}

@Composable
private fun WebtoonImage(
	uri: Uri,
	imageLoader: ImageLoader,
	internalOffsetPx: Int,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	colorFilter: ColorFilter?,
	pageKey: Long,
	split: ReaderPageSplit,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
) {
	val context = LocalContext.current
	var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
	AnimatedDrawableLifecycle(animatable, isPageVisible)
	AsyncImage(
		model = remember(uri, pageKey, split, isCropEnabled, isAnimated) {
			ImageRequest.Builder(context)
				.data(uri)
				.allowHardware(!isAnimated)
				.apply {
					if (!isAnimated) transformations(ComposeReaderPageTransformation(isCropEnabled, split))
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
		modifier = Modifier
			.fillMaxSize()
			.graphicsLayer { translationY = -internalOffsetPx.toFloat() },
	)
}

@Composable
private fun ZoomableReaderImage(
	uri: Uri,
	imageLoader: ImageLoader,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	pageKey: Long,
	split: ReaderPageSplit,
	zoomCommand: ComposeReaderZoomCommand?,
	isAnimationEnabled: Boolean,
	colorFilter: ColorFilter?,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val zoomState = rememberSaveable(pageKey, saver = ReaderZoomState.Saver) { ReaderZoomState() }
	var viewportWidth by remember(pageKey) { mutableIntStateOf(0) }
	var viewportHeight by remember(pageKey) { mutableIntStateOf(0) }
	var imageWidth by remember(pageKey) { mutableIntStateOf(0) }
	var imageHeight by remember(pageKey) { mutableIntStateOf(0) }
	var transformVersion by remember(pageKey) { mutableIntStateOf(0) }
	val zoomAnimationScope = rememberCoroutineScope()
	var zoomAnimationJob by remember(pageKey) { mutableStateOf<Job?>(null) }
	var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
	AnimatedDrawableLifecycle(animatable, isPageVisible)

	fun updateGeometry() {
		zoomState.updateGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight)
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
		model = remember(uri, pageKey, split, isCropEnabled, isAnimated) {
			ImageRequest.Builder(context)
				.data(uri)
				.allowHardware(!isAnimated)
				.apply {
					if (!isAnimated) transformations(ComposeReaderPageTransformation(isCropEnabled, split))
				}
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = ContentScale.Fit,
		colorFilter = colorFilter,
		onSuccess = { result ->
			animatable = (result.result.image as? DrawableImage)?.drawable as? Animatable
			imageWidth = result.result.image.width
			imageHeight = result.result.image.height
			updateGeometry()
			onImageSizeResolved(imageWidth, imageHeight)
		},
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
			},
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

private const val ZOOM_ANIMATION_DURATION_MS = 220
private const val AUTO_BACKGROUND_SAMPLE_SIZE = 64
