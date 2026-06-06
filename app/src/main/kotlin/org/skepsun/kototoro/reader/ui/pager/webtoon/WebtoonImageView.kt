package org.skepsun.kototoro.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.skepsun.kototoro.core.util.ext.resolveDp
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

	private val ct = PointF()

	private var scrollPos = 0
	private var isSourceReady = false
	private var debugPaint: Paint? = null

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (isDebugDrawingEnabled) {
			drawDebug(canvas)
		}
	}

	fun scrollBy(delta: Int) {
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			return
		}
		val newScroll = scrollPos + delta
		scrollToInternal(newScroll.coerceIn(0, maxScroll))
	}

	fun scrollTo(y: Int) {
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			scrollToInternal(0)
			return
		}
		scrollToInternal(y.coerceIn(0, maxScroll))
	}

	fun getScroll() = scrollPos

	fun getScrollRange(): Int {
		if (!isScrollReady()) {
			return 0
		}
		return getMaxScrollForCurrentImage()
	}

	override fun recycle() {
		resetSourceState()
		super.recycle()
	}

	fun resetSourceState() {
		scrollPos = 0
		isSourceReady = false
	}

	fun isScrollReady(): Boolean = isSourceReady && isReady && width > 0 && height > 0 && sWidth > 0 && sHeight > 0

	override fun getSuggestedMinimumHeight(): Int {
		var desiredHeight = super.getSuggestedMinimumHeight()
		if (sHeight == 0) {
			val parentHeight = parentHeight()
			if (desiredHeight < parentHeight) {
				desiredHeight = parentHeight
			}
		}
		return desiredHeight
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
		val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
		val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
		val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
		val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
		val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
		var desiredWidth = parentWidth
		var desiredHeight = parentHeight
		if (sWidth > 0 && sHeight > 0) {
			if (resizeWidth && resizeHeight) {
				desiredWidth = sWidth
				desiredHeight = sHeight
			} else if (resizeHeight) {
				desiredHeight = (sHeight.toDouble() / sWidth.toDouble() * desiredWidth).toInt()
			} else if (resizeWidth) {
				desiredWidth = (sWidth.toDouble() / sHeight.toDouble() * desiredHeight).toInt()
			}
		}
		desiredWidth = desiredWidth.coerceAtLeast(suggestedMinimumWidth)
		desiredHeight = desiredHeight.coerceAtLeast(suggestedMinimumHeight).coerceAtMost(parentHeight())
		setMeasuredDimension(desiredWidth, desiredHeight)
	}

	override fun onDownSamplingChanged() {
		super.onDownSamplingChanged()
		if (isReady) {
			updateReadyState()
		}
	}

	override fun onReady() {
		super.onReady()
		updateReadyState()
	}

	private fun scrollToInternal(pos: Int) {
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		scrollPos = pos
		ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / minScale)
		setScaleAndCenter(minScale, ct)
	}

	private fun adjustScale() {
		if (width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) {
			return
		}
		val newScale = width / sWidth.toFloat()
		minScale = newScale
		maxScale = newScale
		minimumScaleType = SCALE_TYPE_CUSTOM
		if (scrollPos == 0) {
			setScaleAndCenter(newScale, PointF(sWidth / 2f, height / 2f / newScale))
		} else {
			scrollToInternal(scrollPos)
		}
		if (shouldRequestLayoutForCurrentImage()) {
			requestLayoutKeepingReaderPosition()
		}
	}

	private fun updateReadyState() {
		if (width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) {
			return
		}
		isSourceReady = true
		adjustScale()
	}

	private fun getMaxScrollForCurrentImage(): Int {
		val totalHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
		return (totalHeight - height).coerceAtLeast(0)
	}

	private fun shouldRequestLayoutForCurrentImage(): Boolean {
		val desiredHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
			.coerceAtLeast(suggestedMinimumHeight)
			.coerceAtMost(parentHeight())
		return desiredHeight != height
	}

	private fun parentHeight(): Int {
		return ancestors.firstNotNullOfOrNull { it as? RecyclerView }?.height ?: 0
	}

	private fun requestLayoutKeepingReaderPosition() {
		val recyclerView = ancestors.firstNotNullOfOrNull { it as? WebtoonRecyclerView }
		if (recyclerView == null) {
			requestLayout()
			return
		}
		recyclerView.requestChildLayoutKeepingAnchor(this)
	}

	private fun drawDebug(canvas: Canvas) {
		val paint = debugPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.RED
			strokeWidth = context.resources.resolveDp(2f)
			textAlign = Paint.Align.LEFT
			textSize = context.resources.resolveDp(14f)
			debugPaint = this
		}
		paint.style = Paint.Style.STROKE
		canvas.drawRect(1f, 1f, width.toFloat() - 1f, height.toFloat() - 1f, paint)
		paint.style = Paint.Style.FILL
		canvas.drawText("${getScroll()} / ${getScrollRange()}", 100f, 100f, paint)
	}


}
