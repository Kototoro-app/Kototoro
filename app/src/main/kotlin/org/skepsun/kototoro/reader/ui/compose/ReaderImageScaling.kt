package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.FilterQuality
import coil3.size.Size
import coil3.transform.Transformation
import org.skepsun.kototoro.core.prefs.ReaderImageScalingQuality
import kotlin.math.min
import kotlin.math.roundToInt

internal val LocalReaderImageScalingQuality = staticCompositionLocalOf {
	ReaderImageScalingQuality.DEFAULT
}

internal fun ReaderImageScalingQuality.toComposeFilterQuality(): FilterQuality = when (this) {
	ReaderImageScalingQuality.NEAREST -> FilterQuality.None
	ReaderImageScalingQuality.BILINEAR -> FilterQuality.Low
	ReaderImageScalingQuality.DEFAULT -> FilterQuality.Medium
	ReaderImageScalingQuality.BICUBIC,
	ReaderImageScalingQuality.LANCZOS -> FilterQuality.High
}

internal fun ReaderImageScalingQuality.usesTelephoto(): Boolean = this == ReaderImageScalingQuality.DEFAULT

internal class ReaderLanczosTransformation(
	private val maxWidth: Int,
	private val maxHeight: Int,
) : Transformation() {

	override val cacheKey: String = "ReaderLanczosTransformation-v1-$maxWidth-$maxHeight"

	override suspend fun transform(input: Bitmap, size: Size): Bitmap {
		val target = fitWithin(input.width, input.height, maxWidth, maxHeight) ?: return input
		return ReaderLanczosScaler.resize(input, target.width, target.height) ?: input
	}
}

internal data class ReaderScaledSize(val width: Int, val height: Int)

internal fun fitWithin(
	width: Int,
	height: Int,
	maxWidth: Int,
	maxHeight: Int,
): ReaderScaledSize? {
	if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return null
	val scale = min(maxWidth.toDouble() / width, maxHeight.toDouble() / height).coerceAtMost(1.0)
	if (scale >= 1.0) return null
	return ReaderScaledSize(
		width = (width * scale).roundToInt().coerceAtLeast(1),
		height = (height * scale).roundToInt().coerceAtLeast(1),
	)
}

internal object ReaderLanczosScaler {

	private val isAvailable by lazy {
		runCatching { System.loadLibrary("realesrgan_ncnn") }.isSuccess
	}

	fun resize(input: Bitmap, width: Int, height: Int): Bitmap? {
		if (!isAvailable || width <= 0 || height <= 0) return null
		val readable = if (input.config == Bitmap.Config.ARGB_8888) {
			input
		} else {
			input.copy(Bitmap.Config.ARGB_8888, false) ?: return null
		}
		val output = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
		if (output == null) {
			if (readable !== input) readable.recycle()
			return null
		}
		val success = runCatching { resizeNative(readable, output) }.getOrDefault(false)
		if (readable !== input) readable.recycle()
		if (success) {
			return output
		}
		output.recycle()
		return null
	}

	private external fun resizeNative(input: Bitmap, output: Bitmap): Boolean
}
