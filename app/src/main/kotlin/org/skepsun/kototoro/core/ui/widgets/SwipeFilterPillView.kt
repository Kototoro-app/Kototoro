package org.skepsun.kototoro.core.ui.widgets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.annotation.DrawableRes
import androidx.core.animation.doOnEnd
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.performSegmentHapticFeedback
import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.math.abs

class SwipeFilterPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val Int.dpToPx: Int
        get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    private fun getColorAttr(attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    var onFilterSelectedListener: ((ContentType?) -> Unit)? = null
    
    var defaultType: ContentType = ContentType.MANGA
    var swipeLeftType: ContentType = ContentType.VIDEO
    var swipeRightType: ContentType = ContentType.NOVEL
    
    var currentType: ContentType? = null
        private set

    fun setCurrentType(type: ContentType?) {
        currentType = type
        updateIcons()
    }

    private val iconLeft = ImageView(context)
    private val iconCenter = ImageView(context)
    private val iconRight = ImageView(context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getColorAttr(com.google.android.material.R.attr.colorSurfaceVariant)
    }
    
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getColorAttr(com.google.android.material.R.attr.colorPrimaryContainer)
    }

    private var iconTint = getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private var highlightIconTint = getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer)

    private val rect = RectF()
    private val highlightRect = RectF()
    
    private var isExpanded = false
    private var expansionProgress = 0f 
    
    private var downX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = 40.dpToPx
    private var isDragging = false
    
    private var selectedIndex = 1 
    private val modifiedParents = ArrayDeque<ClipState>()
    
    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        val padding = 12.dpToPx
        val size = 48.dpToPx
        
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        
        iconLeft.setPadding(padding, padding, padding, padding)
        iconCenter.setPadding(padding, padding, padding, padding)
        iconRight.setPadding(padding, padding, padding, padding)
        
        updateIcons()
        
        addView(iconLeft, FrameLayout.LayoutParams(size, size))
        addView(iconCenter, FrameLayout.LayoutParams(size, size))
        addView(iconRight, FrameLayout.LayoutParams(size, size))
        
        updateIconOpacities()
    }

    private fun updateIcons() {
        val isCollapsed = !isExpanded && expansionProgress == 0f
        val centerIconRes = if (isCollapsed) {
            getIconForType(currentType)
        } else {
            getIconForType(defaultType)
        }

        iconLeft.setImageResource(getIconForType(swipeLeftType))
        iconCenter.setImageResource(centerIconRes)
        iconRight.setImageResource(getIconForType(swipeRightType))
        
        if (isCollapsed) {
            iconCenter.setColorFilter(if (currentType != null) highlightIconTint else iconTint)
            iconLeft.setColorFilter(iconTint)
            iconRight.setColorFilter(iconTint)
        } else {
            iconLeft.setColorFilter(if (selectedIndex == 0) highlightIconTint else iconTint)
            iconCenter.setColorFilter(if (selectedIndex == 1) highlightIconTint else iconTint)
            iconRight.setColorFilter(if (selectedIndex == 2) highlightIconTint else iconTint)
        }
    }

    @DrawableRes
    private fun getIconForType(type: ContentType?): Int {
        return when (type) {
            ContentType.MANGA, ContentType.HENTAI_MANGA -> R.drawable.ic_content_manga
            ContentType.NOVEL, ContentType.HENTAI_NOVEL -> R.drawable.ic_content_novel
            ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.drawable.ic_content_video
            null -> R.drawable.ic_filter_content_type
            else -> R.drawable.ic_filter_content_type
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = 48.dpToPx
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val size = 48.dpToPx
        iconLeft.layout(-size, 0, 0, size)
        iconCenter.layout(0, 0, size, size)
        iconRight.layout(size, 0, size * 2, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = 48.dpToPx.toFloat()
        val expansionOffset = size * expansionProgress
        
        rect.set(-expansionOffset, 0f, size + expansionOffset, height.toFloat())
        val radius = height / 2f
        
        paint.alpha = (255 * expansionProgress).toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
        
        if (expansionProgress > 0f) {
            // Draw highlight rect behind the selected icon
            val centerOffset = (selectedIndex - 1) * size
            val highlightRadius = height / 2f
            highlightRect.set(centerOffset, 0f, centerOffset + size, height.toFloat())
            highlightPaint.alpha = (255 * expansionProgress).toInt()
            canvas.drawRoundRect(highlightRect, highlightRadius, highlightRadius, highlightPaint)
        }
        
        super.onDraw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                isDragging = false
                selectedIndex = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                expandPill()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                if (!isDragging && abs(dx) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val newIndex = when {
                        dx < -swipeThreshold -> 0 // Left swipe
                        dx > swipeThreshold -> 2  // Right swipe
                        else -> 1
                    }
                    if (newIndex != selectedIndex) {
                        selectedIndex = newIndex
                        performSegmentHapticFeedback()
                        updateIconOpacities()
                        invalidateWithParents()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging || event.actionMasked == MotionEvent.ACTION_UP) {
                    val newType = when (selectedIndex) {
                        0 -> swipeLeftType
                        2 -> swipeRightType
                        else -> defaultType
                    }
                    val finalType = if (newType == currentType) null else newType
                    onFilterSelectedListener?.invoke(finalType)
                }
                collapsePill()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        restoreParentClippingOverrides()
        super.onDetachedFromWindow()
    }

    private fun applyParentClippingOverrides() {
        restoreParentClippingOverrides()
        // Only relax clipping up to the Toolbar. Touching AppBarLayout/CoordinatorLayout
        // changes collapsing behavior and can leave visual gaps during fast scroll.
        var parentView = parent as? ViewGroup
        while (parentView != null) {
            modifiedParents.addLast(
                ClipState(
                    view = parentView,
                    clipChildren = parentView.clipChildren,
                    clipToPadding = parentView.clipToPadding,
                ),
            )
            parentView.clipChildren = false
            parentView.clipToPadding = false
            if (parentView is Toolbar) {
                break
            }
            parentView = parentView.parent as? ViewGroup
        }
    }

    private fun restoreParentClippingOverrides() {
        while (modifiedParents.isNotEmpty()) {
            val state = modifiedParents.removeLast()
            state.view.clipChildren = state.clipChildren
            state.view.clipToPadding = state.clipToPadding
        }
    }

    private fun expandPill() {
        if (isExpanded) return
        isExpanded = true
        applyParentClippingOverrides()
        animateExpansion(1f)
    }

    private fun collapsePill() {
        if (!isExpanded) return
        isExpanded = false
        animateExpansion(0f) {
            updateIcons()
            updateIconOpacities()
            restoreParentClippingOverrides()
        }
    }

    private fun updateIconOpacities() {
        if (!isExpanded && expansionProgress == 0f) {
            iconLeft.alpha = 0f
            iconRight.alpha = 0f
            iconCenter.alpha = 1f
        } else {
            iconLeft.alpha = expansionProgress * if (selectedIndex == 0) 1f else 0.5f
            iconCenter.alpha = if (selectedIndex == 1) 1f else 0.5f
            iconRight.alpha = expansionProgress * if (selectedIndex == 2) 1f else 0.5f
        }
        updateIcons()
    }

    private val locationOnScreen = IntArray(2)

    private fun invalidateWithParents() {
        invalidate()
        var p = parent as? View
        while (p != null) {
            p.invalidate()
            if (p is Toolbar) break
            p = p.parent as? View
        }
    }

    private fun animateExpansion(target: Float, onEnd: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(expansionProgress, target).apply {
            duration = 250
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                expansionProgress = it.animatedValue as Float
                elevation = 16f * expansionProgress
                updateIconOpacities()
                invalidateWithParents()
            }
            if (onEnd != null) doOnEnd { onEnd() }
            start()
        }
    }

    private data class ClipState(
        val view: ViewGroup,
        val clipChildren: Boolean,
        val clipToPadding: Boolean,
    )
}
