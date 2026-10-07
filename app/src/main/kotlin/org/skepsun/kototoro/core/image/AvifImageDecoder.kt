package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.os.Build
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
import java.lang.ref.Reference

class AvifImageDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val animationPool: AvifAnimationPool? = null,
) : Decoder {

    private val poolKey: AvifAnimationPool.Key? by lazy { animationPool?.let { animationPoolKey() } }

    override suspend fun decode(): DecodeResult = runInterruptible {
        poolKey?.let { key -> animationPool?.take(key) }?.let { parked ->
            return@runInterruptible DecodeResult(
                image = parked.drawable.asImage(shareable = false),
                isSampled = parked.isSampled,
            )
        }
        val bytes = source.source().readByteBuffer()
        // create(ByteBuffer) decodes on a single thread; an animated page decodes every frame before
        // it is shown, so the AV1 decoder's own threads directly shorten the wait.
        val decoder = AvifDecoder.create(bytes, DECODER_THREADS) ?: throw ImageDecodeException(
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
                        // Reuse goes through animationPool, which hands it to one consumer at a time.
                        image = animated.drawable.asImage(shareable = false),
                        isSampled = animated.isSampled,
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
            // libavif reads the encoded data straight from this direct buffer without keeping a
            // Java reference to it. Without the fence the buffer becomes unreachable right after
            // create(), and a GC during the long all-frames decode frees memory the native decoder
            // is still reading, failing intermittently with "Decoding of color planes failed".
            keepReachable(bytes)
        }
    }

    private sealed interface AnimatedDecode {
        class Animated(val drawable: AvifAnimatedDrawable, val isSampled: Boolean) : AnimatedDecode

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
        val isSampled = frameWidth < decoder.width || frameHeight < decoder.height
        val pool = animationPool
        val key = poolKey
        val onRelease: ((AvifAnimatedDrawable) -> Boolean)? = if (pool != null && key != null) {
            { drawable -> pool.park(key, drawable, isSampled) }
        } else {
            null
        }
        return AnimatedDecode.Animated(
            AvifAnimatedDrawable(frames, durationsMs, repetitionCount, onRelease),
            isSampled,
        )
    }

    /**
     * Identifies a decode that would produce the same frames: the same file contents and the same
     * inputs to the frame size. Only file-backed sources qualify; a stream has no stable identity.
     */
    private fun animationPoolKey(): AvifAnimationPool.Key? {
        val path = source.fileOrNull() ?: return null
        val metadata = runCatching { source.fileSystem.metadataOrNull(path) }.getOrNull()
        return AvifAnimationPool.Key(
            path = path.toString(),
            length = metadata?.size,
            lastModifiedMillis = metadata?.lastModifiedAtMillis,
            request = "${options.size}|${options.scale}|${options.maxBitmapSize}",
        )
    }

    private companion object {
        private const val DEFAULT_FRAME_DURATION_SEC = 0.042
        private const val ANIMATED_MEMORY_BUDGET_BYTES = 64L * 1024 * 1024

        // Capped: the reader decodes neighbouring pages in parallel, and dav1d gains little beyond this.
        private val DECODER_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }

    class Factory(
        private val animationPool: AvifAnimationPool? = null,
    ) : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? = if (isApplicable(result)) {
            AvifImageDecoder(result.source, options, animationPool)
        } else {
            null
        }

        override fun equals(other: Any?) = other is Factory && other.animationPool === animationPool

        override fun hashCode() = System.identityHashCode(animationPool)

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

private fun keepReachable(ref: Any) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Reference.reachabilityFence(ref)
    } else {
        // A monitor operation is a use the runtime cannot elide, so the object stays reachable until here.
        synchronized(ref) {}
    }
}

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
