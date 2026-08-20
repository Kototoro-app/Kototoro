package org.skepsun.kototoro.details.ui.compose


import android.os.Build
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.appUrl
import org.skepsun.kototoro.core.model.getLocalizedTitle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeFeatherExtension
import org.skepsun.kototoro.core.ui.compose.ImmersiveTopGradientStops
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.util.ext.isHttpUrl
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.model.ActiveLocalSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSupplementAction
import org.skepsun.kototoro.details.ui.model.EntityChapterSourceInfo
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.compose.pane.DetailsPaneHost
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneState
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationSection
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.download.ui.dialog.DownloadDialogViewModel
import org.skepsun.kototoro.download.ui.compose.DownloadDialog
import org.skepsun.kototoro.download.ui.worker.DownloadStartedObserver
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceSwitcherIcon
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.favourites.ui.categories.select.compose.DuplicateFavoritePromptDialog
import org.skepsun.kototoro.favourites.ui.categories.select.compose.FavoriteCategoryDialog
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.stats.ui.sheet.ContentStatsViewModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal fun Color.withDetailsMinAlpha(minAlpha: Float): Color {
    return copy(alpha = alpha.coerceAtLeast(minAlpha))
}

internal fun Color.detailsPanelContainerColor(): Color = withDetailsMinAlpha(0.70f)

@Composable
internal fun rememberDetailsBottomBarGlassPrefs() =
    rememberGlassPrefsOrFallback()

private val DetailsTopChromeShadowElevation = 6.dp
private val ModernDetailsDockHeight = 86.dp
private val ModernDetailsDockBottomClearance = 16.dp
internal val DetailsDockContentHorizontalPadding = 8.dp
internal val ModernDetailsDockCompactPrimaryWidth = 112.dp
internal val ModernDetailsDockToolsWidth = 92.dp
internal val ModernDetailsDockTabButtonWidth = 42.dp
internal val ModernDetailsDockTabSpacing = 2.dp
private val DualPaneDetailsReadDockMinWidth = 146.dp
internal val DualPaneDetailsDockGap = 6.dp
internal val DualPaneDetailsPrimaryDockMinWidth =
    DualPaneDetailsReadDockMinWidth + DualPaneDetailsDockGap + ModernDetailsDockToolsWidth
