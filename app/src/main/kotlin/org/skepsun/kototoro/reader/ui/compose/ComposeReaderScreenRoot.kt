package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver

/**
 * Reader Compose entry point. ReaderViewModel remains the only owner of chapter,
 * position, boundary-loading, and persisted progress state.
 */
@Composable
fun ComposeReaderScreenRoot(
	viewModel: ReaderViewModel,
	imageLoader: ImageLoader,
	imagePipeline: ComposeReaderImagePipeline,
	requestedPage: Int? = null,
	requestedPageSmooth: Boolean = false,
	webtoonScrollRequest: ComposeReaderScrollRequest? = null,
	zoomCommand: ComposeReaderZoomCommand? = null,
	webtoonZoomCommand: ComposeWebtoonZoomCommand? = null,
	isDoublePage: Boolean = false,
	onShowErrorDetails: (Throwable, String?) -> Unit = { _, _ -> },
	onRetryError: (Throwable, retry: () -> Unit) -> Unit = { _, retry -> retry() },
	resolveErrorStringId: (Throwable) -> Int = ExceptionResolver::getResolveStringId,
	onReaderPositionChanged: (position: Int, internalScroll: Int) -> Unit = { _, _ -> },
	modifier: Modifier = Modifier,
) {
	val content by viewModel.content.collectAsStateWithLifecycle()
	val mode by viewModel.readerMode.collectAsStateWithLifecycle()
	val isWebtoonZoomEnabled by viewModel.isWebtoonZooEnabled.collectAsStateWithLifecycle(initialValue = false)
	val defaultWebtoonZoomOut by viewModel.defaultWebtoonZoomOut.collectAsStateWithLifecycle(initialValue = 0f)
	val isWebtoonGapsEnabled by viewModel.isWebtoonGapsEnabled.collectAsStateWithLifecycle(initialValue = false)
	val isWebtoonPullGestureEnabled by viewModel.isWebtoonPullGestureEnabled.collectAsStateWithLifecycle(initialValue = false)
	val readerUiState by viewModel.uiState.collectAsStateWithLifecycle()
	val pageAnimation by viewModel.pageAnimation.collectAsStateWithLifecycle()
	val readerSettings by viewModel.readerSettingsProducer.collectAsStateWithLifecycle()
	val isAnimationEnabled = LocalContext.current.isAnimationsEnabled && pageAnimation != ReaderAnimation.NONE
	val context = LocalContext.current
	val bookBackgroundTint = readerSettings.colorFilter?.getBackgroundTint()?.defaultColor
	val resolvedReaderBackgroundColor = resolveComposeReaderBackground(
		background = readerSettings.background,
		context = context,
		themeBackground = MaterialTheme.colorScheme.background.toArgb(),
	)
	val readerBackgroundColor = if (readerSettings.background.isLight(context)) {
		bookBackgroundTint ?: resolvedReaderBackgroundColor
	} else {
		resolvedReaderBackgroundColor
	}
	val readerImageColorFilter = remember(readerSettings.colorFilter) {
		readerSettings.colorFilter.toComposeColorFilter()
	}
	val initialPosition = content.state?.let { state ->
		content.pages.indexOfFirst { page ->
			page.chapterId == state.chapterId && page.index == state.page
		}.takeIf { it >= 0 }
	} ?: 0

	if (mode == null || content.pages.isEmpty()) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator()
		}
		return
	}

	val pageChanged: (org.skepsun.kototoro.reader.ui.pager.ReaderPage) -> Unit = { page ->
		val position = content.pages.indexOf(page)
		if (position >= 0) {
			viewModel.onCurrentPageChanged(position, position)
			onReaderPositionChanged(position, 0)
		}
	}

	if (isDoublePage) {
		ComposeDoublePageReader(
			pages = content.pages,
			initialPage = initialPosition,
			reverseLayout = mode == ReaderMode.REVERSED,
			coverPage = readerSettings.isReaderDoubleCoverPage,
			imageLoader = imageLoader,
			imagePipeline = imagePipeline,
			onPagesChanged = { lower, upper ->
				viewModel.onCurrentPageChanged(lower, upper)
				onReaderPositionChanged(lower, 0)
			},
			requestedPage = requestedPage,
			requestedPageSmooth = requestedPageSmooth,
			zoomCommand = zoomCommand,
			onShowErrorDetails = onShowErrorDetails,
			onRetryError = onRetryError,
			resolveErrorStringId = resolveErrorStringId,
			isAnimationEnabled = isAnimationEnabled,
			pageAnimation = if (isAnimationEnabled) pageAnimation else ReaderAnimation.NONE,
			readerBackground = readerSettings.background,
			readerBackgroundColor = readerBackgroundColor,
				bookBackgroundTint = bookBackgroundTint,
				imageColorFilter = readerImageColorFilter,
				isCropEnabled = readerSettings.isPagesCropEnabledStandard,
			modifier = modifier,
		)
	} else if (mode == ReaderMode.WEBTOON) {
		ComposeWebtoonReader(
			pages = content.pages,
			initialPage = initialPosition,
			initialScroll = content.state?.scroll ?: 0,
			imageLoader = imageLoader,
			imagePipeline = imagePipeline,
			onPageChanged = pageChanged,
			onInternalScrollChanged = { page, scroll ->
				val position = content.pages.indexOf(page)
				if (position >= 0) onReaderPositionChanged(position, scroll)
			},
			requestedPage = requestedPage,
			requestedPageSmooth = requestedPageSmooth,
			webtoonScrollRequest = webtoonScrollRequest,
			zoomCommand = zoomCommand,
			webtoonZoomCommand = webtoonZoomCommand,
			isZoomEnabled = isWebtoonZoomEnabled,
			defaultScale = 1f - defaultWebtoonZoomOut,
			isGapsEnabled = isWebtoonGapsEnabled,
			isPullGestureEnabled = isWebtoonPullGestureEnabled,
			canGoPreviousChapter = readerUiState?.hasPreviousChapter() != false,
			canGoNextChapter = readerUiState?.hasNextChapter() != false,
			onPullChapter = viewModel::switchChapterBy,
			onShowErrorDetails = onShowErrorDetails,
			onRetryError = onRetryError,
			resolveErrorStringId = resolveErrorStringId,
			isAnimationEnabled = isAnimationEnabled,
			readerBackgroundColor = readerBackgroundColor,
			imageColorFilter = readerImageColorFilter,
			bitmapConfig = readerSettings.bitmapConfig,
			isCropEnabled = readerSettings.isPagesCropEnabledWebtoon,
			modifier = modifier,
		)
	} else ComposePagedReader(
		pages = content.pages,
		initialPage = initialPosition,
		mode = mode ?: ReaderMode.STANDARD,
		imageLoader = imageLoader,
		imagePipeline = imagePipeline,
		onPageChanged = pageChanged,
		modifier = modifier,
		requestedPage = requestedPage,
		requestedPageSmooth = requestedPageSmooth,
		zoomCommand = zoomCommand,
		onShowErrorDetails = onShowErrorDetails,
		onRetryError = onRetryError,
		resolveErrorStringId = resolveErrorStringId,
		isAnimationEnabled = isAnimationEnabled,
		pageAnimation = if (isAnimationEnabled) pageAnimation else ReaderAnimation.NONE,
		readerBackground = readerSettings.background,
			readerBackgroundColor = readerBackgroundColor,
			bookBackgroundTint = bookBackgroundTint,
			imageColorFilter = readerImageColorFilter,
			zoomMode = readerSettings.zoomMode,
			isCropEnabled = readerSettings.isPagesCropEnabledStandard,
	)
}
