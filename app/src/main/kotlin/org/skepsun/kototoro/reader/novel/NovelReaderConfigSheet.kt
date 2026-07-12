package org.skepsun.kototoro.reader.novel

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import org.skepsun.kototoro.databinding.SheetNovelReaderConfigBinding
import org.skepsun.kototoro.core.util.ext.performSegmentHapticFeedback

/**
 * 小说阅读器设置面板
 */
class NovelReaderConfigSheet : BottomSheetDialogFragment(),
    Slider.OnChangeListener,
    CompoundButton.OnCheckedChangeListener,
    View.OnClickListener {

    private var _binding: SheetNovelReaderConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: NovelReaderSettings
    private var callback: Callback? = null
    
    // 防抖动处理
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val updateDelay = 150L // 150ms 延迟

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetNovelReaderConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 获取回调
        callback = parentFragment as? Callback ?: activity as? Callback

        // 加载设置
        settings = NovelReaderSettings.load(requireContext()).normalized()

        // 初始化控件
        configureSliders()
        syncControlsFromSettings()
        binding.switchDualPage.isChecked = settings.enableDualPage
        binding.switchFullscreen.isChecked = settings.enableFullscreen
        binding.switchShowReadingStatus.isChecked = settings.showReadingStatus
        binding.switchReadingStatusTransparent.isChecked = settings.isReadingStatusTransparent
        binding.switchParagraphIndent.isChecked = settings.enableParagraphIndent
        binding.toggleGroupReadingMode.check(if (settings.readingMode == ReadingMode.SCROLL) org.skepsun.kototoro.R.id.btnModeScroll else org.skepsun.kototoro.R.id.btnModePaged)
        binding.toggleGroupPageTurnAnimation.check(
            if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
                org.skepsun.kototoro.R.id.btnAnimationSimulation
            } else {
                org.skepsun.kototoro.R.id.btnAnimationSlide
            }
        )
        binding.toggleGroupThemePreset.check(
            when (settings.themePreset) {
                NovelReaderThemePreset.PAPER -> org.skepsun.kototoro.R.id.btnThemePaper
                NovelReaderThemePreset.SEPIA -> org.skepsun.kototoro.R.id.btnThemeSepia
                NovelReaderThemePreset.MOSS -> org.skepsun.kototoro.R.id.btnThemeMoss
                NovelReaderThemePreset.SLATE -> org.skepsun.kototoro.R.id.btnThemeSlate
            }
        )

        // 初始化翻译展示模式控件
        binding.toggleGroupTranslationMode.check(
            if (settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL) {
                org.skepsun.kototoro.R.id.btnTranslationBilingual
            } else {
                org.skepsun.kototoro.R.id.btnTranslationOnly
            }
        )

        // 初始化值显示
        updateValueDisplays()
        updatePageTurnAnimationEnabled()
        updatePreviewCard()

        // 设置监听器
        binding.sliderFontSize.addOnChangeListener(this)
        binding.sliderLineSpacing.addOnChangeListener(this)
        binding.sliderParagraphSpacing.addOnChangeListener(this)
        binding.sliderMarginHorizontal.addOnChangeListener(this)
        binding.sliderMarginVertical.addOnChangeListener(this)
        binding.switchDualPage.setOnCheckedChangeListener(this)
        binding.switchFullscreen.setOnCheckedChangeListener(this)
        binding.switchShowReadingStatus.setOnCheckedChangeListener(this)
        binding.switchReadingStatusTransparent.setOnCheckedChangeListener(this)
        binding.switchParagraphIndent.setOnCheckedChangeListener(this)
        binding.buttonBookmark.setOnClickListener(this)
        binding.buttonTts.setOnClickListener(this)
        binding.buttonReset.setOnClickListener(this)
        binding.buttonClose.setOnClickListener(this)
        binding.buttonClearTranslationCache.setOnClickListener(this)
        
        binding.toggleGroupReadingMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                group.performSegmentHapticFeedback()
                settings = settings.copy(
                    readingMode = if (checkedId == org.skepsun.kototoro.R.id.btnModeScroll) ReadingMode.SCROLL else ReadingMode.PAGED
                )
                updatePageTurnAnimationEnabled()
                updatePreviewCard()
                applySettings()
            }
        }

        binding.toggleGroupPageTurnAnimation.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                group.performSegmentHapticFeedback()
                settings = settings.copy(
                    pageTurnAnimation = if (checkedId == org.skepsun.kototoro.R.id.btnAnimationSimulation) {
                        NovelPageTurnAnimation.SIMULATION
                    } else {
                        NovelPageTurnAnimation.SLIDE
                    }
                )
                applySettings()
            }
        }

        binding.toggleGroupThemePreset.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                group.performSegmentHapticFeedback()
                settings = settings.copy(
                    themePreset = when (checkedId) {
                        org.skepsun.kototoro.R.id.btnThemeSepia -> NovelReaderThemePreset.SEPIA
                        org.skepsun.kototoro.R.id.btnThemeMoss -> NovelReaderThemePreset.MOSS
                        org.skepsun.kototoro.R.id.btnThemeSlate -> NovelReaderThemePreset.SLATE
                        else -> NovelReaderThemePreset.PAPER
                    }
                )
                updatePreviewCard()
                applySettings()
            }
        }

        // 翻译展示模式切换监听
        binding.toggleGroupTranslationMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                group.performSegmentHapticFeedback()
                settings = settings.copy(
                    translationDisplayMode = if (checkedId == org.skepsun.kototoro.R.id.btnTranslationBilingual) {
                        NovelTranslationDisplayMode.BILINGUAL
                    } else {
                        NovelTranslationDisplayMode.TRANSLATION_ONLY
                    }
                )
                updatePreviewCard()
                applySettings()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理防抖动任务
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
        _binding = null
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (!fromUser) return

        settings = when (slider.id) {
            org.skepsun.kototoro.R.id.sliderFontSize -> settings.copy(fontSizeSp = value)
            org.skepsun.kototoro.R.id.sliderLineSpacing -> settings.copy(lineSpacing = value)
            org.skepsun.kototoro.R.id.sliderParagraphSpacing -> settings.copy(paragraphSpacing = value)
            org.skepsun.kototoro.R.id.sliderMarginHorizontal -> settings.copy(marginHorizontal = value.toInt())
            org.skepsun.kototoro.R.id.sliderMarginVertical -> settings.copy(marginVertical = value.toInt())
            else -> return
        }.normalized()

        slider.performSegmentHapticFeedback()

        updateValueDisplays()
        updatePreviewCard()
        applySettingsDebounced()
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        when (buttonView.id) {
            org.skepsun.kototoro.R.id.switchDualPage -> {
                settings = settings.copy(enableDualPage = isChecked)
                updatePreviewCard()
                applySettings()
            }
            org.skepsun.kototoro.R.id.switchFullscreen -> {
                settings = settings.copy(enableFullscreen = isChecked)
                updatePreviewCard()
                applySettings()
            }
            org.skepsun.kototoro.R.id.switchShowReadingStatus -> {
                settings = settings.copy(showReadingStatus = isChecked)
                updatePreviewCard()
                applySettings()
            }
            org.skepsun.kototoro.R.id.switchReadingStatusTransparent -> {
                settings = settings.copy(isReadingStatusTransparent = isChecked)
                updatePreviewCard()
                applySettings()
            }
            org.skepsun.kototoro.R.id.switchParagraphIndent -> {
                settings = settings.copy(enableParagraphIndent = isChecked)
                updatePreviewCard()
                applySettings()
            }
        }
    }

    override fun onClick(v: View) {
        when (v.id) {
            org.skepsun.kototoro.R.id.buttonBookmark -> {
                callback?.onBookmarkClick()
                dismiss()
            }
            org.skepsun.kototoro.R.id.buttonTts -> {
                callback?.onTtsClick()
                dismiss()
            }
            org.skepsun.kototoro.R.id.buttonClearTranslationCache -> {
                callback?.onClearTranslationCacheClick()
            }
            org.skepsun.kototoro.R.id.buttonReset -> resetSettings()
            org.skepsun.kototoro.R.id.buttonClose -> dismiss()
        }
    }

    private fun applySettings() {
        settings = settings.normalized()
        settings.save(requireContext())
        callback?.onSettingsChanged(settings)
    }

    /**
     * 防抖动应用设置 - 避免频繁更新
     */
    private fun applySettingsDebounced() {
        // 取消之前的更新
        updateRunnable?.let { handler.removeCallbacks(it) }
        
        // 创建新的更新任务
        updateRunnable = Runnable {
            applySettings()
        }
        
        // 延迟执行
        handler.postDelayed(updateRunnable!!, updateDelay)
    }

    private fun resetSettings() {
        // 重置为默认值
        settings = NovelReaderSettings().normalized()

        // 更新 UI
        syncControlsFromSettings()
        binding.switchDualPage.isChecked = settings.enableDualPage
        binding.switchFullscreen.isChecked = settings.enableFullscreen
        binding.switchShowReadingStatus.isChecked = settings.showReadingStatus
        binding.switchReadingStatusTransparent.isChecked = settings.isReadingStatusTransparent
        binding.switchParagraphIndent.isChecked = settings.enableParagraphIndent
        binding.toggleGroupReadingMode.check(if (settings.readingMode == ReadingMode.SCROLL) org.skepsun.kototoro.R.id.btnModeScroll else org.skepsun.kototoro.R.id.btnModePaged)
        binding.toggleGroupPageTurnAnimation.check(
            if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
                org.skepsun.kototoro.R.id.btnAnimationSimulation
            } else {
                org.skepsun.kototoro.R.id.btnAnimationSlide
            }
        )
        binding.toggleGroupThemePreset.check(
            when (settings.themePreset) {
                NovelReaderThemePreset.PAPER -> org.skepsun.kototoro.R.id.btnThemePaper
                NovelReaderThemePreset.SEPIA -> org.skepsun.kototoro.R.id.btnThemeSepia
                NovelReaderThemePreset.MOSS -> org.skepsun.kototoro.R.id.btnThemeMoss
                NovelReaderThemePreset.SLATE -> org.skepsun.kototoro.R.id.btnThemeSlate
            }
        )

        updateValueDisplays()
        updatePageTurnAnimationEnabled()
        updatePreviewCard()
        applySettings()
    }

    private fun updateValueDisplays() {
        binding.textFontSizeValue.text = String.format("%.1fsp", settings.fontSizeSp)
        binding.textLineSpacingValue.text = String.format("%.1f", settings.lineSpacing)
        binding.textParagraphSpacingValue.text = if (settings.paragraphSpacing <= 0f) {
            getString(org.skepsun.kototoro.R.string.novel_paragraph_spacing_follow_line)
        } else {
            "${settings.paragraphSpacing.toInt()}dp"
        }
        binding.textMarginHorizontalValue.text = "${settings.marginHorizontal}dp"
        binding.textMarginVerticalValue.text = "${settings.marginVertical}dp"
    }

    private fun updatePageTurnAnimationEnabled() {
        binding.toggleGroupPageTurnAnimation.isEnabled = settings.readingMode == ReadingMode.PAGED
        for (i in 0 until binding.toggleGroupPageTurnAnimation.childCount) {
            binding.toggleGroupPageTurnAnimation.getChildAt(i).isEnabled = settings.readingMode == ReadingMode.PAGED
        }
    }

    private fun updatePreviewCard() {
        val palette = novelReaderPalette(
            preset = settings.themePreset,
            isDarkTheme = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )

        binding.cardPreview.setCardBackgroundColor(palette.backgroundColor)
        binding.cardPreview.strokeColor = ColorUtils.setAlphaComponent(palette.secondaryTextColor, 76)

        binding.textPreviewCaption.setTextColor(ColorUtils.setAlphaComponent(palette.secondaryTextColor, 190))
        binding.textPreviewTitle.setTextColor(palette.secondaryTextColor)
        binding.textPreviewBody.setTextColor(palette.textColor)
        binding.textPreviewSecondary.setTextColor(ColorUtils.setAlphaComponent(palette.secondaryTextColor, 230))

        binding.textPreviewTitle.textSize = settings.fontSizeSp + 2f
        binding.textPreviewBody.textSize = settings.fontSizeSp
        binding.textPreviewSecondary.textSize = settings.fontSizeSp * 0.86f

        val bodySpacingExtra = ((settings.lineSpacing - 1f) * binding.textPreviewBody.textSize).coerceAtLeast(0f)
        val secondarySpacingExtra = ((settings.lineSpacing - 1f) * binding.textPreviewSecondary.textSize).coerceAtLeast(0f)
        binding.textPreviewBody.setLineSpacing(bodySpacingExtra, 1f)
        binding.textPreviewSecondary.setLineSpacing(secondarySpacingExtra, 1f)

        val previewHorizontalPadding = (settings.marginHorizontal * 0.7f).toInt()
        binding.textPreviewBody.setPadding(previewHorizontalPadding, 0, previewHorizontalPadding, 0)
        binding.textPreviewSecondary.setPadding(previewHorizontalPadding, 0, previewHorizontalPadding, 0)

        val paragraphTopMargin = lineSpacingPx(settings)
        binding.textPreviewTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = paragraphTopMargin + 2.dpToPx()
        }
        binding.textPreviewBody.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = paragraphTopMargin
        }
        binding.textPreviewSecondary.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = paragraphTopMargin
        }
        binding.textPreviewTitle.requestLayout()
        binding.textPreviewBody.requestLayout()
        binding.textPreviewSecondary.requestLayout()

        val indent = if (settings.enableParagraphIndent) "　　" else ""
        binding.textPreviewBody.text = indent + getString(org.skepsun.kototoro.R.string.novel_preview_body)

        val secondaryVisible = settings.translationDisplayMode == NovelTranslationDisplayMode.BILINGUAL
        binding.textPreviewSecondary.visibility = if (secondaryVisible) View.VISIBLE else View.GONE
        if (secondaryVisible) {
            binding.textPreviewSecondary.text = getString(org.skepsun.kototoro.R.string.novel_preview_body_secondary)
        }
    }

    private fun configureSliders() {
        binding.sliderFontSize.apply {
            valueFrom = NovelReaderSettings.FONT_SIZE_RANGE.start
            valueTo = NovelReaderSettings.FONT_SIZE_RANGE.endInclusive
            stepSize = NovelReaderSettings.FONT_SIZE_STEP
        }
        binding.sliderLineSpacing.apply {
            valueFrom = NovelReaderSettings.LINE_SPACING_RANGE.start
            valueTo = NovelReaderSettings.LINE_SPACING_RANGE.endInclusive
            stepSize = NovelReaderSettings.LINE_SPACING_STEP
        }
        binding.sliderParagraphSpacing.apply {
            valueFrom = NovelReaderSettings.PARAGRAPH_SPACING_RANGE.start
            valueTo = NovelReaderSettings.PARAGRAPH_SPACING_RANGE.endInclusive
            stepSize = NovelReaderSettings.PARAGRAPH_SPACING_STEP
        }
        binding.sliderMarginHorizontal.apply {
            valueFrom = NovelReaderSettings.MARGIN_RANGE.first.toFloat()
            valueTo = NovelReaderSettings.MARGIN_RANGE.last.toFloat()
            stepSize = NovelReaderSettings.MARGIN_STEP.toFloat()
        }
        binding.sliderMarginVertical.apply {
            valueFrom = NovelReaderSettings.MARGIN_RANGE.first.toFloat()
            valueTo = NovelReaderSettings.MARGIN_RANGE.last.toFloat()
            stepSize = NovelReaderSettings.MARGIN_STEP.toFloat()
        }
    }

    private fun syncControlsFromSettings() {
        binding.sliderFontSize.value = settings.fontSizeSp
        binding.sliderLineSpacing.value = settings.lineSpacing
        binding.sliderParagraphSpacing.value = settings.paragraphSpacing
        binding.sliderMarginHorizontal.value = settings.marginHorizontal.toFloat()
        binding.sliderMarginVertical.value = settings.marginVertical.toFloat()
    }

    private fun lineSpacingPx(settings: NovelReaderSettings): Int {
        return ((settings.lineSpacing - 1f).coerceAtLeast(0f) * binding.textPreviewBody.textSize).toInt()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    interface Callback {
        fun onSettingsChanged(settings: NovelReaderSettings)
        fun onBookmarkClick()
        fun onTtsClick()
        fun onClearTranslationCacheClick()
    }

    companion object {
        fun newInstance(): NovelReaderConfigSheet {
            return NovelReaderConfigSheet()
        }
    }
}
