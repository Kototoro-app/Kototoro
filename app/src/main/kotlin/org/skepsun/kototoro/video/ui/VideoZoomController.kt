package org.skepsun.kototoro.video.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

/**
 * Manages pinch-to-zoom scaling, focal-point tracking, viewport boundary clamping,
 * and smooth animated reset for the video player.
 */
class VideoZoomController(
    private val onTransformChanged: (scale: Float, translationX: Float, translationY: Float) -> Unit,
    private val animatorFactory: (
        (from: Float, to: Float, onUpdate: (Float) -> Unit, onEnd: () -> Unit) -> Unit
    )? = null,
) {
    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 5.0f
        const val MAGNET_SCALE = 1.05f
        private const val BELOW_MIN_DAMPING = 0.5f
        private const val RESET_DURATION_MS = 220L
    }

    var scale = 1.0f
        private set
    var translationX = 0.0f
        private set
    var translationY = 0.0f
        private set
    var isPinching = false
        private set

    val isZoomed: Boolean
        get() = resetAnimator == null && scale > MAGNET_SCALE

    private var lastFocusX = Float.NaN
    private var lastFocusY = Float.NaN
    private var resetAnimator: ValueAnimator? = null

    /**
     * Dampens the scale when pulling below 1.0x (rubber-band effect).
     */
    fun displayedScale(raw: Float = scale): Float {
        val s = if (raw.isFinite()) raw else 1.0f
        return if (s >= 1.0f) s else 1.0f - (1.0f - s) * BELOW_MIN_DAMPING
    }

    fun onScaleBegin(focusX: Float, focusY: Float) {
        cancelResetAnimation()
        isPinching = true
        lastFocusX = focusX
        lastFocusY = focusY
    }

    fun onScale(
        scaleFactor: Float,
        focusX: Float,
        focusY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        contentWidth: Int = viewportWidth,
        contentHeight: Int = viewportHeight,
    ) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val oldDisplayScale = displayedScale(scale)
        val rawScale = (scale * scaleFactor).coerceIn(MIN_SCALE * 0.7f, MAX_SCALE)
        scale = rawScale
        val newDisplayScale = displayedScale(scale)
        val scaleChange = if (oldDisplayScale > 0f) newDisplayScale / oldDisplayScale else 1.0f

        if (lastFocusX.isFinite() && lastFocusY.isFinite() && viewportWidth > 0 && viewportHeight > 0) {
            val centerX = viewportWidth / 2f
            val centerY = viewportHeight / 2f
            translationX = focusX - centerX - (lastFocusX - centerX - translationX) * scaleChange
            translationY = focusY - centerY - (lastFocusY - centerY - translationY) * scaleChange
        }
        lastFocusX = focusX
        lastFocusY = focusY

        clampTranslation(viewportWidth, viewportHeight, contentWidth, contentHeight)
        notifyTransform()
    }

    fun onScaleEnd(
        viewportWidth: Int,
        viewportHeight: Int,
        contentWidth: Int = viewportWidth,
        contentHeight: Int = viewportHeight,
        onResetEnd: (() -> Unit)? = null,
    ) {
        isPinching = false
        lastFocusX = Float.NaN
        lastFocusY = Float.NaN
        if (scale < MAGNET_SCALE) {
            animateReset(onEnd = onResetEnd)
        } else {
            clampTranslation(viewportWidth, viewportHeight, contentWidth, contentHeight)
            notifyTransform()
        }
    }

    fun panBy(
        dx: Float,
        dy: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        contentWidth: Int = viewportWidth,
        contentHeight: Int = viewportHeight,
    ) {
        if (!isZoomed) return
        translationX += dx
        translationY += dy
        clampTranslation(viewportWidth, viewportHeight, contentWidth, contentHeight)
        notifyTransform()
    }

    fun clampTranslation(
        viewportWidth: Int,
        viewportHeight: Int,
        contentWidth: Int = viewportWidth,
        contentHeight: Int = viewportHeight,
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val currentDisplayScale = displayedScale(scale)
        val effectiveW = if (contentWidth > 0) contentWidth.toFloat() else viewportWidth.toFloat()
        val effectiveH = if (contentHeight > 0) contentHeight.toFloat() else viewportHeight.toFloat()

        val overflowX = (effectiveW * currentDisplayScale - viewportWidth).coerceAtLeast(0f)
        val overflowY = (effectiveH * currentDisplayScale - viewportHeight).coerceAtLeast(0f)

        val maxX = if (currentDisplayScale > 1.0f) overflowX / 2f else 0f
        val maxY = if (currentDisplayScale > 1.0f) overflowY / 2f else 0f

        translationX = translationX.coerceIn(-maxX, maxX)
        translationY = translationY.coerceIn(-maxY, maxY)
    }

    fun animateReset(onEnd: (() -> Unit)? = null) {
        cancelResetAnimation()
        lastFocusX = Float.NaN
        lastFocusY = Float.NaN
        val startScale = scale
        val startX = translationX
        val startY = translationY
        if (startScale == 1.0f && startX == 0f && startY == 0f) {
            onEnd?.invoke()
            return
        }

        if (animatorFactory != null) {
            animatorFactory.invoke(0f, 1f, { fraction ->
                scale = startScale + (1.0f - startScale) * fraction
                translationX = startX * (1.0f - fraction)
                translationY = startY * (1.0f - fraction)
                notifyTransform()
            }, {
                reset()
                onEnd?.invoke()
            })
            return
        }

        val animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = RESET_DURATION_MS
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                scale = startScale + (1.0f - startScale) * f
                translationX = startX * (1.0f - fractionSafely(f))
                translationY = startY * (1.0f - fractionSafely(f))
                notifyTransform()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (resetAnimator == animation) {
                        resetAnimator = null
                        reset()
                        onEnd?.invoke()
                    }
                }
            })
        }
        resetAnimator = animator
        animator.start()
    }

    fun reset() {
        cancelResetAnimation()
        lastFocusX = Float.NaN
        lastFocusY = Float.NaN
        scale = 1.0f
        translationX = 0.0f
        translationY = 0.0f
        isPinching = false
        notifyTransform()
    }

    private fun cancelResetAnimation() {
        resetAnimator?.let {
            it.cancel()
            resetAnimator = null
        }
    }

    private fun fractionSafely(f: Float): Float {
        return f.coerceIn(0f, 1f)
    }

    private fun notifyTransform() {
        onTransformChanged(displayedScale(scale), translationX, translationY)
    }
}
