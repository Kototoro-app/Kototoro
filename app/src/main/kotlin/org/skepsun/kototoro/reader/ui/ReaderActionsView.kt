package org.skepsun.kototoro.reader.ui

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.util.ext.hasVisibleChildren
import org.skepsun.kototoro.core.util.ext.isRtl
import org.skepsun.kototoro.core.util.ext.setContentDescriptionAndTooltip
import org.skepsun.kototoro.core.util.ext.setTooltipCompat
import org.skepsun.kototoro.core.util.ext.setValueRounded
import org.skepsun.kototoro.databinding.LayoutReaderActionsBinding
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.skepsun.kototoro.reader.ui.ReaderControlDelegate.OnInteractionListener
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ReaderActionsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr),
	View.OnClickListener,
	SharedPreferences.OnSharedPreferenceChangeListener,
	Slider.OnChangeListener,
	Slider.OnSliderTouchListener, View.OnLongClickListener {

	@Inject
	lateinit var settings: AppSettings

	private val binding = LayoutReaderActionsBinding.inflate(LayoutInflater.from(context), this)
	private val rotationObserver = object : ContentObserver(handler) {
		override fun onChange(selfChange: Boolean) {
			post {
				updateRotationButton()
			}
		}
	}
	private var isSliderChanged = false
	private var isSliderTracking = false
	private var pageLabelFormatter: ((Int, Int) -> String)? = null
	private var translateButtonRequestedVisible = false
	private var translateButtonContextualVisible = false

	var isSliderEnabled: Boolean
		get() = binding.slider.isEnabled
		set(value) {
			binding.slider.isEnabled = value
			binding.slider.setThumbVisible(value)
		}

	var isNextEnabled: Boolean
		get() = binding.buttonNext.isEnabled
		set(value) {
			binding.buttonNext.isEnabled = value
		}

	var isPrevEnabled: Boolean
		get() = binding.buttonPrev.isEnabled
		set(value) {
			binding.buttonPrev.isEnabled = value
		}

	var isBookmarkAdded: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				updateBookmarkButton()
			}
		}

	var listener: OnInteractionListener? = null

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		binding.buttonNext.initAction()
		binding.buttonPrev.initAction()
		binding.buttonSave.initAction()
		binding.buttonOptions.initAction()
		binding.buttonScreenRotation.initAction()
		binding.buttonPagesThumbs.initAction()
		binding.buttonTimer.initAction()
		binding.buttonBookmark.initAction()
		binding.buttonDownload.initAction()
		binding.buttonTranslate.initAction()
		binding.slider.setLabelFormatter(PageLabelFormatter())
		binding.slider.addOnChangeListener(this)
		binding.slider.addOnSliderTouchListener(this)
		updateControlsVisibility()
		updatePagesSheetButton()
		updateRotationButton()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		settings.subscribe(this)
		context.contentResolver.registerContentObserver(
			Settings.System.CONTENT_URI, true, rotationObserver,
		)
	}

	override fun onDetachedFromWindow() {
		settings.unsubscribe(this)
		context.contentResolver.unregisterContentObserver(rotationObserver)
		super.onDetachedFromWindow()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_prev -> listener?.switchChapterBy(-1)
			R.id.button_next -> listener?.switchChapterBy(1)
			R.id.button_save -> listener?.onSavePageClick()
			R.id.button_timer -> listener?.onScrollTimerClick(isLongClick = false)
			R.id.button_pages_thumbs -> AppRouter.from(this)?.showChapterPagesSheet()
			R.id.button_screen_rotation -> listener?.toggleScreenOrientation()
			R.id.button_options -> listener?.openMenu()
			R.id.button_bookmark -> listener?.onBookmarkClick()
			R.id.button_download -> listener?.onDownloadClick()
			R.id.button_translate -> listener?.onTranslateClick()
		}
	}

	override fun onLongClick(v: View): Boolean = when (v.id) {
		R.id.button_bookmark -> {
			AppRouter.from(this)?.showChapterPagesSheet(ChaptersPagesSheet.TAB_BOOKMARKS)
			true
		}

		R.id.button_timer -> {
			listener?.onScrollTimerClick(isLongClick = true)
			true
		}

		R.id.button_translate -> listener?.onTranslateLongClick() == true

		R.id.button_options -> {
			AppRouter.from(this)?.openReaderSettings()
			true
		}

		else -> false
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			if (isSliderTracking) {
				isSliderChanged = true
			} else {
				listener?.switchPageTo(value.toInt())
			}
		}
	}

	override fun onStartTrackingTouch(slider: Slider) {
		if (!isSliderTracking) {
			isSliderChanged = false
			isSliderTracking = true
		}
	}

	override fun onStopTrackingTouch(slider: Slider) {
		isSliderTracking = false
		if (isSliderChanged) {
			listener?.switchPageTo(slider.value.toInt())
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_READER_CONTROLS -> updateControlsVisibility()
			AppSettings.KEY_PAGES_TAB,
			AppSettings.KEY_DETAILS_TAB,
			AppSettings.KEY_DETAILS_LAST_TAB -> updatePagesSheetButton()
		}
	}

	fun setSliderValue(value: Int, max: Int) {
		// 确保valueTo始终大于valueFrom，避免崩溃
		// 当max为0时，设置为1（最小有效值）
		val safeMax = if (max <= 0) 1f else max.toFloat()
		val safeValue = if (value < 0) 0f else if (value > max) max.toFloat() else value.toFloat()
		
		binding.slider.valueTo = safeMax
		binding.slider.setValueRounded(safeValue)
	}

	fun setPageLabel(current: Int, total: Int) {
		pageLabelFormatter = { c, t -> "$c/$t" }
		binding.slider.setLabelFormatter { pageLabelFormatter?.invoke(current, total) ?: it.toInt().toString() }
	}

	fun setSliderReversed(reversed: Boolean) {
		binding.slider.isRtl = reversed != isRtl
	}

	fun setTimerActive(isActive: Boolean) {
		binding.buttonTimer.setIconResource(
			if (isActive) R.drawable.ic_timer_run else R.drawable.ic_timer,
		)
	}

	/**
	 * 根据阅读器能力请求显示或隐藏翻译按钮。
	 */
	fun setTranslateButtonVisible(visible: Boolean) {
		translateButtonRequestedVisible = visible
		applyTranslateButtonVisibility()
		adjustLayoutParams()
	}

	fun setTranslateButtonContextualVisible(visible: Boolean) {
		translateButtonContextualVisible = visible
		applyTranslateButtonVisibility()
		adjustLayoutParams()
	}

	/**
	 * 更新翻译按钮的激活状态。
	 */
	fun setTranslateActive(isActive: Boolean) {
		binding.buttonTranslate.isSelected = isActive
		// 激活时改变图标色调以提示用户
		val colorAttrRes = if (isActive) {
			androidx.appcompat.R.attr.colorPrimary
		} else {
			android.R.attr.colorControlNormal
		}
		val color = com.google.android.material.color.MaterialColors.getColor(
			this,
			colorAttrRes,
			android.graphics.Color.GRAY,
		)
		binding.buttonTranslate.iconTint = android.content.res.ColorStateList.valueOf(color)
	}

	fun setTranslateButtonContentDescription(text: CharSequence) {
		binding.buttonTranslate.contentDescription = text
		binding.buttonTranslate.setTooltipCompat(text)
	}

	private fun updateControlsVisibility() {
		val controls = settings.readerControls
		binding.buttonPrev.isVisible = ReaderControl.PREV_CHAPTER in controls
		binding.buttonNext.isVisible = ReaderControl.NEXT_CHAPTER in controls
		binding.buttonPagesThumbs.isVisible = ReaderControl.PAGES_SHEET in controls
		binding.buttonScreenRotation.isVisible = ReaderControl.SCREEN_ROTATION in controls
		binding.buttonSave.isVisible = ReaderControl.SAVE_PAGE in controls
		binding.buttonTimer.isVisible = ReaderControl.TIMER in controls
		binding.buttonBookmark.isVisible = ReaderControl.BOOKMARK in controls
		binding.buttonDownload.isVisible = ReaderControl.DOWNLOAD in controls
		binding.slider.isVisible = ReaderControl.SLIDER in controls
		applyTranslateButtonVisibility()
		adjustLayoutParams()
	}

	private fun applyTranslateButtonVisibility() {
		val visible = translateButtonRequestedVisible && (
			translateButtonContextualVisible || ReaderControl.TRANSLATE in settings.readerControls
		)
		binding.buttonTranslate.isVisible = visible
		(binding.buttonTranslate.parent as? View)?.isVisible = visible
	}

	private fun updatePagesSheetButton() {
		val isPagesMode = settings.defaultDetailsTab == TAB_PAGES
		val button = binding.buttonPagesThumbs
		button.setIconResource(
			if (isPagesMode) R.drawable.ic_grid else R.drawable.ic_list,
		)
		button.setContentDescriptionAndTooltip(
			if (isPagesMode) R.string.pages else R.string.chapters,
		)
	}

	private fun updateBookmarkButton() {
		val button = binding.buttonBookmark
		button.setIconResource(
			if (isBookmarkAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark,
		)
		button.setContentDescriptionAndTooltip(
			if (isBookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add,
		)
	}

	private fun adjustLayoutParams() {
		val isSliderVisible = binding.slider.isVisible
		repeat(childCount) { i ->
			val child = getChildAt(i)
			if (child is FrameLayout) {
				child.isVisible = child.hasVisibleChildren
				child.updateLayoutParams<LayoutParams> {
					width = if (isSliderVisible) LayoutParams.WRAP_CONTENT else 0
					weight = if (isSliderVisible) 0f else 1f
				}
			}
		}
	}

	private fun updateRotationButton() {
		val button = binding.buttonScreenRotation
		when {
			!button.isVisible -> return
			isAutoRotationEnabled() -> {
				button.setContentDescriptionAndTooltip(R.string.lock_screen_rotation)
				button.setIconResource(R.drawable.ic_screen_rotation_lock)
			}

			else -> {
				button.setContentDescriptionAndTooltip(R.string.rotate_screen)
				button.setIconResource(R.drawable.ic_screen_rotation)
			}
		}
	}

	private fun Button.initAction() {
		setOnClickListener(this@ReaderActionsView)
		setOnLongClickListener(this@ReaderActionsView)
		setTooltipCompat(contentDescription)
	}

	private fun isAutoRotationEnabled(): Boolean = Settings.System.getInt(
		context.contentResolver,
		Settings.System.ACCELEROMETER_ROTATION,
		0,
	) == 1

	private fun Slider.setThumbVisible(visible: Boolean) {
		thumbWidth = if (visible) {
			resources.getDimensionPixelSize(materialR.dimen.m3_comp_slider_active_handle_width)
		} else {
			0
		}
		thumbHeight = if (visible) {
			resources.getDimensionPixelSize(materialR.dimen.m3_comp_slider_active_handle_height)
		} else {
			0
		}
	}
}
