package org.skepsun.kototoro.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import java.io.Closeable

class LocalImageRegionDecoder private constructor(
	private val uri: Uri,
	private val decoder: BitmapRegionDecoder,
	private val bitmapConfig: Bitmap.Config,
) : Closeable {

	val size = Point(decoder.width, decoder.height)

	fun decodeRegion(region: Rect, sampleSize: Int): Bitmap {
		val options = BitmapFactory.Options().apply {
			inPreferredConfig = bitmapConfig
			inSampleSize = sampleSize.coerceAtLeast(1)
		}
		return decoder.decodeRegion(region, options) ?: throw ImageDecodeException(
			uri = uri.toString(),
			format = null,
		)
	}

	override fun close() {
		decoder.recycle()
	}

	companion object {
		fun open(
			contentResolver: ContentResolver,
			uri: Uri,
			bitmapConfig: Bitmap.Config,
		): LocalImageRegionDecoder {
			val decoder = try {
				contentResolver.openInputStream(uri)?.use { stream ->
					BitmapDecoderCompat.createRegionDecoder(stream)
				}
			} catch (error: Throwable) {
				throw ImageDecodeException(uri.toString(), null, cause = error)
			} ?: throw ImageDecodeException(uri.toString(), null)
			return LocalImageRegionDecoder(uri, decoder, bitmapConfig)
		}
	}
}
