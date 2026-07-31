package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import kotlinx.coroutines.runInterruptible
import org.aomedia.avif.android.AvifDecoder
import org.skepsun.kototoro.core.util.ext.readByteBuffer

class AvifImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val bytes = source.source().readByteBuffer()
		val decoder = AvifDecoder.create(bytes) ?: throw ImageDecodeException(
			uri = source.fileOrNull()?.toString(),
			format = "avif",
			message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
		)
		try {
			val config = if (decoder.depth == 8 || decoder.alphaPresent) {
				Bitmap.Config.ARGB_8888
			} else {
				Bitmap.Config.RGB_565
			}
			// Animated AVIF (AVIS): decode every frame up front so playback is just a
			// bitmap swap. Per-frame JIT decoding of AV1 is too slow on typical devices
			// to hold the nominal frame rate — decoding all frames once trades a longer
			// initial decode + memory for smooth playback and no main-thread CPU during
			// animation. Falls back to the static path if the total would exceed the
			// memory budget (guards against pathological sequences / OOM).
			if (decoder.frameCount > 1) {
				val animated = tryDecodeAllFrames(decoder, config)
				if (animated != null) {
					return@runInterruptible DecodeResult(
						// shareable = false: the drawable owns native bitmaps that will
						// be recycled via release(); it must not be served from Coil's
						// memory cache to a second consumer after the first disposes it.
						image = animated.asImage(shareable = false),
						isSampled = false,
					)
				}
				// Budget exceeded — fall through to static first-frame rendering.
			}
			val bitmap = createBitmap(decoder.width, decoder.height, config)
			val result = decoder.nextFrame(bitmap)
			if (result != 0) {
				bitmap.recycle()
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(result),
				)
			}
			// downscaling
			val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
				srcWidth = bitmap.width,
				srcHeight = bitmap.height,
				targetSize = options.size,
				scale = options.scale,
				maxSize = options.maxBitmapSize,
			)
			if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
				val scaled = bitmap.scale(dstWidth, dstHeight)
				bitmap.recycle()
				DecodeResult(
					image = scaled.asImage(),
					isSampled = true,
				)
			} else {
				DecodeResult(
					image = bitmap.asImage(),
					isSampled = false,
				)
			}
		} finally {
			decoder.release()
		}
	}

	private fun tryDecodeAllFrames(
		decoder: AvifDecoder,
		config: Bitmap.Config,
	): AvifAnimatedDrawable? {
		val frameCount = decoder.frameCount
		val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4 else 2
		val totalBytes = frameCount.toLong() * decoder.width * decoder.height * bytesPerPixel
		if (totalBytes > ANIMATED_MEMORY_BUDGET_BYTES) return null

		val rawDurations = decoder.frameDurations
		val durationsMs = LongArray(frameCount) { idx ->
			val secs = rawDurations?.getOrNull(idx) ?: DEFAULT_FRAME_DURATION_SEC
			(secs * 1000.0).toLong()
		}
		val repetitionCount = decoder.repetitionCount
		val frames = ArrayList<Bitmap>(frameCount)
		try {
			for (i in 0 until frameCount) {
				val bitmap = createBitmap(decoder.width, decoder.height, config)
				val result = decoder.nthFrame(i, bitmap)
				if (result != 0) {
					bitmap.recycle()
					frames.forEach { it.recycle() }
					throw ImageDecodeException(
						uri = source.fileOrNull()?.toString(),
						format = "avif",
						message = AvifDecoder.resultToString(result),
					)
				}
				frames.add(bitmap)
			}
		} catch (e: Throwable) {
			frames.forEach { if (!it.isRecycled) it.recycle() }
			throw e
		}
		return AvifAnimatedDrawable(frames, durationsMs, repetitionCount)
	}

	private companion object {
		private const val DEFAULT_FRAME_DURATION_SEC = 0.042

		
		private const val ANIMATED_MEMORY_BUDGET_BYTES = 1000L * 1024 * 1024
	}

	class Factory : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader
		): Decoder? = if (isApplicable(result)) {
			AvifImageDecoder(result.source, options)
		} else {
			null
		}

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()

		private fun isApplicable(result: SourceFetchResult): Boolean {
			if (result.mimeType == "image/avif") return true
			// File sources loaded from cache often have no mime type propagated.
			// Fall back to probing the ftyp box so AVIF/AVIS files are still routed here
			// instead of the platform decoder (which mis-renders AVIS on API 12).
			return try {
				result.source.source().peek().use { peek ->
					if (!peek.request(12L)) return@use false
					val header = peek.readByteArray(minOf(32L, peek.buffer.size))
					header.size >= 12 &&
						header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
						header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() &&
						String(header, 8, 4).let { it == "avif" || it == "avis" }
				}
			} catch (_: Exception) {
				false
			}
		}
	}
}
