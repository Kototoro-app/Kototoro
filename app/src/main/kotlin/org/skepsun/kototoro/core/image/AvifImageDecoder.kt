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
            // animation. Sample the frames to the requested size and a bounded working set.
            if (decoder.frameCount > 1) {
                when (val animated = tryDecodeAllFrames(decoder, config)) {
                    is AnimatedDecode.Animated -> return@runInterruptible DecodeResult(
                        // shareable = false: the drawable owns native bitmaps that will
                        // be recycled via release(); it must not be served from Coil's
                        // memory cache to a second consumer after the first disposes it.
                        image = animated.drawable.asImage(shareable = false),
                        isSampled = animated.drawable.intrinsicWidth < decoder.width ||
                            animated.drawable.intrinsicHeight < decoder.height,
                    )

                    is AnimatedDecode.FirstFrameOnly -> return@runInterruptible DecodeResult(
                        image = animated.bitmap.asImage(),
                        isSampled = animated.bitmap.width < decoder.width || animated.bitmap.height < decoder.height,
                    )

                    // Even one pixel per frame cannot fit — render only the first frame.
                    null -> Unit
                }
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

    private sealed interface AnimatedDecode {
        class Animated(val drawable: AvifAnimatedDrawable) : AnimatedDecode

        /** A later frame failed to decode; showing a still beats failing the whole page. */
        class FirstFrameOnly(val bitmap: Bitmap) : AnimatedDecode
    }

    private fun tryDecodeAllFrames(
        decoder: AvifDecoder,
        config: Bitmap.Config,
    ): AnimatedDecode? {
        val frameCount = decoder.frameCount
        val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4 else 2
        val (requestedWidth, requestedHeight) = DecodeUtils.computeDstSize(
            srcWidth = decoder.width,
            srcHeight = decoder.height,
            targetSize = options.size,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        val (frameWidth, frameHeight) = resolveAvifAnimatedDecodeSize(
            width = requestedWidth.coerceAtMost(decoder.width),
            height = requestedHeight.coerceAtMost(decoder.height),
            frameCount = frameCount,
            bytesPerPixel = bytesPerPixel,
            memoryBudgetBytes = minOf(ANIMATED_MEMORY_BUDGET_BYTES, Runtime.getRuntime().maxMemory() / 8),
        ) ?: return null

        val rawDurations = decoder.frameDurations
        val durationsMs = LongArray(frameCount) { idx ->
            val secs = rawDurations?.getOrNull(idx) ?: DEFAULT_FRAME_DURATION_SEC
            (secs * 1000.0).toLong()
        }
        val repetitionCount = decoder.repetitionCount
        val frames = ArrayList<Bitmap>(frameCount)
        try {
            for (i in 0 until frameCount) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("AVIF decoding cancelled")
                // libavif scales directly into the destination bitmap, avoiding full-size RGB frames.
                val bitmap = createBitmap(frameWidth, frameHeight, config)
                frames.add(bitmap)
                val result = decoder.nthFrame(i, bitmap)
                if (result != 0) {
                    if (i > 0) {
                        val first = frames.first()
                        frames.forEach { if (it !== first && !it.isRecycled) it.recycle() }
                        return AnimatedDecode.FirstFrameOnly(first)
                    }
                    throw ImageDecodeException(
                        uri = source.fileOrNull()?.toString(),
                        format = "avif",
                        message = AvifDecoder.resultToString(result),
                    )
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException("AVIF decoding cancelled")
            }
        } catch (e: Throwable) {
            frames.forEach { if (!it.isRecycled) it.recycle() }
            throw e
        }
        return AnimatedDecode.Animated(AvifAnimatedDrawable(frames, durationsMs, repetitionCount))
    }

    private companion object {
        private const val DEFAULT_FRAME_DURATION_SEC = 0.042
        private const val ANIMATED_MEMORY_BUDGET_BYTES = 64L * 1024 * 1024
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
                    peek.request(FTYP_PROBE_BYTES)
                    isAvifFileType(peek.readByteArray(minOf(FTYP_PROBE_BYTES, peek.buffer.size)))
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

private const val FTYP_PROBE_BYTES = 64L

/**
 * Whether [header] starts with an ISO-BMFF `ftyp` box declaring AVIF.
 *
 * Many encoders write the generic `mif1`/`msf1` major brand and list `avif`/`avis` only among the
 * compatible brands. Those files must still reach libavif: platform decoders before API 31 have no
 * AVIF support and fail with "unimplemented".
 */
internal fun isAvifFileType(header: ByteArray): Boolean {
    if (header.size < 12 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return false
    val boxSize = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    val end = if (boxSize in 16..header.size) boxSize else header.size
    // Major brand at 8, minor version at 12, compatible brands from 16 to the end of the box.
    val brandOffsets = sequenceOf(8) + generateSequence(16) { it + 4 }.takeWhile { it + 4 <= end }
    return brandOffsets.any { offset ->
        String(header, offset, 4, Charsets.US_ASCII).let { it == "avif" || it == "avis" }
    }
}
