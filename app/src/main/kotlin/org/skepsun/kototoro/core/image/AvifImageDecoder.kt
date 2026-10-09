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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.buffer
import org.aomedia.avif.android.AvifDecoder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class AvifImageDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val animationPool: AvifAnimationPool? = null,
    private val animationPolicy: AvifAnimationPolicy = AvifAnimationPolicy(
        minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8),
    ),
) : Decoder {

    private val poolKey: AvifAnimationPool.Key? by lazy { animationPool?.let { animationPoolKey() } }

    override suspend fun decode(): DecodeResult {
        var animation: AvifAnimatedDrawable? = null
        try {
            val result = runInterruptible { decodeBlocking { animation = it } }
            currentCoroutineContext().ensureActive()
            return result
        } catch (e: Throwable) {
            // A cancelled Coil request has no consumer to release a newly created or taken drawable.
            animation?.recycleFrames()
            throw e
        }
    }

    private fun decodeBlocking(onAnimationOwned: (AvifAnimatedDrawable) -> Unit): DecodeResult {
        poolKey?.let { key -> animationPool?.take(key) }?.let { parked ->
            onAnimationOwned(parked.drawable)
            return DecodeResult(
                image = parked.drawable.asImage(shareable = false),
                isSampled = parked.isSampled,
            )
        }
        val bytes = readEncodedInput()
        // Two codec threads, with at most two playback decodes in flight across animations.
        val decoder = AvifDecoder.create(bytes, DECODER_THREADS) ?: throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
        )
        var ownsDecoder = true
        try {
            checkImageLimits(decoder)
            val config = if (decoder.depth == 8 || decoder.alphaPresent) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
            // Decode only the first frame here; the drawable owns the decoder for later frames.
            if (decoder.frameCount > 1) {
                when (val animated = createFrameStream(decoder, bytes, config)) {
                    is AnimatedDecode.Animated -> {
                        onAnimationOwned(animated.drawable)
                        ownsDecoder = false
                        return DecodeResult(
                            // Native resources have one consumer; reuse goes through animationPool.
                            image = animated.drawable.asImage(shareable = false),
                            isSampled = animated.isSampled,
                        )
                    }

                    is AnimatedDecode.FirstFrameOnly -> return DecodeResult(
                        image = animated.bitmap.asImage(shareable = false),
                        isSampled = animated.bitmap.width < decoder.width || animated.bitmap.height < decoder.height,
                    )
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
                return DecodeResult(
                    image = scaled.asImage(),
                    isSampled = true,
                )
            } else {
                return DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = false,
                )
            }
        } finally {
            if (ownsDecoder) decoder.release()
            // A streaming drawable takes over this buffer along with the native decoder.
            keepAvifInputReachable(bytes)
        }
    }

    private sealed interface AnimatedDecode {
        class Animated(val drawable: AvifAnimatedDrawable, val isSampled: Boolean) : AnimatedDecode

        /** Only one original-resolution frame fits the budget. */
        class FirstFrameOnly(val bitmap: Bitmap) : AnimatedDecode
    }

    private fun createFrameStream(
        decoder: AvifDecoder,
        bytes: ByteBuffer,
        config: Bitmap.Config,
    ): AnimatedDecode {
        val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4 else 2
        val (requestedWidth, requestedHeight) = if (!animationPolicy.allowDownsampling) {
            decoder.width to decoder.height
        } else {
            val (width, height) = DecodeUtils.computeDstSize(
                srcWidth = decoder.width,
                srcHeight = decoder.height,
                targetSize = options.size,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            )
            width to height
        }
        val (frameWidth, frameHeight) = resolveAvifAnimatedDecodeSize(
            width = requestedWidth.coerceAtMost(decoder.width),
            height = requestedHeight.coerceAtMost(decoder.height),
            frameCount = 2, // One displayed bitmap and one bitmap for the next frame.
            bytesPerPixel = bytesPerPixel,
            memoryBudgetBytes = animationPolicy.memoryBudgetBytes,
            allowDownsampling = animationPolicy.allowDownsampling,
        ) ?: return decodeFirstFrameWithinBudget(decoder, config, bytesPerPixel)

        val rawDurations = decoder.frameDurations
        val durationsMs = LongArray(decoder.frameCount) { index ->
            val seconds = rawDurations?.getOrNull(index)?.takeIf { it.isFinite() && it > 0 }
                ?: DEFAULT_FRAME_DURATION_SEC
            (seconds * 1000.0).toLong()
        }
        val frameBytes = frameWidth.toLong() * frameHeight * bytesPerPixel
        // Extra budget absorbs decode spikes without retaining the animation's complete frame set.
        val bufferCount = minOf(
            decoder.frameCount,
            (animationPolicy.memoryBudgetBytes / frameBytes).coerceIn(2L, MAX_PLAYBACK_BUFFERS.toLong()).toInt(),
        )
        val buffers = ArrayList<Bitmap>(bufferCount)
        try {
            repeat(bufferCount) { buffers.add(createBitmap(frameWidth, frameHeight, config)) }
            val result = decoder.nthFrame(0, buffers.first())
            if (result != 0) throw ImageDecodeException(
                uri = source.fileOrNull()?.toString(),
                format = "avif",
                message = AvifDecoder.resultToString(result),
            )
            if (Thread.currentThread().isInterrupted) throw InterruptedException("AVIF decoding cancelled")
            val isSampled = frameWidth < decoder.width || frameHeight < decoder.height
            val pool = animationPool
            val key = poolKey
            val onRelease: ((AvifAnimatedDrawable) -> Boolean)? = if (pool != null && key != null) {
                { drawable -> pool.park(key, drawable, isSampled) }
            } else {
                null
            }
            return AnimatedDecode.Animated(
                AvifAnimatedDrawable(
                    AvifFrameStream(decoder, bytes, buffers),
                    durationsMs,
                    decoder.repetitionCount,
                    onRelease,
                ),
                isSampled,
            )
        } catch (e: Throwable) {
            buffers.forEach { if (!it.isRecycled) it.recycle() }
            throw e
        }
    }

    /** File-backed input avoids a whole-file Java byte array alongside the native input. */
    private fun readEncodedInput(): ByteBuffer {
        val path = source.file()
        if (source.fileSystem === FileSystem.SYSTEM) return mapEncodedInput(File(path.toString()))
        // CBZ pages use Okio's ZIP filesystem: its entry path cannot be opened by java.io.File.
        val temporary = File.createTempFile("avif-input-", ".tmp", options.context.cacheDir)
        try {
            source.fileSystem.source(path).buffer().use { input ->
                FileOutputStream(temporary).use { output ->
                    val chunk = ByteArray(64 * 1024)
                    var length = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("AVIF decoding cancelled")
                        val count = input.read(chunk)
                        if (count == -1) break
                        length += count
                        checkInputLength(length)
                        output.write(chunk, 0, count)
                    }
                }
            }
            return mapEncodedInput(temporary)
        } finally {
            // Android keeps the mapped bytes valid after unlinking the temporary file.
            temporary.delete()
        }
    }

    private fun mapEncodedInput(file: File): ByteBuffer = FileInputStream(file).channel.use { channel ->
        val length = channel.size()
        checkInputLength(length)
        channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
    }

    private fun checkInputLength(length: Long) {
        if (length <= 0 || length > MAX_INPUT_BYTES) throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "AVIF input exceeds the supported size",
        )
    }

    private fun checkImageLimits(decoder: AvifDecoder) {
        val pixels = decoder.width.toLong() * decoder.height
        if (decoder.width !in 1..MAX_IMAGE_DIMENSION || decoder.height !in 1..MAX_IMAGE_DIMENSION ||
            pixels > MAX_IMAGE_PIXELS || pixels * decoder.frameCount > MAX_ANIMATION_PIXELS
        ) throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "AVIF image exceeds the supported dimensions or animation size",
        )
    }

    private fun decodeFirstFrameWithinBudget(
        decoder: AvifDecoder,
        config: Bitmap.Config,
        bytesPerPixel: Int,
    ): AnimatedDecode.FirstFrameOnly {
        val (width, height) = resolveAvifAnimatedDecodeSize(
            decoder.width,
            decoder.height,
            1,
            bytesPerPixel,
            animationPolicy.memoryBudgetBytes,
            animationPolicy.allowDownsampling,
        ) ?: throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "An AVIF frame exceeds the animation memory limit",
        )
        val bitmap = createBitmap(width, height, config)
        try {
            val result = decoder.nthFrame(0, bitmap)
            if (result != 0) throw ImageDecodeException(
                uri = source.fileOrNull()?.toString(),
                format = "avif",
                message = AvifDecoder.resultToString(result),
            )
            return AnimatedDecode.FirstFrameOnly(bitmap)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    /**
     * Identifies a decode that would produce the same frames: the same file contents and the same
     * inputs to the frame size. Only file-backed sources qualify; a stream has no stable identity.
     */
    private fun animationPoolKey(): AvifAnimationPool.Key? {
        // Paths inside different archives are not globally unique.
        if (source.fileSystem !== FileSystem.SYSTEM) return null
        val path = source.fileOrNull() ?: return null
        val metadata = runCatching { source.fileSystem.metadataOrNull(path) }.getOrNull()
        return AvifAnimationPool.Key(
            path = path.toString(),
            length = metadata?.size,
            lastModifiedMillis = metadata?.lastModifiedAtMillis,
            request = "${options.size}|${options.scale}|${options.maxBitmapSize}|$animationPolicy",
        )
    }

    private companion object {
        private const val DEFAULT_FRAME_DURATION_SEC = 0.042
        private const val MAX_INPUT_BYTES = 512L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 32_768
        private const val MAX_IMAGE_PIXELS = 1L shl 28
        private const val MAX_ANIMATION_PIXELS = 1L shl 34

        // Capped: the reader decodes neighbouring pages in parallel, and dav1d gains little beyond this.
        private val DECODER_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
    }

    class Factory(
        private val animationPool: AvifAnimationPool? = null,
        private val policyProvider: (() -> AvifAnimationPolicy)? = null,
    ) : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? = if (isApplicable(result)) {
            policyProvider?.invoke()?.let { policy ->
                AvifImageDecoder(result.source, options, animationPool, policy)
            } ?: AvifImageDecoder(result.source, options, animationPool)
        } else {
            null
        }

        override fun equals(other: Any?) = other is Factory && other.animationPool === animationPool &&
            other.policyProvider === policyProvider

        override fun hashCode() = 31 * System.identityHashCode(animationPool) + System.identityHashCode(policyProvider)

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
private const val MAX_PLAYBACK_BUFFERS = 8

internal fun keepAvifInputReachable(ref: Any) {
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
