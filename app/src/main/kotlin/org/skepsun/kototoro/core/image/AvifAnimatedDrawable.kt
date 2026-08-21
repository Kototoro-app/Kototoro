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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drawable that plays back an AVIF image sequence (AVIS) from pre-decoded frames.
 *
 * Exists because the platform AnimatedImageDecoder does not reliably drive AVIS, and
 * just-in-time per-frame libavif decoding on a ~1K page stutters (AV1 decode cost exceeds
 * the frame budget). The caller is expected to decode every frame up front so playback is
 * just a bitmap swap — no work on the main thread during a frame.
 */
class AvifAnimatedDrawable(
    private val frames: List<Bitmap>,
    private val frameDurationsMs: LongArray,
    // libavif semantics: >0 = finite loop count, anything else = loop forever.
    repetitionCount: Int,
) : Drawable(), Animatable, Runnable {

    init {
        require(frames.isNotEmpty()) { "AvifAnimatedDrawable requires at least one frame" }
        require(frameDurationsMs.size == frames.size) {
            "frameDurationsMs.size=${frameDurationsMs.size} must match frames.size=${frames.size}"
        }
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val intrinsicW = frames[0].width
    private val intrinsicH = frames[0].height
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val finiteLoops = repetitionCount.takeIf { it > 0 }

    private var currentFrame = 0
    private var loopsDone = 0
    private var disposed = false

    override fun draw(canvas: Canvas) {
        if (disposed) return
        val frame = frames.getOrNull(currentFrame) ?: return
        if (!frame.isRecycled) {
            canvas.drawBitmap(frame, null, bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH

    override fun start() {
        if (disposed || frames.size <= 1) return
        if (!running.compareAndSet(false, true)) return
        scheduleNextFrame()
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        handler.removeCallbacks(this)
    }

    override fun isRunning(): Boolean = running.get()

    /**
     * Ties playback to the host view's visibility so the drawable stops burning battery
     * while scrolled off-screen, and resumes automatically when it comes back.
     */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (restart || !running.get()) start()
        } else {
            stop()
        }
        return changed
    }

    override fun run() {
        if (!running.get() || disposed) return
        val nextIndex = currentFrame + 1
        currentFrame = if (nextIndex >= frames.size) {
            loopsDone++
            val cap = finiteLoops
            if (cap != null && loopsDone >= cap) {
                running.set(false)
                return
            }
            0
        } else {
            nextIndex
        }
        invalidateSelf()
        scheduleNextFrame()
    }

    private fun scheduleNextFrame() {
        val delay = frameDurationsMs[currentFrame].coerceAtLeast(MIN_FRAME_DELAY_MS)
        handler.postAtTime(this, SystemClock.uptimeMillis() + delay)
    }

    fun release() {
        if (disposed) return
        disposed = true
        stop()
        frames.forEach { if (!it.isRecycled) it.recycle() }
    }

    companion object {
        // Clamp below this to avoid busy-looping on malformed duration metadata.
        private const val MIN_FRAME_DELAY_MS = 16L
    }
}
