package org.skepsun.kototoro.reader.ui.compose


import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntRect
import coil3.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlin.math.abs
import kotlin.math.roundToInt
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.ui.resolvePagedReaderAnchorPosition
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

internal data class WebtoonImageSize(
    val width: Int,
    val height: Int,
)

internal sealed interface ReaderCropBoundsState {
    data object NotRequired : ReaderCropBoundsState
    data object Loading : ReaderCropBoundsState
    data class Ready(val bounds: IntRect?) : ReaderCropBoundsState
}

@Composable
internal fun rememberReaderCropBounds(
    uri: Uri?,
    isCropEnabled: Boolean,
    imagePipeline: ComposeReaderImagePipeline,
): ReaderCropBoundsState {
    return key(uri, isCropEnabled) {
        val initialState = if (isCropEnabled && uri != null) {
            ReaderCropBoundsState.Loading
        } else {
            ReaderCropBoundsState.NotRequired
        }
        val state by produceState<ReaderCropBoundsState>(initialState, uri, isCropEnabled) {
            if (isCropEnabled && uri != null) {
                value = ReaderCropBoundsState.Ready(imagePipeline.getTrimmedBounds(uri))
            }
        }
        state
    }
}

internal data class DoublePageTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal data class ReaderPagerAnimationDebugState(
    val scrolling: Boolean,
    val currentPage: Int,
    val settledPage: Int,
    val targetPage: Int,
    val offsetPercent: Int,
    val anchorPage: Int,
)

internal data class ReaderPagerReportState(
    val isDragged: Boolean,
    val isScrollInProgress: Boolean,
    val settledPage: Int,
    val targetPage: Int,
    val isRestoringAnchor: Boolean,
    val isProgrammaticRequestPending: Boolean,
)

internal data class WebtoonListAnchor(
    val pageKey: Long,
    val offsetPx: Int,
)

internal class WebtoonViewportAnchorState(
    var pageKey: Long,
    var offsetPx: Int,
)

internal data class WebtoonViewportConfiguration(
    val orientation: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
)

internal data class WebtoonViewportUpdate(
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
    val isPagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val zoomedPages = remember { mutableStateMapOf<Long, Boolean>() }
    val isCurrentPageZoomed = displayedPages.getOrNull(pagerState.currentPage)?.readerKey?.let {
        zoomedPages[it]
    } == true
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
    LaunchedEffect(pagerState, displayedPages, isRestoringPageAnchor, requestedPage) {
        snapshotFlow {
            ReaderPagerReportState(
                isDragged = isPagerDragged,
                isScrollInProgress = pagerState.isScrollInProgress,
                settledPage = pagerState.settledPage,
                targetPage = pagerState.targetPage,
                isRestoringAnchor = isRestoringPageAnchor,
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
            .collect { position ->
                displayedPages.getOrNull(position)?.let(onPageChanged)
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
                    onZoomedChanged = { zoomed ->
                        if (zoomed) zoomedPages[page.readerKey] = true else zoomedPages.remove(page.readerKey)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                when (pageAnimation) {
                    ReaderAnimation.SIMULATION -> ComposeReaderSimulationPageShadow(transform)
                    else -> Unit
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isVertical) {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
                modifier = Modifier
                    .fillMaxSize()
                    .trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION),
                key = { displayedPages[it].readerKey },
                userScrollEnabled = !isCurrentPageZoomed,
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = resolveReaderBeyondViewportPageCount(isPreloadReductionEnabled),
                modifier = Modifier
                    .fillMaxSize()
                    .trackComposeReaderPageCurl(pageCurlState, pageAnimation == ReaderAnimation.SIMULATION),
                reverseLayout = reverseLayout,
                key = { displayedPages[it].readerKey },
                userScrollEnabled = !isCurrentPageZoomed,
                pageContent = pageContent,
            )
        }
        pageOverlay()
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

internal fun resolveReaderPageToReport(
    isDragged: Boolean,
    isScrollInProgress: Boolean,
    settledPage: Int,
    targetPage: Int,
    isRestoringAnchor: Boolean,
    isProgrammaticRequestPending: Boolean = false,
): Int? = when {
    isRestoringAnchor || isDragged -> null
    isScrollInProgress && isProgrammaticRequestPending -> null
    isScrollInProgress -> targetPage
    else -> settledPage
}

internal fun shouldFlingAfterTransform(singlePointerTransformed: Boolean, hadMultiplePointers: Boolean): Boolean =
    singlePointerTransformed && !hadMultiplePointers

internal const val ZOOM_ANIMATION_DURATION_MS = 220
internal const val PAGED_ZOOM_EPSILON = 0.001f
internal const val ADVANCED_PAGE_EPSILON = 0.001f
internal const val SIMULATION_PAGE_EPSILON = 0.001f
internal const val READER_ANIMATION_DEBUG_TAG = "ReaderPageAnimation"
private const val WEBTOON_AHEAD_CACHE_FRACTION = 2f
internal const val WEBTOON_PAGE_CONTENT_TYPE = "webtoon_page"
internal const val WEBTOON_PULL_THRESHOLD = 0.3f
internal const val READER_WINDOW_LOG_TAG = "ReaderWindow"
internal const val AUTO_BACKGROUND_SAMPLE_SIZE = 64

