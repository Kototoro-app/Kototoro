package org.skepsun.kototoro.reader.render.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.VerticalReaderScene
import org.skepsun.kototoro.reader.image.ReaderImageAsset
import kotlin.math.roundToInt

/**
 * Candidate B (Reference Control): Custom Android View Canvas Scene Renderer.
 *
 * Implements standard View.onDraw(Canvas) and OverScroller fling to serve as the
 * experimental control group against Candidate A (ComposeSceneRenderer).
 */
class AndroidViewSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var scene: VerticalReaderScene? = null
        set(value) {
            field = value
            scrollYOffset = scrollYOffset.coerceIn(0f, maxScrollY)
            invalidate()
        }

    var assetProvider: ((PageId) -> Bitmap?)? = null
    var readerAssetProvider: ((PageId) -> ReaderImageAsset?)? = null

    var onActivePageChanged: ((PageId) -> Unit)? = null

    var scrollYOffset: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, maxScrollY)
            if (field != clamped) {
                field = clamped
                checkActivePageChanged()
                postInvalidateOnAnimation()
            }
        }

    private val maxScrollY: Float
        get() = ((scene?.totalSceneHeight ?: 0f) - height).coerceAtLeast(0f)

    private val scroller = OverScroller(context)
    private var lastActivePageId: PageId? = null

    private val placeholderPaint = Paint().apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) {
                scroller.forceFinished(true)
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            scrollYOffset += distanceY
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            scroller.fling(
                0,
                scrollYOffset.roundToInt(),
                0,
                (-velocityY).roundToInt(),
                0,
                0,
                0,
                maxScrollY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYOffset = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val currentScene = scene ?: return
        if (width <= 0 || height <= 0) return

        val viewport = ReaderViewport(
            bounds = FloatRect.fromLtwh(0f, scrollYOffset, width.toFloat(), height.toFloat()),
        )
        val frame = currentScene.resolve(viewport)

        for (node in frame.visibleNodes) {
            val screenTop = node.sceneBounds.top - scrollYOffset
            val screenLeft = node.sceneBounds.left
            val screenRight = screenLeft + node.sceneBounds.width
            val screenBottom = screenTop + node.sceneBounds.height

            dstRectF.set(screenLeft, screenTop, screenRight, screenBottom)

            val resolvedBitmap = when (val asset = readerAssetProvider?.invoke(node.pageId)) {
                is ReaderImageAsset.AndroidBitmap -> asset.bitmap
                else -> assetProvider?.invoke(node.pageId)
            }
            if (resolvedBitmap != null && !resolvedBitmap.isRecycled) {
                srcRect.set(0, 0, resolvedBitmap.width, resolvedBitmap.height)
                canvas.drawBitmap(resolvedBitmap, srcRect, dstRectF, bitmapPaint)
            } else {
                canvas.drawRect(dstRectF, placeholderPaint)
            }
        }
    }

    private fun checkActivePageChanged() {
        val callback = onActivePageChanged ?: return
        val currentScene = scene ?: return
        if (width <= 0 || height <= 0) return
        val viewport = ReaderViewport(
            bounds = FloatRect.fromLtwh(0f, scrollYOffset, width.toFloat(), height.toFloat()),
        )
        val activeId = currentScene.resolveActivePageId(viewport)
        if (activeId != null && activeId != lastActivePageId) {
            lastActivePageId = activeId
            callback(activeId)
        }
    }
}