internal val ModernDetailsDockMoreButtonWidth = 40.dp
internal val ModernDetailsDockChromeHeight = 52.dp
internal val GridSizeControlsHeight = 84.dp
internal val ModernDetailsDockExpandedPanelGap = 12.dp
internal const val ModernDetailsDockAnimationDurationMillis = 380
internal const val PageThumbnailAspectRatioMin = 0.35f
internal const val PageThumbnailAspectRatioMax = 1f
internal const val PageThumbnailHeightRatioMin = 1f
internal const val PageThumbnailHeightRatioMax = 1f / PageThumbnailAspectRatioMin

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel,
    pagesViewModel: PagesViewModel,
    bookmarksViewModel: BookmarksViewModel,
    settings: AppSettings,
    appRouter: AppRouter,
    pageSaveHelper: PageSaveHelper,
    onBackClick: () -> Unit,
    activeSpaceId: SpaceId? = null,
    onSpaceSwitcherClick: () -> Unit = {},
    onBottomPanelStateChanged: (Float, Dp) -> Unit = { _, _ -> },
    sharedElementKey: String? = null,
    onActionClick: (DetailsAction) -> Unit = {},
    isTemporaryReadOnly: Boolean = false,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val baseColorScheme = MaterialTheme.colorScheme
    val detailsColorScheme = remember(baseColorScheme, isDarkTheme) {
        if (isDarkTheme) {
            baseColorScheme.copy(
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color.White,
            )
        } else {
            baseColorScheme
        }
    }
    MaterialTheme(colorScheme = detailsColorScheme) {
        DetailsScreenContent(
            viewModel = viewModel,
            pagesViewModel = pagesViewModel,
            bookmarksViewModel = bookmarksViewModel,
            settings = settings,
            appRouter = appRouter,
            pageSaveHelper = pageSaveHelper,
            onBackClick = onBackClick,
            activeSpaceId = activeSpaceId,
            onSpaceSwitcherClick = onSpaceSwitcherClick,
            onBottomPanelStateChanged = onBottomPanelStateChanged,
            sharedElementKey = sharedElementKey,
            onActionClick = onActionClick,
            isTemporaryReadOnly = isTemporaryReadOnly,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun DetailsScreenContent(
    viewModel: DetailsViewModel,
    pagesViewModel: PagesViewModel,
    bookmarksViewModel: BookmarksViewModel,
    settings: AppSettings,
    appRouter: AppRouter,
    pageSaveHelper: PageSaveHelper,
    onBackClick: () -> Unit,
    activeSpaceId: SpaceId? = null,
    onSpaceSwitcherClick: () -> Unit = {},
    onBottomPanelStateChanged: (Float, Dp) -> Unit = { _, _ -> },
    sharedElementKey: String? = null,
    onActionClick: (DetailsAction) -> Unit = {},
    isTemporaryReadOnly: Boolean = false,
) {
    val interfaceStyleTokens = LocalInterfaceStyleTokens.current
    val detailsPrimaryUiState by viewModel.detailsPrimaryUiState.collectAsStateWithLifecycle()
    val localSize by viewModel.localSize.collectAsStateWithLifecycle()
    val readingRecordSnapshot by viewModel.readingRecordSnapshot.collectAsStateWithLifecycle()
    val translationUiState by viewModel.translationUiState.collectAsStateWithLifecycle()
    val chaptersPaneControlsUiState by viewModel.chaptersPaneControlsUiState.collectAsStateWithLifecycle()
    val pagesGridScale by pagesViewModel.gridScale.collectAsStateWithLifecycle(initialValue = settings.gridSizePages / 100f)
    val pageThumbnailAspectRatio by settings.observeAsState(AppSettings.KEY_PAGE_THUMBNAIL_ASPECT_RATIO) {
        pageThumbnailAspectRatio
    }
    val sourceBindingUiState by viewModel.sourceBindingUiState.collectAsStateWithLifecycle()
    val detailsSupplementUiState by viewModel.detailsSupplementUiState.collectAsStateWithLifecycle()
    val metadataSearchUiState by viewModel.metadataSearchUiState.collectAsStateWithLifecycle()
    val readingSearchUiState by viewModel.readingSearchUiState.collectAsStateWithLifecycle()
    val mangaDetails = detailsPrimaryUiState.mangaDetails
    val remoteContent = detailsPrimaryUiState.remoteContent
    val favouriteCategories = detailsPrimaryUiState.favouriteCategories
    val historyInfo = detailsPrimaryUiState.historyInfo
    val branches = detailsPrimaryUiState.branches
    val isStatsAvailable = detailsPrimaryUiState.isStatsAvailable
    val relatedContent = detailsPrimaryUiState.relatedContent
    val trackingSuggestion = detailsPrimaryUiState.trackingSuggestion
    val linkedTrackingItems = detailsPrimaryUiState.linkedTrackingItems
    val readingStatus = detailsPrimaryUiState.readingStatus
    val unifiedRating = detailsPrimaryUiState.unifiedRating
    val canEditUnifiedRating = detailsPrimaryUiState.canEditUnifiedRating
    val isLoading = detailsPrimaryUiState.isLoading
    val entityRelationSections = detailsPrimaryUiState.entityRelationSections
    val activeLocalBrowserContent = detailsPrimaryUiState.activeLocalBrowserContent
    val isWorkDetails = detailsPrimaryUiState.isWorkDetails
    val isWorkActionEnabled = isWorkDetails && !isTemporaryReadOnly
    val isChaptersReversed = chaptersPaneControlsUiState.isChaptersReversed
    val isChaptersInGridView = chaptersPaneControlsUiState.isChaptersInGridView
    val isHideReadChapters = chaptersPaneControlsUiState.isHideReadChapters
    val isMergeRepeatedChapters = chaptersPaneControlsUiState.isMergeRepeatedChapters
    val showMergeRepeatedChapters = chaptersPaneControlsUiState.showMergeRepeatedChapters
    val isDownloadedOnly = chaptersPaneControlsUiState.isDownloadedOnly
    val chapterEmptyReason = chaptersPaneControlsUiState.emptyReason
    val activeLocalSourceOptions = sourceBindingUiState.activeLocalSourceOptions
    val entityChapterSourceInfo = sourceBindingUiState.entityChapterSourceInfo
    val metadataSourceOptions = sourceBindingUiState.metadataSourceOptions
    val readingSourceOptions = sourceBindingUiState.readingSourceOptions
    val metadataChapterTabs = sourceBindingUiState.metadataChapterTabs
    val readingChapterTabs = sourceBindingUiState.readingChapterTabs
    val resolvedMetadataContentType = sourceBindingUiState.resolvedMetadataContentType
    val resolvedMetadataLanguage = sourceBindingUiState.resolvedMetadataLanguage
    val resolvedReadingLanguage = sourceBindingUiState.resolvedReadingLanguage
    val translatedTitle = translationUiState.translatedTitle
    val translatedDescription = translationUiState.translatedDescription
    val isShowingTranslation = translationUiState.isShowingTranslation
    val hasTranslationCache = translationUiState.hasTranslationCache
    val isTranslating = translationUiState.isTranslating
    val showTranslateAction = translationUiState.showTranslateAction
    val supplementalMetadataProperties = detailsSupplementUiState.metadataProperties
    val supplementalSections = detailsSupplementUiState.sections
    val supplementalActions = detailsSupplementUiState.actions
    val supplementalCommentThreads = detailsSupplementUiState.commentThreads
    val supplementalCommentsUrl = detailsSupplementUiState.commentsUrl
    val supplementalReviews = detailsSupplementUiState.reviews
    val supplementalReviewsUrl = detailsSupplementUiState.reviewsUrl
    val metadataSearchServices = metadataSearchUiState.services
    val authorizedTrackingServices = metadataSearchUiState.authorizedServices
    val selectedMetadataSearchService = metadataSearchUiState.selectedService
    val metadataSearchQuery = metadataSearchUiState.query
    val metadataSearchResults = metadataSearchUiState.results
    val metadataSearchSections = metadataSearchUiState.sections
    val metadataSearchLoading = metadataSearchUiState.isLoading
    val metadataSearchHasSearched = metadataSearchUiState.hasSearched
    val metadataSearchError = metadataSearchUiState.errorMessage
    val languagePresets by viewModel.languagePresets.collectAsStateWithLifecycle()
    val activeLanguagePresetId by viewModel.activeLanguagePresetId.collectAsStateWithLifecycle()
    val readingSearchSources = readingSearchUiState.sources
    val readingSearchQuery = readingSearchUiState.query
    val readingSearchSections = readingSearchUiState.sections
    val readingSearchLoading = readingSearchUiState.isLoading
    val readingSearchHasSearched = readingSearchUiState.hasSearched
    val readingSearchState = readingSearchUiState.state
    val readingSearchScopeFilterUiState = readingSearchUiState.scopeFilterUiState

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val panoramaPrefs = rememberPanoramaBackdropPrefs(settings)
    val downloadDialogViewModel: DownloadDialogViewModel = hiltViewModel()
    val initialContent = remember { PendingDetailsNavigation.lastContent() }
    val content = mangaDetails?.toContent() ?: initialContent
    val contentType = resolvedMetadataContentType
	LaunchedEffect(
		content?.id,
		content?.source?.name,
		content?.source?.locale,
		metadataSourceOptions,
		readingSourceOptions,
		resolvedMetadataLanguage,
		resolvedReadingLanguage,
	) {
		Log.i(
			"DetailsTrace",
			"ui.state contentId=${content?.id} contentSource=${content?.source?.name} " +
				"contentLocale=${content?.source?.locale} metadata=${metadataSourceOptions.map { "${it.key}:${it.source?.name}:${it.source?.locale}:${it.isSelected}" }} " +
				"reading=${readingSourceOptions.map { "${it.key}:${it.source?.name}:${it.source?.locale}:${it.isSelected}" }} " +
				"metadataLanguage=$resolvedMetadataLanguage readingLanguage=$resolvedReadingLanguage",
		)
	}
    val selectedMetadataOption = metadataSourceOptions.firstOrNull { it.isSelected }
        ?: metadataSourceOptions.firstOrNull()
    val metadataBrowserTarget = remember(selectedMetadataOption, content) {
        selectedMetadataOption?.url
            ?.takeIf { it.isHttpUrl() }
            ?.let { url ->
                BrowserTarget(
                    url = url,
                    source = selectedMetadataOption.source,
                    title = selectedMetadataOption.title ?: content?.title,
                )
            }
            ?: content
                ?.takeIf { selectedMetadataOption?.trackingService == null && it.publicUrl.isHttpUrl() }
                ?.let { localContent ->
                    BrowserTarget(
                        url = localContent.publicUrl,
                        source = localContent.source,
                        title = localContent.title,
                    )
                }
    }
    val localBrowserTarget = remember(activeLocalBrowserContent, metadataBrowserTarget) {
        activeLocalBrowserContent?.takeIf { it.publicUrl.isHttpUrl() }?.takeUnless { local ->
            local.publicUrl == metadataBrowserTarget?.url &&
                local.source == metadataBrowserTarget.source
        }?.let { local ->
            BrowserTarget(
                url = local.publicUrl,
                source = local.source,
                title = local.title,
            )
        }
    }
    val readingSourceLabelRes = remember(contentType) {
        when (contentType) {
            ContentType.VIDEO,
            ContentType.HENTAI_VIDEO -> R.string.details_playback_source
            else -> R.string.details_reading_source
        }
    }
    val isShortcutSupported = remember(context) { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()
    val landscapeLeftScrollState = rememberScrollState()
    var showDeleteLocalDialog by remember { mutableStateOf(false) }
    var showShareOptions by remember { mutableStateOf(false) }
    var pendingAuthorSearch by remember { mutableStateOf<PendingAuthorSearch?>(null) }
    var pendingTagSearch by remember { mutableStateOf<ContentTag?>(null) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showReadingRecordSheet by remember { mutableStateOf(false) }
    var showCommentsDialog by remember { mutableStateOf(false) }
    var showReviewsDialog by remember { mutableStateOf(false) }
    var selectedSupplementalRelationItem by remember { mutableStateOf<EntityRelationItem?>(null) }
    var showMetadataSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showReadingSourceDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeSpaceId) {
        viewModel.setSpaceContext(activeSpaceId)
    }
    LaunchedEffect(showMetadataSourceDialog) {
        if (showMetadataSourceDialog && !metadataSearchHasSearched && !metadataSearchLoading) {
            viewModel.searchMetadataBindings()
        }
    }
    LaunchedEffect(showReadingSourceDialog, isWorkDetails) {
        if (showReadingSourceDialog && isWorkDetails && !readingSearchHasSearched && !readingSearchLoading) {
            viewModel.searchReadingBindings()
        }
    }
    val availableTabIds = remember(contentType, settings.isPagesTabEnabled) {
        resolveAvailableDetailsTabIds(contentType, settings)
    }
    val tabletUiMode by settings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
    val isWideAdaptiveLayout = remember(context, configuration.orientation, configuration.screenWidthDp, tabletUiMode) {
        FoldableUtils.shouldUseTabletLayout(context, settings, configuration)
    }
    val isModernDetailsDockEnabled by settings.observeAsState(AppSettings.KEY_MODERN_DETAILS_DOCK) {
        isModernDetailsDockEnabled
    }
    val density = LocalDensity.current
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val compactPaneCollapsedHeight = remember(navigationBarBottomPadding, isModernDetailsDockEnabled) {
        if (isModernDetailsDockEnabled) {
            (ModernDetailsDockHeight + navigationBarBottomPadding + ModernDetailsDockBottomClearance)
                .coerceIn(112.dp, 160.dp)
        } else {
            (96.dp + navigationBarBottomPadding).coerceIn(104.dp, 160.dp)
        }
    }
    val detailsPaneState = rememberDetailsPaneState(
        screenHeightDp = configuration.screenHeightDp,
        collapsedHeight = compactPaneCollapsedHeight,
        initialPageGridSizeValue = settings.gridSizePages.toFloat(),
        initialPageThumbnailAspectRatio = settings.pageThumbnailAspectRatio,
        initialSelectedTabId = settings.defaultDetailsTab,
        initialChapterQuery = "",
    )
    val compactPaneHeight = detailsPaneState.paneHeight
    val compactPaneAnchor = detailsPaneState.anchor
    val pageGridSizeValue = detailsPaneState.pageGridSizeValue
    val pageThumbnailAspectRatioValue = detailsPaneState.pageThumbnailAspectRatio
    val isPageThumbnailsFitPreview by settings.observeAsState(AppSettings.KEY_PAGE_THUMBNAILS_FIT_PREVIEW) {
        isPageThumbnailsFitPreview
    }
    val sheetTabSelection = remember(detailsPaneState.selectedTabId, availableTabIds) {
        detailsPaneState.resolvedSelectedTabId(availableTabIds)
    }
    var isModernDockCompact by rememberSaveable { mutableStateOf(false) }
    val modernDockCollapseThresholdPx = with(density) { 32.dp.roundToPx() }
    val modernDockExpandThresholdPx = with(density) { 16.dp.roundToPx() }
    LaunchedEffect(
        isModernDetailsDockEnabled,
        isWideAdaptiveLayout,
        compactPaneAnchor,
        scrollState,
        modernDockCollapseThresholdPx,
        modernDockExpandThresholdPx,
    ) {
        if (!isModernDetailsDockEnabled || isWideAdaptiveLayout || compactPaneAnchor != CompactDetailsPaneAnchor.Collapsed) {
            isModernDockCompact = false
            return@LaunchedEffect
        }
        var lastScrollValue = scrollState.value
        var accumulatedScroll = 0
        snapshotFlow { scrollState.value }.collect { currentScrollValue ->
            val delta = currentScrollValue - lastScrollValue
            lastScrollValue = currentScrollValue
            if (delta == 0) return@collect

            accumulatedScroll = when {
                delta > 0 && accumulatedScroll < 0 -> delta
                delta < 0 && accumulatedScroll > 0 -> delta
                else -> accumulatedScroll + delta
            }
            when {
                accumulatedScroll >= modernDockCollapseThresholdPx -> {
                    isModernDockCompact = true
                    accumulatedScroll = 0
                }
                accumulatedScroll <= -modernDockExpandThresholdPx -> {
                    isModernDockCompact = false
                    accumulatedScroll = 0
                }
            }
        }
    }
    LaunchedEffect(isWideAdaptiveLayout, detailsPaneState.chapterSelectionState) {
        if (!isWideAdaptiveLayout && detailsPaneState.chapterSelectionState != null) {
            detailsPaneState.onChapterSelectionActivated()
        }
    }
    LaunchedEffect(pagesGridScale) {
        detailsPaneState.syncPageGridSizeValue((pagesGridScale * 100f).coerceIn(50f, 150f))
    }
    LaunchedEffect(pageThumbnailAspectRatio) {
        detailsPaneState.syncPageThumbnailAspectRatio(
            pageThumbnailAspectRatio.coerceIn(PageThumbnailAspectRatioMin, PageThumbnailAspectRatioMax),
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val toolbarGapPx = with(density) { 12.dp.toPx() }
    var toolbarBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    var lastToolbarBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    var infoCardTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var infoCardMidPx by remember { mutableFloatStateOf(Float.NaN) }
    var initialInfoCardTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var initialInfoCardMidPx by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(availableTabIds) {
        detailsPaneState.syncSelectedTabs(
            availableTabIds = availableTabIds,
            defaultTabId = settings.defaultDetailsTab,
            onDefaultResolved = { resolvedDefaultTab ->
                settings.defaultDetailsTab = resolvedDefaultTab
            },
        )
    }

    LaunchedEffect(isWideAdaptiveLayout) {
        if (isWideAdaptiveLayout) {
            landscapeLeftScrollState.scrollTo(0)
        }
    }
    DisposableEffect(lifecycleOwner, rootView, viewModel) {
        viewModel.onError.observeEvent(lifecycleOwner, SnackbarErrorObserver(rootView))
        viewModel.onActionDone.observeEvent(lifecycleOwner, ReversibleActionObserver(rootView))
        viewModel.onDownloadStarted.observeEvent(lifecycleOwner, DownloadStartedObserver(rootView))
        val sourceBindingsObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSourceBindings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(sourceBindingsObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(sourceBindingsObserver)
        }
    }
    val compactCollapseProgressProvider = remember(
        scrollState,
        landscapeLeftScrollState,
        toolbarGapPx,
        isWideAdaptiveLayout,
    ) {
        {
            calculateDetailsScrollProgress(
                scrollValue = scrollState.value,
                landscapeScrollValue = landscapeLeftScrollState.value,
                toolbarBottomPx = toolbarBottomPx,
                infoCardTopPx = infoCardTopPx,
                initialInfoCardTopPx = initialInfoCardTopPx,
                toolbarGapPx = toolbarGapPx,
                isWideAdaptiveLayout = isWideAdaptiveLayout,
                disableInWideLayout = true,
            )
        }
    }
    val toolbarTitleProgressProvider = remember(
        scrollState,
        landscapeLeftScrollState,
        toolbarGapPx,
        isWideAdaptiveLayout,
    ) {
        {
            calculateDetailsScrollProgress(
                scrollValue = scrollState.value,
                landscapeScrollValue = landscapeLeftScrollState.value,
                toolbarBottomPx = toolbarBottomPx,
                infoCardTopPx = infoCardTopPx,
                initialInfoCardTopPx = initialInfoCardTopPx,
                toolbarGapPx = toolbarGapPx,
                isWideAdaptiveLayout = isWideAdaptiveLayout,
                disableInWideLayout = false,
            )
        }
    }
    val syncInfoCardBounds: (Float, Float) -> Unit = remember {
        { top, bottom ->
            val midpoint = (top + bottom) / 2f
            infoCardTopPx = top
            infoCardMidPx = midpoint
            if (top.isFinite() && (!initialInfoCardTopPx.isFinite() || top > initialInfoCardTopPx)) {
                initialInfoCardTopPx = top
                initialInfoCardMidPx = midpoint
            }
        }
    }
    val compactSheetExpansionProgressProvider = remember(detailsPaneState) {
        { detailsPaneState.expansionProgress }
    }
    val reportedBottomPanelExpansion = if (compactPaneAnchor == CompactDetailsPaneAnchor.Collapsed) 0f else 1f
    val currentBottomPanelStateChanged by rememberUpdatedState(onBottomPanelStateChanged)
    LaunchedEffect(reportedBottomPanelExpansion, compactPaneCollapsedHeight, isWideAdaptiveLayout) {
        currentBottomPanelStateChanged(
            if (isWideAdaptiveLayout) 0f else reportedBottomPanelExpansion,
            if (isWideAdaptiveLayout) 0.dp else compactPaneCollapsedHeight,
        )
    }
    DisposableEffect(Unit) {
        onDispose { currentBottomPanelStateChanged(0f, 0.dp) }
    }
    val detailsGradientAlpha = if (scrollState.maxValue > 0) {
        (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val toolbarTitle = translatedTitle ?: content?.title.orEmpty()
    val isCompactPaneFullyExpanded = !isWideAdaptiveLayout && compactPaneAnchor == CompactDetailsPaneAnchor.Full
    val visibleStatusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarTopPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val fallbackStatusBarTopPadding = remember(context, density) {
        val statusBarHeightResId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (statusBarHeightResId > 0) {
            with(density) { context.resources.getDimensionPixelSize(statusBarHeightResId).toDp() }
        } else {
            40.dp
        }
    }
    var stableStatusBarTopPadding by remember {
        mutableStateOf(statusBarTopPadding.takeIf { it > 0.dp } ?: fallbackStatusBarTopPadding)
    }
    LaunchedEffect(isWideAdaptiveLayout, statusBarTopPadding) {
        if (isWideAdaptiveLayout) {
            stableStatusBarTopPadding = 0.dp
        } else if (statusBarTopPadding > stableStatusBarTopPadding) {
            stableStatusBarTopPadding = statusBarTopPadding
        }
    }
    val overlayTopBarInset = remember(
        isWideAdaptiveLayout,
        stableStatusBarTopPadding,
        interfaceStyleTokens.mainTopBarHeight,
    ) {
        if (isWideAdaptiveLayout) {
            0.dp
        } else {
            // Keep the content start position stable when returning from fullscreen surfaces that briefly report zero insets.
            stableStatusBarTopPadding + interfaceStyleTokens.mainTopBarHeight + 8.dp
        }
    }
    val panoramaExtraHeightDp = panoramaPrefs.extraHeight.coerceAtLeast(0).dp
    val compactPanoramaTopBarInset = remember(
        stableStatusBarTopPadding,
        interfaceStyleTokens.mainTopBarHeight,
    ) {
        stableStatusBarTopPadding + interfaceStyleTokens.mainTopBarHeight
    }
    val detailsHeaderTopSpacing = if (panoramaPrefs.isEnabled) {
        compactPanoramaTopBarInset + panoramaExtraHeightDp
    } else {
        overlayTopBarInset
    }
    val landscapeHeaderTopSpacing = if (panoramaPrefs.isEnabled) {
        panoramaExtraHeightDp
    } else {
        0.dp
    }
    val headerCoverVisualAlphaProvider = remember(isWideAdaptiveLayout, compactSheetExpansionProgressProvider) {
        if (isWideAdaptiveLayout) {
            { 1f }
        } else {
            { (1f - compactSheetExpansionProgressProvider()).coerceIn(0f, 1f) }
        }
    }

    val clearChapterSearch: () -> Unit = remember(detailsPaneState, viewModel) {
        {
            detailsPaneState.clearChapterQuery {
                viewModel.performChapterSearch(null)
            }
        }
    }
    val normalizedPrimaryCoverUrl = mangaDetails?.coverUrl?.takeIfUsableImageUri()
    val normalizedFallbackCoverUrl = content?.coverUrl?.takeIfUsableImageUri()
    var hasPanoramaLoadFailed by remember(normalizedPrimaryCoverUrl) { mutableStateOf(false) }
    val currentPanoramaCoverUrl = if (hasPanoramaLoadFailed && normalizedFallbackCoverUrl != null) {
        normalizedFallbackCoverUrl
    } else {
        normalizedPrimaryCoverUrl
            ?: content?.largeCoverUrl?.takeIfUsableImageUri()
            ?: normalizedFallbackCoverUrl
    }
    val handleBackPress = remember(isWideAdaptiveLayout, compactPaneAnchor, detailsPaneState, clearChapterSearch, onBackClick) {
        {
            if (isWideAdaptiveLayout) {
                onBackClick()
            } else {
                detailsPaneState.handleBack(
                    onBackClick = onBackClick,
                    onChapterSearchClosed = clearChapterSearch,
                )
            }
        }
    }

    val shouldInterceptPaneBack = !isWideAdaptiveLayout && detailsPaneState.shouldHandleBack
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        PredictiveBackHandler(enabled = shouldInterceptPaneBack) { progress ->
            try {
                progress.collect { }
                handleBackPress()
            } catch (_: CancellationException) {
                Unit
            }
        }
    } else {
        BackHandler(enabled = shouldInterceptPaneBack) {
            handleBackPress()
        }
    }

    LaunchedEffect(sheetTabSelection, isCompactPaneFullyExpanded) {
        detailsPaneState.syncChapterSearchContext(
            selectedTabId = sheetTabSelection,
            chaptersTabId = DETAILS_TAB_CHAPTERS,
            isSheetFullyExpanded = isCompactPaneFullyExpanded,
            onClosed = clearChapterSearch,
        )
    }

    val updateChapterQuery: (String) -> Unit = remember(detailsPaneState, viewModel) {
        { query ->
            detailsPaneState.updateChapterQuery(query) { searchQuery ->
                viewModel.performChapterSearch(searchQuery)
            }
        }
    }
    val updatePageGridSize: (Float) -> Unit = remember(detailsPaneState, settings) {
        { value ->
            detailsPaneState.updatePageGridSizeValue(value) { updatedValue ->
                settings.gridSizePages = updatedValue.toInt()
            }
        }
    }
    val updatePageThumbnailAspectRatio: (Float) -> Unit = remember(detailsPaneState, settings) {
        { value ->
            detailsPaneState.updatePageThumbnailAspectRatio(value) { updatedValue ->
                settings.pageThumbnailAspectRatio = updatedValue
            }
        }
    }
    val togglePageThumbnailsFitPreview: () -> Unit = remember(settings, isPageThumbnailsFitPreview) {
        {
            settings.isPageThumbnailsFitPreview = !isPageThumbnailsFitPreview
        }
    }
    val toggleChapterSearch: () -> Unit = remember(detailsPaneState, clearChapterSearch) {
        {
            detailsPaneState.toggleChapterSearch(onClosed = clearChapterSearch)
        }
    }
    val persistSelectedPaneTab: (Int) -> Unit = remember(detailsPaneState, availableTabIds, settings) {
        { requestedTabId ->
            detailsPaneState.selectTab(
                requestedTabId = requestedTabId,
                availableTabIds = availableTabIds,
                onPersist = { resolvedTab ->
                    settings.lastDetailsTab = resolvedTab
                },
            )
        }
    }

    val openPaneTab: (Int) -> Unit = remember(
        isWideAdaptiveLayout,
        compactPaneAnchor,
        sheetTabSelection,
        isModernDetailsDockEnabled,
        persistSelectedPaneTab,
    ) {
        { requestedTabId ->
            val shouldCollapseModernPane = isModernDetailsDockEnabled &&
                !isWideAdaptiveLayout &&
                compactPaneAnchor != CompactDetailsPaneAnchor.Collapsed &&
                requestedTabId == sheetTabSelection
            if (isModernDetailsDockEnabled) {
                isModernDockCompact = false
            }
            persistSelectedPaneTab(requestedTabId)
            if (!isWideAdaptiveLayout) {
                if (shouldCollapseModernPane) {
                    detailsPaneState.animateTo(CompactDetailsPaneAnchor.Collapsed)
                } else {
                    detailsPaneState.onOpenPaneRequested()
                }
            }
        }
    }
    val handleActionClick: (DetailsAction) -> Unit = handleDetailsAction@{ action ->
        if (!isWorkActionEnabled && action.isWorkOnlyAction()) {
            return@handleDetailsAction
        }
        when (action) {
            DetailsAction.ToggleList -> {
                openPaneTab(DETAILS_TAB_CHAPTERS)
            }
            DetailsAction.ToggleGrid -> {
                openPaneTab(DETAILS_TAB_PAGES)
            }
            DetailsAction.ToggleBookmarkView -> {
                openPaneTab(DETAILS_TAB_BOOKMARKS)
            }
            DetailsAction.Download -> {
                showDownloadDialog = true
            }
            DetailsAction.OpenReadingRecord -> {
                showReadingRecordSheet = true
            }
            DetailsAction.OpenAlternatives -> {
                if (isWorkActionEnabled) showReadingSourceDialog = true
            }
            else -> onActionClick(action)
        }
    }
    val openEntityRelationItem: (EntityRelationItem) -> Unit = { item ->
        val entityType = item.type
        val service = item.trackingService
        val remoteId = item.remoteId
        when {
            entityType == org.skepsun.kototoro.entitygraph.domain.EntityType.WORK && item.entityId != null -> {
                appRouter.openEntityDetails(
                    entityId = item.entityId,
                    service = service,
                    remoteId = remoteId,
                    url = item.url,
                )
            }
            entityType != null &&
                entityType != org.skepsun.kototoro.entitygraph.domain.EntityType.WORK &&
                service != null &&
                remoteId != null -> {
                appRouter.openTrackingEntityDetails(
                    service = service,
                    entityType = entityType,
                    remoteId = remoteId,
                    name = item.name,
                    coverUrl = item.coverUrl,
                    url = item.url,
                )
            }
            service != null && remoteId != null -> {
                handleActionClick(DetailsAction.OpenTrackingDetails(service, remoteId, item.url))
            }
            item.entityId != null -> {
                appRouter.openEntityDetails(
                    entityId = item.entityId,
                    service = service,
                    remoteId = remoteId,
                    url = item.url,
                )
            }
            !item.url.isNullOrBlank() -> {
                handleActionClick(DetailsAction.OpenWebUrl(item.url))
            }
        }
    }

    val effectiveGlassPrefs = rememberGlassPrefsOrFallback()
    val routeLayerBackdrop = LocalLiquidGlassLayerBackdrop.current
    val detailsBackdropBackground = MaterialTheme.colorScheme.background
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val detailsBackgroundBackdrop = if (isIosStyle) {
        rememberLayerBackdrop {
            drawRect(detailsBackdropBackground)
            drawContent()
        }
    } else {
        null
    }
    val detailsContentBackdrop = if (isIosStyle) rememberLayerBackdrop() else null
    val routeLiquidGlassSourceModifier = if (
        isIosStyle && routeLayerBackdrop != null
    ) {
        Modifier.layerBackdrop(routeLayerBackdrop)
    } else {
        Modifier
    }
    val detailsBackgroundSourceModifier = if (detailsBackgroundBackdrop != null) {
        Modifier.layerBackdrop(detailsBackgroundBackdrop)
    } else {
        Modifier
    }
    val effectivePanoramaInfoCardMidPx = if (
        panoramaPrefs.isScrollLinkedEnabled &&
        initialInfoCardMidPx.isFinite()
    ) {
        initialInfoCardMidPx
    } else {
        infoCardMidPx
    }
    val shouldLimitPanoramaToInfoCardMidpoint =
        panoramaPrefs.limitToInfoCardMidpoint && effectivePanoramaInfoCardMidPx.isFinite()
    val panoramaMaxHeightPx = if (shouldLimitPanoramaToInfoCardMidpoint) {
        effectivePanoramaInfoCardMidPx
    } else {
        null
    }
    val panoramaScrollLinkedTranslationPx = if (panoramaPrefs.isScrollLinkedEnabled) {
        if (isWideAdaptiveLayout) {
            -landscapeLeftScrollState.value.toFloat()
        } else {
            -scrollState.value.toFloat()
        }
    } else {
        0f
    }
    val panoramaFadeDistancePx = remember(density, isWideAdaptiveLayout, initialInfoCardTopPx) {
        when {
            initialInfoCardTopPx.isFinite() -> initialInfoCardTopPx.coerceAtLeast(with(density) { 180.dp.toPx() })
            isWideAdaptiveLayout -> with(density) { 260.dp.toPx() }
            else -> with(density) { 180.dp.toPx() }
        }
    }
    val panoramaContentAlphaProvider = remember(
        panoramaPrefs.isScrollLinkedEnabled,
        isWideAdaptiveLayout,
        scrollState,
        landscapeLeftScrollState,
        panoramaFadeDistancePx,
    ) {
        if (panoramaPrefs.isScrollLinkedEnabled) {
            null
        } else {
            {
                val scrollValue = if (isWideAdaptiveLayout) {
                    landscapeLeftScrollState.value
                } else {
                    scrollState.value
                }
                val fadeProgress = easedOpacityProgress(scrollValue / panoramaFadeDistancePx)
                (1f - fadeProgress).coerceIn(0f, 1f)
            }
        }
    }

    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides detailsBackgroundBackdrop,
        LocalLiquidGlassLayerBackdrop provides detailsBackgroundBackdrop,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(routeLiquidGlassSourceModifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(detailsBackgroundSourceModifier),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface),
                )
                if (panoramaPrefs.isEnabled) {
                    if (currentPanoramaCoverUrl != null || sharedElementKey != null) {
                        val panoramaPlaceholderCacheKey = remember(content?.source?.name, content?.url, normalizedFallbackCoverUrl) {
                            sharedCoverMemoryCacheKey(
                                sourceName = content?.source?.name,
                                ownerKey = content?.url,
                                url = normalizedFallbackCoverUrl,
                            )
                            }
                        val request = remember(content?.source?.name, content?.url, currentPanoramaCoverUrl) {
                            currentPanoramaCoverUrl?.let { coverUrl ->
                                val panoramaCacheKey = sharedCoverMemoryCacheKey(
                                    sourceName = content?.source?.name,
                                    ownerKey = content?.url,
                                    url = coverUrl,
                                )
                                ImageRequest.Builder(context)
                                    .data(coverUrl)
                                    .memoryCacheKey(panoramaCacheKey)
                                    .diskCacheKey(panoramaCacheKey)
                                    .apply { content?.let { mangaExtra(it) } }
                                    .build()
                            }
                        }
                        AnimatedPanoramaBackdrop(
                            prefs = panoramaPrefs,
                            model = request,
                            placeholderMemoryCacheKey = panoramaPlaceholderCacheKey,
                            snapshotKey = sharedElementKey,
                            contentAlpha = 1f,
                            contentAlphaProvider = panoramaContentAlphaProvider,
                            backgroundColor = MaterialTheme.colorScheme.surface,
                            crossfadeEnabled = false,
                            onLoadError = {
                                if (!hasPanoramaLoadFailed && normalizedFallbackCoverUrl != null && normalizedFallbackCoverUrl != normalizedPrimaryCoverUrl) {
                                    hasPanoramaLoadFailed = true
                                }
                            },
                            fadeToBackground = true,
                            maxHeightPx = panoramaMaxHeightPx,
                            scrollLinkedTranslationYPx = panoramaScrollLinkedTranslationPx,
                            modifier = Modifier,
                        )
                    }
                }
            }
            val commonTopBar: @Composable () -> Unit = {
                val titleAlpha = ((toolbarTitleProgressProvider() - 0.82f) / 0.18f).coerceIn(0f, 1f)
                val panoramaTopBarContainerColor = if (panoramaPrefs.isEnabled) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    null
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val bottom = coordinates.boundsInRoot().bottom
                            toolbarBottomPx = bottom
                            if (bottom.isFinite() && bottom > 0f) {
                                lastToolbarBottomPx = bottom
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(interfaceStyleTokens.mainTopBarHeight)
                            .padding(
                                horizontal = if (isWideAdaptiveLayout) {
                                    0.dp
                                } else {
                                    CompactTopBarHorizontalPadding
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
                    ) {
                        TopBarControlSurface(
                            fallbackContainerColor = panoramaTopBarContainerColor,
                            shadowElevation = DetailsTopChromeShadowElevation,
                        ) {
                            CompositionLocalProvider(
                                LocalMinimumInteractiveComponentSize provides interfaceStyleTokens.topBarButtonSize,
                            ) {
                                DetailsChromeButton(
                                    onClick = handleBackPress,
                                    modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                        modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = toolbarTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    alpha = titleAlpha
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        TopBarControlSurface(
                            fallbackContainerColor = panoramaTopBarContainerColor,
                            shadowElevation = DetailsTopChromeShadowElevation,
                        ) {
                            CompositionLocalProvider(
                                LocalMinimumInteractiveComponentSize provides interfaceStyleTokens.topBarButtonSize,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .height(interfaceStyleTokens.topBarButtonSize)
                                        .padding(horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    activeSpaceId?.let { spaceId ->
                                        DetailsChromeButton(
                                            onClick = onSpaceSwitcherClick,
                                            modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
                                        ) {
                                            SpaceSwitcherIcon(
                                                activeSpaceId = spaceId,
                                                modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
                                            )
                                        }
                                    }
                                    DetailsChromeButton(
                                        onClick = {
                                            showShareOptions = true
                                        },
                                        modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.share),
                                            modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
                                        )
                                    }
                                    if (isWorkActionEnabled) {
                                        DetailsChromeButton(
                                            onClick = {
                                                handleActionClick(DetailsAction.Download)
                                            },
                                            modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
                                        ) {
                                            Icon(
                                                painter = rememberSafePainter(R.drawable.ic_download),
                                                contentDescription = stringResource(R.string.download),
                                                modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
                                            )
                                        }
                                    }
                                    DetailsOverflowMenu(
                                        contentTitle = content?.title,
                                        showTranslateAction = showTranslateAction,
                                        hasTranslationCache = hasTranslationCache,
                                        isShowingTranslation = isShowingTranslation,
                                        isTranslating = isTranslating,
                                        hasMetadataBrowserTarget = metadataBrowserTarget != null,
                                        hasLocalBrowserTarget = isWorkActionEnabled && localBrowserTarget != null,
                                        localBrowserTitleRes = when (contentType) {
                                            ContentType.VIDEO,
                                            ContentType.HENTAI_VIDEO -> R.string.open_playback_page_in_browser
                                            else -> R.string.open_reading_page_in_browser
                                        },
                                        hasOnlineVariant = isWorkActionEnabled && remoteContent != null,
                                        isReadingRecordAvailable = isWorkActionEnabled,
                                        isDeleteLocalAvailable = isWorkActionEnabled && content?.source == LocalMangaSource,
                                        isEditOverrideAvailable = isWorkActionEnabled && content != null,
                                        isShortcutSupported = isWorkActionEnabled && isShortcutSupported && content != null,
                                        isNsfw = content?.isNsfw() == true,
                                        onDeleteLocalRequest = { handleActionClick(DetailsAction.DeleteLocal) },
                                        onActionClick = { action ->
                                            when (action) {
                                                is DetailsAction.OpenMetadataInBrowser -> {
                                                    metadataBrowserTarget?.let {
                                                        handleActionClick(
                                                            DetailsAction.OpenBrowserPage(
                                                                it.url,
                                                                it.source,
                                                                it.title,
                                                            ),
                                                        )
                                                    }
                                                }

                                                is DetailsAction.OpenLocalSourceInBrowser -> {
                                                    localBrowserTarget?.let {
                                                        handleActionClick(
                                                            DetailsAction.OpenBrowserPage(
                                                                it.url,
                                                                it.source,
                                                                it.title,
                                                            ),
                                                        )
                                                    }
                                                }

                                                else -> handleActionClick(action)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isWideAdaptiveLayout) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(
                                horizontal = CompactTopBarHorizontalPadding,
                                vertical = 8.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Scaffold(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            containerColor = Color.Transparent,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                CompositionLocalProvider(
                                    LocalLiquidGlassBackdrop provides detailsContentBackdrop,
                                    LocalLiquidGlassLayerBackdrop provides detailsContentBackdrop,
                                ) {
                                    commonTopBar()
                                }
                            },
                        ) { paddingValues ->
                            KototoroPullToRefreshBox(
                                isRefreshing = isLoading,
                                onRefresh = { viewModel.reload() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (detailsContentBackdrop != null) {
                                            Modifier.layerBackdrop(detailsContentBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                indicatorTopInset = paddingValues,
                            ) {
                                DetailsScrollableContent(
                                    modifier = Modifier.fillMaxSize(),
                                    scrollState = landscapeLeftScrollState,
                                    contentPadding = paddingValues,
                                    outerHorizontalPadding = 0.dp,
                                    headerTopSpacing = landscapeHeaderTopSpacing,
                                    bottomSpacerHeight = 40.dp,
                                    preferLightweightFirstFrame = false,
                                    mangaDetails = mangaDetails,
                                    localSize = localSize,
                                    favouriteCategories = favouriteCategories,
                                    historyInfo = historyInfo,
                                    linkedTrackingItems = linkedTrackingItems,
                                    readingStatus = readingStatus,
                                    unifiedRating = unifiedRating,
                                    canEditUnifiedRating = canEditUnifiedRating,
                                    trackingSuggestion = trackingSuggestion,
                                    metadataSourceOptions = metadataSourceOptions,
                                    readingSourceOptions = readingSourceOptions,
                                    activeLocalSourceOptions = activeLocalSourceOptions,
                                    entityChapterSourceInfo = entityChapterSourceInfo,
                                    relatedContent = relatedContent,
                                    supplementalMetadataProperties = supplementalMetadataProperties,
                                    supplementalSections = supplementalSections,
                                    supplementalActions = supplementalActions,
                                    resolvedContentType = contentType,
                                    resolvedMetadataLanguage = resolvedMetadataLanguage,
                                    resolvedReadingLanguage = resolvedReadingLanguage,
                                    entityRelationSections = entityRelationSections,
                                    translatedTitle = translatedTitle,
                                    translatedDescription = translatedDescription,
                                    isShowingTranslation = isShowingTranslation,
                                    settings = settings,
                                    collapseProgressProvider = remember { { 0f } },
                                    coverVisualAlphaProvider = remember { { 1f } },
                                    coverUrl = mangaDetails?.coverUrl?.takeIfUsableImageUri()
                                        ?: content?.coverUrl?.takeIfUsableImageUri(),
                                    fallbackCoverUrl = content?.coverUrl?.takeIfUsableImageUri(),
                                    content = content,
                                    isTemporaryReadOnly = isTemporaryReadOnly,
                                    isWorkDetails = isWorkDetails,
                                    sharedElementKey = sharedElementKey,
                                    pendingTagSearch = { pendingTagSearch = it },
                                    pendingAuthorSearch = { author, source ->
                                        pendingAuthorSearch = PendingAuthorSearch(author = author, source = source)
                                    },
                                    onInfoCardBoundsSync = syncInfoCardBounds,
                                    onFavoriteClick = { showFavoriteDialog = true },
                                    onSupplementalRelationClick = { item ->
                                        when {
                                            shouldOpenTrackingRelationSheet(item) -> {
                                                selectedSupplementalRelationItem = item
                                            }
                                            !item.url.isNullOrBlank() -> {
                                                handleActionClick(DetailsAction.OpenWebUrl(item.url))
                                            }
                                        }
                                    },
                                    onOpenMetadataSourceSheet = {
                                        if (!isTemporaryReadOnly) showMetadataSourceDialog = true
                                    },
                                    onOpenReadingSourceSheet = {
                                        if (isWorkActionEnabled) showReadingSourceDialog = true
                                    },
                                    onUpdateLinkedTrackingStatus = { linked, status ->
                                        viewModel.updateScrobbling(
                                            scrobblerServiceId = linked.service.id,
                                            rating = linked.rating ?: 0f,
                                            status = status,
                                        )
                                    },
                                    onUpdateReadingStatus = viewModel::updateUnifiedReadingStatus,
                                    onUpdateUnifiedRating = viewModel::updateUnifiedRating,
                                    onEntityClick = openEntityRelationItem,
                                    onActionClick = handleActionClick,
                                )
                            }
                        }
                        if (isWorkDetails) {
                            val widePaneTopPadding = statusBarTopPadding +
                                (interfaceStyleTokens.mainTopBarHeight - interfaceStyleTokens.topBarButtonSize) / 2
                            Surface(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f)
                                    .padding(
                                        top = widePaneTopPadding,
                                        bottom = navigationBarBottomPadding,
                                    ),
                                color = Color.Transparent,
                                shape = RoundedCornerShape(28.dp),
                                tonalElevation = 0.dp,
                            ) {
                                CompositionLocalProvider(
                                    LocalLiquidGlassBackdrop provides detailsBackgroundBackdrop,
                                    LocalLiquidGlassLayerBackdrop provides detailsBackgroundBackdrop,
                                ) {
                                    DetailsPaneContent(
                                    detailsPaneState = detailsPaneState,
                                    contentType = contentType,
                                    historyInfo = historyInfo,
                                    branches = branches,
                                    isLoading = isLoading,
                                    viewModel = viewModel,
                                    pagesViewModel = pagesViewModel,
                                    bookmarksViewModel = bookmarksViewModel,
                                    settings = settings,
                                    appRouter = appRouter,
                                    pageSaveHelper = pageSaveHelper,
                                    metadataChapterTabs = metadataChapterTabs,
                                    readingChapterTabs = readingChapterTabs,
                                    onSelectMetadataChapterTab = { tab ->
                                        val matchingOption = metadataSourceOptions.firstOrNull { option -> option.key == tab.key }
                                            ?: return@DetailsPaneContent
                                        viewModel.selectMetadataSource(matchingOption)
                                    },
                                    onSelectReadingChapterTab = { tab ->
                                        tab.targetMangaId?.let(viewModel::selectActiveLocalSource)
                                    },
                                    selectedTabId = sheetTabSelection,
                                    availableTabIds = availableTabIds,
                                    isSheetFullyExpanded = false,
                                    sheetExpansionProgressProvider = remember { { 0f } },
                                    isChapterSearchAvailable = chapterEmptyReason == null,
                                    isChaptersReversed = isChaptersReversed,
                                    isChaptersInGridView = isChaptersInGridView,
                                    isHideReadChapters = isHideReadChapters,
                                    isMergeRepeatedChapters = isMergeRepeatedChapters,
                                    showMergeRepeatedChapters = showMergeRepeatedChapters,
                                    isDownloadedOnly = isDownloadedOnly,
                                    isDownloadedFilterVisible = mangaDetails?.local != null,
                                    pageGridSizeValue = pageGridSizeValue,
                                    pageThumbnailAspectRatio = pageThumbnailAspectRatioValue,
                                    isPageThumbnailsFitPreview = isPageThumbnailsFitPreview,
                                    onChapterQueryChange = updateChapterQuery,
                                    onChapterSearchToggle = toggleChapterSearch,
                                    onToggleChaptersReversed = { viewModel.setChaptersReversed(!isChaptersReversed) },
                                    onToggleChaptersGrid = { viewModel.setChaptersInGridView(!isChaptersInGridView) },
                                    onToggleHideReadChapters = { viewModel.setHideReadChapters(!isHideReadChapters) },
                                    onToggleMergeRepeatedChapters = { viewModel.setMergeRepeatedChapters(!isMergeRepeatedChapters) },
                                    onToggleDownloadedOnly = { viewModel.isDownloadedOnly.value = !isDownloadedOnly },
                                    onPageGridSizeChange = updatePageGridSize,
                                    onPageThumbnailAspectRatioChange = updatePageThumbnailAspectRatio,
                                    onTogglePageThumbnailsFitPreview = togglePageThumbnailsFitPreview,
                                    showCollapsedHandle = false,
                                    isModernDetailsDockEnabled = false,
                                    isModernDockCompact = false,
                                    onSelectedTabIdChange = persistSelectedPaneTab,
                                    onActionClick = handleActionClick,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        detailsPaneState.onHostHeightChanged(size.height.toFloat())
                    },
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { paddingValues ->
                            KototoroPullToRefreshBox(
                                isRefreshing = isLoading,
                                onRefresh = { viewModel.reload() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (detailsContentBackdrop != null) {
                                            Modifier.layerBackdrop(detailsContentBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        indicatorTopInset = paddingValues,
                    ) {
                        DetailsScrollableContent(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = (1f - compactSheetExpansionProgressProvider()).coerceIn(0f, 1f)
                                    },
                                scrollState = scrollState,
                                contentPadding = paddingValues,
                                headerTopSpacing = detailsHeaderTopSpacing,
                                bottomSpacerHeight = if (isWorkDetails) compactPaneCollapsedHeight + 28.dp else 28.dp,
                                preferLightweightFirstFrame = false,
                                mangaDetails = mangaDetails,
                                localSize = localSize,
                                favouriteCategories = favouriteCategories,
                                historyInfo = historyInfo,
                                linkedTrackingItems = linkedTrackingItems,
                                readingStatus = readingStatus,
                                unifiedRating = unifiedRating,
                                canEditUnifiedRating = canEditUnifiedRating,
                                trackingSuggestion = trackingSuggestion,
                                metadataSourceOptions = metadataSourceOptions,
                                readingSourceOptions = readingSourceOptions,
                                activeLocalSourceOptions = activeLocalSourceOptions,
                                entityChapterSourceInfo = entityChapterSourceInfo,
                                relatedContent = relatedContent,
                                supplementalMetadataProperties = supplementalMetadataProperties,
                                supplementalSections = supplementalSections,
                                supplementalActions = supplementalActions,
                                resolvedContentType = contentType,
                                resolvedMetadataLanguage = resolvedMetadataLanguage,
                                resolvedReadingLanguage = resolvedReadingLanguage,
                                entityRelationSections = entityRelationSections,
                                translatedTitle = translatedTitle,
                                translatedDescription = translatedDescription,
                                isShowingTranslation = isShowingTranslation,
                                settings = settings,
                                collapseProgressProvider = compactCollapseProgressProvider,
                                coverVisualAlphaProvider = headerCoverVisualAlphaProvider,
                                coverUrl = mangaDetails?.coverUrl?.takeIfUsableImageUri()
                                    ?: content?.coverUrl?.takeIfUsableImageUri(),
                                fallbackCoverUrl = content?.coverUrl?.takeIfUsableImageUri(),
                                content = content,
                                isTemporaryReadOnly = isTemporaryReadOnly,
                                isWorkDetails = isWorkDetails,
                                sharedElementKey = sharedElementKey,
                                pendingTagSearch = { pendingTagSearch = it },
                                pendingAuthorSearch = { author, source ->
                                    pendingAuthorSearch = PendingAuthorSearch(author = author, source = source)
                                },
                                onInfoCardBoundsSync = syncInfoCardBounds,
                                onFavoriteClick = { showFavoriteDialog = true },
                                onSupplementalRelationClick = { item ->
                                    when {
                                        shouldOpenTrackingRelationSheet(item) -> {
                                            selectedSupplementalRelationItem = item
                                        }
                                        !item.url.isNullOrBlank() -> {
                                            handleActionClick(DetailsAction.OpenWebUrl(item.url))
                                        }
                                    }
                                },
                                onOpenMetadataSourceSheet = {
                                    if (!isTemporaryReadOnly) showMetadataSourceDialog = true
                                },
                                onOpenReadingSourceSheet = {
                                    if (isWorkActionEnabled) showReadingSourceDialog = true
                                },
                                onUpdateLinkedTrackingStatus = { linked, status ->
                                    viewModel.updateScrobbling(
                                        scrobblerServiceId = linked.service.id,
                                        rating = linked.rating ?: 0f,
                                        status = status,
                                    )
                                },
                                onUpdateReadingStatus = viewModel::updateUnifiedReadingStatus,
                                onUpdateUnifiedRating = viewModel::updateUnifiedRating,
                                onEntityClick = openEntityRelationItem,
                                onActionClick = handleActionClick,
                            )
                    }
                }
                if (isWorkDetails) {
                    DetailsPaneHost(
                        state = detailsPaneState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .zIndex(1f),
                    ) {
                                CompositionLocalProvider(
                                    LocalLiquidGlassBackdrop provides detailsBackgroundBackdrop,
                                    LocalLiquidGlassLayerBackdrop provides detailsBackgroundBackdrop,
                                ) {
                            DetailsPaneContent(
                            detailsPaneState = detailsPaneState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(compactPaneHeight),
                            contentType = contentType,
                            historyInfo = historyInfo,
                            branches = branches,
                            isLoading = isLoading,
                            viewModel = viewModel,
                            pagesViewModel = pagesViewModel,
                            bookmarksViewModel = bookmarksViewModel,
                            settings = settings,
                            appRouter = appRouter,
                            pageSaveHelper = pageSaveHelper,
                            metadataChapterTabs = metadataChapterTabs,
                            readingChapterTabs = readingChapterTabs,
                            onSelectMetadataChapterTab = { tab ->
                                val matchingOption = metadataSourceOptions.firstOrNull { option -> option.key == tab.key } ?: return@DetailsPaneContent
                                viewModel.selectMetadataSource(matchingOption)
                            },
                            onSelectReadingChapterTab = { tab ->
                                tab.targetMangaId?.let(viewModel::selectActiveLocalSource)
                            },
                            selectedTabId = sheetTabSelection,
                            availableTabIds = availableTabIds,
                            isSheetFullyExpanded = isCompactPaneFullyExpanded,
                            sheetExpansionProgressProvider = compactSheetExpansionProgressProvider,
                            isChapterSearchAvailable = chapterEmptyReason == null,
                            isChaptersReversed = isChaptersReversed,
                            isChaptersInGridView = isChaptersInGridView,
                            isHideReadChapters = isHideReadChapters,
                            isMergeRepeatedChapters = isMergeRepeatedChapters,
                            showMergeRepeatedChapters = showMergeRepeatedChapters,
                            isDownloadedOnly = isDownloadedOnly,
                            isDownloadedFilterVisible = mangaDetails?.local != null,
                            pageGridSizeValue = pageGridSizeValue,
                            pageThumbnailAspectRatio = pageThumbnailAspectRatioValue,
                            isPageThumbnailsFitPreview = isPageThumbnailsFitPreview,
                            onChapterQueryChange = updateChapterQuery,
                            onChapterSearchToggle = toggleChapterSearch,
                            onToggleChaptersReversed = { viewModel.setChaptersReversed(!isChaptersReversed) },
                            onToggleChaptersGrid = { viewModel.setChaptersInGridView(!isChaptersInGridView) },
                            onToggleHideReadChapters = { viewModel.setHideReadChapters(!isHideReadChapters) },
                            onToggleMergeRepeatedChapters = { viewModel.setMergeRepeatedChapters(!isMergeRepeatedChapters) },
                            onToggleDownloadedOnly = { viewModel.isDownloadedOnly.value = !isDownloadedOnly },
                            onPageGridSizeChange = updatePageGridSize,
                            onPageThumbnailAspectRatioChange = updatePageThumbnailAspectRatio,
                            onTogglePageThumbnailsFitPreview = togglePageThumbnailsFitPreview,
                            showCollapsedHandle = true,
                            isModernDetailsDockEnabled = isModernDetailsDockEnabled,
                            isModernDockCompact = isModernDockCompact,
                            onSelectedTabIdChange = persistSelectedPaneTab,
                            onActionClick = handleActionClick,
                            )
                        }
                    }
                }
            }
            val detailsImmersiveStrength = ((LocalGlassPrefs.current?.immersiveStrengthPercent ?: 65).coerceIn(0, 100)) / 100f
            val detailsImmersiveIsDark = isSystemInDarkTheme()
            val detailsImmersiveBase = if (detailsImmersiveIsDark) Color.Black else Color.White
            val detailsTopImmersiveHeight = with(density) {
                val sbPx = statusBarTopPadding.roundToPx()
                val tbPx = interfaceStyleTokens.mainTopBarHeight.roundToPx()
                val overflowPx = 6.dp.roundToPx()
                (sbPx + tbPx + overflowPx).coerceAtLeast(sbPx + overflowPx).toDp()
            }
            if (detailsGradientAlpha > 0.01f) {
                ImmersiveEdgeGradient(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = if (isWideAdaptiveLayout) {
                                detailsGradientAlpha
                            } else {
                                detailsGradientAlpha *
                                    (1f - compactSheetExpansionProgressProvider()).coerceIn(0f, 1f)
                            }
                        },
                    height = detailsTopImmersiveHeight + ImmersiveEdgeFeatherExtension,
                    colors = listOf(
                        detailsImmersiveBase.copy(alpha = (0.72f + (0.98f - 0.72f) * detailsImmersiveStrength)),
                        detailsImmersiveBase.copy(alpha = (0.56f + (0.82f - 0.56f) * detailsImmersiveStrength)),
                        detailsImmersiveBase.copy(alpha = (0.32f + (0.52f - 0.32f) * detailsImmersiveStrength)),
                        detailsImmersiveBase.copy(alpha = (0.12f + (0.22f - 0.12f) * detailsImmersiveStrength)),
                        detailsImmersiveBase.toTransparentImmersiveColor(),
                    ),
                    stops = ImmersiveTopGradientStops,
                )
            }
            if (isWideAdaptiveLayout || compactPaneAnchor != CompactDetailsPaneAnchor.Full) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            alpha = if (isWideAdaptiveLayout) {
                                1f
                            } else {
                                (1f - compactSheetExpansionProgressProvider()).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    commonTopBar()
                }
            }
            }

            pendingAuthorSearch?.let { pending ->
            SearchTargetDialog(
                iconRes = R.drawable.ic_user,
                title = pending.author,
                sourceTitle = rememberResolvedSourceTitle(pending.source),
                onDismissRequest = { pendingAuthorSearch = null },
                onSearchOnSource = {
                    pendingAuthorSearch = null
                    handleActionClick(DetailsAction.SearchAuthorOnSource(pending.author, pending.source))
                },
                onSearchEverywhere = {
                    pendingAuthorSearch = null
                    handleActionClick(DetailsAction.SearchAuthorEverywhere(pending.author))
                },
            )
            }

            pendingTagSearch?.let { tag ->
            SearchTargetDialog(
                iconRes = R.drawable.ic_tag,
                title = tag.title,
                sourceTitle = rememberResolvedSourceTitle(tag.source),
                onDismissRequest = { pendingTagSearch = null },
                onSearchOnSource = {
                    pendingTagSearch = null
                    handleActionClick(DetailsAction.SearchTagOnSource(tag))
                },
                onSearchEverywhere = {
                    pendingTagSearch = null
                    handleActionClick(DetailsAction.SearchTagEverywhere(tag.title))
                },
            )
            }

            if (showShareOptions && content != null) {
            ShareOptionsDialog(
                title = content.title,
                sourceTitle = rememberResolvedSourceTitle(content.source),
                onDismissRequest = { showShareOptions = false },
                onShareAppLink = {
                    showShareOptions = false
                    handleActionClick(
                        DetailsAction.ShareLink(
                            title = content.title,
                            link = content.appUrl.toString(),
                        ),
                    )
                },
                onShareSourceLink = {
                    showShareOptions = false
                    handleActionClick(
                        DetailsAction.ShareLink(
                            title = content.title,
                            link = content.publicUrl,
                        ),
                    )
                },
            )
            }

            if (showDeleteLocalDialog && content != null) {
            DeleteLocalDialog(
                title = content.title,
                onDismissRequest = { showDeleteLocalDialog = false },
                onConfirm = {
                    showDeleteLocalDialog = false
                    handleActionClick(DetailsAction.DeleteLocal)
                },
            )
            }

            if (showFavoriteDialog && isWorkActionEnabled && content != null) {
            val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
            val duplicateFavoritePrompt by viewModel.duplicateFavoritePrompt.collectAsStateWithLifecycle()
            val memberCategoryIds = remember(favouriteCategories) {
                favouriteCategories.mapTo(mutableSetOf()) { it.id }
            }
            FavoriteCategoryDialog(
                contentTitle = content.title,
                allCategories = allCategories,
                memberCategoryIds = memberCategoryIds,
                onCategoryToggle = { categoryId, isChecked ->
                    viewModel.setFavouriteCategory(categoryId, isChecked)
                },
                onManageCategories = {
                    showFavoriteDialog = false
                    handleActionClick(DetailsAction.ManageCategories)
                },
                onDismiss = { showFavoriteDialog = false },
            )
            DuplicateFavoritePromptDialog(
                prompt = duplicateFavoritePrompt,
                onConfirm = viewModel::confirmDuplicateFavourite,
                onMergeBack = viewModel::mergeBackDuplicateFavourite,
                onDismiss = viewModel::dismissDuplicateFavourite,
            )
            }

            if (showDownloadDialog && isWorkActionEnabled && content != null) {
            DownloadDialog(
                mangaList = listOf(content),
                snackbarHostState = snackbarHostState,
                onOpenDownloads = appRouter::openDownloads,
                viewModel = downloadDialogViewModel,
                onDismiss = { showDownloadDialog = false },
            )
            }

            if (showReadingRecordSheet && isWorkActionEnabled && content != null) {
                val statsViewModel = if (isStatsAvailable) {
                    hiltViewModel<ContentStatsViewModel>(key = "details-reading-stats-${content.id}")
                } else {
                    null
                }
                LaunchedEffect(content.id, statsViewModel) {
                    statsViewModel?.initialize(content)
                }
                ReadingRecordSheet(
                    manga = content,
                    statsViewModel = statsViewModel,
                    snapshot = readingRecordSnapshot,
                    chapterTitle = { chapterId ->
                        content.chapters
                            ?.firstOrNull { it.id == chapterId }
                            ?.getLocalizedTitle(context.resources)
                            ?: context.getString(R.string.chapter_number, chapterId.toString())
                    },
                    progressPercent = historyInfo.percent,
                    onDismissRequest = { showReadingRecordSheet = false },
                    onJumpPointClick = { point ->
                        showReadingRecordSheet = false
                        appRouter.openReader(
                            org.skepsun.kototoro.core.nav.ReaderIntent.Builder(context)
                                .manga(content)
                                .state(ReaderState(point.fromChapterId, point.fromPage, point.fromScroll))
                                .build(),
                        )
                    },
                )
            }

            if (showMetadataSourceDialog) {
                MetadataSourceSheet(
                    currentOptions = metadataSourceOptions,
                    selectedOption = metadataSourceOptions.firstOrNull { it.isSelected },
                    searchServices = metadataSearchServices,
                    authorizedServices = authorizedTrackingServices,
                    searchQuery = metadataSearchQuery,
                    searchSections = metadataSearchSections,
                    isLoading = metadataSearchLoading,
                    hasSearched = metadataSearchHasSearched,
                    currentContent = content,
                    unavailableText = stringResource(R.string.details_metadata_binding_unavailable),
                    linkedTrackingItems = linkedTrackingItems,
                    scrobblingStatuses = arrayOf(
                        stringResource(R.string.status_planned),
                        stringResource(R.string.status_reading),
                        stringResource(R.string.status_re_reading),
                        stringResource(R.string.status_completed),
                        stringResource(R.string.status_on_hold),
                        stringResource(R.string.status_dropped),
                    ),
                    onDismissRequest = { showMetadataSourceDialog = false },
                    onSelectOption = viewModel::selectMetadataSource,
                    onRemoveOption = viewModel::removeMetadataSourceBinding,
                    onSearchQueryChange = viewModel::updateMetadataSearchQuery,
                    onSearch = viewModel::searchMetadataBindings,
                    onBindResult = viewModel::bindMetadataSource,
                    onOpenResult = { item ->
                        onActionClick(DetailsAction.OpenTrackingDetails(item.service, item.remoteId, item.url))
                    },
                    onOpenLinkedTracking = { linked ->
                        onActionClick(DetailsAction.OpenTrackingDetails(linked.service, linked.remoteId, linked.url))
                    },
                    onUpdateLinkedTrackingStatus = { linked, status ->
                        viewModel.updateScrobbling(
                            scrobblerServiceId = linked.service.id,
                            rating = linked.rating ?: 0f,
                            status = status,
                        )
                    },
                )
            }

            if (showReadingSourceDialog && isWorkActionEnabled) {
                ReadingSourceSheet(
                    currentOptions = readingSourceOptions,
                    selectedOption = readingSourceOptions.firstOrNull { it.isSelected },
                    label = stringResource(readingSourceLabelRes),
                    searchSources = readingSearchSources,
                    searchQuery = readingSearchQuery,
                    searchSections = readingSearchSections,
                    isLoading = readingSearchLoading,
                    hasSearched = readingSearchHasSearched,
                    scopeFilterUiState = readingSearchScopeFilterUiState,
                    languagePresets = languagePresets,
                    activeLanguagePresetId = activeLanguagePresetId,
                    currentContent = content,
                    entityChapterSourceInfo = entityChapterSourceInfo,
                    unavailableText = stringResource(R.string.details_reading_source_unavailable),
                    onSelectOption = { option -> option.targetMangaId?.let(viewModel::selectActiveLocalSource) },
                    onSearchQueryChange = viewModel::updateReadingSearchQuery,
                    onSearch = viewModel::searchReadingBindings,
                    onLanguagePresetSelected = viewModel::setActiveLanguagePreset,
                    onManageLanguagePresets = appRouter::openSourcePresets,
                    onSourceTypeToggle = viewModel::toggleReadingSearchSourceType,
                    onContentKindToggle = viewModel::toggleReadingSearchContentKind,
                    onPinnedOnlyChange = viewModel::setReadingSearchPinnedOnly,
                    onHideEmptyChange = viewModel::setReadingSearchHideEmpty,
                    onTemporaryOpenResult = { candidate ->
                        appRouter.openTemporaryDetails(candidate)
                    },
                    onMigrateResult = { candidate ->
                        viewModel.bindReadingCandidateToTracking(candidate) {
                            showReadingSourceDialog = false
                        }
                    },
                    onDeleteProjection = { option ->
                        option.targetMangaId?.let(viewModel::removeActiveLocalSource)
                    },
                    onActivateProjection = { option ->
                        option.targetMangaId?.let(viewModel::selectActiveLocalSource)
                    },
                    onDismissRequest = { showReadingSourceDialog = false },
                )
            }

            if (showCommentsDialog) {
                TrackingCommentsSheet(
                    threads = supplementalCommentThreads,
                    externalUrl = supplementalCommentsUrl,
                    onDismissRequest = { showCommentsDialog = false },
                    onOpenExternal = { url ->
                        showCommentsDialog = false
                        handleActionClick(DetailsAction.OpenWebUrl(url))
                    },
                )
            }

            if (showReviewsDialog) {
                TrackingReviewsSheet(
                    reviews = supplementalReviews,
                    externalUrl = supplementalReviewsUrl,
                    onDismissRequest = { showReviewsDialog = false },
                    onOpenExternal = { url ->
                        showReviewsDialog = false
                        handleActionClick(DetailsAction.OpenWebUrl(url))
                    },
                )
            }

            selectedSupplementalRelationItem?.let { item ->
                TrackingRelationItemSheet(
                    item = item,
                    onDismissRequest = { selectedSupplementalRelationItem = null },
                    onOpenExternal = { url ->
                        selectedSupplementalRelationItem = null
                        handleActionClick(DetailsAction.OpenWebUrl(url))
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailsScrollableContent(
    mangaDetails: org.skepsun.kototoro.details.data.ContentDetails?,
    localSize: Long,
    historyInfo: HistoryInfo,
    favouriteCategories: Set<org.skepsun.kototoro.core.model.FavouriteCategory>,
    linkedTrackingItems: List<org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel>,
    readingStatus: ScrobblingStatus,
    unifiedRating: Float,
    canEditUnifiedRating: Boolean,
    trackingSuggestion: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult?,
    metadataSourceOptions: List<DetailsSourceOption>,
    readingSourceOptions: List<DetailsSourceOption>,
    activeLocalSourceOptions: List<ActiveLocalSourceOption>,
    entityChapterSourceInfo: EntityChapterSourceInfo?,
    relatedContent: List<ContentListModel>,
    supplementalMetadataProperties: List<Pair<String, String>>,
    supplementalSections: List<EntityRelationSection>,
    supplementalActions: List<DetailsSupplementAction>,
    resolvedContentType: ContentType?,
    resolvedMetadataLanguage: String?,
    resolvedReadingLanguage: String?,
    entityRelationSections: List<EntityRelationSection>,
    translatedTitle: String?,
    translatedDescription: String?,
    isShowingTranslation: Boolean,
    settings: org.skepsun.kototoro.core.prefs.AppSettings,
    collapseProgressProvider: () -> Float,
    coverVisualAlphaProvider: () -> Float,
    coverUrl: String?,
    fallbackCoverUrl: String?,
    content: org.skepsun.kototoro.parsers.model.Content?,
    isTemporaryReadOnly: Boolean,
    isWorkDetails: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    outerHorizontalPadding: Dp = AppLayoutTokens.screenHorizontalPadding,
    headerTopSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    bottomSpacerHeight: androidx.compose.ui.unit.Dp,
    preferLightweightFirstFrame: Boolean = false,
    pendingTagSearch: (ContentTag) -> Unit,
    pendingAuthorSearch: (String, ContentSource) -> Unit,
    onInfoCardBoundsSync: (Float, Float) -> Unit,
    onFavoriteClick: () -> Unit,
    onSupplementalRelationClick: (EntityRelationItem) -> Unit,
    onOpenMetadataSourceSheet: () -> Unit,
    onOpenReadingSourceSheet: () -> Unit,
    onUpdateLinkedTrackingStatus: (org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel, ScrobblingStatus) -> Unit,
    onUpdateReadingStatus: (ScrobblingStatus) -> Unit,
    onUpdateUnifiedRating: (Float) -> Unit,
    onEntityClick: (EntityRelationItem) -> Unit,
    onActionClick: (DetailsAction) -> Unit,
    sharedElementKey: String? = null,
) {
    val context = LocalContext.current
    val isWorkActionEnabled = isWorkDetails && !isTemporaryReadOnly
    val source = content?.source
    val visibleSupplementalSections = remember(preferLightweightFirstFrame, supplementalSections, entityRelationSections) {
        if (preferLightweightFirstFrame) {
            return@remember emptyList()
        }
        val hasEntityCharacterSection = entityRelationSections.any { it.titleRes == R.string.entity_graph_section_characters }
        if (hasEntityCharacterSection) {
            supplementalSections.filterNot { it.titleRes == R.string.entity_graph_section_characters }
        } else {
            supplementalSections
        }
    }
    Column(
        modifier = modifier
            .padding(contentPadding)
            .verticalScroll(scrollState),
    ) {
        if (headerTopSpacing > 0.dp) {
            Spacer(modifier = Modifier.height(headerTopSpacing))
        }
        if (isTemporaryReadOnly || !isWorkDetails) {
            TemporaryDetailsReadOnlyNotice(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = outerHorizontalPadding, vertical = 8.dp),
            )
        }
        DetailsHeader(
            mangaDetails = mangaDetails,
            localSize = localSize,
            favouriteCategories = favouriteCategories,
            historyInfo = historyInfo,
            linkedTrackingItems = linkedTrackingItems,
            readingStatus = readingStatus,
            unifiedRating = unifiedRating,
            canEditUnifiedRating = canEditUnifiedRating,
            trackingSuggestion = trackingSuggestion,
            metadataSourceOptions = metadataSourceOptions,
            readingSourceOptions = readingSourceOptions,
            supplementalActions = supplementalActions,
            resolvedContentType = resolvedContentType,
            metadataLanguageCode = resolvedMetadataLanguage,
            readingLanguageCode = resolvedReadingLanguage,
            translatedTitle = translatedTitle,
            translatedDescription = translatedDescription,
            isShowingTranslation = isShowingTranslation,
            panoramaEnabled = settings.isPanoramaCoverEnabled,
            settings = settings,
            collapseProgressProvider = collapseProgressProvider,
            coverVisualAlphaProvider = coverVisualAlphaProvider,
            coverUrl = coverUrl,
            fallbackCoverUrl = fallbackCoverUrl,
            sharedElementKey = sharedElementKey,
            showWorkActions = isWorkActionEnabled,
            outerHorizontalPadding = outerHorizontalPadding,

            onInfoCardBoundsSync = onInfoCardBoundsSync,
            onCoverClick = { onActionClick(DetailsAction.OpenCover) },
            onFavoriteClick = onFavoriteClick,
            onSourceClick = { onActionClick(DetailsAction.OpenSource(it)) },
            onTrackingSourceClick = { option ->
                option.trackingService?.let { service ->
                    onActionClick(DetailsAction.OpenTrackingDiscover(service, forceLoad = true))
                }
            },
            onOpenTrackingDiscover = { service ->
                onActionClick(DetailsAction.OpenTrackingDiscover(service))
            },
            onOpenMetadataSourceSheet = {
                if (!isTemporaryReadOnly) onOpenMetadataSourceSheet()
            },
            onOpenReadingSourceSheet = {
                if (isWorkActionEnabled) onOpenReadingSourceSheet()
            },
            onOpenChapters = {
                if (isWorkActionEnabled) onActionClick(DetailsAction.ToggleList)
            },
            onOpenSupplementalAction = { action ->
                onActionClick(DetailsAction.OpenWebUrl(action.url))
            },
            onAuthorClick = { author ->
                source?.let { currentSource ->
                    pendingAuthorSearch(author, currentSource)
                }
            },
            onTagClick = pendingTagSearch,
            onOpenLinkedTracking = { linked ->
                onActionClick(DetailsAction.OpenTrackingDetails(linked.service, linked.remoteId, linked.url))
            },
            onManageLinkedTracking = { linked ->
                onActionClick(DetailsAction.ManageTrackingBinding(linked.service, linked.remoteId, linked.title, linked.url))
            },
            onUpdateLinkedTrackingStatus = onUpdateLinkedTrackingStatus,
            onUpdateReadingStatus = onUpdateReadingStatus,
            onUpdateUnifiedRating = onUpdateUnifiedRating,
            onRemoveLinkedTracking = { match -> onActionClick(DetailsAction.RemoveTrackingMatch(match)) },
            onBindTrackingSuggestion = { match -> onActionClick(DetailsAction.BindTrackingMatch(match)) },
            onOpenTrackingSuggestion = { match ->
                onActionClick(DetailsAction.OpenTrackingDetails(match.service, match.remoteId, match.url))
            },
            onIgnoreTrackingSuggestion = { match -> onActionClick(DetailsAction.IgnoreTrackingSuggestion(match)) },
            onManageTrackingSuggestion = { match ->
                onActionClick(DetailsAction.ManageTrackingBinding(match.service, match.remoteId, match.title, match.url))
            },
        )
        if (!preferLightweightFirstFrame && relatedContent.isNotEmpty()) {
            DetailsRelatedContentSection(
                items = relatedContent,
                outerHorizontalPadding = outerHorizontalPadding,
                onItemClick = { item ->
                    onActionClick(DetailsAction.OpenContent(item.toContentWithOverride()))
                },
            )
        }
        if (!preferLightweightFirstFrame && supplementalMetadataProperties.isNotEmpty()) {
            DetailsSupplementMetadataCard(
                properties = supplementalMetadataProperties,
                outerHorizontalPadding = outerHorizontalPadding,
            )
        }
        if (visibleSupplementalSections.isNotEmpty()) {
            DetailsRelationSections(
                sections = visibleSupplementalSections,
                outerHorizontalPadding = outerHorizontalPadding,
                onItemClick = { item ->
                    val service = item.trackingService
                    val remoteId = item.remoteId
                    when {
                        item.type != null -> {
                            onEntityClick(item)
                        }
                        service != null && remoteId != null -> {
                            onActionClick(DetailsAction.OpenTrackingDetails(service, remoteId, item.url))
                        }
                        shouldOpenTrackingRelationSheet(item) -> {
                            onSupplementalRelationClick(item)
                        }
                        !item.url.isNullOrBlank() -> {
                            onSupplementalRelationClick(item)
                        }
                    }
                },
            )
        }
        if (!preferLightweightFirstFrame && entityRelationSections.isNotEmpty()) {
            DetailsRelationSections(
                sections = entityRelationSections,
                outerHorizontalPadding = outerHorizontalPadding,
                onItemClick = { item ->
                    val service = item.trackingService
                    val remoteId = item.remoteId
                    when {
                        item.entityId != null || item.type != null -> {
                            onEntityClick(item)
                        }
                        service != null && remoteId != null -> {
                            onActionClick(DetailsAction.OpenTrackingDetails(service, remoteId, item.url))
                        }
                        !item.url.isNullOrBlank() -> {
                            onSupplementalRelationClick(item)
                        }
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(bottomSpacerHeight))
    }
}

@Composable
private fun TemporaryDetailsReadOnlyNotice(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = stringResource(R.string.details_temporary_read_only_notice),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun shouldOpenTrackingRelationSheet(item: EntityRelationItem): Boolean {
    return item.trackingService == null &&
        item.remoteId == null &&
        !item.url.isNullOrBlank() &&
        (!item.subtitle.isNullOrBlank() || !item.supportingText.isNullOrBlank() || item.detailLines.isNotEmpty())
}
