package org.skepsun.kototoro.reader.ui

import android.app.assist.AssistContent
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
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
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.util.SystemUiController
import org.skepsun.kototoro.core.ui.util.MenuInvalidator
import org.skepsun.kototoro.core.util.IdlingDetector
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.ext.isAnimationsEnabled
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.postDelayed
import org.skepsun.kototoro.core.util.ext.performConfirmHapticFeedback
import org.skepsun.kototoro.core.util.ext.performSegmentHapticFeedback
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.zipWithPrevious
import org.skepsun.kototoro.details.ui.compose.ChaptersPagesTabsContent
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_BOOKMARKS
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_PAGES
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.config.ImageServerDelegate
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderController
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderChromeCallbacks
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsCallbacks
import org.skepsun.kototoro.reader.ui.compose.ReaderChapterPanelCallbacks
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderOptionsState
import org.skepsun.kototoro.reader.ui.compose.ReaderAutoScrollCallbacks
import org.skepsun.kototoro.reader.ui.compose.DefaultComposeReaderImagePipeline
import org.skepsun.kototoro.reader.domain.TranslationLayerState
import org.skepsun.kototoro.reader.translate.domain.isAutoReaderTranslationLanguage
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderUiState
import org.skepsun.kototoro.space.domain.SpaceProgressFlusher
import org.skepsun.kototoro.space.domain.SpaceSwitchAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchOrigin
import org.skepsun.kototoro.space.ui.SpaceSwitcherDelegate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ReaderActivity :
    BaseComposeActivity(),
    ReaderControlDelegate.OnInteractionListener,
    ReaderNavigationCallback,
    IdlingDetector.Callback,
    ReaderErrorHost {

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

    @Inject
    lateinit var composeReaderImagePipeline: DefaultComposeReaderImagePipeline

    @Inject
    lateinit var mangaRepositoryFactory: ContentRepository.Factory

    @Inject
    lateinit var contentDataRepository: ContentDataRepository

    private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)

    private val viewModel: ReaderViewModel by viewModels()
    private val pagesViewModel: PagesViewModel by viewModels()
    private val bookmarksViewModel: BookmarksViewModel by viewModels()

    override val readerMode: ReaderMode?
        get() = composeReaderController.readerMode

    private lateinit var scrollTimer: ScrollTimer
    private lateinit var pageSaveHelper: PageSaveHelper
    private lateinit var controlDelegate: ReaderControlDelegate
    private lateinit var composeReaderController: ComposeReaderController
	private lateinit var systemUiController: SystemUiController
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }
    private var currentTranslationLayerState: TranslationLayerState = TranslationLayerState.IDLE
    private var lastMangaTranslationProgress: ReaderViewModel.ChapterTranslationProgress? = null
    private var lastMangaTranslationToastAtMs: Long = 0L
    private var translationShortcutVisibleForSession = false
    private var enableTranslationAfterSetup = false
    private var composeSliderValue = 0
	private var areControlsVisible = true
	private var loadingError by mutableStateOf<Throwable?>(null)
	private var pendingIncognitoDialog by mutableStateOf<IncognitoDialogState?>(null)

    // Tracks whether the foldable device is in an unfolded state (half-opened or flat)
    private var isFoldUnfolded: Boolean = false
    private var isDoubleReaderMode: Boolean = false

    private fun resetTranslationSession() {
        settings.isReaderTranslationEnabled = false
        settings.isReaderTranslationShowTranslated = false
    }

    override fun showReaderErrorDetails(error: Throwable, url: String?) {
        exceptionResolver.showErrorDetails(error, url)
    }

    override fun resolveReaderError(error: Throwable, retry: () -> Unit) {
        lifecycleScope.launch {
            if (ExceptionResolver.canResolve(error)) exceptionResolver.resolve(error)
            retry()
        }
    }

    override fun getReaderErrorActionStringId(error: Throwable): Int {
        return exceptionResolver.getResolveStringId(error)
    }

    private fun dismissLoadingError() {
        loadingError = null
        if (viewModel.content.value.pages.isEmpty()) {
            dispatchNavigateUp()
        }
    }

    private fun resolveLoadingError(error: Throwable) {
        lifecycleScope.launch {
            val resolved = exceptionResolver.resolve(error, tryAutoResolve = false)
            loadingError = null
            if (resolved) {
                viewModel.reload()
            } else if (viewModel.content.value.pages.isEmpty()) {
                dispatchNavigateUp()
            }
        }
    }

    private fun checkAndRedirectMedia(intent: Intent): Boolean {
        val parcelable = intent.getParcelableExtraCompat<ParcelableContent>(AppRouter.KEY_MANGA)
        var manga: Content? = parcelable?.manga
        if (manga == null) {
            val contentIntent = ContentIntent(intent)
            val mangaId = contentIntent.mangaId
            if (mangaId != ContentIntent.ID_NONE) {
                manga = runBlocking(Dispatchers.IO) {
                    contentDataRepository.findDisplayContentById(mangaId, withChapters = false)
                        ?: contentDataRepository.findContentById(mangaId, withChapters = false)
                }
            }
        }
        if (manga != null) {
            val source = manga.source.unwrap()
            val contentType = if (manga.looksLikeLocalVideoContent()) {
                ContentType.VIDEO
            } else {
                source.getContentType()
            }
            if (contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL ||
                contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO
            ) {
                val state = intent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)
                AppRouter(this).openReader(
                    ReaderIntent.Builder(this)
                        .manga(manga)
                        .state(state)
                        .build(),
                )
                finish()
                return true
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkAndRedirectMedia(intent)) {
            return
        }
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
        composeReaderController = ComposeReaderController(
            lifecycleOwner = this,
            viewModel = viewModel,
            imagePipeline = composeReaderImagePipeline,
            errorHost = this,
			chaptersPanelContent = { selectedTabId, panelState, onSelectionStateChange ->
				ChaptersPagesTabsContent(
                    viewModel = viewModel,
                    pagesViewModel = pagesViewModel,
                    bookmarksViewModel = bookmarksViewModel,
                    settings = settings,
					appRouter = router,
					pageSaveHelper = pageSaveHelper,
					selectedTabId = selectedTabId,
					showTabStrip = false,
					isSheetFullyExpanded = true,
					isChapterListScrollEnabled = true,
					chapterQuery = panelState.searchQuery,
					isChapterSearchVisible = panelState.searchVisible,
					onChapterQueryChange = { query -> viewModel.performChapterSearch(query) },
					onChapterSelectionStateChange = onSelectionStateChange,
					onSelectedTabIdChange = composeReaderController::selectChaptersTab,
				)
            },
            chromeCallbacks = ComposeReaderChromeCallbacks(
                onNavigateBack = { dispatchNavigateUp() },
                actions = ReaderActionsCallbacks(
                    onPreviousChapter = { switchChapterBy(-1) },
                    onNextChapter = { switchChapterBy(1) },
                    onSavePage = ::onSavePageClick,
                    onTimer = ::onScrollTimerClick,
                    onPages = {
                        if (!onPagesButtonClick()) composeReaderController.toggleChapters()
                    },
                    onPagesLongClick = ::onPagesButtonLongClick,
                    onScreenRotation = ::toggleScreenOrientation,
                    onBookmark = ::onBookmarkClick,
                    onBookmarkLongClick = {
                        composeReaderController.toggleChapters(DETAILS_TAB_BOOKMARKS)
                    },
                    onDownload = ::onDownloadClick,
                    onTranslate = ::onTranslateClick,
                    onTranslateLongClick = { onTranslateLongClick() },
                    onOptions = {
                        if (!composeReaderController.closeOptions()) openMenu()
                    },
                    onOptionsLongClick = router::openReaderSettings,
						onSliderValueChanged = { value ->
							val page = value.toInt()
							if (page != composeSliderValue) {
								window.decorView.performSegmentHapticFeedback()
								composeSliderValue = page
								composeReaderController.updateActions { copy(sliderValue = value) }
							}
						},
						onSliderValueChangeFinished = { switchPageTo(composeSliderValue) },
                ),
                autoScroll = ReaderAutoScrollCallbacks(
                    onOpen = {
                        setUiIsVisible(true)
                        composeReaderController.showAutoScroll()
                    },
                    onClose = { composeReaderController.updateAutoScroll { copy(visible = false) } },
                    onActiveChanged = {
                        scrollTimer.setActive(it)
                        composeReaderController.updateAutoScroll { copy(active = it) }
                    },
                    onPausedChanged = {
                        scrollTimer.setManuallyPaused(it)
                        composeReaderController.updateAutoScroll { copy(manuallyPaused = it) }
                    },
                    onSpeedChanged = {
                        settings.readerAutoscrollSpeed = it
                        composeReaderController.updateAutoScroll { copy(speed = it) }
                    },
                    onFabChanged = {
                        settings.isReaderAutoscrollFabVisible = it
                        composeReaderController.updateAutoScroll { copy(fabVisible = it) }
                    },
                    onPauseOnUiChanged = {
                        settings.isReaderAutoscrollPauseOnUi = it
                        composeReaderController.updateAutoScroll { copy(pauseOnUi = it) }
                    },
                ),
				options = ComposeReaderOptionsCallbacks(
					onModeChanged = { mode ->
						composeReaderController.updateOptions { copy(mode = mode) }
						onReaderModeChanged(mode)
					},
					onAnimationChanged = { animation ->
						settings.readerAnimation = animation
						composeReaderController.updateOptions { copy(animation = animation) }
					},
					onDoublePageChanged = { enabled ->
						settings.isReaderDoubleOnLandscape = enabled
						composeReaderController.updateOptions { copy(doublePage = enabled) }
						onDoubleModeChanged(enabled)
					},
					onDoublePageFoldableChanged = { enabled ->
						settings.isReaderDoubleOnFoldable = enabled
						composeReaderController.updateOptions { copy(doublePageFoldable = enabled) }
						onDoubleModeChanged(settings.isReaderDoubleOnLandscape)
					},
					onDoublePageCoverChanged = { enabled ->
						settings.isReaderDoubleCoverPage = enabled
						composeReaderController.updateOptions { copy(doublePageCover = enabled) }
					},
					onSplitPagesChanged = { enabled ->
						settings.isReaderSplitPagesEnabled = enabled
						composeReaderController.updateOptions { copy(splitPages = enabled) }
						onSplitModeChanged(enabled)
					},
					onDoublePageSensitivityChanged = { value ->
						settings.readerDoublePagesSensitivity = value
						composeReaderController.updateOptions { copy(doublePageSensitivity = value) }
					},
					onSuperResolutionChanged = { enabled ->
						settings.isReaderSuperResolutionEnabled = enabled
						composeReaderController.updateOptions { copy(superResolution = enabled) }
						viewModel.reload()
						composeReaderController.refreshAppearancePreview()
					},
					onBackgroundChanged = { background ->
						settings.readerBackground = background
						composeReaderController.updateOptions { copy(background = background) }
					},
					onImageServerChanged = ::updateImageServer,
					onSavePage = ::onSavePageClick,
					onPreviousChapter = { switchChapterBy(-1) },
					onNextChapter = { switchChapterBy(1) },
					onPages = { composeReaderController.toggleChapters() },
					onBookmark = ::onBookmarkClick,
					onDownload = ::onDownloadClick,
					onRotate = ::toggleScreenOrientation,
					onAutoScroll = { onScrollTimerClick(false) },
					onTranslation = ::onTranslateClick,
					onOpenSettings = router::openReaderSettings,
					onColorFilterChanged = { colorFilter ->
						composeReaderController.updateOptions { copy(colorFilter = colorFilter) }
					},
					onImageScalingQualityChanged = { quality ->
						settings.readerImageScalingQuality = quality
						composeReaderController.updateOptions { copy(imageScalingQuality = quality) }
					},
					onSaveColorFilterForManga = { colorFilter ->
						val manga = viewModel.getContentOrNull()
						if (manga != null) {
							lifecycleScope.launch {
								contentDataRepository.saveColorFilter(manga, colorFilter)
								viewModel.reload()
							}
						}
					},
					onSaveColorFilterGlobally = { colorFilter ->
						lifecycleScope.launch {
							settings.readerColorFilter = colorFilter
							contentDataRepository.resetColorFilters()
							viewModel.reload()
						}
					},
					onOpenBrowser = ::openCurrentChapterInBrowser,
					onTranslationSettings = router::openTranslationSettings,
					onRetranslatePage = viewModel::retranslateCurrent,
					onRetryFailedTranslations = viewModel::retranslateFailedInCurrentChapter,
					onRetranslateChapter = viewModel::retranslateCurrentChapter,
				),
				chapterPanel = ReaderChapterPanelCallbacks(
					onTabSelected = { tabId -> composeReaderController.selectChaptersTab(tabId) },
					onSearchToggle = { composeReaderController.toggleChapterSearch() },
					onSearchQueryChange = { query -> viewModel.performChapterSearch(query) },
					onToggleChaptersReversed = {
					viewModel.setChaptersReversed(!viewModel.isChaptersReversed.value)
				},
					onToggleChaptersGrid = {
					viewModel.setChaptersInGridView(!viewModel.isChaptersInGridView.value)
				},
					onToggleHideReadChapters = {
					viewModel.setHideReadChapters(!viewModel.isHideReadChapters.value)
				},
					onToggleMergeRepeatedChapters = {
					viewModel.setMergeRepeatedChapters(!viewModel.isMergeRepeatedChapters.value)
				},
					onToggleDownloadedOnly = {
					viewModel.isDownloadedOnly.value = !viewModel.isDownloadedOnly.value
					},
				),
				onReaderInteraction = { scrollTimer.onUserInteraction() },
				onGridTap = { area ->
					if (composeReaderController.closeChrome()) {
						setUiIsVisible(false)
					} else {
						onGridTouch(area)
					}
				},
				onGridLongTap = ::onGridLongTouch,
				onBackPressed = ::onReaderBackPressed,
				onPrimaryDestination = { destination ->
					when (destination) {
						org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.NAVIGATION -> {
							if (!onPagesButtonClick()) composeReaderController.toggleChapters()
						}
						org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.DISPLAY -> openMenu()
						org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.TOOLS -> composeReaderController.showTools()
						org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.TRANSLATION -> onTranslateClick()
						org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.CHAPTERS_PANEL -> {
							composeReaderController.toggleChapters()
						}
					}
				},
				onPrimaryDestinationLongPress = { destination ->
					if (destination == org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination.TRANSLATION) {
						onTranslateLongClick()
					}
				},
            ),
        )
        composeReaderController.updateActions {
            copy(
                controls = settings.readerControls,
                pagesMode = settings.defaultDetailsTab == DETAILS_TAB_PAGES,
                translateRequestedVisible = viewModel.shouldShowTranslationToggle(),
                translateContextualVisible = translationShortcutVisibleForSession,
            )
        }
		viewModel.readerControls
			.onEach { controls -> composeReaderController.updateActions { copy(controls = controls) } }
			.launchIn(lifecycleScope)
		viewModel.chapterQuery
			.onEach { query -> composeReaderController.updateChapterPanel { copy(searchQuery = query) } }
			.launchIn(lifecycleScope)
		viewModel.emptyReason
			.onEach { reason -> composeReaderController.updateChapterPanel { copy(searchEnabled = reason == null) } }
			.launchIn(lifecycleScope)
		viewModel.isChaptersReversed
			.onEach { value -> composeReaderController.updateChapterPanel { copy(chaptersReversed = value) } }
			.launchIn(lifecycleScope)
		viewModel.isChaptersInGridView
			.onEach { value -> composeReaderController.updateChapterPanel { copy(chaptersInGridView = value) } }
			.launchIn(lifecycleScope)
		viewModel.isHideReadChapters
			.onEach { value -> composeReaderController.updateChapterPanel { copy(hideReadChapters = value) } }
			.launchIn(lifecycleScope)
		viewModel.isMergeRepeatedChapters
			.onEach { value -> composeReaderController.updateChapterPanel { copy(mergeRepeatedChapters = value) } }
			.launchIn(lifecycleScope)
		viewModel.showMergeRepeatedChapters
			.onEach { value -> composeReaderController.updateChapterPanel { copy(showMergeRepeatedChapters = value) } }
			.launchIn(lifecycleScope)
		viewModel.isDownloadedOnly
			.onEach { value -> composeReaderController.updateChapterPanel { copy(downloadedOnly = value) } }
			.launchIn(lifecycleScope)
		viewModel.mangaDetails
			.onEach { details ->
				composeReaderController.updateChapterPanel {
					copy(downloadedFilterVisible = details?.local != null)
				}
			}
			.launchIn(lifecycleScope)
		composeReaderController.updateAutoScroll {
			copy(
				speed = settings.readerAutoscrollSpeed,
				fabVisible = settings.isReaderAutoscrollFabVisible,
				pauseOnUi = settings.isReaderAutoscrollPauseOnUi,
			)
		}
		composeReaderController.setChromeEnabled(true)
		systemUiController = SystemUiController(window)
		systemUiController.setSystemUiVisible(true)
        scrollTimer = scrollTimerFactory.create(resources, this, this)
        pageSaveHelper = pageSaveHelperFactory.create(this)
        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        spaceSwitcherDelegate.bind(
            activity = this,
            snackbarAnchor = window.decorView,
            origin = SpaceSwitchOrigin.READER,
            availabilityProvider = { SpaceSwitchAvailability.SAVE_AND_SWITCH },
            progressFlusher = SpaceProgressFlusher {
                viewModel.flushForSpaceSwitch(composeReaderController.getCurrentState())
            },
        )
        spaceSwitcherDelegate.setControlsVisible(areControlsVisible)
		setComposeContent {
			val showControlLabels by settings.observeAsState(AppSettings.KEY_READER_CONTROL_LABELS) {
				isReaderControlLabelsEnabled
			}
			Box(modifier = Modifier.fillMaxSize()) {
				composeReaderController.Content(showControlLabels = showControlLabels)
				spaceSwitcherDelegate.Fab(
					modifier = Modifier.fillMaxSize(),
				)
				loadingError?.let { error ->
					ReaderLoadingErrorDialog(
						message = error.getDisplayMessage(resources),
						resolveActionStringId = exceptionResolver.getResolveStringId(error),
						onDismiss = ::dismissLoadingError,
						onResolve = { resolveLoadingError(error) },
					)
				}
				pendingIncognitoDialog?.let { dialog ->
					IncognitoModeDialog(
						dontAskAgain = dialog.dontAskAgain,
						onDismissRequest = ::dismissIncognitoModeDialog,
						onDontAskAgainChange = { checked ->
							pendingIncognitoDialog = dialog.copy(dontAskAgain = checked)
						},
						onIncognitoModeSelected = { consumeIncognitoMode(true) },
						onDisabledSelected = { consumeIncognitoMode(false) },
					)
				}
				spaceSwitcherDelegate.Overlays()
			}
		}
        idlingDetector.bindToLifecycle(this)
        screenOrientationHelper.applySettings()
        viewModel.isBookmarkAdded.observe(this) {
            composeReaderController.updateActions { copy(bookmarkAdded = it) }
        }
        scrollTimer.isActive.observe(this) {
            composeReaderController.updateActions { copy(timerActive = it) }
            composeReaderController.updateAutoScroll { copy(active = it) }
        }
        scrollTimer.isManuallyPaused.observe(this) {
            composeReaderController.updateAutoScroll { copy(manuallyPaused = it) }
        }
		viewModel.onLoadingError.observeEvent(this) { error ->
            val cf = error.findCloudFlareException()
            val source = cf?.source
            val autoDisabled = source != null && SourceSettings(this@ReaderActivity, source).isCaptchaAutoResolveDisabled
            if (cf is CloudFlareProtectedException && !autoDisabled) {
                val resolved = exceptionResolver.resolve(cf, tryAutoResolve = true)
                if (resolved) {
                    viewModel.reload()
                } else {
					loadingError = error
				}
			} else {
				loadingError = error
			}
		}
        val errorSnackbar = SnackbarErrorObserver(
            host = window.decorView,
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
        viewModel.onRedirectToReader.observeEvent(this) { manga ->
            val state = intent.getParcelableExtraCompat<ReaderState>(ReaderIntent.EXTRA_STATE)
            AppRouter(this).openReader(
                ReaderIntent.Builder(this)
                    .manga(manga)
                    .state(state)
                    .build(),
            )
            finish()
        }
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        viewModel.onPageSaved.observeEvent(this) { pages ->
			val message = when (pages.size) {
				0 -> R.string.nothing_found
				1 -> R.string.page_saved
				else -> R.string.pages_saved
			}
			val page = pages.singleOrNull()
			composeReaderController.showMessage(
				text = getString(message),
				actionLabel = page?.let { getString(R.string.share) },
				onAction = page?.let { uri -> { ShareHelper(this).shareImage(uri) } },
			)
		}
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        combine(
            viewModel.isLoading,
            viewModel.content.map { it.pages.isNotEmpty() }.distinctUntilChanged(),
            ::Pair,
        ).flowOn(Dispatchers.Default)
            .observe(this, this::onLoadingStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarTransparent.observe(this) {
            composeReaderController.updateInfoBar { copy(drawBackground = !it) }
        }
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            if (msgId == R.string.bookmark_added || msgId == R.string.bookmark_removed) {
                window.decorView.performConfirmHapticFeedback()
            }
			composeReaderController.showMessage(getString(msgId), TOAST_DURATION)
        }
        viewModel.readerSettingsProducer.observe(this) {
            composeReaderController.updateInfoBar {
                copy(darkContent = it.background.isLight(this@ReaderActivity))
            }
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            composeReaderController.setZoomVisible(it)
        }
        settings.observeAsFlow(AppSettings.KEY_READER_TRANSLATION_ENABLED) {
            isReaderTranslationEnabled
        }.onEach { enabled ->
            if (enabled) {
                translationShortcutVisibleForSession = true
            }
            composeReaderController.updateActions {
                copy(translateContextualVisible = translationShortcutVisibleForSession)
            }
            updateTranslationToggleButton()
            invalidateOptionsMenu()
            viewModel.refreshTranslationDisplay()
        }.launchIn(lifecycleScope)
        settings.observeAsFlow(AppSettings.KEY_READER_TRANSLATION_SHOW_TRANSLATED) {
            isReaderTranslationShowTranslated
        }.onEach {
            updateTranslationToggleButton()
            viewModel.refreshTranslationDisplay()
        }.launchIn(lifecycleScope)
        viewModel.translationLayerState.onEach {
            currentTranslationLayerState = it
            updateTranslationToggleButton()
        }.launchIn(lifecycleScope)
        viewModel.chapterTranslationProgress.onEach(::onChapterTranslationProgressChanged)
            .launchIn(lifecycleScope)

        observeWindowLayout()

        // Apply initial double-mode considering foldable setting
        applyDoubleModeAuto()
        
        // Listen for layout changes (e.g., entering/exiting split-screen)
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
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
            composeReaderController.showMessage(hint, 2000L)
            return
        }
        translationShortcutVisibleForSession = true
        settings.isReaderTranslationEnabled = true
        settings.isReaderTranslationShowTranslated = true
        composeReaderController.updateActions {
            copy(translateContextualVisible = true)
        }
        updateTranslationToggleButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::composeReaderController.isInitialized) {
            val currentState = composeReaderController.getCurrentState()
            viewModel.beginTransientStateSuppression(currentState)
            viewModel.saveCurrentState(currentState)
        }
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
        scrollTimer.onUserInteraction()
        idlingDetector.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentState(composeReaderController.getCurrentState())
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
        viewModel.saveCurrentState(composeReaderController.getCurrentState())
        viewModel.onIdle()
    }

    private fun onInitReader(mode: ReaderMode?) {
        if (mode == null) {
            return
        }
        if (composeReaderController.readerMode != mode) {
            composeReaderController.updateConfiguration(mode, isDoubleReaderMode)
        }
        if (areControlsVisible) {
            lifecycle.postDelayed(TimeUnit.SECONDS.toMillis(1), hideUiRunnable)
        }
        composeReaderController.updateActions { copy(sliderReversed = mode == ReaderMode.REVERSED) }
    }

    private fun onLoadingStateChanged(value: Pair<Boolean, Boolean>) {
        val (isLoading, hasPages) = value
        val showLoadingLayout = isLoading && !hasPages
        composeReaderController.setLoadingVisible(showLoadingLayout)
        if (isLoading && hasPages) {
			composeReaderController.hideMessage()
        }
        invalidateOptionsMenu()
    }

	private fun onGridTouch(area: TapGridArea) {
		if (isReaderResumed()) controlDelegate.onGridTouch(area)
	}

	private fun onReaderBackPressed() {
		if (composeReaderController.closeExpandedPanel()) return
		if (composeReaderController.isChromeControlsVisible) {
			setUiIsVisible(false)
		} else {
			dispatchNavigateUp()
		}
	}

    private fun onGridLongTouch(area: TapGridArea, position: androidx.compose.ui.geometry.Offset, size: androidx.compose.ui.unit.IntSize) {
        if (isReaderResumed()) {
            val width = size.width
            val height = size.height
            viewModel.setTargetPageBySide(position.x, width, isDoubleReaderMode)

            val isMenuTrigger = if (isDoubleReaderMode && width > 0 && height > 0) {
                val x = position.x
                val y = position.y
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
                    composeReaderController.switchPageTo(index, true)
                }
            } else {
                viewModel.recordExplicitJump(ReaderState(page.chapterId, page.index, 0), "page")
                viewModel.switchChapter(page.chapterId, page.index)
            }
        }
        return true
    }

    private fun dismissChapterPagesSheet() {
        composeReaderController.hideChapters()
    }

    fun onReaderModeChanged(mode: ReaderMode) {
        val stateBeforeSwitch = composeReaderController.getCurrentState()
        Log.d(
            LOG_TAG,
            "onReaderModeChanged: mode=$mode, currentMode=${composeReaderController.readerMode}, " +
                "stateBeforeSwitch=$stateBeforeSwitch, reader=${composeReaderController.javaClass.simpleName}",
        )
        viewModel.saveCurrentState(stateBeforeSwitch)
        viewModel.switchMode(mode)
    }

    fun onSplitModeChanged(isEnabled: Boolean) {
        viewModel.reload()
    }

    fun onDoubleModeChanged(isEnabled: Boolean) {
        // Combine manual toggle with foldable auto setting
        applyDoubleModeAuto(isEnabled)
    }

    private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Also enable dual-page in split-screen when aspect ratio is close to square
        // This handles foldable devices in split-screen mode
        val windowWidth = window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val windowHeight = window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
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
                "currentMode=${composeReaderController.readerMode}, reader=${composeReaderController.javaClass.simpleName}, " +
                "viewModelState=${viewModel.getCurrentState()}, composeState=${composeReaderController.getCurrentState()}",
        )
        isDoubleReaderMode = autoEnabled
        composeReaderController.setDoublePageEnabled(autoEnabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(
            LOG_TAG,
            "onConfigurationChanged: orientation=${newConfig.orientation}, " +
                "isDoubleReaderMode=$isDoubleReaderMode, currentMode=${composeReaderController.readerMode}, " +
                "reader=${composeReaderController.javaClass.simpleName}, " +
                "viewModelState=${viewModel.getCurrentState()}, composeState=${composeReaderController.getCurrentState()}",
        )
        applyDoubleModeAuto()
        updateTranslationToggleButton()
    }


    private fun setKeepScreenOn(isKeep: Boolean) {
        if (isKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setUiIsVisible(isUiVisible: Boolean) {
		areControlsVisible = isUiVisible
        viewModel.isMenuVisible.value = isUiVisible
        composeReaderController.setControlsVisible(isUiVisible)
		val isFullscreen = settings.isReaderFullscreenEnabled
		systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
        spaceSwitcherDelegate.setControlsVisible(
            visible = isUiVisible,
            hideWithControlsTransition = !isUiVisible && isAnimationsEnabled,
        )
    }

    override fun switchPageBy(delta: Int) {
        composeReaderController.switchPageBy(delta)
    }

    override fun switchChapterBy(delta: Int) {
        Log.d(
            LOG_TAG,
            "switchChapterBy: delta=$delta, currentState=${viewModel.getCurrentState()}, " +
                "reader=${composeReaderController.javaClass.simpleName}",
        )
        viewModel.switchChapterBy(delta)
    }

    override fun openMenu() {
        setUiIsVisible(true)
        viewModel.saveCurrentState(composeReaderController.getCurrentState())
		composeReaderController.showOptions(
			ComposeReaderOptionsState(
				mode = composeReaderController.readerMode,
				animation = settings.readerAnimation,
				doublePage = settings.isReaderDoubleOnLandscape,
				doublePageFoldable = settings.isReaderDoubleOnFoldable,
				doublePageCover = settings.isReaderDoubleCoverPage,
				splitPages = settings.isReaderSplitPagesEnabled,
				doublePageSensitivity = settings.readerDoublePagesSensitivity,
				superResolution = settings.isReaderSuperResolutionEnabled,
				background = settings.readerBackground,
				colorFilter = viewModel.readerSettingsProducer.value.colorFilter,
				imageScalingQuality = settings.readerImageScalingQuality,
			),
		)
		loadImageServerOptions()
    }

	private fun loadImageServerOptions() {
		val source = viewModel.getContentOrNull()?.source ?: return
		lifecycleScope.launch {
			val options = ImageServerDelegate(mangaRepositoryFactory, source).loadOptions()
			composeReaderController.updateOptions { copy(imageServer = options) }
		}
	}

	private fun updateImageServer(value: String?) {
		val source = viewModel.getContentOrNull()?.source ?: return
		lifecycleScope.launch {
			val changed = ImageServerDelegate(mangaRepositoryFactory, source).select(value)
			if (changed) {
				composeReaderController.updateOptions {
					copy(imageServer = imageServer?.copy(selectedValue = value))
				}
				viewModel.reload()
			}
		}
	}

	private fun openCurrentChapterInBrowser() {
		val manga = viewModel.getContentOrNull() ?: return
		val chapter = viewModel.uiState.value?.chapter
		if (chapter == null) {
			router.openBrowser(manga)
			return
		}
		val chapterUrl = runCatching {
			when {
				chapter.url.startsWith("http", ignoreCase = true) -> chapter.url
				manga.publicUrl.startsWith("http", ignoreCase = true) ->
					java.net.URL(java.net.URL(manga.publicUrl), chapter.url).toString()
				else -> null
			}
		}.getOrNull()
		val url = chapterUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
			?: manga.publicUrl.takeIf { it.startsWith("http", ignoreCase = true) }
		if (url != null) router.openBrowser(url, manga.source, chapter.title) else router.openBrowser(manga)
	}

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return composeReaderController.scrollBy(delta, smooth)
    }

    override fun toggleUiVisibility() {
        if (!areControlsVisible) {
            if (scrollTimer.isActive.value && settings.isReaderAutoscrollPauseOnUi && !scrollTimer.isManuallyPaused.value) {
                scrollTimer.setManuallyPaused(true)
				composeReaderController.updateAutoScroll { copy(visible = true) }
                return
            }
        }
        setUiIsVisible(!areControlsVisible)
    }

    override fun isReaderResumed(): Boolean {
        return composeReaderController.isReaderResumed
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
			composeReaderController.updateAutoScroll {
				copy(active = scrollTimer.isActive.value, manuallyPaused = scrollTimer.isManuallyPaused.value)
			}
			composeReaderController.toggleAutoScroll()
		}
    }

    override fun onTranslateClick() {
        toggleTranslationLayer()
    }

    override fun onTranslateLongClick(): Boolean {
        showTranslationLanguageQuickActions()
        return true
    }

    override fun toggleScreenOrientation() {
        if (screenOrientationHelper.toggleScreenOrientation()) {
			composeReaderController.showMessage(
				getString(
					if (screenOrientationHelper.isLocked) {
						R.string.screen_rotation_locked
					} else {
						R.string.screen_rotation_unlocked
					},
				),
				TOAST_DURATION,
			)
        }
    }

    override fun switchPageTo(index: Int) {
        val pages = viewModel.getCurrentChapterPages()
        val page = pages?.getOrNull(index) ?: return
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return
        onPageSelected(ReaderPage(page, index, chapterId))
    }

    private fun onReaderBarChanged(isBarEnabled: Boolean) {
        composeReaderController.updateInfoBar { copy(visible = isBarEnabled) }
    }

    private fun onUiStateChanged(pair: Pair<ReaderUiState?, ReaderUiState?>) {
        val (previous: ReaderUiState?, uiState: ReaderUiState?) = pair
        title = uiState?.mangaName ?: getString(R.string.loading_)
        updateReaderInfoBar(uiState)
        updateTranslationToggleButton()
        if (uiState == null) {
            composeReaderController.setTitle(title.toString(), "")
            composeReaderController.updateActions {
                copy(sliderValue = 0f, sliderMax = 1, sliderEnabled = false)
            }
            return
        }
        val chapterTitle = uiState.getChapterTitle(resources)
        val chromeSubtitle = if (uiState.incognito) getString(R.string.incognito_mode) else chapterTitle
        composeReaderController.setTitle(title.toString(), chromeSubtitle)
        if (
            settings.isReaderChapterToastEnabled &&
            chapterTitle != previous?.getChapterTitle(resources) &&
            chapterTitle.isNotEmpty()
        ) {
            composeReaderController.showMessage(chapterTitle, TOAST_DURATION)
        }
        composeReaderController.updateActions {
            copy(
                sliderValue = if (uiState.isSliderAvailable()) uiState.currentPage.toFloat() else 0f,
                sliderMax = if (uiState.isSliderAvailable()) uiState.totalPages - 1 else 1,
                sliderEnabled = uiState.isSliderAvailable(),
                previousEnabled = uiState.hasPreviousChapter(),
                nextEnabled = uiState.hasNextChapter(),
                pageLabel = "${uiState.currentPage + 1}/${uiState.totalPages}",
            )
        }
    }

    private fun updateReaderInfoBar(uiState: ReaderUiState?) {
        composeReaderController.updateInfoBar {
            copy(
                text = uiState?.let {
                    getString(
                        R.string.reader_info_pattern,
                        it.chapterNumber,
                        it.chaptersTotal,
                        it.currentPage + 1,
                        it.totalPages,
                    ) + if (it.percent in 0f..1f) {
                        "     " + getString(
                            R.string.percent_string_pattern,
                            (it.percent * 100).roundToInt(),
                        )
                    } else {
                        ""
                    }
                }.orEmpty(),
                showSystemStatus = settings.isReaderFullscreenEnabled,
            )
        }
    }

    private fun updateTranslationToggleButton() {
        val shouldShow = viewModel.shouldShowTranslationToggle()
        val isShowingTranslated = settings.isReaderTranslationEnabled && settings.isReaderTranslationShowTranslated
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
        composeReaderController.updateActions {
            copy(
                translateRequestedVisible = shouldShow,
                translateActive = isShowingTranslated,
                translateContentDescription = contentDescription,
            )
        }
    }

    private fun toggleTranslationLayer() {
		if (!viewModel.hasTranslationEngineConfigured()) {
			enableTranslationAfterSetup = true
			router.openTranslationSettings()
			return
		}
		viewModel.getTranslationBypassHint(this)?.let { hint ->
			composeReaderController.showMessage(hint, 2000L)
			return
		}
		translationShortcutVisibleForSession = true
		if (settings.isReaderTranslationEnabled) {
			settings.isReaderTranslationShowTranslated = !settings.isReaderTranslationShowTranslated
		} else {
			settings.isReaderTranslationShowTranslated = true
			settings.isReaderTranslationEnabled = true
			composeReaderController.showMessage(getString(R.string.reader_translation_long_press_hint), 2500L)
		}
    }

    private fun onChapterTranslationProgressChanged(progress: ReaderViewModel.ChapterTranslationProgress?) {
        if (progress == null) {
            lastMangaTranslationProgress = null
            updateReaderInfoBar(viewModel.uiState.value)
            return
        }
        lastMangaTranslationProgress = progress
        if (!settings.isReaderTranslationEnabled) {
            return
        }
		composeReaderController.updateInfoBar {
			copy(text = getString(R.string.reader_translation_status_compact, progress.readyCount, progress.totalCount))
		}
    }

    private fun showTranslationLanguageQuickActions() {
        val actions = arrayOf(
            getString(R.string.reader_translation_quick_change_source),
            getString(R.string.reader_translation_quick_change_target),
            getString(R.string.reader_translation_quick_swap_languages),
        )
        composeReaderController.showSelectionDialog(
            title = getString(R.string.reader_translation_quick_actions),
            entries = actions.toList(),
        ) { which ->
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
        composeReaderController.showSelectionDialog(
            title = getString(titleRes),
            entries = labels.toList(),
            selectedIndex = selectedIndex,
        ) { which ->
                onSelected(values[which])
            }
    }

    private fun swapTranslationLanguages() {
        val source = settings.readerTranslationSourceLanguage
        val target = settings.readerTranslationTargetLanguage
        if (isAutoReaderTranslationLanguage(source)) {
			composeReaderController.showMessage(
				getString(R.string.reader_translation_swap_auto_unsupported),
				TOAST_DURATION,
			)
            return
        }
        settings.readerTranslationSourceLanguage = target
        settings.readerTranslationTargetLanguage = source
		composeReaderController.showMessage(
			getString(
                R.string.reader_translation_languages_swapped,
                displayTranslationLanguage(target, isSource = true),
                displayTranslationLanguage(source, isSource = false),
            ),
			TOAST_DURATION,
		)
    }

    private fun showTranslationLanguageChangedMessage(messageRes: Int, value: String, isSource: Boolean) {
		composeReaderController.showMessage(
			getString(messageRes, displayTranslationLanguage(value, isSource)),
			TOAST_DURATION,
		)
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

	private fun dispatchNavigateUp() {
		if (intent.getBooleanExtra(AppRouter.EXTRA_HAS_IN_APP_CALLER, false)) {
			finishAfterTransition()
			return
		}
		val upIntent = parentActivityIntent
		if (upIntent != null) {
			if (!navigateUpTo(upIntent)) startActivity(upIntent)
		} else {
			finishAfterTransition()
		}
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
        pendingIncognitoDialog = IncognitoDialogState()
    }

    private fun dismissIncognitoModeDialog() {
        pendingIncognitoDialog = null
        finishAfterTransition()
    }

    private fun consumeIncognitoMode(isIncognito: Boolean) {
        val dialog = pendingIncognitoDialog ?: return
        pendingIncognitoDialog = null
        viewModel.setIncognitoMode(isIncognito, dialog.dontAskAgain)
    }

    companion object {

        private const val LOG_TAG = "ReaderDebug"
        private const val TOAST_DURATION = 2000L
        private const val TRANSLATION_PROGRESS_MIN_INTERVAL_MS = 800L
        private const val STATE_TRANSLATION_SHORTCUT_VISIBLE = "translation_shortcut_visible"
        private const val STATE_ENABLE_TRANSLATION_AFTER_SETUP = "enable_translation_after_setup"
    }
}

private data class IncognitoDialogState(
    val dontAskAgain: Boolean = false,
)

@Composable
private fun ReaderLoadingErrorDialog(
    message: String,
    resolveActionStringId: Int,
    onDismiss: () -> Unit,
    onResolve: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            if (resolveActionStringId != 0) {
                TextButton(onClick = onResolve) {
                    Text(stringResource(resolveActionStringId))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun IncognitoModeDialog(
    dontAskAgain: Boolean,
    onDismissRequest: () -> Unit,
    onDontAskAgainChange: (Boolean) -> Unit,
    onIncognitoModeSelected: () -> Unit,
    onDisabledSelected: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_incognito),
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.incognito_mode)) },
        text = {
            Column {
                Text(stringResource(R.string.incognito_mode_hint_nsfw))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = dontAskAgain,
                        onCheckedChange = onDontAskAgainChange,
                    )
                    Text(stringResource(R.string.dont_ask_again))
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDisabledSelected) {
                    Text(stringResource(R.string.disable))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = onIncognitoModeSelected) {
                    Text(stringResource(R.string.incognito))
                }
            }
        },
    )
}
