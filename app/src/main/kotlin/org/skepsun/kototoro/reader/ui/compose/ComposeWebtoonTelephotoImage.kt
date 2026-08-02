package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.layout.ContentScale
import me.saket.telephoto.subsamplingimage.ImageBitmapOptions
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.skepsun.kototoro.core.model.ZoomMode
import java.io.IOException

@Composable
internal fun ComposeWebtoonStaticSubsamplingImage(
	uri: Uri,
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	placeholder: @Composable () -> Unit,
	modifier: Modifier = Modifier,
) {
	ComposeTelephotoSubsamplingImage(
		uri = uri,
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
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	zoomMode: ZoomMode,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	ComposeTelephotoSubsamplingImage(
		uri = uri,
		pageKey = pageKey,
		bitmapConfig = bitmapConfig,
		colorFilter = colorFilter,
		contentScale = when (zoomMode) {
			ZoomMode.FIT_CENTER, ZoomMode.KEEP_START -> ContentScale.Fit
			ZoomMode.FIT_HEIGHT -> ContentScale.FillHeight
			ZoomMode.FIT_WIDTH -> ContentScale.FillWidth
		},
		contentAlignment = if (zoomMode == ZoomMode.KEEP_START) Alignment.TopCenter else Alignment.Center,
		gestures = if (isZoomEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
		zoomCommand = zoomCommand,
		isAnimationEnabled = isAnimationEnabled,
		onImageSizeResolved = onImageSizeResolved,
		onImageError = onImageError,
		modifier = modifier,
	)
}

@Composable
private fun ComposeTelephotoSubsamplingImage(
	uri: Uri,
	pageKey: Long? = null,
	bitmapConfig: Bitmap.Config,
	colorFilter: ColorFilter?,
	contentScale: ContentScale,
	contentAlignment: Alignment,
	gestures: EnabledZoomGestures,
	zoomCommand: ComposeReaderZoomCommand? = null,
	isAnimationEnabled: Boolean = false,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	placeholder: (@Composable () -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val currentOnImageSizeResolved by rememberUpdatedState(onImageSizeResolved)
	val currentOnImageError by rememberUpdatedState(onImageError)
	val imageSource = remember(uri) { SubSamplingImageSource.contentUriOrNull(uri) }
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
	val zoomableState = key(uri) { rememberZoomableState() }
	SideEffect {
		zoomableState.contentScale = contentScale
		zoomableState.contentAlignment = contentAlignment
	}
	val imageState = rememberSubSamplingImageState(
		imageSource = imageSource,
		zoomableState = zoomableState,
		imageOptions = imageOptions,
		errorReporter = errorReporter,
	)
	val imageSize = imageState.imageSize
	LaunchedEffect(uri, imageSize) {
		if (imageSize != null) {
			currentOnImageSizeResolved(imageSize.width, imageSize.height)
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
	Box(modifier = modifier) {
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
				),
		)
	}
}

internal fun Bitmap.Config.toImageBitmapConfig(): ImageBitmapConfig = when (this) {
	Bitmap.Config.RGB_565 -> ImageBitmapConfig.Rgb565
	else -> ImageBitmapConfig.Argb8888
}
