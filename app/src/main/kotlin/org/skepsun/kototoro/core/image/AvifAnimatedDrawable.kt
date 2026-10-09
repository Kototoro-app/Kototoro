package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/** Plays AVIS with a bounded decoded-frame queue. Late frames are shown, not skipped. */
class AvifAnimatedDrawable internal constructor(
    private val frames: AvifAnimationFrames,
    private val frameDurationsMs: LongArray,
    repetitionCount: Int,
    private val onRelease: ((AvifAnimatedDrawable) -> Boolean)? = null,
) : Drawable(), Animatable, Runnable {

    /** Also accepts already-decoded frames, e.g. a synthetic animation without a native decoder. */
    constructor(
        frames: List<Bitmap>,
        frameDurationsMs: LongArray,
        repetitionCount: Int,
        onRelease: ((AvifAnimatedDrawable) -> Boolean)? = null,
    ) : this(PredecodedAvifFrames(frames), frameDurationsMs, repetitionCount, onRelease) {
        require(frameDurationsMs.size == frames.size)
    }

    init {
        require(frameDurationsMs.isNotEmpty())
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val intrinsicW = frames.firstFrame.width
    private val intrinsicH = frames.firstFrame.height
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    // libavif counts repetitions after the first play; negative means infinite or unknown.
    private val finiteLoops = repetitionCount.takeIf { it >= 0 }?.toLong()?.plus(1)
    private val decodeScope = CoroutineScope(SupervisorJob() + FRAME_DISPATCHER)

    private var displayed = frames.firstFrame
    private var currentFrame = 0
    private var loopsDone = 0L
    private var disposed = false
    private var closed = false
    private var failed = false
    private var decodeJob: Job? = null
    private val readyFrames = ArrayDeque<Bitmap>()
    private var nextFrameAt = 0L

    /** Decoded RGB buffers; independent of the file's total frame count for streamed playback. */
    val byteCount: Long = frames.byteCount

    /** Includes the encoded input while parked; codec working memory is additional. */
    internal val retainedByteCount: Long = frames.retainedByteCount

    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH

    @Synchronized
    override fun draw(canvas: Canvas) {
        if (!disposed && !displayed.isRecycled) canvas.drawBitmap(displayed, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    @Synchronized
    override fun start() {
        if (disposed || failed || frameDurationsMs.size <= 1) return
        if (!running.compareAndSet(false, true)) return
        nextFrameAt = SystemClock.uptimeMillis() + frameDelay()
        prepareNextFrame()
        scheduleNextFrame()
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        handler.removeCallbacks(this)
        // Let an in-flight native decode finish. Its result is kept for resume; no further work is queued.
    }

    override fun isRunning(): Boolean = running.get()

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    @Synchronized
    override fun run() {
        if (!running.get() || disposed) return
        handler.removeCallbacks(this)
        val nextIndex = (currentFrame + 1) % frameDurationsMs.size
        if (!hasNextFrame()) {
            stop()
            return
        }
        val next = if (frames.decodeInBackground) {
            readyFrames.removeFirstOrNull() ?: return // Completion schedules us when a slow decode finishes.
        } else {
            frames.decode(nextIndex, displayed)
        }
        displayed = next
        currentFrame = nextIndex
        if (nextIndex == 0) loopsDone++
        val shownAt = SystemClock.uptimeMillis()
        val duration = frameDelay()
        // Keep the playback timeline through redraw work and small scheduling delays.
        // A delay longer than a frame starts a new timeline instead of rushing through late frames.
        val baseline = if (shownAt - nextFrameAt > duration) shownAt else nextFrameAt
        nextFrameAt = baseline + duration
        prepareNextFrame()
        invalidateSelf()
        scheduleNextFrame()
    }

    /** At most one decode is in flight; displayed and queued bitmaps remain owned by the consumer. */
    private fun prepareNextFrame() {
        if (!frames.decodeInBackground || readyFrames.size >= frames.bufferCount - 1 || decodeJob != null) return
        val offset = readyFrames.size + 1
        val nextIndex = (currentFrame + offset) % frameDurationsMs.size
        if (!hasNextFrame(offset)) return
        val previous = displayed
        val job = decodeScope.launch(start = CoroutineStart.LAZY) {
            try {
                val decoded = runInterruptible { frames.decode(nextIndex, previous) }
                synchronized(this@AvifAnimatedDrawable) {
                    decodeJob = null
                    if (!closed) {
                        readyFrames.addLast(decoded)
                        if (!disposed && running.get()) {
                            prepareNextFrame()
                            scheduleNextFrame()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e !is Exception && e !is OutOfMemoryError) throw e
                Log.w("AvifAnimatedDrawable", "Animation stopped on its last decoded frame", e)
                synchronized(this@AvifAnimatedDrawable) {
                    decodeJob = null
                    failed = true
                    stop()
                }
            }
        }
        decodeJob = job
        job.start()
    }

    private fun frameDelay() = frameDurationsMs[currentFrame].coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS)

    private fun hasNextFrame(offset: Int = 1) = finiteLoops?.let {
        loopsDone + (currentFrame.toLong() + offset) / frameDurationsMs.size < it
    } != false

    private fun scheduleNextFrame() {
        handler.removeCallbacks(this)
        handler.postAtTime(this, nextFrameAt.coerceAtLeast(SystemClock.uptimeMillis()))
    }

    /** Ends this consumer's ownership; a parked animation stays paused at its current frame. */
    fun release() {
        val reusable = synchronized(this) {
            if (disposed) return
            disposed = true
            stop()
            // A finite animation that finished needs a fresh decoder to replay from its first frame.
            !failed && hasNextFrame()
        }
        callback = null
        // Outside the lock: pool eviction takes the drawable's lock.
        if (!reusable || onRelease?.invoke(this) != true) recycleFrames()
    }

    /** Cancels delivery immediately; native resources are freed after any in-flight decode finishes. */
    internal fun recycleFrames() {
        val job = synchronized(this) {
            if (closed) return
            closed = true
            disposed = true
            readyFrames.clear()
            stop()
            decodeScope.cancel()
            decodeJob
        }
        if (frames.decodeInBackground) {
            CLEANUP_SCOPE.launch {
                job?.join()
                frames.close()
            }
        } else {
            frames.close()
        }
    }

    @Synchronized
    internal fun revive() {
        if (closed || failed || displayed.isRecycled) return
        disposed = false
        loopsDone = 0
    }

    @Synchronized
    internal fun isUsable(): Boolean = !disposed && !closed && !failed && !displayed.isRecycled

    private companion object {
        // Match Mihon's playback floor and decoder duration ceiling.
        const val MIN_FRAME_DELAY_MS = 10L
        const val MAX_FRAME_DELAY_MS = 60_000L
        val FRAME_DISPATCHER = Dispatchers.IO.limitedParallelism(2)
        val CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
