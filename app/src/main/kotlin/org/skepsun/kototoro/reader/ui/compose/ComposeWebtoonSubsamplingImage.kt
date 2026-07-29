package org.skepsun.kototoro.reader.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.util.ext.isLowRamDevice

@Composable
internal fun ComposeWebtoonSubsamplingImage(
	uri: Uri,
	bitmapConfig: Bitmap.Config,
	colorFilter: androidx.compose.ui.graphics.ColorFilter?,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	val androidColorFilter = colorFilter?.asAndroidColorFilter()
	AndroidView(
		factory = { context -> ComposeWebtoonSubsamplingImageView(context) },
		update = { view ->
			view.bind(
				uri = uri,
				bitmapConfig = bitmapConfig,
				colorFilter = androidColorFilter,
				onImageSizeResolved = onImageSizeResolved,
				onImageError = onImageError,
			)
		},
		onRelease = { it.recycle() },
		modifier = modifier,
	)
}

@Composable
internal fun ComposePagedSubsamplingImage(
	uri: Uri,
	bitmapConfig: Bitmap.Config,
	colorFilter: androidx.compose.ui.graphics.ColorFilter?,
	zoomMode: ZoomMode,
	zoomCommand: ComposeReaderZoomCommand?,
	isZoomEnabled: Boolean,
	isAnimationEnabled: Boolean,
	onImageSizeResolved: (width: Int, height: Int) -> Unit,
	onImageError: (Throwable) -> Unit,
	modifier: Modifier = Modifier,
) {
	val androidColorFilter = colorFilter?.asAndroidColorFilter()
	AndroidView(
		factory = { context -> ComposePagedSubsamplingImageView(context) },
		update = { view ->
			view.bind(
				uri = uri,
				bitmapConfig = bitmapConfig,
				colorFilter = androidColorFilter,
				zoomMode = zoomMode,
				zoomCommand = zoomCommand,
				isZoomEnabled = isZoomEnabled,
				isAnimationEnabled = isAnimationEnabled,
				onImageSizeResolved = onImageSizeResolved,
				onImageError = onImageError,
			)
		},
		onRelease = { it.recycle() },
		modifier = modifier,
	)
}

private class ComposePagedSubsamplingImageView(context: Context) : SubsamplingScaleImageView(context) {

	private var boundUri: Uri? = null
	private var boundBitmapConfig: Bitmap.Config? = null
	private var boundZoomMode: ZoomMode? = null
	private var appliedZoomCommand: ComposeReaderZoomCommand? = null
	private var onImageSizeResolved: ((Int, Int) -> Unit)? = null
	private var onImageError: ((Throwable) -> Unit)? = null

	init {
		panLimit = PAN_LIMIT_INSIDE
		addOnImageEventListener(object : DefaultOnImageEventListener {
			override fun onReady() {
				configureScale()
				onImageSizeResolved?.invoke(sWidth, sHeight)
			}

			override fun onImageLoadError(e: Throwable) = onImageError?.invoke(e) ?: Unit
			override fun onTileLoadError(e: Throwable) = onImageError?.invoke(e) ?: Unit
			override fun onImageLoaded() = Unit
			override fun onPreviewLoadError(e: Throwable) = Unit
			override fun onPreviewReleased() = Unit
		})
	}

	fun bind(
		uri: Uri,
		bitmapConfig: Bitmap.Config,
		colorFilter: ColorFilter?,
		zoomMode: ZoomMode,
		zoomCommand: ComposeReaderZoomCommand?,
		isZoomEnabled: Boolean,
		isAnimationEnabled: Boolean,
		onImageSizeResolved: (Int, Int) -> Unit,
		onImageError: (Throwable) -> Unit,
	) {
		this.colorFilter = colorFilter
		this.onImageSizeResolved = onImageSizeResolved
		this.onImageError = onImageError
		this.isZoomEnabled = isZoomEnabled
		isPanEnabled = isZoomEnabled
		isQuickScaleEnabled = isZoomEnabled
		val sourceChanged = boundUri != uri || boundBitmapConfig != bitmapConfig
		val zoomModeChanged = boundZoomMode != zoomMode
		boundZoomMode = zoomMode
		if (sourceChanged) {
			boundUri = uri
			boundBitmapConfig = bitmapConfig
			appliedZoomCommand = null
			recycle()
			regionDecoderFactory = if (context.isLowRamDevice()) {
				SkiaImageRegionDecoder.Factory(bitmapConfig)
			} else {
				SkiaPooledImageRegionDecoder.Factory(bitmapConfig)
			}
			bitmapDecoderFactory = SkiaImageDecoder.Factory(bitmapConfig)
			setImage(ImageSource.uri(uri))
		} else if (isReady && zoomModeChanged) {
			configureScale()
		}
		if (isReady && zoomCommand != null && zoomCommand !== appliedZoomCommand) {
			appliedZoomCommand = zoomCommand
			val target = (scale * zoomCommand.factor).coerceIn(minScale, maxScale)
			val sourceCenter = getCenter() ?: PointF(sWidth / 2f, sHeight / 2f)
			if (isAnimationEnabled) {
				animateScaleAndCenter(target, sourceCenter)?.withDuration(220)?.withInterruptible(true)?.start()
			} else {
				setScaleAndCenter(target, sourceCenter)
			}
		}
	}

