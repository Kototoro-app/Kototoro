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
import org.skepsun.kototoro.core.util.ext.isLowRamDevice

@Composable
internal fun ComposeWebtoonSubsamplingImage(
	uri: Uri,
	internalOffsetPx: Int,
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
				internalOffsetPx = internalOffsetPx,
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

private class ComposeWebtoonSubsamplingImageView(context: Context) : SubsamplingScaleImageView(context) {

	private var boundUri: Uri? = null
	private var boundBitmapConfig: Bitmap.Config? = null
	private var internalOffsetPx = 0
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
				applyInternalScroll()
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
		internalOffsetPx: Int,
		bitmapConfig: Bitmap.Config,
		colorFilter: ColorFilter?,
		onImageSizeResolved: (Int, Int) -> Unit,
		onImageError: (Throwable) -> Unit,
	) {
		this.internalOffsetPx = internalOffsetPx
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
		} else if (isReady) {
			applyInternalScroll()
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (isReady) applyInternalScroll()
	}

	private fun applyInternalScroll() {
		if (!isReady || width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) return
		val scale = width.toFloat() / sWidth.toFloat()
		minScale = scale
		maxScale = scale
		val maxScroll = (sHeight * scale - height).coerceAtLeast(0f).toInt()
		val scroll = internalOffsetPx.coerceIn(0, maxScroll)
		setScaleAndCenter(
			scale,
			PointF(
				sWidth / 2f,
				(height / 2f + scroll) / scale,
			),
		)
	}
}
