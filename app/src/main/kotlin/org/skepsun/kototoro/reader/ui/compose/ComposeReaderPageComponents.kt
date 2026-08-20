package org.skepsun.kototoro.reader.ui.compose


import android.graphics.Bitmap
import android.net.Uri
import android.graphics.drawable.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.DrawableImage
import coil3.request.SuccessResult
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

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
	applyPageBackground: Boolean = true,
	pageBackgroundColorOverride: Int? = null,
	onAutoBackgroundResolved: (Int) -> Unit = {},
	onZoomedChanged: (Boolean) -> Unit = {},
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
	val cropBoundsState = rememberReaderCropBounds(displayUri, isCropEnabled, imagePipeline)
	val cropBounds = (cropBoundsState as? ReaderCropBoundsState.Ready)?.bounds
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
				cropBounds = cropBounds,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = value.isAnimated,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				forceCoil = forceCoil,
				onZoomedChanged = onZoomedChanged,
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
				cropBounds = cropBounds,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = false,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				forceCoil = forceCoil,
				onZoomedChanged = onZoomedChanged,
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
				cropBounds = cropBounds,
				isCropEnabled = isCropEnabled,
				zoomMode = zoomMode,
				isAnimated = false,
				isPageVisible = isPageVisible,
				isReaderOptimizationEnabled = isReaderOptimizationEnabled,
				forceCoil = forceCoil,
				onZoomedChanged = onZoomedChanged,
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
internal fun ReaderPageError(
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
internal fun ReaderPageLoading(progress: Float?) {
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
internal fun ReaderPreviewImage(
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
		modifier = modifier,
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
	cropBounds: IntRect?,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
	isReaderOptimizationEnabled: Boolean,
	zoomMode: ZoomMode,
	forceCoil: Boolean,
	onZoomedChanged: (Boolean) -> Unit,
	onSubsamplingError: () -> Unit,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (isAnimated) {
		TelephotoCoilReaderImage(
			uri = uri,
			imageLoader = imageLoader,
			pageKey = pageKey,
			zoomMode = zoomMode,
			zoomCommand = zoomCommand,
			isZoomEnabled = isZoomEnabled,
			isAnimationEnabled = isAnimationEnabled,
			colorFilter = colorFilter,
			isPageVisible = isPageVisible,
			isReaderOptimizationEnabled = isReaderOptimizationEnabled,
			onImageSizeResolved = onImageSizeResolved,
			onImageError = onImageError,
			onZoomedChanged = onZoomedChanged,
			modifier = modifier,
		)
	} else if (!forceCoil) {
		ComposePagedTelephotoImage(
			uri = uri,
			pageKey = pageKey,
			split = split,
			cropBounds = cropBounds,
			bitmapConfig = bitmapConfig,
			colorFilter = colorFilter,
			zoomMode = zoomMode,
			zoomCommand = zoomCommand,
			isZoomEnabled = isZoomEnabled,
			isAnimationEnabled = isAnimationEnabled,
			onImageSizeResolved = onImageSizeResolved,
			onImageError = { onSubsamplingError() },
			onZoomedChanged = onZoomedChanged,
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
			onZoomedChanged = onZoomedChanged,
			modifier = modifier,
		)
	}
}

@Composable
internal fun TelephotoCoilReaderImage(
	uri: Uri,
	imageLoader: ImageLoader,
	pageKey: Long,
	zoomMode: ZoomMode?,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	colorFilter: ColorFilter?,
	isPageVisible: Boolean,
	isReaderOptimizationEnabled: Boolean,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	onZoomedChanged: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val currentOnImageSizeResolved by rememberUpdatedState(onImageSizeResolved)
	val currentOnImageError by rememberUpdatedState(onImageError)
	val currentOnZoomedChanged by rememberUpdatedState(onZoomedChanged)
	var animatable by remember(uri) { mutableStateOf<Animatable?>(null) }
	var imageSize by remember(uri, pageKey) { mutableStateOf(IntSize.Zero) }
	var viewportSize by remember(uri, pageKey) { mutableStateOf(IntSize.Zero) }
	var initialZoomApplied by remember(uri, pageKey, zoomMode) { mutableStateOf(false) }
	AnimatedDrawableLifecycle(animatable, isPageVisible)
	val zoomableState = key(uri, pageKey) {
		rememberZoomableState(zoomSpec = ReaderTelephotoZoomSpec)
	}
	val imageState = key(uri, pageKey) { rememberZoomableImageState(zoomableState) }
	val contentScale = when (zoomMode) {
		ZoomMode.FIT_HEIGHT -> ContentScale.FillHeight
		ZoomMode.FIT_WIDTH -> ContentScale.FillWidth
		ZoomMode.FIT_CENTER, ZoomMode.KEEP_START -> ContentScale.Fit
		null -> ContentScale.FillWidth
	}
	val alignment = if (zoomMode == null || zoomMode == ZoomMode.KEEP_START) Alignment.TopCenter else Alignment.Center
	SideEffect {
		zoomableState.contentScale = contentScale
		zoomableState.contentAlignment = alignment
	}
	LaunchedEffect(uri, pageKey, zoomableState) {
		snapshotFlow { (zoomableState.zoomFraction ?: 0f) > PAGED_ZOOM_EPSILON }
			.distinctUntilChanged()
			.collect(currentOnZoomedChanged)
	}
	DisposableEffect(uri, pageKey) {
		onDispose { currentOnZoomedChanged(false) }
	}
	val request = remember(
		uri,
		pageKey,
		isReaderOptimizationEnabled,
	) {
		ImageRequest.Builder(context)
			.data(uri)
			.allowHardware(false)
			.apply {
				if (isReaderOptimizationEnabled) memoryCachePolicy(CachePolicy.DISABLED)
			}
			.listener(
				onSuccess = { _, result ->
					animatable = (result.image as? DrawableImage)?.drawable as? Animatable
					imageSize = IntSize(result.image.width, result.image.height)
					currentOnImageSizeResolved(result.image.width, result.image.height)
				},
				onError = { _, result -> currentOnImageError(result.throwable) },
			)
			.build()
	}
	LaunchedEffect(uri, pageKey, zoomMode, imageSize, viewportSize) {
		if (!initialZoomApplied && zoomMode == ZoomMode.KEEP_START &&
			imageSize != IntSize.Zero && viewportSize != IntSize.Zero
		) {
			zoomableState.zoomBy(
				zoomFactor = initialReaderScale(
					zoomMode,
					viewportSize.width,
					viewportSize.height,
					imageSize.width,
					imageSize.height,
				),
				animationSpec = snap(),
			)
			initialZoomApplied = true
		}
	}
	LaunchedEffect(uri, pageKey, zoomCommand, isAnimationEnabled) {
		if (zoomCommand?.pageKey == pageKey) {
			zoomableState.zoomBy(
				zoomFactor = zoomCommand.factor,
				animationSpec = if (isAnimationEnabled) tween(220) else snap(),
			)
		}
	}
	ZoomableAsyncImage(
		model = request,
		imageLoader = imageLoader,
		state = imageState,
		contentDescription = null,
		gestures = if (isZoomEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
		contentScale = contentScale,
		alignment = alignment,
		colorFilter = colorFilter,
		onDoubleClick = ReaderTelephotoDoubleClickListener,
		modifier = modifier.onSizeChanged { viewportSize = it },
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
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	colorFilter: ColorFilter?,
	isCropEnabled: Boolean,
	isAnimated: Boolean,
	isPageVisible: Boolean,
	isReaderOptimizationEnabled: Boolean,
	zoomMode: ZoomMode,
	onImageError: (Throwable) -> Unit,
	onZoomedChanged: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
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
	val currentOnZoomedChanged by rememberUpdatedState(onZoomedChanged)
	LaunchedEffect(uri, pageKey, transformVersion) {
		currentOnZoomedChanged(zoomState.scale > PAGED_ZOOM_EPSILON + 1f)
	}
	DisposableEffect(uri, pageKey) {
		onDispose { currentOnZoomedChanged(false) }
	}

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
		model = remember(uri, pageKey, split, isCropEnabled, isAnimated, isReaderOptimizationEnabled) {
			ImageRequest.Builder(context)
				.data(uri)
				.allowHardware(!isAnimated)
				.apply {
					if (!isAnimated) transformations(ComposeReaderPageTransformation(isCropEnabled, split))
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

