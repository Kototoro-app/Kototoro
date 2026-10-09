package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer

/** Frame ownership shared by streamed AVIF playback and already-decoded drawables. */
internal interface AvifAnimationFrames {
    val firstFrame: Bitmap
    val byteCount: Long
    val retainedByteCount: Long get() = byteCount
    val bufferCount: Int get() = 2
    val decodeInBackground: Boolean
    fun decode(index: Int, displayed: Bitmap): Bitmap
    fun close()
}

/** Owns libavif's input and cycles through RGB buffers bounded by the animation's memory budget. */
internal class AvifFrameStream(
    private val decoder: AvifDecoder,
    private var encoded: ByteBuffer?,
    private val buffers: List<Bitmap>,
) : AvifAnimationFrames {
    init {
        require(buffers.size >= 2)
    }

    override val firstFrame: Bitmap = buffers.first()
    override val byteCount: Long = buffers.sumOf { it.allocationByteCount.toLong() }
    override val retainedByteCount: Long = byteCount + requireNotNull(encoded).capacity()
    override val bufferCount: Int = buffers.size
    override val decodeInBackground: Boolean = true
    private var closed = false
    private var bufferIndex = 0

    @Synchronized
    override fun decode(index: Int, displayed: Bitmap): Bitmap {
        check(!closed)
        val bytes = requireNotNull(encoded)
        val nextBuffer = (bufferIndex + 1) % buffers.size
        val target = buffers[nextBuffer]
        check(target !== displayed)
        try {
            val result = decoder.nthFrame(index, target)
            if (result != 0) throw ImageDecodeException(
                uri = null,
                format = "avif",
                message = AvifDecoder.resultToString(result),
            )
            bufferIndex = nextBuffer
            return target
        } finally {
            keepAvifInputReachable(bytes)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val bytes = encoded
        try {
            decoder.release()
        } finally {
            if (bytes != null) keepAvifInputReachable(bytes)
            encoded = null
            buffers.forEach { if (!it.isRecycled) it.recycle() }
        }
    }
}

internal class PredecodedAvifFrames(private val frames: List<Bitmap>) : AvifAnimationFrames {
    override val firstFrame: Bitmap = frames.first()
    override val byteCount: Long = frames.sumOf { it.allocationByteCount.toLong() }
    override val decodeInBackground: Boolean = false
    override fun decode(index: Int, displayed: Bitmap): Bitmap = frames[index]
    override fun close() = frames.forEach { if (!it.isRecycled) it.recycle() }
}
