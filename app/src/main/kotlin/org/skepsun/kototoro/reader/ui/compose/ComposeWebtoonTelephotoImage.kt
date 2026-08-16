package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import me.saket.telephoto.subsamplingimage.ImageBitmapOptions
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlinx.coroutines.flow.distinctUntilChanged
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit
import java.io.File
import java.io.IOException

@Composable
internal fun ComposeWebtoonStaticSubsamplingImage(
	uri: Uri,
	split: ReaderPageSplit,
	cropBounds: IntRect?,
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	placeholder: @Composable () -> Unit,
	modifier: Modifier = Modifier,
) {
	ComposeTelephotoSubsamplingImage(
		uri = uri,
		split = split,
		cropBounds = cropBounds,
		bitmapConfig = bitmapConfig,
		colorFilter = colorFilter,
		contentScale = ContentScale.FillWidth,
		contentAlignment = Alignment.TopCenter,
		gestures = EnabledZoomGestures.None,
		onImageSizeResolved = onImageSizeResolved,
		onImageError = onImageError,
		placeholder = placeholder,
		modifier = modifier,
	)
}

@Composable
internal fun ComposePagedTelephotoImage(
	uri: Uri,
	pageKey: Long,
	split: ReaderPageSplit,
	cropBounds: IntRect?,
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	zoomMode: ZoomMode,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	onZoomedChanged: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	ComposeTelephotoSubsamplingImage(
		uri = uri,
		pageKey = pageKey,
		split = split,
		cropBounds = cropBounds,
		bitmapConfig = bitmapConfig,
		colorFilter = colorFilter,
		contentScale = when (zoomMode) {
			ZoomMode.FIT_CENTER, ZoomMode.KEEP_START -> ContentScale.Fit
			ZoomMode.FIT_HEIGHT -> ContentScale.FillHeight
			ZoomMode.FIT_WIDTH -> ContentScale.FillWidth
		},
		contentAlignment = if (zoomMode == ZoomMode.KEEP_START) Alignment.TopCenter else Alignment.Center,
		initialZoomMode = zoomMode,
		gestures = if (isZoomEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
		zoomCommand = zoomCommand,
		isAnimationEnabled = isAnimationEnabled,
		onImageSizeResolved = onImageSizeResolved,
		onImageError = onImageError,
		onZoomedChanged = onZoomedChanged,
		modifier = modifier,
	)
}

@Composable
private fun ComposeTelephotoSubsamplingImage(
	uri: Uri,
	pageKey: Long? = null,
	split: ReaderPageSplit = ReaderPageSplit.NONE,
	cropBounds: IntRect? = null,
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	contentScale: ContentScale,
	contentAlignment: Alignment,
	initialZoomMode: ZoomMode? = null,
	gestures: EnabledZoomGestures,
	zoomCommand: ComposeReaderZoomCommand? = null,
	isAnimationEnabled: Boolean = false,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	onZoomedChanged: (Boolean) -> Unit = {},
	placeholder: (@Composable () -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	val currentOnImageSizeResolved by rememberUpdatedState(onImageSizeResolved)
	val currentOnImageError by rememberUpdatedState(onImageError)
	val currentOnZoomedChanged by rememberUpdatedState(onZoomedChanged)
	val imageSource = remember(uri, split, cropBounds, context) {
		val source = if (uri.isAvifImage() || uri.isContentZipUri()) {
			NativeSubSamplingImageSource(context, uri)
		} else if (uri.isZipUri()) {
			val entryName = uri.fragment
			if (entryName != null) {
				ZipSubSamplingImageSource(
					zipFile = File(uri.schemeSpecificPart),
					entryName = entryName,
				)
			} else {
				null
			}
		} else {
			SubSamplingImageSource.contentUriOrNull(uri)
		}
		source?.withRegion(cropBounds, split)
	}
	if (imageSource == null) {
		LaunchedEffect(uri) {
			currentOnImageError(IOException("URI is not supported by Telephoto: $uri"))
		}
		return
	}
	val imageOptions = remember(bitmapConfig) {
		ImageBitmapOptions(config = bitmapConfig.toImageBitmapConfig())
	}
	val errorReporter = remember {
		object : SubSamplingImageErrorReporter {
			override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) {
				currentOnImageError(e)
			}
		}
	}
	val zoomableState = key(uri, pageKey, split) {
		rememberZoomableState(zoomSpec = ReaderTelephotoZoomSpec)
	}
	var viewportSize by remember(uri, pageKey, split) { mutableStateOf(IntSize.Zero) }
	var initialZoomApplied by remember(uri, pageKey, split, initialZoomMode) { mutableStateOf(false) }
	SideEffect {
		zoomableState.contentScale = contentScale
		zoomableState.contentAlignment = contentAlignment
	}
	LaunchedEffect(uri, pageKey, split, zoomableState) {
		snapshotFlow { (zoomableState.zoomFraction ?: 0f) > ZOOMED_EPSILON }
			.distinctUntilChanged()
			.collect(currentOnZoomedChanged)
	}
	DisposableEffect(uri, pageKey, split) {
		onDispose { currentOnZoomedChanged(false) }
	}
	val imageState = rememberSubSamplingImageState(
		imageSource = imageSource,
		zoomableState = zoomableState,
		imageOptions = imageOptions,
		errorReporter = errorReporter,
	)
	val imageSize = imageState.imageSize
	LaunchedEffect(uri, pageKey, split, imageSize) {
		if (imageSize != null) {
			currentOnImageSizeResolved(imageSize.width, imageSize.height)
		}
	}
	LaunchedEffect(uri, pageKey, split, initialZoomMode, imageSize, viewportSize) {
		if (!initialZoomApplied && initialZoomMode == ZoomMode.KEEP_START && imageSize != null &&
			viewportSize.width > 0 && viewportSize.height > 0
		) {
			zoomableState.zoomBy(
				zoomFactor = initialReaderScale(
					mode = initialZoomMode,
					viewportWidth = viewportSize.width,
					viewportHeight = viewportSize.height,
					imageWidth = imageSize.width,
					imageHeight = imageSize.height,
				),
				animationSpec = snap(),
			)
			initialZoomApplied = true
		}
	}
	LaunchedEffect(uri, zoomCommand) {
		if (zoomCommand != null && zoomCommand.pageKey == pageKey) {
			zoomableState.zoomBy(
				zoomFactor = zoomCommand.factor,
				animationSpec = if (isAnimationEnabled) tween(220) else snap(),
			)
		}
	}
	Box(modifier = modifier.onSizeChanged { viewportSize = it }) {
		if (!imageState.isImageDisplayedInFullQuality) {
			placeholder?.invoke()
		}
		SubSamplingImage(
			state = imageState,
			contentDescription = null,
			colorFilter = colorFilter,
			modifier = Modifier
				.fillMaxSize()
				.zoomable(
					state = zoomableState,
					gestures = gestures,
					onDoubleClick = ReaderTelephotoDoubleClickListener,
				),
		)
	}
}

private const val ZOOMED_EPSILON = 0.001f

internal fun Bitmap.Config.toImageBitmapConfig(): ImageBitmapConfig = when (this) {
	Bitmap.Config.RGB_565 -> ImageBitmapConfig.Rgb565
	else -> ImageBitmapConfig.Argb8888
}
