package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.runtime.saveable.listSaver
import org.skepsun.kototoro.core.model.ZoomMode
import kotlin.math.abs
import kotlin.math.min

class ReaderZoomState(
	private val maxScale: Float = 5f,
) {

	var scale: Float = 1f
		private set
	var offsetX: Float = 0f
		private set
	var offsetY: Float = 0f
		private set

	private var viewportWidth = 0f
	private var viewportHeight = 0f
	private var fittedImageWidth = 0f
	private var fittedImageHeight = 0f

	fun updateGeometry(
		viewportWidth: Int,
		viewportHeight: Int,
		imageWidth: Int,
		imageHeight: Int,
		mode: ZoomMode = ZoomMode.FIT_CENTER,
	) {
		if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return
		this.viewportWidth = viewportWidth.toFloat()
		this.viewportHeight = viewportHeight.toFloat()
		val fitScale = when (mode) {
			ZoomMode.FIT_CENTER,
			ZoomMode.KEEP_START -> min(
				this.viewportWidth / imageWidth,
				this.viewportHeight / imageHeight,
			)
			ZoomMode.FIT_HEIGHT -> this.viewportHeight / imageHeight
			ZoomMode.FIT_WIDTH -> this.viewportWidth / imageWidth
		}
		fittedImageWidth = imageWidth * fitScale
		fittedImageHeight = imageHeight * fitScale
		clampOffset()
	}

	fun transform(panX: Float, panY: Float, zoom: Float): TransformConsumption {
		val previousScale = scale
		val previousX = offsetX
		val previousY = offsetY
		scale = (scale * zoom).coerceIn(MIN_SCALE, maxScale)
		offsetX += panX
		offsetY += panY
		clampOffset()
		val consumedPanX = offsetX - previousX
		val consumedPanY = offsetY - previousY
		return TransformConsumption(
			panX = consumedPanX,
			panY = consumedPanY,
			remainingPanX = panX - consumedPanX,
			remainingPanY = panY - consumedPanY,
			zoomed = abs(scale - previousScale) > EPSILON,
		)
	}

	fun toggleDoubleTapZoom() {
		zoomTo(doubleTapTargetScale())
	}

	fun doubleTapTargetScale(): Float {
		return if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE.coerceAtMost(maxScale)
	}

	fun targetScaleForFactor(factor: Float): Float {
		return (scale * factor).coerceIn(MIN_SCALE, maxScale)
	}

	fun zoomTo(targetScale: Float) {
		scale = targetScale.coerceIn(MIN_SCALE, maxScale)
		if (scale == MIN_SCALE) {
			offsetX = 0f
			offsetY = 0f
		} else {
			clampOffset()
		}
	}

	fun zoomBy(factor: Float) {
		zoomTo(targetScaleForFactor(factor))
	}

	internal fun snapshot() = Snapshot(
		scale = scale,
		offsetX = offsetX,
		offsetY = offsetY,
	)

	internal data class Snapshot(
		val scale: Float,
		val offsetX: Float,
		val offsetY: Float,
	)

	private fun clampOffset() {
		val maxOffsetX = ((fittedImageWidth * scale - viewportWidth) / 2f).coerceAtLeast(0f)
		val maxOffsetY = ((fittedImageHeight * scale - viewportHeight) / 2f).coerceAtLeast(0f)
		offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
		offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
	}

	data class TransformConsumption(
		/** Pan actually consumed by the image after clamping to its bounds. */
		val panX: Float,
		val panY: Float,
		/** Pan that can be offered to the parent pager/list at an image edge. */
		val remainingPanX: Float,
		val remainingPanY: Float,
		val zoomed: Boolean,
	) {
		val consumed: Boolean
			get() = zoomed || abs(panX) > EPSILON || abs(panY) > EPSILON
	}

	companion object {
		val Saver = listSaver<ReaderZoomState, Float>(
			save = { state ->
				state.snapshot().let { snapshot ->
					listOf(snapshot.scale, snapshot.offsetX, snapshot.offsetY)
				}
			},
			restore = { values ->
				ReaderZoomState().apply {
					scale = values[0]
					offsetX = values[1]
					offsetY = values[2]
				}
			},
		)

		private const val MIN_SCALE = 1f
		private const val DOUBLE_TAP_SCALE = 2f
		private const val EPSILON = 0.001f
	}
}

internal fun initialReaderScale(
	mode: ZoomMode,
	viewportWidth: Int,
	viewportHeight: Int,
	imageWidth: Int,
	imageHeight: Int,
): Float {
	if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return 1f
	val fitScale = min(
		viewportWidth.toFloat() / imageWidth,
		viewportHeight.toFloat() / imageHeight,
	)
	if (fitScale <= 0f) return 1f
	return when (mode) {
		ZoomMode.FIT_CENTER,
		ZoomMode.FIT_HEIGHT,
		ZoomMode.FIT_WIDTH -> 1f
		ZoomMode.KEEP_START -> {
			2f * maxOf(
				viewportWidth.toFloat() / imageWidth,
				viewportHeight.toFloat() / imageHeight,
			) / fitScale
		}
	}
}
