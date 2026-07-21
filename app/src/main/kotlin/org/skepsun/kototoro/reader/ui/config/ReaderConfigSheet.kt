package org.skepsun.kototoro.reader.ui.config

import android.os.Bundle
import android.app.Dialog
import android.content.res.Configuration
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.sidesheet.SideSheetDialog
import com.google.android.material.sidesheet.SideSheetBehavior
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.TabletUiMode
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.util.ext.consume
import org.skepsun.kototoro.core.util.ext.findParentCallback
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.performSegmentHapticFeedback
import org.skepsun.kototoro.core.util.ext.setValueRounded
import org.skepsun.kototoro.core.util.ext.viewLifecycleScope
import org.skepsun.kototoro.core.util.progress.IntPercentLabelFormatter
import org.skepsun.kototoro.databinding.SheetReaderConfigBinding
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.ui.TranslationTaskPanelSheet
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.reader.ui.ScreenOrientationHelper
import javax.inject.Inject

private const val STATE_ENABLE_TRANSLATION_AFTER_SETUP = "enable_translation_after_setup"

@AndroidEntryPoint
class ReaderConfigSheet :
    BaseAdaptiveSheet<SheetReaderConfigBinding>(),
    View.OnClickListener,
    MaterialButtonToggleGroup.OnButtonCheckedListener,
    CompoundButton.OnCheckedChangeListener,
    Slider.OnChangeListener {

    private val viewModel by viewModels<ReaderViewModel>(
        ownerProducer = { parentFragment ?: requireActivity() },
    )

    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var mangaRepositoryFactory: ContentRepository.Factory

    @Inject
    lateinit var pageLoader: PageLoader

    private lateinit var mode: ReaderMode
    private lateinit var imageServerDelegate: ImageServerDelegate
    private var enableTranslationAfterSetup = false

    @Inject
    lateinit var settings: AppSettings

    /**
     * 在折叠屏展开竖屏或宽屏竖向场景下，将 Reader 设置弹窗改为右侧侧栏 SideSheet。
     * 其他场景仍保持默认的底部弹窗样式。
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val conf = context.resources.configuration
        val isPortrait = conf.orientation == Configuration.ORIENTATION_PORTRAIT
        // 宽松模式沿用当前逻辑：竖屏下只要宽度达到阈值也改用侧边面板。
        val isWidePortrait = settings.tabletUiMode == TabletUiMode.RELAXED && conf.screenWidthDp >= 600

        return if (isPortrait && isWidePortrait) {
            object : SideSheetDialog(context, theme) {
                override fun onSupportActionModeStarted(mode: ActionMode?) {
                    super.onSupportActionModeStarted(mode)
                    if (mode != null) dispatchSupportActionModeStarted(mode)
                }

                override fun onSupportActionModeFinished(mode: ActionMode?) {
                    super.onSupportActionModeFinished(mode)
                    if (mode != null) dispatchSupportActionModeFinished(mode)
                }
            }
        } else {
            // 其他场景维持 BaseAdaptiveSheet 默认逻辑（平板为侧栏，手机为底部弹窗）
            super.onCreateDialog(savedInstanceState)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTranslationAfterSetup = savedInstanceState?.getBoolean(
            STATE_ENABLE_TRANSLATION_AFTER_SETUP,
        ) == true
        mode = arguments?.getInt(AppRouter.KEY_READER_MODE)
            ?.let { ReaderMode.valueOf(it) }
            ?: ReaderMode.STANDARD
        imageServerDelegate = ImageServerDelegate(
            mangaRepositoryFactory = mangaRepositoryFactory,
            mangaSource = viewModel.getContentOrNull()?.source,
        )
    }

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetReaderConfigBinding {
        return SheetReaderConfigBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(
        binding: SheetReaderConfigBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onViewBindingCreated(binding, savedInstanceState)
        observeScreenOrientation()
        binding.buttonStandard.isChecked = mode == ReaderMode.STANDARD
        binding.buttonReversed.isChecked = mode == ReaderMode.REVERSED
        binding.buttonWebtoon.isChecked = mode == ReaderMode.WEBTOON
        binding.buttonVertical.isChecked = mode == ReaderMode.VERTICAL
        binding.switchDoubleReader.isChecked = settings.isReaderDoubleOnLandscape
        binding.switchDoubleReader.isEnabled = mode == ReaderMode.STANDARD || mode == ReaderMode.REVERSED
        binding.switchDoubleFoldable.isChecked = settings.isReaderDoubleOnFoldable
        binding.switchDoubleFoldable.isEnabled = binding.switchDoubleReader.isEnabled
        binding.switchSplitPages.isChecked = settings.isReaderSplitPagesEnabled
        binding.switchSuperResolution.isChecked = settings.isReaderSuperResolutionEnabled
        bindReaderBackgroundTitle(binding)
        bindTranslationControls(binding)
        binding.sliderDoubleSensitivity.setValueRounded(settings.readerDoublePagesSensitivity * 100f)
        binding.sliderDoubleSensitivity.setLabelFormatter(IntPercentLabelFormatter(binding.root.context))
        binding.adjustSensitivitySlider(withAnimation = false)

        binding.checkableGroup.addOnButtonCheckedListener(this)
        binding.buttonSavePage.setOnClickListener(this)
        binding.buttonScreenRotate.setOnClickListener(this)
        binding.buttonSettings.setOnClickListener(this)
        binding.buttonImageServer.setOnClickListener(this)
        binding.buttonColorFilter.setOnClickListener(this)
        binding.buttonReaderBackground.setOnClickListener(this)
        binding.buttonScrollTimer.setOnClickListener(this)
        binding.buttonBookmark.setOnClickListener(this)
        binding.buttonTranslation.setOnClickListener(this)
        binding.buttonTranslationSettings.setOnClickListener(this)
        binding.switchDoubleReader.setOnCheckedChangeListener(this)
        binding.switchDoubleFoldable.setOnCheckedChangeListener(this)
        binding.switchSplitPages.setOnCheckedChangeListener(this)
        binding.switchSuperResolution.setOnCheckedChangeListener(this)
        binding.buttonRetranslate.setOnClickListener(this)
        binding.buttonTranslationLog.setOnClickListener(this)
        binding.sliderDoubleSensitivity.addOnChangeListener(this)
        binding.buttonOpenInBrowser.setOnClickListener(this)

        viewModel.isBookmarkAdded.observe(viewLifecycleOwner) {
            binding.buttonBookmark.setText(if (it) R.string.bookmark_remove else R.string.bookmark_add)
            binding.buttonBookmark.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (it) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark, 0, 0, 0,
            )
        }

        viewLifecycleScope.launch {
            val isAvailable = imageServerDelegate.isAvailable()
            if (isAvailable) {
                bindImageServerTitle()
            }
            binding.buttonImageServer.isVisible = isAvailable
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val typeMask = WindowInsetsCompat.Type.systemBars()
        viewBinding?.scrollView?.updatePadding(
            bottom = insets.getInsets(typeMask).bottom,
        )
        return insets.consume(v, typeMask, bottom = true)
    }

    override fun onResume() {
        super.onResume()
        viewBinding?.let(::bindReaderBackgroundTitle)
        if (!enableTranslationAfterSetup) {
            viewBinding?.let(::bindTranslationControls)
            return
        }
        enableTranslationAfterSetup = false
        if (!viewModel.hasTranslationEngineConfigured()) {
            viewBinding?.let(::bindTranslationControls)
            return
        }
        viewModel.getTranslationBypassHint(requireContext())?.let { hint ->
            Toast.makeText(requireContext(), hint, Toast.LENGTH_SHORT).show()
            viewBinding?.let(::bindTranslationControls)
            return
        }
        settings.isReaderTranslationEnabled = true
        settings.isReaderTranslationShowTranslated = true
        dismissAllowingStateLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ENABLE_TRANSLATION_AFTER_SETUP, enableTranslationAfterSetup)
        super.onSaveInstanceState(outState)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_settings -> {
                router.openReaderSettings()
                dismissAllowingStateLoss()
            }

            R.id.button_scroll_timer -> {
                findParentCallback(Callback::class.java)?.onScrollTimerClick(false) ?: return
                dismissAllowingStateLoss()
            }

            R.id.button_save_page -> {
                findParentCallback(Callback::class.java)?.onSavePageClick() ?: return
                dismissAllowingStateLoss()
            }

            R.id.button_screen_rotate -> {
                orientationHelper.isLandscape = !orientationHelper.isLandscape
            }

            R.id.button_bookmark -> {
                viewModel.toggleBookmark()
            }

            R.id.button_color_filter -> {
                val page = viewModel.getCurrentPage() ?: return
                val manga = viewModel.getContentOrNull() ?: return
                router.openColorFilterConfig(manga, page)
            }

            R.id.button_reader_background -> showReaderBackgroundDialog()

            R.id.button_translation -> handleTranslationAction()

            R.id.button_translation_settings -> router.openTranslationSettings()

            R.id.button_open_in_browser -> {
                val manga = viewModel.getContentOrNull() ?: return
                val chapter = viewModel.uiState.value?.chapter
                if (chapter != null) {
                    val url = kotlin.runCatching {
                        if (chapter.url.startsWith("http", ignoreCase = true)) {
                            chapter.url
                        } else if (manga.publicUrl.startsWith("http", ignoreCase = true)) {
                            // Resolve relative chapter path against manga's absolute public URL
                            java.net.URL(java.net.URL(manga.publicUrl), chapter.url).toString()
                        } else {
                            // publicUrl is not a valid HTTP URL, fall back to manga.publicUrl itself
                            null
                        }
                    }.getOrNull()
                    val resolvedUrl = url?.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: manga.publicUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                    if (resolvedUrl != null) {
                        router.openBrowser(resolvedUrl, manga.source, chapter.title)
                    } else {
                        router.openBrowser(manga)
                    }
                } else {
                    router.openBrowser(manga)
                }
                dismissAllowingStateLoss()
            }

            R.id.button_retranslate -> {
                if (settings.isReaderTranslationEnabled) {
                    showRetranslateActionDialog()
                }
            }

            R.id.button_translation_log -> {
                showTranslationTaskPanel()
            }

            R.id.button_image_server -> viewLifecycleScope.launch {
                if (imageServerDelegate.showDialog(v.context)) {
                    bindImageServerTitle()
                    pageLoader.invalidate(clearCache = true)
                    viewModel.switchChapterBy(0)
                }
            }
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        when (buttonView.id) {
            R.id.switch_screen_lock_rotation -> {
                orientationHelper.isLocked = isChecked
            }

            R.id.switch_double_reader -> {
                settings.isReaderDoubleOnLandscape = isChecked
                viewBinding?.adjustSensitivitySlider(withAnimation = true)
                findParentCallback(Callback::class.java)?.onDoubleModeChanged(isChecked)
            }

            R.id.switch_double_foldable -> {
                settings.isReaderDoubleOnFoldable = isChecked
                // Re-evaluate double-page considering foldable state and current manual toggle
                findParentCallback(Callback::class.java)?.onDoubleModeChanged(settings.isReaderDoubleOnLandscape)
            }

            R.id.switch_split_pages -> {
                settings.isReaderSplitPagesEnabled = isChecked
                findParentCallback(Callback::class.java)?.onSplitModeChanged(isChecked)
            }

            R.id.switch_super_resolution -> {
                settings.isReaderSuperResolutionEnabled = isChecked
                viewLifecycleScope.launch {
                    pageLoader.invalidate(clearCache = true)
                    viewModel.switchChapterBy(0)
                }
            }
        }
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        settings.readerDoublePagesSensitivity = value / 100f
    }

    override fun onButtonChecked(
        group: MaterialButtonToggleGroup?,
        checkedId: Int,
        isChecked: Boolean,
    ) {
        if (!isChecked) {
            return
        }
        val newMode = when (checkedId) {
            R.id.button_standard -> ReaderMode.STANDARD
            R.id.button_webtoon -> ReaderMode.WEBTOON
            R.id.button_reversed -> ReaderMode.REVERSED
            R.id.button_vertical -> ReaderMode.VERTICAL
            else -> return
        }
        viewBinding?.run {
            switchDoubleReader.isEnabled = newMode == ReaderMode.STANDARD || newMode == ReaderMode.REVERSED
            switchDoubleFoldable.isEnabled = switchDoubleReader.isEnabled
            adjustSensitivitySlider(withAnimation = true)
        }
        if (newMode == mode) {
            return
        }
        findParentCallback(Callback::class.java)?.onReaderModeChanged(newMode) ?: return
        group?.performSegmentHapticFeedback()
        mode = newMode
    }

    private fun observeScreenOrientation() {
        orientationHelper.observeAutoOrientation()
            .onEach {
                with(requireViewBinding()) {
                    buttonScreenRotate.isGone = it
                    switchScreenLockRotation.isVisible = it
                    updateOrientationLockSwitch()
                }
            }.launchIn(viewLifecycleScope)
    }

    private fun updateOrientationLockSwitch() {
        val switch = viewBinding?.switchScreenLockRotation ?: return
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = orientationHelper.isLocked
        switch.setOnCheckedChangeListener(this)
    }

    private fun updateTranslationBypassHint(binding: SheetReaderConfigBinding) {
        val hint = viewModel.getTranslationBypassHint(requireContext())
        val visible = settings.isReaderTranslationEnabled && !hint.isNullOrBlank()
        binding.textTranslationBypassHint.isVisible = visible
        if (visible) {
            binding.textTranslationBypassHint.text = hint
        }
    }

    private fun handleTranslationAction() {
        if (!viewModel.hasTranslationEngineConfigured()) {
            enableTranslationAfterSetup = true
            router.openTranslationSettings()
            return
        }
        val enabled = !settings.isReaderTranslationEnabled
        if (enabled) {
            viewModel.getTranslationBypassHint(requireContext())?.let { hint ->
                Toast.makeText(requireContext(), hint, Toast.LENGTH_SHORT).show()
                return
            }
        }
        settings.isReaderTranslationEnabled = enabled
        settings.isReaderTranslationShowTranslated = enabled
        dismissAllowingStateLoss()
    }

    private fun bindTranslationControls(binding: SheetReaderConfigBinding) {
        val configured = viewModel.hasTranslationEngineConfigured()
        val enabled = settings.isReaderTranslationEnabled
        binding.buttonTranslation.setText(
            when {
                !configured -> R.string.reader_translation_action_setup
                enabled -> R.string.reader_translation_action_disable
                else -> R.string.reader_translation_action
            },
        )
        binding.buttonTranslation.isSelected = enabled
        binding.buttonTranslationSettings.isVisible = configured
        binding.buttonRetranslate.isVisible = enabled
        binding.buttonTranslationLog.isVisible = enabled
        updateTranslationBypassHint(binding)
    }

    private fun showRetranslateActionDialog() {
        val options = arrayOf(
            getString(R.string.reader_translation_retranslate_current_page),
            getString(R.string.reader_translation_retry_failed_pages),
            getString(R.string.reader_translation_retranslate_current_chapter),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reader_translation_retranslate)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.retranslateCurrent()
                    1 -> viewModel.retranslateFailedInCurrentChapter()
                    2 -> viewModel.retranslateCurrentChapter()
                }
                dismissAllowingStateLoss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showTranslationTaskPanel() {
        TranslationTaskPanelSheet.show(parentFragmentManager)
    }

    private fun showReaderBackgroundDialog() {
        val options = ReaderBackground.entries
        val labels = resources.getStringArray(R.array.reader_backgrounds)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.background)
            .setSingleChoiceItems(labels, options.indexOf(settings.readerBackground)) { dialog, which ->
                settings.readerBackground = options.getOrNull(which) ?: return@setSingleChoiceItems
                viewBinding?.let(::bindReaderBackgroundTitle)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun bindReaderBackgroundTitle(binding: SheetReaderConfigBinding) {
        val labels = resources.getStringArray(R.array.reader_backgrounds)
        val selectedLabel = labels.getOrNull(ReaderBackground.entries.indexOf(settings.readerBackground))
            ?: getString(R.string.system_default)
        binding.buttonReaderBackground.text = getString(
            R.string.inline_preference_pattern,
            getString(R.string.background),
            selectedLabel,
        )
    }

    private suspend fun bindImageServerTitle() {
        viewBinding?.buttonImageServer?.text = getString(
            R.string.inline_preference_pattern,
            getString(R.string.image_server),
            imageServerDelegate.getValue() ?: getString(R.string.automatic),
        )
    }

    private fun SheetReaderConfigBinding.adjustSensitivitySlider(withAnimation: Boolean) {
        val isSubOptionsVisible = switchDoubleReader.isEnabled && switchDoubleReader.isChecked
        val needTransition = withAnimation && (
            (isSubOptionsVisible != sliderDoubleSensitivity.isVisible) ||
                (isSubOptionsVisible != textDoubleSensitivity.isVisible) ||
                (isSubOptionsVisible != switchDoubleFoldable.isVisible)
            )
        if (needTransition) {
            TransitionManager.beginDelayedTransition(layoutMain)
        }
        sliderDoubleSensitivity.isVisible = isSubOptionsVisible
        textDoubleSensitivity.isVisible = isSubOptionsVisible
        switchDoubleFoldable.isVisible = isSubOptionsVisible
    }

    interface Callback {

        fun onReaderModeChanged(mode: ReaderMode)

        fun onDoubleModeChanged(isEnabled: Boolean)

        fun onSplitModeChanged(isEnabled: Boolean)

        fun onSavePageClick()

        fun onScrollTimerClick(isLongClick: Boolean)

        fun onBookmarkClick()
    }
}