	private fun configureScale() {
		val customScale = when (boundZoomMode) {
			ZoomMode.FIT_HEIGHT -> height.toFloat() / sHeight
			ZoomMode.FIT_WIDTH -> width.toFloat() / sWidth
			else -> null
		}
		minimumScaleType = if (customScale != null) SCALE_TYPE_CUSTOM else when (boundZoomMode) {
			ZoomMode.KEEP_START -> SCALE_TYPE_START
			else -> SCALE_TYPE_CENTER_INSIDE
		}
		if (customScale != null && customScale.isFinite() && customScale > 0f) {
			minScale = customScale
			setScaleAndCenter(customScale, PointF(sWidth / 2f, sHeight / 2f))
		}
		maxScale = minScale * 5f
		doubleTapZoomScale = minScale * 2f
	}
}

private class ComposeWebtoonSubsamplingImageView(context: Context) : SubsamplingScaleImageView(context) {

	private var boundUri: Uri? = null
	private var boundBitmapConfig: Bitmap.Config? = null
	private var onImageSizeResolved: ((Int, Int) -> Unit)? = null
	private var onImageError: ((Throwable) -> Unit)? = null

	init {
		minimumScaleType = SCALE_TYPE_CUSTOM
		panLimit = PAN_LIMIT_INSIDE
		isZoomEnabled = false
		isPanEnabled = false
		isQuickScaleEnabled = false
		addOnImageEventListener(object : DefaultOnImageEventListener {
			override fun onReady() {
				applyScale()
				onImageSizeResolved?.invoke(sWidth, sHeight)
			}

			override fun onImageLoadError(e: Throwable) {
				onImageError?.invoke(e)
			}

			override fun onImageLoaded() = Unit

			override fun onPreviewLoadError(e: Throwable) = Unit

			override fun onTileLoadError(e: Throwable) {
				onImageError?.invoke(e)
			}

			override fun onPreviewReleased() = Unit
		})
	}

	fun bind(
		uri: Uri,
		bitmapConfig: Bitmap.Config,
		colorFilter: ColorFilter?,
		onImageSizeResolved: (Int, Int) -> Unit,
		onImageError: (Throwable) -> Unit,
	) {
		this.colorFilter = colorFilter
		this.onImageSizeResolved = onImageSizeResolved
		this.onImageError = onImageError
		if (boundUri != uri || boundBitmapConfig != bitmapConfig) {
			boundUri = uri
			boundBitmapConfig = bitmapConfig
			recycle()
			regionDecoderFactory = if (context.isLowRamDevice()) {
				SkiaImageRegionDecoder.Factory(bitmapConfig)
			} else {
				SkiaPooledImageRegionDecoder.Factory(bitmapConfig)
			}
			bitmapDecoderFactory = SkiaImageDecoder.Factory(bitmapConfig)
			minimumScaleType = SCALE_TYPE_CUSTOM
			panLimit = PAN_LIMIT_INSIDE
			setImage(ImageSource.uri(uri))
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (isReady) applyScale()
	}

	private fun applyScale() {
		if (!isReady || width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) return
		val scale = width.toFloat() / sWidth.toFloat()
		minScale = scale
		maxScale = scale
		setScaleAndCenter(
			scale,
			PointF(sWidth / 2f, sHeight / 2f),
		)
	}
}
