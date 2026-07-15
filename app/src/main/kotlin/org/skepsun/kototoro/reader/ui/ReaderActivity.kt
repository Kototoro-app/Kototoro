package org.skepsun.kototoro.reader.ui

import android.app.assist.AssistContent
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.exceptions.resolve.DialogErrorObserver
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.ui.BaseFullscreenActivity
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.dialog.setCheckbox
import org.skepsun.kototoro.core.ui.util.MenuInvalidator
import org.skepsun.kototoro.core.ui.widgets.ZoomControl
import org.skepsun.kototoro.core.util.IdlingDetector
import org.skepsun.kototoro.core.util.ext.getThemeDimensionPixelOffset
import org.skepsun.kototoro.core.util.ext.hasGlobalPoint
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.postDelayed
import org.skepsun.kototoro.core.util.ext.performConfirmHapticFeedback
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.zipWithPrevious
import org.skepsun.kototoro.databinding.ActivityReaderBinding
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet
import org.skepsun.kototoro.details.ui.pager.pages.PagesSavedObserver
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.config.ReaderConfigSheet
import org.skepsun.kototoro.reader.domain.TranslationLayerState
import org.skepsun.kototoro.reader.translate.domain.isAutoReaderTranslationLanguage
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderUiState
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridDispatcher
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.ui.SpaceSwitcherDelegate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ReaderActivity :
    BaseFullscreenActivity<ActivityReaderBinding>(),
    TapGridDispatcher.OnGridTouchListener,
    ReaderConfigSheet.Callback,
    ReaderControlDelegate.OnInteractionListener,
    ReaderNavigationCallback,
    IdlingDetector.Callback,
    ZoomControl.ZoomControlListener,
    View.OnClickListener,
    ScrollTimerControlView.OnVisibilityChangeListener {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var tapGridSettings: TapGridSettings

    @Inject
    lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

    @Inject
    lateinit var scrollTimerFactory: ScrollTimer.Factory

    @Inject
    lateinit var screenOrientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var spaceSwitcherDelegate: SpaceSwitcherDelegate

    private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)

    private val viewModel: ReaderViewModel by viewModels()

    override val readerMode: ReaderMode?
        get() = readerManager.currentMode

    private lateinit var scrollTimer: ScrollTimer
    private lateinit var pageSaveHelper: PageSaveHelper
    private lateinit var touchHelper: TapGridDispatcher
    private lateinit var controlDelegate: ReaderControlDelegate
    private var gestureInsets: Insets = Insets.NONE
    private lateinit var readerManager: ReaderManager
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }
    private var currentTranslationLayerState: TranslationLayerState = TranslationLayerState.IDLE
    private var lastMangaTranslationProgress: ReaderViewModel.ChapterTranslationProgress? = null
    private var lastMangaTranslationToastAtMs: Long = 0L
    private var translationShortcutVisibleForSession = false
    private var enableTranslationAfterSetup = false

    // Tracks whether the foldable device is in an unfolded state (half-opened or flat)
    private var isFoldUnfolded: Boolean = false
    private var isDoubleReaderMode: Boolean = false

    private fun resetTranslationSession() {
        settings.isReaderTranslationEnabled = false
        settings.isReaderTranslationShowTranslated = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            resetTranslationSession()
        } else {
            translationShortcutVisibleForSession = savedInstanceState.getBoolean(
                STATE_TRANSLATION_SHORTCUT_VISIBLE,
            )
            enableTranslationAfterSetup = savedInstanceState.getBoolean(
                STATE_ENABLE_TRANSLATION_AFTER_SETUP,
            )
        }
        setContentView(ActivityReaderBinding.inflate(layoutInflater))
        readerManager = ReaderManager(supportFragmentManager, viewBinding.container, settings)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        touchHelper = TapGridDispatcher(viewBinding.root, this)
        scrollTimer = scrollTimerFactory.create(resources, this, this)
        pageSaveHelper = pageSaveHelperFactory.create(this)
        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        spaceSwitcherDelegate.bind(
            activity = this,
            snackbarAnchor = viewBinding.container,
            origin = SpaceSwitchOrigin.READER,
            availabilityProvider = { SpaceSwitchAvailability.SAVE_AND_SWITCH },
            progressFlusher = SpaceProgressFlusher {
                viewModel.flushForSpaceSwitch(readerManager.currentReader?.getCurrentState())
            },
            onMediaUniverseContentClick = { content -> router.openResolvedDetails(content) },
        )
        spaceSwitcherDelegate.installFab(findViewById(R.id.immersive_space_switcher_fab))
        spaceSwitcherDelegate.setControlsVisible(viewBinding.appbarTop.isVisible)
        viewBinding.zoomControl.listener = this
        viewBinding.actionsView.listener = this
        viewBinding.actionsView.setTranslateButtonVisible(viewModel.shouldShowTranslationToggle())
        viewBinding.actionsView.setTranslateButtonContextualVisible(translationShortcutVisibleForSession)
        viewBinding.buttonTimer?.setOnClickListener(this)
        idlingDetector.bindToLifecycle(this)
        screenOrientationHelper.applySettings()
        viewModel.isBookmarkAdded.observe(this) { viewBinding.actionsView.isBookmarkAdded = it }
        scrollTimer.isActive.observe(this) {
            updateScrollTimerButton()
            viewBinding.actionsView.setTimerActive(it)
        }
        viewBinding.timerControl.onVisibilityChangeListener = this
        viewBinding.timerControl.attach(scrollTimer, this)
        if (FoldableUtils.shouldUseTabletLayout(this, settings)) {
            viewBinding.timerControl.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                topMargin = marginEnd + getThemeDimensionPixelOffset(appcompatR.attr.actionBarSize)
            }
        }

        val loadingErrorDialog = DialogErrorObserver(
            host = viewBinding.container,
            fragment = null,
            resolver = exceptionResolver,
            onResolved = { isResolved ->
                if (isResolved) {
                    viewModel.reload()
                } else if (viewModel.content.value.pages.isEmpty()) {
                    dispatchNavigateUp()
                }
            },
        )
        viewModel.onLoadingError.observeEvent(this) { error ->
            val cf = error.findCloudFlareException()
            val source = cf?.source
            val autoDisabled = source != null && SourceSettings(this@ReaderActivity, source).isCaptchaAutoResolveDisabled
            if (cf is CloudFlareProtectedException && !autoDisabled) {
                val resolved = exceptionResolver.resolve(cf, tryAutoResolve = true)
                if (resolved) {
                    viewModel.reload()
                } else {
                    loadingErrorDialog.emit(error)
                }
            } else {
                loadingErrorDialog.emit(error)
            }
        }
        val errorSnackbar = SnackbarErrorObserver(
            host = viewBinding.container,
            fragment = null,
            resolver = exceptionResolver,
            onResolved = null,
        )
        viewModel.onError.observeEvent(this) { error ->
            val cf = error.findCloudFlareException()
            val source = cf?.source
            val autoDisabled = source != null &&
                SourceSettings(this@ReaderActivity, source).isCaptchaAutoResolveDisabled
            if (cf is CloudFlareProtectedException && !autoDisabled) {
                val resolved = exceptionResolver.resolve(cf, tryAutoResolve = true)
                if (resolved) {
                    viewModel.reload()
                } else {
                    errorSnackbar.emit(error)
                }
            } else {
                errorSnackbar.emit(error)
            }
        }
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(viewBinding.container))
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        combine(
            viewModel.isLoading,
            viewModel.content.map { it.pages.isNotEmpty() }.distinctUntilChanged(),
            ::Pair,
        ).flowOn(Dispatchers.Default)
            .observe(this, this::onLoadingStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarTransparent.observe(this) { viewBinding.infoBar.drawBackground = !it }
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            if (msgId == R.string.bookmark_added || msgId == R.string.bookmark_removed) {
                viewBinding.container.performConfirmHapticFeedback()
            }
            Snackbar.make(viewBinding.container, msgId, Snackbar.LENGTH_SHORT)
                .setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
        viewModel.readerSettingsProducer.observe(this) {
            viewBinding.infoBar.applyColorScheme(isBlackOnWhite = it.background.isLight(this))
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            viewBinding.zoomControl.isVisible = it
        }
        settings.observeAsFlow(AppSettings.KEY_READER_TRANSLATION_ENABLED) {
            isReaderTranslationEnabled
        }.onEach { enabled ->
            if (enabled) {
                translationShortcutVisibleForSession = true
            }
            viewBinding.actionsView.setTranslateButtonContextualVisible(translationShortcutVisibleForSession)
            updateTranslationToggleButton()
            invalidateOptionsMenu()
            viewModel.reload()
        }.launchIn(lifecycleScope)
        settings.observeAsFlow(AppSettings.KEY_READER_TRANSLATION_SHOW_TRANSLATED) {
            isReaderTranslationShowTranslated
        }.onEach {
            updateTranslationToggleButton()
            viewModel.reload()
        }.launchIn(lifecycleScope)
        viewModel.translationLayerState.onEach {
            currentTranslationLayerState = it
            updateTranslationToggleButton()
        }.launchIn(lifecycleScope)
        viewModel.chapterTranslationProgress.onEach(::onChapterTranslationProgressChanged)
            .launchIn(lifecycleScope)

        settings.observeAsFlow(AppSettings.KEY_READER_TOOLBAR_FLOATING) {
            isReaderToolbarFloating
        }.onEach { isFloating ->
            updateToolbarFloatingStyle(isFloating)
        }.launchIn(lifecycleScope)

        observeWindowLayout()

        // Apply initial double-mode considering foldable setting
        applyDoubleModeAuto()
        
        // Listen for layout changes (e.g., entering/exiting split-screen)
        viewBinding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyDoubleModeAuto()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!enableTranslationAfterSetup) {
            return
        }
        enableTranslationAfterSetup = false
        if (!viewModel.hasTranslationEngineConfigured()) {
            return
        }
        viewModel.getTranslationBypassHint(this)?.let { hint ->
            viewBinding.toastView.showTemporary(hint, 2000L)
            return
        }
        translationShortcutVisibleForSession = true
        settings.isReaderTranslationEnabled = true
        settings.isReaderTranslationShowTranslated = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_TRANSLATION_SHORTCUT_VISIBLE, translationShortcutVisibleForSession)
        outState.putBoolean(STATE_ENABLE_TRANSLATION_AFTER_SETUP, enableTranslationAfterSetup)
        super.onSaveInstanceState(outState)
    }

    override fun getParentActivityIntent(): Intent? {
        val manga = viewModel.getContentOrNull() ?: return null
        return AppRouter.detailsIntent(this, manga)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!viewBinding.timerControl.isVisible) {
            scrollTimer.onUserInteraction()
        }
        idlingDetector.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getContentOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
    }

    override fun isNsfwContent(): Flow<Boolean> = viewModel.isContentNsfw

    override fun onIdle() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.onIdle()
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        updateScrollTimerButton()
    }

    override fun onZoomIn() {
        readerManager.currentReader?.onZoomIn()
    }

    override fun onZoomOut() {
        readerManager.currentReader?.onZoomOut()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_timer -> onScrollTimerClick(isLongClick = false)
        }
    }

    private fun onInitReader(mode: ReaderMode?) {
        if (mode == null) {
            return
        }
        if (readerManager.currentMode != mode) {
            readerManager.replace(mode)
        }
        if (viewBinding.appbarTop.isVisible) {
            lifecycle.postDelayed(TimeUnit.SECONDS.toMillis(1), hideUiRunnable)
        }
        viewBinding.actionsView.setSliderReversed(mode == ReaderMode.REVERSED)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    private fun onLoadingStateChanged(value: Pair<Boolean, Boolean>) {
        val (isLoading, hasPages) = value
        val showLoadingLayout = isLoading && !hasPages
        if (viewBinding.layoutLoading.isVisible != showLoadingLayout) {
            val transition = Fade().addTarget(viewBinding.layoutLoading)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            viewBinding.layoutLoading.isVisible = showLoadingLayout
        }
        if (isLoading && hasPages) {
            viewBinding.toastView.show(R.string.loading_)
        } else {
            viewBinding.toastView.hide()
        }
        invalidateOptionsMenu()
    }

    override fun onGridTouch(area: TapGridArea): Boolean {
        return isReaderResumed() && controlDelegate.onGridTouch(area)
    }

    override fun onGridLongTouch(area: TapGridArea, event: MotionEvent) {
        if (isReaderResumed()) {
            val width = viewBinding.root.width
            val height = viewBinding.root.height
            viewModel.setTargetPageBySide(event.rawX, width, isDoubleReaderMode)

            val isMenuTrigger = if (isDoubleReaderMode && width > 0 && height > 0) {
                val x = event.rawX
                val y = event.rawY
                val inVerticalCenter = y > height * 0.25f && y < height * 0.75f
                val inLeftPageCenter = x > width * 0.125f && x < width * 0.375f
                val inRightPageCenter = x > width * 0.625f && x < width * 0.875f
                inVerticalCenter && (inLeftPageCenter || inRightPageCenter)
            } else {
                false
            }

            if (isMenuTrigger) {
                openMenu()
            } else {
                controlDelegate.onGridLongTouch(area)
            }
        }
    }

    override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
        return if (
            rawY <= gestureInsets.top ||
            rawY >= viewBinding.root.height - gestureInsets.bottom ||
            viewBinding.appbarTop.hasGlobalPoint(rawX, rawY) ||
            viewBinding.toolbarDocked?.hasGlobalPoint(rawX, rawY) == true ||
            viewBinding.zoomControl.hasGlobalPoint(rawX, rawY) ||
            viewBinding.timerControl.hasGlobalPoint(rawX, rawY) ||
            viewBinding.buttonTimer?.hasGlobalPoint(rawX, rawY) == true
        ) {
            false
        } else {
            val touchables = window.peekDecorView()?.touchables
            touchables?.none {
                it.id != R.id.ssiv &&
                    it.id != R.id.recyclerView &&
                    it.id != R.id.pager &&
                    it.id != R.id.textView_number &&
                    it.id != R.id.layout_progress &&
                    it.id != R.id.progressBar &&
                    it.id != R.id.textView_status &&
                    it.hasGlobalPoint(rawX, rawY)
            } != false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchHelper.dispatchTouchEvent(ev)
        if (!viewBinding.timerControl.hasGlobalPoint(ev.rawX.toInt(), ev.rawY.toInt())) {
            scrollTimer.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onChapterSelected(chapter: ContentChapter): Boolean {
        dismissChapterPagesSheet()
        viewModel.recordExplicitJump(ReaderState(chapter.id, 0, 0), "chapter_list")
        viewModel.switchChapter(chapter.id, 0)
        return true
    }

    override fun onPageSelected(page: ReaderPage): Boolean {
        dismissChapterPagesSheet()
        lifecycleScope.launch(Dispatchers.Default) {
            val pages = viewModel.content.value.pages
            val index = pages.indexOfFirst { it.chapterId == page.chapterId && it.id == page.id }
            if (index != -1) {
                withContext(Dispatchers.Main) {
                    viewModel.recordExplicitJump(ReaderState(page.chapterId, page.index, 0), "page")
                    readerManager.currentReader?.switchPageTo(index, true)
                }
            } else {
                viewModel.recordExplicitJump(ReaderState(page.chapterId, page.index, 0), "page")
                viewModel.switchChapter(page.chapterId, page.index)
            }
        }
        return true
    }

    private fun dismissChapterPagesSheet() {
        supportFragmentManager.findFragmentByTag(ChaptersPagesSheet::class.java.name)?.let {
            (it as? DialogFragment)?.dismiss()
        }
    }

    override fun onReaderModeChanged(mode: ReaderMode) {
        val stateBeforeSwitch = readerManager.currentReader?.getCurrentState()
        Log.d(
            LOG_TAG,
            "onReaderModeChanged: mode=$mode, currentMode=${readerManager.currentMode}, " +
                "stateBeforeSwitch=$stateBeforeSwitch, reader=${readerManager.currentReader?.javaClass?.simpleName}",
        )
        viewModel.saveCurrentState(stateBeforeSwitch)
        viewModel.switchMode(mode)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    override fun onSplitModeChanged(isEnabled: Boolean) {
        viewModel.reload()
    }

    override fun onDoubleModeChanged(isEnabled: Boolean) {
        // Combine manual toggle with foldable auto setting
        applyDoubleModeAuto(isEnabled)
    }

    private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Also enable dual-page in split-screen when aspect ratio is close to square
        // This handles foldable devices in split-screen mode
        val windowWidth = viewBinding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val windowHeight = viewBinding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val aspectRatio = if (windowHeight > 0) windowWidth.toFloat() / windowHeight else 0f
        // If aspect ratio >= 0.7 (width is at least 70% of height, i.e. close to square or wider),
        // consider it suitable for dual-page. This covers split-screen on foldables where the
        // window is not extremely narrow.
        val isNearSquareOrWider = aspectRatio >= 0.7f
        val isSuitableForDual = isNearSquareOrWider

        // Auto double-page on foldable when device is unfolded (half-opened or flat)
        val autoFoldable = settings.isReaderDoubleOnFoldable && isFoldUnfolded && isSuitableForDual
        val manualLandscape = (manualEnabled ?: settings.isReaderDoubleOnLandscape) && isLandscape && isSuitableForDual
        val autoSplitScreen = settings.isReaderDoubleOnFoldable && isSuitableForDual && !isLandscape

        val autoEnabled = autoFoldable || manualLandscape || autoSplitScreen
        Log.d(
            LOG_TAG,
            "applyDoubleModeAuto: manualEnabled=$manualEnabled, isLandscape=$isLandscape, " +
                "window=${windowWidth}x${windowHeight}, aspectRatio=$aspectRatio, " +
                "isSuitableForDual=$isSuitableForDual, isFoldUnfolded=$isFoldUnfolded, " +
                "autoFoldable=$autoFoldable, manualLandscape=$manualLandscape, " +
                "autoSplitScreen=$autoSplitScreen, autoEnabled=$autoEnabled, " +
                "currentMode=${readerManager.currentMode}, reader=${readerManager.currentReader?.javaClass?.simpleName}, " +
                "viewModelState=${viewModel.getCurrentState()}, fragmentState=${readerManager.currentReader?.getCurrentState()}",
        )
        isDoubleReaderMode = autoEnabled
        readerManager.setDoubleReaderMode(autoEnabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(
            LOG_TAG,
            "onConfigurationChanged: orientation=${newConfig.orientation}, " +
                "isDoubleReaderMode=$isDoubleReaderMode, currentMode=${readerManager.currentMode}, " +
                "reader=${readerManager.currentReader?.javaClass?.simpleName}, " +
                "viewModelState=${viewModel.getCurrentState()}, fragmentState=${readerManager.currentReader?.getCurrentState()}",
        )
        applyDoubleModeAuto()
    }


    private fun setKeepScreenOn(isKeep: Boolean) {
        if (isKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setUiIsVisible(isUiVisible: Boolean) {
        viewModel.isMenuVisible.value = isUiVisible
        if (viewBinding.appbarTop.isVisible != isUiVisible) {
            if (isAnimationsEnabled) {
                val transition = TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(Slide(Gravity.TOP).addTarget(viewBinding.appbarTop))
                    .addTransition(Fade().addTarget(viewBinding.infoBar))
                viewBinding.toolbarDocked?.let {
                    transition.addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                }
                if (!isUiVisible) {
                    spaceSwitcherDelegate.addControlsHideTransition(transition)
                }
                TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            }
            val isFullscreen = settings.isReaderFullscreenEnabled
            viewBinding.appbarTop.isVisible = isUiVisible
            viewBinding.toolbarDocked?.isVisible = isUiVisible
            viewBinding.infoBar.isGone = isUiVisible || (!viewModel.isInfoBarEnabled.value)
            viewBinding.infoBar.isTimeVisible = isFullscreen
            updateScrollTimerButton()
            updateTranslationToggleButton()
            systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
            viewBinding.root.requestApplyInsets()
        }
        spaceSwitcherDelegate.setControlsVisible(
            visible = isUiVisible,
            hideWithControlsTransition = !isUiVisible && isAnimationsEnabled,
        )
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        viewBinding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemBars.top
            rightMargin = systemBars.right
            leftMargin = systemBars.left
        }
        if (viewBinding.toolbarDocked != null) {
            val navMargin = if (isToolbarFloating) (16 * resources.displayMetrics.density).toInt() else 0
            val bottomMargin = if (isToolbarFloating) systemBars.bottom + navMargin else 0


            viewBinding.toolbarDocked?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.bottomMargin = bottomMargin
                leftMargin = if (isToolbarFloating) systemBars.left + navMargin else 0
                rightMargin = if (isToolbarFloating) systemBars.right + navMargin else 0
            }
            viewBinding.actionsView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.bottomMargin = if (isToolbarFloating) 0 else systemBars.bottom
                leftMargin = if (isToolbarFloating) 0 else systemBars.left
                rightMargin = if (isToolbarFloating) 0 else systemBars.right
            }
        }
        viewBinding.infoBar.updatePadding(
            top = systemBars.top,
        )
        val innerInsets = Insets.of(
            systemBars.left,
            if (viewBinding.appbarTop.isVisible) viewBinding.appbarTop.height else systemBars.top,
            systemBars.right,
            viewBinding.toolbarDocked?.takeIf { it.isVisible }?.height ?: systemBars.bottom,
        )
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), innerInsets)
            .build()
    }

    override fun switchPageBy(delta: Int) {
        readerManager.currentReader?.switchPageBy(delta)
    }

    override fun switchChapterBy(delta: Int) {
        Log.d(
            LOG_TAG,
            "switchChapterBy: delta=$delta, currentState=${viewModel.getCurrentState()}, " +
                "reader=${readerManager.currentReader?.javaClass?.simpleName}",
        )
        viewModel.switchChapterBy(delta)
    }

    override fun openMenu() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        val currentMode = readerManager.currentMode ?: return
        router.showReaderConfigSheet(currentMode)
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return readerManager.currentReader?.scrollBy(delta, smooth) == true
    }

    override fun toggleUiVisibility() {
        if (viewBinding.timerControl.isVisible) {
            viewBinding.timerControl.hide()
            return
        }
        if (!viewBinding.appbarTop.isVisible) {
            if (scrollTimer.isActive.value && settings.isReaderAutoscrollPauseOnUi && !scrollTimer.isManuallyPaused.value) {
                scrollTimer.setManuallyPaused(true)
                viewBinding.timerControl.show()
                return
            }
        }
        setUiIsVisible(!viewBinding.appbarTop.isVisible)
    }

    override fun isReaderResumed(): Boolean {
        val reader = readerManager.currentReader ?: return false
        return reader.isResumed && supportFragmentManager.fragments.lastOrNull() === reader
    }

    override fun onBookmarkClick() {
        viewModel.toggleBookmark()
    }

    override fun onDownloadClick() {
        viewModel.downloadCurrentChapter()
    }

    override fun onSavePageClick() {
        viewModel.saveCurrentPage(pageSaveHelper)
    }

    override fun onScrollTimerClick(isLongClick: Boolean) {
        if (isLongClick) {
            scrollTimer.setActive(!scrollTimer.isActive.value)
        } else {
            viewBinding.timerControl.showOrHide()
        }
    }

    override fun onTranslateClick() {
        toggleTranslationLayer()
    }

    override fun onTranslateLongClick(): Boolean {
        if (!viewModel.shouldShowTranslationToggle()) {
            return false
        }
        showTranslationLanguageQuickActions()
        return true
    }

    override fun toggleScreenOrientation() {
        if (screenOrientationHelper.toggleScreenOrientation()) {
            Snackbar.make(
                viewBinding.container,
                if (screenOrientationHelper.isLocked) {
                    R.string.screen_rotation_locked
                } else {
                    R.string.screen_rotation_unlocked
                },
                Snackbar.LENGTH_SHORT,
            ).setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
    }

    override fun switchPageTo(index: Int) {
        val pages = viewModel.getCurrentChapterPages()
        val page = pages?.getOrNull(index) ?: return
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return
        onPageSelected(ReaderPage(page, index, chapterId))
    }

    private fun onReaderBarChanged(isBarEnabled: Boolean) {
        viewBinding.infoBar.isVisible = isBarEnabled && viewBinding.appbarTop.isGone
    }

    private fun onUiStateChanged(pair: Pair<ReaderUiState?, ReaderUiState?>) {
        val (previous: ReaderUiState?, uiState: ReaderUiState?) = pair
        title = uiState?.mangaName ?: getString(R.string.loading_)
        viewBinding.infoBar.update(uiState)
        if (uiState == null) {
            supportActionBar?.subtitle = null
            viewBinding.actionsView.setSliderValue(0, 1)
            viewBinding.actionsView.isSliderEnabled = false
            return
        }
        val chapterTitle = uiState.getChapterTitle(resources)
        supportActionBar?.subtitle = when {
            uiState.incognito -> getString(R.string.incognito_mode)
            else -> chapterTitle
        }
        if (
            settings.isReaderChapterToastEnabled &&
            chapterTitle != previous?.getChapterTitle(resources) &&
            chapterTitle.isNotEmpty()
        ) {
            viewBinding.toastView.showTemporary(chapterTitle, TOAST_DURATION)
        }
        if (uiState.isSliderAvailable()) {
            viewBinding.actionsView.setSliderValue(
                value = uiState.currentPage,
                max = uiState.totalPages - 1,
            )
        } else {
            viewBinding.actionsView.setSliderValue(0, 1)
        }
        viewBinding.actionsView.isSliderEnabled = uiState.isSliderAvailable()
        viewBinding.actionsView.isNextEnabled = uiState.hasNextChapter()
        viewBinding.actionsView.isPrevEnabled = uiState.hasPreviousChapter()
    }

    private fun updateScrollTimerButton() {
        val button = viewBinding.buttonTimer ?: return
        val isButtonVisible = scrollTimer.isActive.value
            && settings.isReaderAutoscrollFabVisible
            && !viewBinding.appbarTop.isVisible
            && !viewBinding.timerControl.isVisible
        if (button.isVisible != isButtonVisible) {
            val transition = Fade().addTarget(button)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            button.isVisible = isButtonVisible
        }
    }

    private fun updateTranslationToggleButton() {
        val shouldShow = viewModel.shouldShowTranslationToggle()
        viewBinding.actionsView.setTranslateButtonVisible(shouldShow)
        val isShowingTranslated = settings.isReaderTranslationEnabled && settings.isReaderTranslationShowTranslated
        viewBinding.actionsView.setTranslateActive(isShowingTranslated)
        val contentDescription = when {
            currentTranslationLayerState == TranslationLayerState.GENERATING ->
                getString(R.string.reader_translation_layer_generating)

            currentTranslationLayerState == TranslationLayerState.FAILED && isShowingTranslated ->
                getString(R.string.reader_translation_layer_failed)

            isShowingTranslated ->
                getString(R.string.reader_translation_toggle_show_original)

            else ->
                getString(R.string.reader_translation_toggle_show_translated)
        }
        viewBinding.actionsView.setTranslateButtonContentDescription(contentDescription)
    }

    private fun toggleTranslationLayer() {
        if (!settings.isReaderTranslationEnabled) {
            if (!viewModel.hasTranslationEngineConfigured()) {
                enableTranslationAfterSetup = true
                router.openTranslationSettings()
                return
            }
            viewModel.getTranslationBypassHint(this)?.let { hint ->
                viewBinding.toastView.showTemporary(hint, 2000L)
                return
            }
            translationShortcutVisibleForSession = true
            settings.isReaderTranslationEnabled = true
            settings.isReaderTranslationShowTranslated = true
            viewBinding.toastView.showTemporary(
                getString(R.string.reader_translation_mode_switched_translated),
                1500L,
            )
            return
        }
        settings.isReaderTranslationShowTranslated = false
        settings.isReaderTranslationEnabled = false
        viewBinding.toastView.showTemporary(
            getString(R.string.reader_translation_mode_switched_original),
            1500L,
        )
    }

    private fun onChapterTranslationProgressChanged(progress: ReaderViewModel.ChapterTranslationProgress?) {
        if (progress == null) {
            lastMangaTranslationProgress = null
            return
        }
        val previous = lastMangaTranslationProgress
        lastMangaTranslationProgress = progress
        if (!settings.isReaderTranslationEnabled) {
            return
        }
        val now = System.currentTimeMillis()
        val shouldShow = when {
            previous == null || previous.chapterId != progress.chapterId -> true
            progress.isFinished && progress != previous -> true
            progress.failedCount != previous.failedCount -> true
            progress.readyCount != previous.readyCount ->
                now - lastMangaTranslationToastAtMs >= TRANSLATION_PROGRESS_MIN_INTERVAL_MS

            previous.runningCount == 0 && progress.runningCount > 0 -> true
            else -> false
        }
        if (!shouldShow) {
            return
        }
        lastMangaTranslationToastAtMs = now
        val message = when {
            progress.isFinished && progress.failedCount > 0 -> getString(
                R.string.reader_translation_progress_complete_partial,
                progress.readyCount,
                progress.failedCount,
            )

            progress.isFinished -> getString(
                R.string.reader_translation_progress_complete,
                progress.readyCount,
                progress.totalCount,
            )

            progress.readyCount == 0 && progress.failedCount == 0 -> getString(
                R.string.reader_translation_progress_started,
                progress.readyCount,
                progress.totalCount,
            )

            else -> getString(
                R.string.reader_translation_progress_update,
                progress.readyCount,
                progress.totalCount,
            )
        }
        viewBinding.toastView.showTemporary(message, TOAST_DURATION)
    }

    private fun showTranslationLanguageQuickActions() {
        val actions = arrayOf(
            getString(R.string.reader_translation_quick_change_source),
            getString(R.string.reader_translation_quick_change_target),
            getString(R.string.reader_translation_quick_swap_languages),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reader_translation_quick_actions)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showTranslationLanguagePicker(
                        titleRes = R.string.reader_translation_source_lang,
                        entriesRes = R.array.reader_translation_source_languages,
                        valuesRes = R.array.values_reader_translation_source_languages,
                        currentValue = settings.readerTranslationSourceLanguage,
                    ) { selected ->
                        settings.readerTranslationSourceLanguage = selected
                        showTranslationLanguageChangedMessage(
                            R.string.reader_translation_source_lang_updated,
                            selected,
                            isSource = true,
                        )
                    }
                    1 -> showTranslationLanguagePicker(
                        titleRes = R.string.reader_translation_target_lang,
                        entriesRes = R.array.reader_translation_target_languages,
                        valuesRes = R.array.values_reader_translation_target_languages,
                        currentValue = settings.readerTranslationTargetLanguage,
                    ) { selected ->
                        settings.readerTranslationTargetLanguage = selected
                        showTranslationLanguageChangedMessage(
                            R.string.reader_translation_target_lang_updated,
                            selected,
                            isSource = false,
                        )
                    }
                    2 -> swapTranslationLanguages()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            resetTranslationSession()
        }
        super.onDestroy()
    }

    private fun showTranslationLanguagePicker(
        titleRes: Int,
        entriesRes: Int,
        valuesRes: Int,
        currentValue: String,
        onSelected: (String) -> Unit,
    ) {
        val labels = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes)
        val selectedIndex = values.indexOf(currentValue).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun swapTranslationLanguages() {
        val source = settings.readerTranslationSourceLanguage
        val target = settings.readerTranslationTargetLanguage
        if (isAutoReaderTranslationLanguage(source)) {
            Snackbar.make(
                viewBinding.container,
                R.string.reader_translation_swap_auto_unsupported,
                Snackbar.LENGTH_SHORT,
            ).setAnchorView(viewBinding.toolbarDocked).show()
            return
        }
        settings.readerTranslationSourceLanguage = target
        settings.readerTranslationTargetLanguage = source
        Snackbar.make(
            viewBinding.container,
            getString(
                R.string.reader_translation_languages_swapped,
                displayTranslationLanguage(target, isSource = true),
                displayTranslationLanguage(source, isSource = false),
            ),
            Snackbar.LENGTH_SHORT,
        ).setAnchorView(viewBinding.toolbarDocked).show()
    }

    private fun showTranslationLanguageChangedMessage(messageRes: Int, value: String, isSource: Boolean) {
        Snackbar.make(
            viewBinding.container,
            getString(messageRes, displayTranslationLanguage(value, isSource)),
            Snackbar.LENGTH_SHORT,
        ).setAnchorView(viewBinding.toolbarDocked).show()
    }

    private fun displayTranslationLanguage(value: String, isSource: Boolean): String {
        val valuesRes = if (isSource) {
            R.array.values_reader_translation_source_languages
        } else {
            R.array.values_reader_translation_target_languages
        }
        val entriesRes = if (isSource) {
            R.array.reader_translation_source_languages
        } else {
            R.array.reader_translation_target_languages
        }
        val values = resources.getStringArray(valuesRes)
        val labels = resources.getStringArray(entriesRes)
        val index = values.indexOf(value)
        return if (index in labels.indices) labels[index] else value
    }

    private var isToolbarFloating = false
    
    private fun updateToolbarFloatingStyle(isFloating: Boolean) {
        if (isToolbarFloating == isFloating) return
        isToolbarFloating = isFloating
        val toolbar = viewBinding.toolbarDocked ?: return
        val radius = if (isFloating) 24 * resources.displayMetrics.density else 0f
        
        if (toolbar is com.google.android.material.card.MaterialCardView) {
            toolbar.radius = radius
        } else {
            val bg = toolbar.background
            if (bg is com.google.android.material.shape.MaterialShapeDrawable) {
                bg.shapeAppearanceModel = bg.shapeAppearanceModel.toBuilder().setAllCornerSizes(radius).build()
            }
            toolbar.clipToOutline = isFloating
        }
        
        val appbarTop = viewBinding.appbarTop
        val hazeOpacityPercent = settings.hazeOpacityPercent
        val isGlassEffectEnabled = settings.isGlassEffectEnabled
        val handleBgColor = { targetView: View ->
            if (targetView.background is com.google.android.material.shape.MaterialShapeDrawable) {
                val bg = targetView.background as com.google.android.material.shape.MaterialShapeDrawable
                val baseColor = com.google.android.material.color.MaterialColors.getColor(targetView, com.google.android.material.R.attr.colorSurfaceContainer)
                if (isFloating && isGlassEffectEnabled) {
                    val alphaVal = ((hazeOpacityPercent / 100f) * 255).toInt().coerceIn(30, 255)
                    bg.fillColor = android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, alphaVal))
                } else {
                    val alphaVal = if (isFloating) 248 else 255
                    bg.fillColor = android.content.res.ColorStateList.valueOf(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, alphaVal)
                    )
                }
            }
        }
        handleBgColor(toolbar)
        handleBgColor(viewBinding.appbarTop)
        
        viewBinding.root.requestApplyInsets()
    }

    // Observe foldable window layout to auto-enable double-page if configured
    private fun observeWindowLayout() {
        WindowInfoTracker.getOrCreate(this)
            .windowLayoutInfo(this)
            .onEach { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val unfolded = when (fold?.state) {
                    FoldingFeature.State.HALF_OPENED, FoldingFeature.State.FLAT -> true
                    else -> false
                }
                if (unfolded != isFoldUnfolded) {
                    isFoldUnfolded = unfolded
                    applyDoubleModeAuto()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun askForIncognitoMode() {
        buildAlertDialog(this, isCentered = true) {
            var dontAskAgain = false
            val listener = DialogInterface.OnClickListener { _, which ->
                if (which == DialogInterface.BUTTON_NEUTRAL) {
                    finishAfterTransition()
                } else {
                    viewModel.setIncognitoMode(which == DialogInterface.BUTTON_POSITIVE, dontAskAgain)
                }
            }
            setCheckbox(R.string.dont_ask_again, dontAskAgain) { _, isChecked ->
                dontAskAgain = isChecked
            }
            setIcon(R.drawable.ic_incognito)
            setTitle(R.string.incognito_mode)
            setMessage(R.string.incognito_mode_hint_nsfw)
            setPositiveButton(R.string.incognito, listener)
            setNegativeButton(R.string.disable, listener)
            setNeutralButton(android.R.string.cancel, listener)
            setOnCancelListener { finishAfterTransition() }
            setCancelable(true)
        }.show()
    }

    companion object {

        private const val LOG_TAG = "ReaderDebug"
        private const val TOAST_DURATION = 2000L
        private const val TRANSLATION_PROGRESS_MIN_INTERVAL_MS = 800L
        private const val STATE_TRANSLATION_SHORTCUT_VISIBLE = "translation_shortcut_visible"
        private const val STATE_ENABLE_TRANSLATION_AFTER_SETUP = "enable_translation_after_setup"
    }
}
