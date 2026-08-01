package org.skepsun.kototoro.details.ui.compose

import android.os.Build
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState as createAnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.appUrl
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocalizedTitle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.model.isBroken
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.sharedCoverMemoryCacheKey
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeFeatherExtension
import org.skepsun.kototoro.core.ui.compose.ImmersiveTopGradientStops
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.util.ext.isHttpUrl
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.model.ActiveLocalSourceOption
import org.skepsun.kototoro.details.ui.model.ContentBranch
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSupplementAction
import org.skepsun.kototoro.details.ui.model.EntityChapterSourceInfo
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.compose.pane.DetailsPaneHost
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneFlingBehavior
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneTopBarMode
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationSection
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.download.ui.dialog.DownloadDialogViewModel
import org.skepsun.kototoro.download.ui.compose.DownloadDialog
import org.skepsun.kototoro.download.ui.worker.DownloadStartedObserver
import org.skepsun.kototoro.list.ui.compose.KototoroContentCard
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceSwitcherIcon
import org.skepsun.kototoro.readingrecord.data.ReadingChapterAggregateEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordSnapshot
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.favourites.ui.categories.select.compose.DuplicateFavoritePromptDialog
import org.skepsun.kototoro.favourites.ui.categories.select.compose.FavoriteCategoryDialog
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.stats.ui.sheet.compose.ContentStatsSheetContent
import org.skepsun.kototoro.stats.ui.sheet.ContentStatsViewModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private fun Color.withDetailsMinAlpha(minAlpha: Float): Color {
    return copy(alpha = alpha.coerceAtLeast(minAlpha))
}

private fun Color.detailsPanelContainerColor(): Color = withDetailsMinAlpha(0.70f)

@Composable
private fun rememberDetailsBottomBarGlassPrefs() =
    rememberGlassPrefsOrFallback()

private val DetailsTopChromeShadowElevation = 6.dp
private val ModernDetailsDockHeight = 86.dp
private val ModernDetailsDockBottomClearance = 16.dp
private val DetailsDockContentHorizontalPadding = 8.dp
private val ModernDetailsDockCompactPrimaryWidth = 112.dp
private val ModernDetailsDockToolsWidth = 112.dp
private val ModernDetailsDockTabSlotWidth = 50.dp
private val ModernDetailsDockMoreButtonWidth = 40.dp
private val ModernDetailsDockChromeHeight = 52.dp
private val ModernDetailsDockExpandedPanelGap = 12.dp
private const val ModernDetailsDockAnimationDurationMillis = 380
private const val PageThumbnailAspectRatioMin = 0.35f
private const val PageThumbnailAspectRatioMax = 1f
private const val PageThumbnailHeightRatioMin = 1f
private const val PageThumbnailHeightRatioMax = 1f / PageThumbnailAspectRatioMin

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
    var showStatsDialog by remember { mutableStateOf(false) }
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
            (68.dp + navigationBarBottomPadding).coerceIn(88.dp, 120.dp)
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
        viewModel.onError.observeEvent(lifecycleOwner, SnackbarErrorObserver(rootView, null))
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
    val compactSheetExpansionProgress = detailsPaneState.expansionProgress
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
    val compactTopBarAlpha = if (isWideAdaptiveLayout) {
        1f
    } else {
        (1f - compactSheetExpansionProgress).coerceIn(0f, 1f)
    }
    val animatedHeaderCoverVisualAlpha by animateFloatAsState(
        targetValue = if (isWideAdaptiveLayout) {
            1f
        } else {
            (1f - compactSheetExpansionProgress).coerceIn(0f, 1f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "details_header_cover_visual_alpha",
    )
    val headerCoverVisualAlpha = animatedHeaderCoverVisualAlpha

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
    val effectivePanoramaInfoCardTopPx = if (
        panoramaPrefs.isScrollLinkedEnabled &&
        initialInfoCardTopPx.isFinite()
    ) {
        initialInfoCardTopPx
    } else {
        infoCardTopPx
    }
    val shouldLimitPanoramaToInfoCardMidpoint =
        panoramaPrefs.limitToInfoCardMidpoint && effectivePanoramaInfoCardMidPx.isFinite()
    val panoramaFullOpacityAtY = if (shouldLimitPanoramaToInfoCardMidpoint) {
        effectivePanoramaInfoCardMidPx
    } else {
        null
    }
    val panoramaFullOpacityFadeDistancePx = if (
        shouldLimitPanoramaToInfoCardMidpoint &&
        effectivePanoramaInfoCardTopPx.isFinite() &&
        effectivePanoramaInfoCardMidPx.isFinite()
    ) {
        (effectivePanoramaInfoCardMidPx - effectivePanoramaInfoCardTopPx).coerceAtLeast(with(density) { 48.dp.toPx() })
    } else {
        0f
    }
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
                            fullOpacityAtY = panoramaFullOpacityAtY,
                            fullOpacityFadeDistancePx = panoramaFullOpacityFadeDistancePx,
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
                            .padding(horizontal = CompactTopBarHorizontalPadding),
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
                                        isStatsAvailable = isWorkActionEnabled && isStatsAvailable,
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

                                                DetailsAction.OpenStatistics -> {
                                                    showStatsDialog = true
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
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    headerTopSpacing = landscapeHeaderTopSpacing,
                                    bottomSpacerHeight = 40.dp,
                                    preferLightweightFirstFrame = false,
                                    mangaDetails = mangaDetails,
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
                                    coverVisualAlpha = 1f,
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
                            Surface(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f)
                                    .padding(
                                        top = statusBarTopPadding,
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
                                    sheetExpansionProgress = 0f,
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
                        if (compactSheetExpansionProgress < 0.995f) {
                            DetailsScrollableContent(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = (1f - compactSheetExpansionProgress).coerceIn(0f, 1f)
                                    },
                                scrollState = scrollState,
                                contentPadding = paddingValues,
                                headerTopSpacing = detailsHeaderTopSpacing,
                                bottomSpacerHeight = if (isWorkDetails) compactPaneCollapsedHeight + 28.dp else 28.dp,
                                preferLightweightFirstFrame = false,
                                mangaDetails = mangaDetails,
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
                                coverVisualAlpha = headerCoverVisualAlpha,
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
                }
                if (isWorkDetails) {
                    DetailsPaneHost(
                        state = detailsPaneState,
                        dragEnabled = !isModernDetailsDockEnabled,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
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
                            sheetExpansionProgress = compactSheetExpansionProgress,
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
            val detailsTopImmersiveAlpha = if (isWideAdaptiveLayout) {
                detailsGradientAlpha
            } else {
                detailsGradientAlpha * (1f - compactSheetExpansionProgress).coerceIn(0f, 1f)
            }
            val detailsTopImmersiveHeight = with(density) {
                val sbPx = statusBarTopPadding.roundToPx()
                val tbPx = interfaceStyleTokens.mainTopBarHeight.roundToPx()
                val overflowPx = 6.dp.roundToPx()
                (sbPx + tbPx + overflowPx).coerceAtLeast(sbPx + overflowPx).toDp()
            }
            if (detailsTopImmersiveAlpha > 0.01f) {
                ImmersiveEdgeGradient(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = detailsTopImmersiveAlpha },
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
                if (compactTopBarAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .alpha(compactTopBarAlpha),
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

            if (showStatsDialog && isWorkActionEnabled && content != null) {
                val statsViewModel: ContentStatsViewModel = hiltViewModel(key = "details-content-stats-${content.id}")
                LaunchedEffect(content.id) {
                    statsViewModel.initialize(content)
                }
                DetailsStatsSheet(
                    manga = content,
                    viewModel = statsViewModel,
                    onDismissRequest = { showStatsDialog = false },
                    onOpenDetails = { showStatsDialog = false },
                )
            }

            if (showReadingRecordSheet && isWorkActionEnabled && content != null) {
                ReadingRecordSheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsPlainBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sheetColors = rememberGlassSurfaceColors(style = GlassDefaults.regularStyle())
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = if (expressive) {
            sheetColors.containerColor.detailsPanelContainerColor()
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = if (expressive) 36.dp else 28.dp, topEnd = if (expressive) 36.dp else 28.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsTranslucentBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sheetColors = rememberGlassSurfaceColors(
        style = GlassDefaults.regularStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
            shape = RoundedCornerShape(topStart = if (expressive) 36.dp else 28.dp, topEnd = if (expressive) 36.dp else 28.dp),
            color = sheetColors.containerColor.detailsPanelContainerColor(),
            border = sheetColors.border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
        }
    }
}

@Composable
private fun DetailsStatsSheet(
    manga: Content,
    viewModel: ContentStatsViewModel,
    onDismissRequest: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
    ) {
        ContentStatsSheetContent(
            manga = manga,
            viewModel = viewModel,
            onOpenDetails = onOpenDetails,
            modifier = Modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingRelationItemSheet(
    item: EntityRelationItem,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(0.72f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item.coverUrl.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_placeholder),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        } else {
                            val normalizedCoverUrl = item.coverUrl?.takeIfUsableImageUri()
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(normalizedCoverUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = rememberSafePainter(R.drawable.ic_placeholder),
                                placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item.supportingText?.takeIf { it.isNotBlank() }?.let { supportingText ->
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            }

            item.subtitle?.takeIf { it.isNotBlank() }?.let { role ->
                item {
                    TrackingRelationMetaBlock(
                        label = stringResource(R.string.details_character_role_label),
                        value = role,
                    )
                }
            }

            item.detailLines
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n")
                ?.let { voiceActors ->
                    item {
                        TrackingRelationMetaBlock(
                            label = stringResource(R.string.details_character_voice_actors_label),
                            value = voiceActors,
                        )
                    }
                }

            item.url?.takeIf { it.isNotBlank() }?.let { url ->
                item {
                    Button(
                        onClick = { onOpenExternal(url) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = rememberSafePainter(R.drawable.ic_open_external),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(text = stringResource(R.string.details_open_character_site))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingRelationMetaBlock(
    label: String,
    value: String,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val blockColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = blockColors.containerColor.detailsPanelContainerColor(),
        border = blockColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingReviewsSheet(
    reviews: List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.ReviewEntry>,
    externalUrl: String?,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val reviewCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_reviews),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!externalUrl.isNullOrBlank()) {
                    TextButton(onClick = { onOpenExternal(externalUrl) }) {
                        Text(stringResource(R.string.details_more_reviews))
                    }
                }
            }
            }
            if (reviews.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = reviewCardColors.containerColor.detailsPanelContainerColor(),
                        border = reviewCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.details_no_reviews),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    }
                }
            } else {
                items(reviews, key = { review -> review.url }) { review ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = reviewCardColors.containerColor.detailsPanelContainerColor(),
                        border = reviewCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AsyncImage(
                                    model = review.avatarUrl?.takeIfUsableImageUri(),
                                    contentDescription = review.authorName,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                    error = rememberSafePainter(R.drawable.ic_placeholder),
                                    placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = review.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val metaLine = buildList {
                                        add(review.authorName)
                                        review.postedAt?.takeIf { it.isNotBlank() }?.let(::add)
                                        review.repliesCount?.let { replies ->
                                            add(stringResource(R.string.details_review_reply_count, replies))
                                        }
                                    }.joinToString(" · ")
                                    Text(
                                        text = metaLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Text(
                                text = review.excerpt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                modifier = Modifier.align(Alignment.End),
                                onClick = { onOpenExternal(review.url) },
                            ) {
                                Text(stringResource(R.string.details_open_review))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingCommentsSheet(
    threads: List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.CommentThread>,
    externalUrl: String?,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val commentCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_comments),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!externalUrl.isNullOrBlank()) {
                    TextButton(onClick = { onOpenExternal(externalUrl) }) {
                        Text(stringResource(R.string.details_more_comments))
                    }
                }
            }
            }
            if (threads.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = commentCardColors.containerColor.detailsPanelContainerColor(),
                        border = commentCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.details_no_comments),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    }
                }
            } else {
                items(threads, key = { thread -> "${thread.userName}:${thread.postedAt}:${thread.content.hashCode()}" }) { thread ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = commentCardColors.containerColor.detailsPanelContainerColor(),
                        border = commentCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AsyncImage(
                                    model = thread.avatarUrl?.takeIfUsableImageUri(),
                                    contentDescription = thread.userName,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                    error = rememberSafePainter(R.drawable.ic_placeholder),
                                    placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = thread.userName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val metaLine = buildList {
                                        thread.rating?.let { add(String.format(Locale.ROOT, "%.1f", it)) }
                                        thread.status?.takeIf { it.isNotBlank() }?.let(::add)
                                        thread.postedAt?.takeIf { it.isNotBlank() }?.let(::add)
                                    }.joinToString(" · ")
                                    if (metaLine.isNotBlank()) {
                                        Text(
                                            text = metaLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = thread.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (thread.replies.isNotEmpty()) {
                                val replyCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    thread.replies.forEach { reply ->
                                        Surface(
                                            shape = RoundedCornerShape(if (expressive) 22.dp else 18.dp),
                                            color = if (expressive) {
                                                replyCardColors.containerColor.detailsPanelContainerColor()
                                            } else {
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)
                                            },
                                            border = if (expressive) replyCardColors.border else null,
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = buildString {
                                                        append(reply.userName)
                                                        reply.postedAt?.takeIf { it.isNotBlank() }?.let {
                                                            append(" · ")
                                                            append(it)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = reply.content,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsScrollableContent(
    mangaDetails: org.skepsun.kototoro.details.data.ContentDetails?,
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
    coverVisualAlpha: Float,
    coverUrl: String?,
    fallbackCoverUrl: String?,
    content: org.skepsun.kototoro.parsers.model.Content?,
    isTemporaryReadOnly: Boolean,
    isWorkDetails: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        DetailsHeader(
            mangaDetails = mangaDetails,
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
            coverVisualAlpha = coverVisualAlpha,
            coverUrl = coverUrl,
            fallbackCoverUrl = fallbackCoverUrl,
            sharedElementKey = sharedElementKey,
            showWorkActions = isWorkActionEnabled,

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
                onItemClick = { item ->
                    onActionClick(DetailsAction.OpenContent(item.toContentWithOverride()))
                },
            )
        }
        if (!preferLightweightFirstFrame && supplementalMetadataProperties.isNotEmpty()) {
            DetailsSupplementMetadataCard(properties = supplementalMetadataProperties)
        }
        if (visibleSupplementalSections.isNotEmpty()) {
            DetailsRelationSections(
                sections = visibleSupplementalSections,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsPaneContent(
    detailsPaneState: DetailsPaneState,
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    branches: List<ContentBranch>,
    isLoading: Boolean,
    viewModel: DetailsViewModel,
    pagesViewModel: PagesViewModel,
    bookmarksViewModel: BookmarksViewModel,
    settings: AppSettings,
    appRouter: AppRouter,
    pageSaveHelper: PageSaveHelper,
    metadataChapterTabs: List<DetailsChapterSourceTab>,
    readingChapterTabs: List<DetailsChapterSourceTab>,
    onSelectMetadataChapterTab: (DetailsChapterSourceTab) -> Unit,
    onSelectReadingChapterTab: (DetailsChapterSourceTab) -> Unit,
    selectedTabId: Int,
    availableTabIds: List<Int>,
    isSheetFullyExpanded: Boolean,
    sheetExpansionProgress: Float,
    isChapterSearchAvailable: Boolean,
    isChaptersReversed: Boolean,
    isChaptersInGridView: Boolean,
    isHideReadChapters: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    isDownloadedOnly: Boolean,
    isDownloadedFilterVisible: Boolean,
    pageGridSizeValue: Float,
    pageThumbnailAspectRatio: Float,
    isPageThumbnailsFitPreview: Boolean,
    onChapterQueryChange: (String) -> Unit,
    onChapterSearchToggle: () -> Unit,
    onToggleChaptersReversed: () -> Unit,
    onToggleChaptersGrid: () -> Unit,
    onToggleHideReadChapters: () -> Unit,
    onToggleMergeRepeatedChapters: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onPageGridSizeChange: (Float) -> Unit,
    onPageThumbnailAspectRatioChange: (Float) -> Unit,
    onTogglePageThumbnailsFitPreview: () -> Unit,
    showCollapsedHandle: Boolean,
    isModernDetailsDockEnabled: Boolean,
    isModernDockCompact: Boolean,
    onSelectedTabIdChange: (Int) -> Unit,
    onActionClick: (DetailsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapterQuery = detailsPaneState.chapterQuery
    val isChapterSearchVisible = detailsPaneState.isChapterSearchVisible
    val paneOpacityProgress = easedOpacityProgress(sheetExpansionProgress)
    val modernPanelRevealProgress = if (!showCollapsedHandle) {
        1f
    } else {
        ((paneOpacityProgress - 0.04f) / 0.28f).coerceIn(0f, 1f)
    }
    val density = LocalDensity.current
    val actionsExpansionProgress = sheetExpansionProgress
    val statusBarTopPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val modernDragHandleRevealProgress = modernDockDragHandleRevealProgress(
        isModernDockEnabled = isModernDetailsDockEnabled,
        paneOpacityProgress = paneOpacityProgress,
    )
    val modernPanelTopPadding = if (showCollapsedHandle) {
        modernDockActionsTopPadding(
            handleTopInset = statusBarTopPadding,
            paneOpacityProgress = paneOpacityProgress,
            handleRevealProgress = modernDragHandleRevealProgress,
        ) + modernDockDragHandleHeight(modernDragHandleRevealProgress) +
            modernDockDragHandleGap(modernDragHandleRevealProgress) +
            ModernDetailsDockChromeHeight +
            ModernDetailsDockExpandedPanelGap
    } else {
        76.dp
    }
    val useCompactPaneSurfaceTint = showCollapsedHandle
    val paneShape = RoundedCornerShape(28.dp)
    val paneGlassStyle = if (useCompactPaneSurfaceTint || !showCollapsedHandle) {
        GlassDefaults.prominentStyle()
    } else {
        GlassDefaults.regularStyle()
    }
    val bottomBarGlassPrefs = rememberDetailsBottomBarGlassPrefs()
    val actionsRow: @Composable (Modifier) -> Unit = { actionsModifier ->
        DetailsPaneActionsRow(
            modifier = actionsModifier,
            detailsPaneState = detailsPaneState,
            isModernDockEnabled = isModernDetailsDockEnabled,
            isModernDockCompact = isModernDockCompact,
            selectedTabId = resolveDetailsTabSelection(selectedTabId, availableTabIds),
            isSheetFullyExpanded = isSheetFullyExpanded,
            sheetExpansionProgress = actionsExpansionProgress,
            isChapterSearchAvailable = isChapterSearchAvailable,
            isChaptersReversed = isChaptersReversed,
            isChaptersInGridView = isChaptersInGridView,
            isHideReadChapters = isHideReadChapters,
            isMergeRepeatedChapters = isMergeRepeatedChapters,
            showMergeRepeatedChapters = showMergeRepeatedChapters,
            isDownloadedOnly = isDownloadedOnly,
            isDownloadedFilterVisible = isDownloadedFilterVisible,
            pageGridSizeValue = pageGridSizeValue,
            pageThumbnailAspectRatio = pageThumbnailAspectRatio,
            isPageThumbnailsFitPreview = isPageThumbnailsFitPreview,
            onChapterSearchToggle = onChapterSearchToggle,
            onToggleChaptersReversed = onToggleChaptersReversed,
            onToggleChaptersGrid = onToggleChaptersGrid,
            onToggleHideReadChapters = onToggleHideReadChapters,
            onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
            onToggleDownloadedOnly = onToggleDownloadedOnly,
            onPageGridSizeChange = onPageGridSizeChange,
            onPageThumbnailAspectRatioChange = onPageThumbnailAspectRatioChange,
            onTogglePageThumbnailsFitPreview = onTogglePageThumbnailsFitPreview,
            showCollapsedHandle = showCollapsedHandle,
            handleTopInset = statusBarTopPadding,
            contentType = contentType,
            historyInfo = historyInfo,
            branches = branches,
            isLoading = isLoading,
            onActionClick = onActionClick,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalGlassPrefs provides bottomBarGlassPrefs) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isModernDetailsDockEnabled) {
                            Modifier
                                .padding(top = modernPanelTopPadding)
                                .graphicsLayer {
                                    alpha = modernPanelRevealProgress
                                    translationY = with(density) {
                                        (18.dp * (1f - modernPanelRevealProgress)).toPx()
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
                shape = paneShape,
                style = paneGlassStyle,
                dialogSurface = LocalInterfaceStyle.current != InterfaceStyle.IOS,
                componentRole = GlassComponentRole.Sheet,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                    ) {
                        if (!isModernDetailsDockEnabled) {
                            actionsRow(Modifier)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ChaptersPagesTabsContent(
                                viewModel = viewModel,
                                pagesViewModel = pagesViewModel,
                                bookmarksViewModel = bookmarksViewModel,
                                settings = settings,
                                appRouter = appRouter,
                                pageSaveHelper = pageSaveHelper,
                                metadataChapterTabs = metadataChapterTabs,
                                readingChapterTabs = readingChapterTabs,
                                onSelectMetadataChapterTab = onSelectMetadataChapterTab,
                                onSelectReadingChapterTab = onSelectReadingChapterTab,
                                isMergeRepeatedChapters = isMergeRepeatedChapters,
                                selectedTabId = resolveDetailsTabSelection(selectedTabId, availableTabIds),
                                showTabStrip = false,
                                isSheetFullyExpanded = isSheetFullyExpanded,
                                isChapterListScrollEnabled = true,
                                handleSelectionBackPressInternally = !showCollapsedHandle,
                                detailsPaneState = if (showCollapsedHandle) detailsPaneState else null,
                                pageThumbnailAspectRatio = pageThumbnailAspectRatio,
                                chapterQuery = chapterQuery,
                                isChapterSearchVisible = isChapterSearchVisible,
                                onChapterQueryChange = onChapterQueryChange,
                                onChapterSelectionStateChange = detailsPaneState::onChapterSelectionStateChanged,
                                onSelectedTabIdChange = { tabId ->
                                    val resolvedTab = resolveDetailsTabSelection(tabId, availableTabIds)
                                    onSelectedTabIdChange(resolvedTab)
                                },
                            )
                        }
                    }
                }
            }
            if (isModernDetailsDockEnabled) {
                actionsRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = CompactTopBarHorizontalPadding - DetailsDockContentHorizontalPadding,
                        )
                        .graphicsLayer {
                            scaleY = 0.98f + (0.02f * paneOpacityProgress)
                        }
                        .zIndex(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailsDockContainer(
    modernStyle: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (modernStyle) {
        if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
            GlassBottomBarContainer(modifier = modifier) {
                content()
            }
        } else {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
            ) {
                content()
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

@Composable
private fun DetailsPaneActionsRow(
    modifier: Modifier = Modifier,
    detailsPaneState: DetailsPaneState,
    isModernDockEnabled: Boolean,
    isModernDockCompact: Boolean,
    selectedTabId: Int,
    isSheetFullyExpanded: Boolean,
    sheetExpansionProgress: Float,
    isChapterSearchAvailable: Boolean,
    isChaptersReversed: Boolean,
    isChaptersInGridView: Boolean,
    isHideReadChapters: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    isDownloadedOnly: Boolean,
    isDownloadedFilterVisible: Boolean,
    pageGridSizeValue: Float,
    pageThumbnailAspectRatio: Float,
    isPageThumbnailsFitPreview: Boolean,
    onChapterSearchToggle: () -> Unit,
    onToggleChaptersReversed: () -> Unit,
    onToggleChaptersGrid: () -> Unit,
    onToggleHideReadChapters: () -> Unit,
    onToggleMergeRepeatedChapters: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onPageGridSizeChange: (Float) -> Unit,
    onPageThumbnailAspectRatioChange: (Float) -> Unit,
    onTogglePageThumbnailsFitPreview: () -> Unit,
    showCollapsedHandle: Boolean,
    handleTopInset: androidx.compose.ui.unit.Dp,
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    branches: List<ContentBranch>,
    isLoading: Boolean,
    onActionClick: (DetailsAction) -> Unit,
) {
    val isChapterSearchVisible = detailsPaneState.isChapterSearchVisible
    val chapterSelectionState = detailsPaneState.chapterSelectionState
    val paneOpacityProgress = easedOpacityProgress(sheetExpansionProgress)
    val showPagesTab = contentType != ContentType.VIDEO &&
        contentType != ContentType.HENTAI_VIDEO &&
        contentType != ContentType.NOVEL &&
        contentType != ContentType.HENTAI_NOVEL
    val showBookmarksTab = contentType != ContentType.VIDEO &&
        contentType != ContentType.HENTAI_VIDEO
    val compactModernDock = isModernDockEnabled && isModernDockCompact
    val showAllDockTabs = !compactModernDock
    val modernDragHandleRevealProgress = modernDockDragHandleRevealProgress(
        isModernDockEnabled = isModernDockEnabled,
        paneOpacityProgress = paneOpacityProgress,
    )
    val paneFlingBehavior = rememberDetailsPaneFlingBehavior(detailsPaneState)
    val shouldShowPaneDragHandle = showCollapsedHandle && modernDragHandleRevealProgress > 0.01f
    val dragHandleAlpha by animateFloatAsState(
        targetValue = if (
            isModernDockEnabled && detailsPaneState.anchor == CompactDetailsPaneAnchor.Collapsed
        ) {
            0f
        } else {
            lerpFloat(0.68f, 1f, paneOpacityProgress) * modernDragHandleRevealProgress
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "detailsPaneDragHandleAlpha",
    )
    val dockItemEnter = fadeIn(tween(260)) + expandHorizontally(
        animationSpec = tween(ModernDetailsDockAnimationDurationMillis, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Start,
    )
    val dockItemExit = fadeOut(tween(200)) + shrinkHorizontally(
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        shrinkTowards = Alignment.Start,
    )
    val modernDockDragModifier = if (isModernDockEnabled) {
        Modifier.anchoredDraggable(
            state = detailsPaneState.anchoredState,
            orientation = Orientation.Vertical,
            enabled = !detailsPaneState.isGridSizeControlsVisible,
            flingBehavior = paneFlingBehavior,
        )
    } else {
        Modifier
    }

    LaunchedEffect(selectedTabId, isSheetFullyExpanded) {
        detailsPaneState.syncTopBarContext(
            selectedTabId = selectedTabId,
            chaptersTabId = DETAILS_TAB_CHAPTERS,
            isSheetFullyExpanded = isSheetFullyExpanded,
        )
    }
    val topBarMode = detailsPaneState.topBarMode(
        selectedTabId = selectedTabId,
        chaptersTabId = DETAILS_TAB_CHAPTERS,
        isCompactLayout = showCollapsedHandle,
    )

    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .then(
                if (isModernDockEnabled) {
                    Modifier
                } else {
                    Modifier.anchoredDraggable(
                        state = detailsPaneState.anchoredState,
                        orientation = Orientation.Vertical,
                        enabled = detailsPaneState.anchor == CompactDetailsPaneAnchor.Full &&
                            !detailsPaneState.isGridSizeControlsVisible,
                        flingBehavior = paneFlingBehavior,
                    )
                },
            )
            .padding(
                start = DetailsDockContentHorizontalPadding,
                end = DetailsDockContentHorizontalPadding,
                top = if (showCollapsedHandle) {
                    modernDockActionsTopPadding(
                        handleTopInset = handleTopInset,
                        paneOpacityProgress = paneOpacityProgress,
                        handleRevealProgress = modernDragHandleRevealProgress,
                    )
                } else {
                    7.dp
                },
                bottom = 2.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(
            if (showCollapsedHandle) {
                modernDockDragHandleGap(modernDragHandleRevealProgress)
            } else {
                4.dp
            },
        ),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        if (shouldShowPaneDragHandle) {
            Box(
                modifier = Modifier
                    .height(modernDockDragHandleHeight(modernDragHandleRevealProgress))
                    .then(
                        if (isModernDockEnabled) {
                            Modifier
                                .width(64.dp)
                                .then(
                                    if (detailsPaneState.anchor == CompactDetailsPaneAnchor.Collapsed) {
                                        Modifier
                                    } else {
                                        Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            detailsPaneState.animateTo(
                                                when (detailsPaneState.anchor) {
                                                    CompactDetailsPaneAnchor.Collapsed -> CompactDetailsPaneAnchor.Hovered
                                                    CompactDetailsPaneAnchor.Hovered -> CompactDetailsPaneAnchor.Full
                                                    CompactDetailsPaneAnchor.Full -> CompactDetailsPaneAnchor.Collapsed
                                                },
                                            )
                                        }
                                    },
                                )
                                .anchoredDraggable(
                                    state = detailsPaneState.anchoredState,
                                    orientation = Orientation.Vertical,
                                    enabled = detailsPaneState.anchor != CompactDetailsPaneAnchor.Collapsed &&
                                        !detailsPaneState.isGridSizeControlsVisible,
                                    flingBehavior = paneFlingBehavior,
                                )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DetailsPaneDragHandle(
                    modifier = Modifier
                        .alpha(dragHandleAlpha),
                )
            }
        }
        when (topBarMode) {
            DetailsPaneTopBarMode.ChapterSelection -> {
                ChapterSelectionTopBar(
                    state = chapterSelectionState ?: return@Column,
                    modernStyle = isModernDockEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            DetailsPaneTopBarMode.GridSizeControls -> {
                PageGridSizeControlsRow(
                    sizeValue = pageGridSizeValue,
                    aspectRatio = pageThumbnailAspectRatio,
                    onSizeValueChange = onPageGridSizeChange,
                    onAspectRatioChange = onPageThumbnailAspectRatioChange,
                    onBackClick = detailsPaneState::hideGridSizeControls,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            else -> Unit
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isModernDockEnabled) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                    },
                ),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val visibleDockTabCount = 1 +
                    (if (showPagesTab) 1 else 0) +
                    (if (showBookmarksTab) 1 else 0)
                val tabSlotWidth = if (isModernDockEnabled) ModernDetailsDockTabSlotWidth else 52.dp
                val tabsDockPadding = if (isModernDockEnabled) 12.dp else 4.dp
                val dockGap = if (isModernDockEnabled) 8.dp else 4.dp
                val expandedTabsDockWidth = tabsDockPadding + (tabSlotWidth * visibleDockTabCount)
                val expandedPrimaryDockWidth = (maxWidth - expandedTabsDockWidth - dockGap)
                    .coerceAtLeast(ModernDetailsDockCompactPrimaryWidth)
                val isExpandedTools = topBarMode == DetailsPaneTopBarMode.ExpandedChapterTools ||
                    topBarMode == DetailsPaneTopBarMode.ExpandedGridTools
                val primaryDockWidth by animateDpAsState(
                    targetValue = when {
                        compactModernDock -> ModernDetailsDockCompactPrimaryWidth
                        isModernDockEnabled && isExpandedTools -> ModernDetailsDockToolsWidth
                        else -> expandedPrimaryDockWidth
                    },
                    animationSpec = tween(
                        durationMillis = ModernDetailsDockAnimationDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "detailsPrimaryDockWidth",
                )

                DetailsDockContainer(
                    modernStyle = isModernDockEnabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .then(modernDockDragModifier),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (isModernDockEnabled) 5.dp else 2.dp,
                            vertical = if (isModernDockEnabled) 5.dp else 0.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = showAllDockTabs || selectedTabId == DETAILS_TAB_CHAPTERS,
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_list,
                                contentDescription = stringResource(R.string.chapters),
                                isSelected = selectedTabId == DETAILS_TAB_CHAPTERS,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = if (isModernDockEnabled && showAllDockTabs && (showPagesTab || showBookmarksTab)) {
                                    2.dp
                                } else {
                                    0.dp
                                },
                                onClick = { onActionClick(DetailsAction.ToggleList) },
                            )
                        }
                        AnimatedVisibility(
                            visible = showPagesTab && (showAllDockTabs || selectedTabId == DETAILS_TAB_PAGES),
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_grid,
                                contentDescription = stringResource(R.string.pages),
                                isSelected = selectedTabId == DETAILS_TAB_PAGES,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = if (isModernDockEnabled && showAllDockTabs && showBookmarksTab) {
                                    2.dp
                                } else {
                                    0.dp
                                },
                                onClick = { onActionClick(DetailsAction.ToggleGrid) },
                            )
                        }
                        AnimatedVisibility(
                            visible = showBookmarksTab && (showAllDockTabs || selectedTabId == DETAILS_TAB_BOOKMARKS),
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_bookmark,
                                contentDescription = stringResource(R.string.bookmarks),
                                isSelected = selectedTabId == DETAILS_TAB_BOOKMARKS,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = 0.dp,
                                onClick = { onActionClick(DetailsAction.ToggleBookmarkView) },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .then(modernDockDragModifier)
                        .width(primaryDockWidth),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (topBarMode) {
                            DetailsPaneTopBarMode.ExpandedChapterTools -> {
                                if (showCollapsedHandle) {
                                    ExpandedPaneUtilityDock(
                                        modifier = Modifier.weight(1f),
                                        modernStyle = isModernDockEnabled,
                                        sheetExpansionProgress = paneOpacityProgress,
                                        isSearchEnabled = isChapterSearchAvailable,
                                        isSearchActive = isChapterSearchVisible,
                                        isChaptersReversed = isChaptersReversed,
                                        isChaptersInGridView = isChaptersInGridView,
                                        isHideReadChapters = isHideReadChapters,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        isDownloadedOnly = isDownloadedOnly,
                                        isDownloadedFilterVisible = isDownloadedFilterVisible,
                                        onSearchClick = onChapterSearchToggle,
                                        onToggleChaptersReversed = onToggleChaptersReversed,
                                        onToggleChaptersGrid = onToggleChaptersGrid,
                                        onToggleHideReadChapters = onToggleHideReadChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onToggleDownloadedOnly = onToggleDownloadedOnly,
                                        onShowGridSizeControls = detailsPaneState::showGridSizeControls,
                                    )
                                } else {
                                    ReadDock(
                                        modifier = Modifier.weight(0.64f),
                                        modernStyle = isModernDockEnabled,
                                        compact = compactModernDock,
                                        readLabel = resolveReadActionLabel(
                                            contentType = contentType,
                                            historyInfo = historyInfo,
                                            isLoading = isLoading,
                                        ),
                                        contentType = contentType,
                                        branches = branches,
                                        historyInfo = historyInfo,
                                        isDownloadAvailable = historyInfo.canDownload,
                                        isEnabled = !isLoading && historyInfo.isValid,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onReadClick = { onActionClick(DetailsAction.Resume) },
                                        onIncognitoClick = { onActionClick(DetailsAction.ResumeIncognito) },
                                        onForgetClick = { onActionClick(DetailsAction.ForgetHistory) },
                                        onDownloadClick = { onActionClick(DetailsAction.Download) },
                                        onBranchSelected = { onActionClick(DetailsAction.SelectBranch(it)) },
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    ExpandedPaneUtilityDock(
                                        modifier = Modifier.weight(0.36f),
                                        modernStyle = isModernDockEnabled,
                                        sheetExpansionProgress = paneOpacityProgress,
                                        isSearchEnabled = isChapterSearchAvailable,
                                        isSearchActive = isChapterSearchVisible,
                                        isChaptersReversed = isChaptersReversed,
                                        isChaptersInGridView = isChaptersInGridView,
                                        isHideReadChapters = isHideReadChapters,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        isDownloadedOnly = isDownloadedOnly,
                                        isDownloadedFilterVisible = isDownloadedFilterVisible,
                                        onSearchClick = onChapterSearchToggle,
                                        onToggleChaptersReversed = onToggleChaptersReversed,
                                        onToggleChaptersGrid = onToggleChaptersGrid,
                                        onToggleHideReadChapters = onToggleHideReadChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onToggleDownloadedOnly = onToggleDownloadedOnly,
                                        onShowGridSizeControls = detailsPaneState::showGridSizeControls,
                                    )
                                }
                            }

                            DetailsPaneTopBarMode.ExpandedGridTools -> {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    DetailsDockContainer(
                                        modernStyle = isModernDockEnabled,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            DetailsChromeButton(
                                                onClick = onTogglePageThumbnailsFitPreview,
                                            ) {
                                                Icon(
                                                    painter = rememberSafePainter(R.drawable.ic_aspect_ratio),
                                                    contentDescription = stringResource(R.string.fit_page_thumbnails),
                                                    tint = if (isPageThumbnailsFitPreview) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                            }
                                            DetailsChromeButton(
                                                onClick = detailsPaneState::showGridSizeControls,
                                            ) {
                                                Icon(
                                                    painter = rememberSafePainter(R.drawable.ic_size_large),
                                                    contentDescription = stringResource(R.string.grid_size),
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                ReadDock(
                                    modifier = Modifier.weight(1f),
                                    modernStyle = isModernDockEnabled,
                                    compact = compactModernDock,
                                    readLabel = resolveReadActionLabel(
                                        contentType = contentType,
                                        historyInfo = historyInfo,
                                        isLoading = isLoading,
                                    ),
                                    contentType = contentType,
                                    branches = branches,
                                    historyInfo = historyInfo,
                                    isDownloadAvailable = historyInfo.canDownload,
                                    isEnabled = !isLoading && historyInfo.isValid,
                                    isMergeRepeatedChapters = isMergeRepeatedChapters,
                                    showMergeRepeatedChapters = showMergeRepeatedChapters,
                                    onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                    onReadClick = { onActionClick(DetailsAction.Resume) },
                                    onIncognitoClick = { onActionClick(DetailsAction.ResumeIncognito) },
                                    onForgetClick = { onActionClick(DetailsAction.ForgetHistory) },
                                    onDownloadClick = { onActionClick(DetailsAction.Download) },
                                    onBranchSelected = { onActionClick(DetailsAction.SelectBranch(it)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsPaneDragHandle(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(28.dp)
            .height(4.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                shape = RoundedCornerShape(999.dp),
            ),
    )
}

@Composable
private fun ChapterSelectionTopBar(
    state: ChapterSelectionUiState,
    modernStyle: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(ModernDetailsDockChromeHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailsDockContainer(modernStyle = modernStyle) {
                IconButton(
                    onClick = state.onClearSelection,
                    modifier = Modifier.size(ModernDetailsDockChromeHeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            Text(
                text = state.selectedCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        DetailsDockContainer(
            modernStyle = modernStyle,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Row(
                modifier = Modifier
                    .height(ModernDetailsDockChromeHeight)
                    .padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canSelectAll) {
                    ChapterSelectionActionButton(onClick = state.onSelectAll) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_select_all),
                            contentDescription = stringResource(android.R.string.selectAll),
                        )
                    }
                }
                if (state.canDownload) {
                    ChapterSelectionActionButton(onClick = state.onDownload) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.download),
                        )
                    }
                }
                if (state.canDelete) {
                    ChapterSelectionActionButton(onClick = state.onDelete) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
                if (state.canBookmark) {
                    ChapterSelectionActionButton(onClick = state.onBookmark) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_bookmark),
                            contentDescription = stringResource(R.string.bookmarks),
                        )
                    }
                }
                if (state.canMarkCurrent) {
                    ChapterSelectionActionButton(onClick = state.onMarkCurrent) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_current_chapter),
                            contentDescription = stringResource(R.string.mark_as_current),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterSelectionActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
    ) {
        content()
    }
}

@Composable
private fun PageGridSizeControlsRow(
    sizeValue: Float,
    aspectRatio: Float,
    onSizeValueChange: (Float) -> Unit,
    onAspectRatioChange: (Float) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heightRatio = 1f / aspectRatio.coerceIn(PageThumbnailAspectRatioMin, PageThumbnailAspectRatioMax)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            ),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LabeledGridSlider(
                label = stringResource(R.string.grid_size),
                value = sizeValue,
                onValueChange = onSizeValueChange,
                valueRange = 50f..150f,
            )
            LabeledGridSlider(
                label = stringResource(R.string.grid_aspect_ratio),
                value = heightRatio,
                onValueChange = { heightRatioValue ->
                    onAspectRatioChange(1f / heightRatioValue.coerceIn(PageThumbnailHeightRatioMin, PageThumbnailHeightRatioMax))
                },
                valueRange = PageThumbnailHeightRatioMin..PageThumbnailHeightRatioMax,
            )
        }
    }
}

@Composable
private fun LabeledGridSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        KototoroSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExpandedPaneUtilityDock(
    modifier: Modifier = Modifier,
    modernStyle: Boolean,
    sheetExpansionProgress: Float,
    isSearchEnabled: Boolean,
    isSearchActive: Boolean,
    isChaptersReversed: Boolean,
    isChaptersInGridView: Boolean,
    isHideReadChapters: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    isDownloadedOnly: Boolean,
    isDownloadedFilterVisible: Boolean,
    onSearchClick: () -> Unit,
    onToggleChaptersReversed: () -> Unit,
    onToggleChaptersGrid: () -> Unit,
    onToggleHideReadChapters: () -> Unit,
    onToggleMergeRepeatedChapters: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onShowGridSizeControls: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    DetailsDockContainer(
        modernStyle = modernStyle,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .height(ModernDetailsDockChromeHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onSearchClick,
                enabled = isSearchEnabled,
                modifier = Modifier
                    .width(42.dp)
                    .height(42.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_chapters),
                    tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .width(42.dp)
                        .height(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.options),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                GlassDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                ) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.reverse)) },
                        leadingIcon = {
                            MenuSelectionIndicator(selected = isChaptersReversed)
                        },
                        onClick = {
                            expanded = false
                            onToggleChaptersReversed()
                        },
                    )
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.chapters_grid_view)) },
                        leadingIcon = {
                            MenuSelectionIndicator(selected = isChaptersInGridView)
                        },
                        onClick = {
                            expanded = false
                            onToggleChaptersGrid()
                        },
                    )
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.hide_read_chapters)) },
                        leadingIcon = {
                            MenuSelectionIndicator(selected = isHideReadChapters)
                        },
                        onClick = {
                            expanded = false
                            onToggleHideReadChapters()
                        },
                    )
                    if (showMergeRepeatedChapters) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.merge_repeated_chapters)) },
                            leadingIcon = {
                                MenuSelectionIndicator(selected = isMergeRepeatedChapters)
                            },
                            onClick = {
                                expanded = false
                                onToggleMergeRepeatedChapters()
                            },
                        )
                    }
                    if (isChaptersInGridView) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.display_options)) },
                            onClick = {
                                expanded = false
                                onShowGridSizeControls()
                            },
                        )
                    }
                    if (isDownloadedFilterVisible) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.downloaded)) },
                            leadingIcon = {
                                MenuSelectionIndicator(selected = isDownloadedOnly)
                            },
                            onClick = {
                                expanded = false
                                onToggleDownloadedOnly()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuSelectionIndicator(
    selected: Boolean,
) {
    val strokeColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(
                width = 1.5.dp,
                color = strokeColor,
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = strokeColor,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
internal fun DetailsDockActionButton(
    iconRes: Int,
    contentDescription: String,
    isSelected: Boolean,
    modernStyle: Boolean = false,
    spacingAfter: androidx.compose.ui.unit.Dp = if (modernStyle) 0.dp else 4.dp,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = Color.Transparent,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "detailsDockSelectionColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            modernStyle && isSelected -> MaterialTheme.colorScheme.primary
            modernStyle -> MaterialTheme.colorScheme.onSurface
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "detailsDockSelectionContentColor",
    )
    Surface(
        modifier = Modifier.padding(end = spacingAfter),
        shape = RoundedCornerShape(if (modernStyle) 18.dp else 16.dp),
        color = containerColor,
        tonalElevation = 0.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .width(42.dp)
                .height(42.dp),
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = contentDescription,
                tint = contentColor,
            )
        }
    }
}

sealed interface DetailsAction {
    data object OpenCover : DetailsAction
    data class OpenContent(val content: Content) : DetailsAction
    data class OpenSource(val source: ContentSource) : DetailsAction
    data class OpenTrackingDiscover(
        val service: ScrobblerService,
        val forceLoad: Boolean = false,
    ) : DetailsAction
    data class SearchAuthorOnSource(val author: String, val source: ContentSource) : DetailsAction
    data class SearchAuthorEverywhere(val author: String) : DetailsAction
    data class SearchTagOnSource(val tag: ContentTag) : DetailsAction
    data class SearchTagEverywhere(val tagTitle: String) : DetailsAction
    data class OpenWebUrl(val url: String) : DetailsAction
    data class SelectBranch(val branch: String?) : DetailsAction
    data object ManageCategories : DetailsAction
    data object ManageDownloads : DetailsAction
    data object Favorite : DetailsAction
    data object Share : DetailsAction
    data class ShareLink(val title: String, val link: String) : DetailsAction
    data object Download : DetailsAction
    data object DeleteLocal : DetailsAction
    data object EditOverride : DetailsAction
    data object CreateShortcut : DetailsAction
    data object Translate : DetailsAction
    data object ToggleTranslation : DetailsAction
    data object FindSimilar : DetailsAction
    data object OpenAlternatives : DetailsAction
    data object OpenOnlineVariant : DetailsAction
    data class OpenBrowserPage(
        val url: String,
        val source: ContentSource?,
        val title: String?,
    ) : DetailsAction
    data object OpenMetadataInBrowser : DetailsAction
    data object OpenLocalSourceInBrowser : DetailsAction
    data object OpenStatistics : DetailsAction
    data object OpenReadingRecord : DetailsAction
    data object ToggleSafe : DetailsAction
    data object ToggleList : DetailsAction
    data object ToggleGrid : DetailsAction
    data object ToggleBookmarkView : DetailsAction
    data object Resume : DetailsAction
    data object ResumeIncognito : DetailsAction
    data object ForgetHistory : DetailsAction
    data class OpenTrackingDetails(
        val service: org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService,
        val remoteId: Long,
        val url: String?,
    ) : DetailsAction
    data class ManageTrackingBinding(
        val service: org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService,
        val remoteId: Long,
        val title: String,
        val url: String?,
    ) : DetailsAction
    data class BindTrackingMatch(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
    data class IgnoreTrackingSuggestion(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
    data class RemoveTrackingMatch(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
}

private fun DetailsAction.isWorkOnlyAction(): Boolean = when (this) {
    is DetailsAction.SelectBranch,
    DetailsAction.ManageCategories,
    DetailsAction.ManageDownloads,
    DetailsAction.Favorite,
    DetailsAction.Download,
    DetailsAction.DeleteLocal,
    DetailsAction.EditOverride,
    DetailsAction.CreateShortcut,
    DetailsAction.FindSimilar,
    DetailsAction.OpenAlternatives,
    DetailsAction.OpenOnlineVariant,
    DetailsAction.OpenLocalSourceInBrowser,
    DetailsAction.OpenStatistics,
    DetailsAction.OpenReadingRecord,
    DetailsAction.ToggleList,
    DetailsAction.ToggleGrid,
    DetailsAction.ToggleBookmarkView,
    DetailsAction.Resume,
    DetailsAction.ResumeIncognito,
    DetailsAction.ForgetHistory,
    is DetailsAction.ManageTrackingBinding,
    is DetailsAction.BindTrackingMatch,
    is DetailsAction.IgnoreTrackingSuggestion,
    is DetailsAction.RemoveTrackingMatch -> true
    else -> false
}

private data class BrowserTarget(
    val url: String,
    val source: ContentSource?,
    val title: String?,
)

@Composable
private fun ReadDock(
    modifier: Modifier = Modifier,
    modernStyle: Boolean = false,
    compact: Boolean = false,
    readLabel: String,
    contentType: ContentType?,
    branches: List<ContentBranch>,
    historyInfo: HistoryInfo,
    isDownloadAvailable: Boolean,
    isEnabled: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    onToggleMergeRepeatedChapters: () -> Unit,
    onReadClick: () -> Unit,
    onIncognitoClick: () -> Unit,
    onForgetClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onBranchSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val hasBranchOptions = branches.size > 1
    val canOpenIncognito = !historyInfo.isIncognitoMode
    val canForgetHistory = historyInfo.history != null
    val hasQuickActions = canOpenIncognito || canForgetHistory || isDownloadAvailable
    val hasMenuActions = hasQuickActions || hasBranchOptions

    val shapeRadiusPercent by androidx.compose.animation.core.animateIntAsState(targetValue = if (expanded) 50 else 0)
    val optionGap by androidx.compose.animation.core.animateDpAsState(
        targetValue = when {
            expanded -> 8.dp
            modernStyle -> 0.dp
            else -> 2.dp
        },
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = if (modernStyle && !expanded) 0.22f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "readDockDividerAlpha",
    )
    val actionIconRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.drawable.ic_play
        else -> R.drawable.ic_read
    }
    val readButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStartPercent = 50,
        bottomStartPercent = 50,
        topEndPercent = shapeRadiusPercent,
        bottomEndPercent = shapeRadiusPercent,
    )
    val trailingButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topEndPercent = 50,
        bottomEndPercent = 50,
        topStartPercent = shapeRadiusPercent,
        bottomStartPercent = shapeRadiusPercent,
    )

    Row(
        modifier = modifier
            .height(if (modernStyle) 52.dp else 50.dp)
            .padding(
                all = if (modernStyle) 0.dp else 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(optionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = readButtonShape,
            color = if (modernStyle) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
            },
            contentColor = if (modernStyle) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            tonalElevation = 0.dp,
            shadowElevation = if (modernStyle) 4.dp else 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(readButtonShape)
                    .clickable(enabled = isEnabled, onClick = onReadClick)
                    .padding(horizontal = if (modernStyle) 6.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (modernStyle) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = rememberSafePainter(actionIconRes),
                            contentDescription = if (compact) readLabel else null,
                            modifier = Modifier.size(22.dp),
                        )
                        AnimatedVisibility(
                            visible = !compact,
                            enter = fadeIn(tween(260)) + expandHorizontally(
                                animationSpec = tween(
                                    ModernDetailsDockAnimationDurationMillis,
                                    easing = FastOutSlowInEasing,
                                ),
                                expandFrom = Alignment.Start,
                            ),
                            exit = fadeOut(tween(200)) + shrinkHorizontally(
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Start,
                            ),
                        ) {
                            Text(
                                text = readLabel,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text(
                        text = readLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .width(if (modernStyle) ModernDetailsDockMoreButtonWidth else 50.dp)
                .fillMaxHeight()
                .onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = trailingButtonShape,
                color = if (modernStyle) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                },
                contentColor = if (modernStyle) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                tonalElevation = 0.dp,
                shadowElevation = if (modernStyle) 4.dp else 0.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(trailingButtonShape)
                        .clickable(enabled = hasMenuActions, onClick = { expanded = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (modernStyle) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = dividerAlpha)),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (hasBranchOptions) {
                            stringResource(R.string.system_default)
                        } else {
                            stringResource(R.string.options)
                        },
                    )
                }
            }
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                anchorBounds = menuAnchorBounds,
                openAboveAnchor = true,
            ) {
                if (canOpenIncognito) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.incognito_mode)) },
                        onClick = {
                            expanded = false
                            onIncognitoClick()
                        },
                    )
                }
                if (canForgetHistory) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_history)) },
                        onClick = {
                            expanded = false
                            onForgetClick()
                        },
                    )
                }
                if (isDownloadAvailable) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.download)) },
                        onClick = {
                            expanded = false
                            onDownloadClick()
                        },
                    )
                }
                if (hasQuickActions && (showMergeRepeatedChapters || hasBranchOptions)) {
                    HorizontalDivider()
                }
                if (showMergeRepeatedChapters) {
                        CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.merge_repeated_chapters)) },
                        leadingIcon = {
                            MenuSelectionIndicator(selected = isMergeRepeatedChapters)
                        },
                        onClick = {
                            expanded = false
                            onToggleMergeRepeatedChapters()
                        },
                    )
                    if (!isMergeRepeatedChapters && hasBranchOptions) {
                        HorizontalDivider()
                    }
                }
                if (!isMergeRepeatedChapters) {
                    branches.forEach { branch ->
                        CompactDropdownMenuItem(
                            text = {
                                Text(
                                    text = buildString {
                                        append(branch.name ?: stringResource(R.string.system_default))
                                        append(" / ")
                                        append(branch.count)
                                    },
                                )
                            },
                            leadingIcon = {
                                if (branch.isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onBranchSelected(branch.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun lerpDp(
    start: androidx.compose.ui.unit.Dp,
    stop: androidx.compose.ui.unit.Dp,
    fraction: Float,
): androidx.compose.ui.unit.Dp {
    return start + ((stop - start) * fraction.coerceIn(0f, 1f))
}

private fun modernDockDragHandleRevealProgress(
    isModernDockEnabled: Boolean,
    paneOpacityProgress: Float,
): Float {
    return if (isModernDockEnabled) {
        ((1f - paneOpacityProgress) / 0.32f).coerceIn(0f, 1f)
    } else {
        1f
    }
}

private fun modernDockActionsTopPadding(
    handleTopInset: androidx.compose.ui.unit.Dp,
    paneOpacityProgress: Float,
    handleRevealProgress: Float,
): androidx.compose.ui.unit.Dp {
    return lerpDp(
        start = handleTopInset,
        stop = 2.dp + (handleTopInset * paneOpacityProgress),
        fraction = handleRevealProgress,
    )
}

private fun modernDockDragHandleHeight(revealProgress: Float): androidx.compose.ui.unit.Dp {
    return 18.dp * revealProgress.coerceIn(0f, 1f)
}

private fun modernDockDragHandleGap(revealProgress: Float): androidx.compose.ui.unit.Dp {
    return 4.dp * revealProgress.coerceIn(0f, 1f)
}

@Composable
private fun rememberDetailsSheetGlassPrefs() =
    rememberGlassPrefsOrFallback()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingRecordSheet(
    snapshot: ReadingRecordSnapshot,
    chapterTitle: (Long) -> String,
    progressPercent: Float,
    onDismissRequest: () -> Unit,
    onJumpPointClick: (ReadingJumpPointEntity) -> Unit,
) {
    val sessions = snapshot.sessions
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sheetColors = rememberGlassSurfaceColors(
        style = GlassDefaults.regularStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    val lastReadAt = snapshot.summary.lastReadAt ?: sessions.maxOfOrNull { it.endAt }
    val totalDuration = snapshot.summary.totalDuration.takeIf { it > 0L }
        ?: sessions.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
    val readingDays = snapshot.summary.readingDays.takeIf { it > 0 }
        ?: sessions.map { it.startAt / MILLIS_PER_DAY }.distinct().size
    val timelineItems = remember(sessions, snapshot.jumpPoints) {
        (sessions.map { ReadingTimelineItem.Session(it) } +
            snapshot.jumpPoints.map { ReadingTimelineItem.Jump(it) })
            .sortedByDescending { it.time }
            .take(30)
    }
    val progress = progressPercent.coerceIn(0f, 1f)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val maxListHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp * 0.86f).coerceAtLeast(360.dp)
    }
    ModalBottomSheet(
        onDismissRequest = {
            onDismissRequest()
        },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = if (expressive) 36.dp else 28.dp, topEnd = if (expressive) 36.dp else 28.dp),
            color = sheetColors.containerColor.detailsPanelContainerColor(),
            border = sheetColors.border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = 20.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.reading_record),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    ReadingRecordSummaryCard(
                        totalDuration = totalDuration,
                        readingDays = readingDays,
                        lastReadAt = lastReadAt,
                        progress = progress,
                    )
                }
                if (snapshot.chapters.isNotEmpty()) {
                    item {
                        ChapterStatisticsSummary(
                            chapters = snapshot.chapters.take(4),
                            chapterTitle = chapterTitle,
                        )
                    }
                }
                item {
                    RecordSectionHeader(
                        title = stringResource(R.string.timeline),
                        count = timelineItems.size,
                    )
                }
                if (timelineItems.isEmpty()) {
                    item { RecordEmptyLine(stringResource(R.string.no_reading_record)) }
                } else {
                    items(timelineItems, key = { it.key }) { item ->
                        when (item) {
                            is ReadingTimelineItem.Session -> TimelineSessionRow(item.session, chapterTitle)
                            is ReadingTimelineItem.Jump -> TimelineJumpRow(item.point, chapterTitle) { point ->
                                onJumpPointClick(point)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingRecordSummaryCard(
    totalDuration: Long,
    readingDays: Int,
    lastReadAt: Long?,
    progress: Float,
    ) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val summaryCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = summaryCardColors.containerColor.detailsPanelContainerColor(),
        border = summaryCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryMetric(
                    label = stringResource(R.string.total_reading_time),
                    value = formatDuration(totalDuration),
                    modifier = Modifier.weight(1.2f),
                )
                SummaryMetric(
                    label = stringResource(R.string.reading_days),
                    value = readingDays.toString(),
                    modifier = Modifier.weight(0.8f),
                )
                SummaryMetric(
                    label = stringResource(R.string.current_progress),
                    value = "${(progress * 100f).roundToInt()}%",
                    modifier = Modifier.weight(0.8f),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )
            Text(
                text = "${stringResource(R.string.recent_reading)}: ${lastReadAt?.let(::formatDateTime) ?: "-"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface ReadingTimelineItem {
    val time: Long
    val key: String

    data class Session(val session: ReadingRecordEntity) : ReadingTimelineItem {
        override val time: Long = session.endAt
        override val key: String = "session_${session.id}"
    }

    data class Jump(val point: ReadingJumpPointEntity) : ReadingTimelineItem {
        override val time: Long = point.createdAt
        override val key: String = "jump_${point.id}"
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChapterStatisticsSummary(
    chapters: List<ReadingChapterAggregateEntity>,
    chapterTitle: (Long) -> String,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val summaryCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = summaryCardColors.containerColor.detailsPanelContainerColor(),
        border = summaryCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.chapter_statistics),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            chapters.forEach { chapter ->
                RecordCompactRow(
                    title = chapterTitle(chapter.chapterId),
                    body = stringResource(
                        R.string.reading_chapter_record_format,
                        chapter.sessionsCount,
                        formatDuration(chapter.duration),
                    ),
                    trailing = formatDateTime(chapter.lastReadAt),
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun RecordSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RecordEmptyLine(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TimelineSessionRow(
    session: ReadingRecordEntity,
    chapterTitle: (Long) -> String,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    val nodeColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 10.dp.toPx()
                val centerY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(
                    color = nodeColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, centerY),
                )
            }
            .padding(start = 28.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(session.endAt),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(chapterTitle(session.endChapterId), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = formatDuration(session.endAt - session.startAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "${(session.endPercent.coerceIn(0f, 1f) * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TimelineJumpRow(
    point: ReadingJumpPointEntity,
    chapterTitle: (Long) -> String,
    onJumpPointClick: (ReadingJumpPointEntity) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    val nodeColor = MaterialTheme.colorScheme.secondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 10.dp.toPx()
                val centerY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(
                    color = nodeColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, centerY),
                )
            }
            .clip(MaterialTheme.shapes.small)
            .clickable { onJumpPointClick(point) }
            .padding(start = 28.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(point.createdAt),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${chapterTitle(point.fromChapterId)} P${point.fromPage + 1} -> ${chapterTitle(point.toChapterId)} P${point.toPage + 1}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.restore_jump_point),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RecordCompactRow(
    title: String,
    body: String,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.width(92.dp),
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs.coerceAtLeast(0L))
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours > 0 -> "${hours}h ${remainingMinutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

private fun formatDateTime(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun calculateDetailsScrollProgress(
    scrollValue: Int,
    landscapeScrollValue: Int,
    toolbarBottomPx: Float,
    infoCardTopPx: Float,
    initialInfoCardTopPx: Float,
    toolbarGapPx: Float,
    isWideAdaptiveLayout: Boolean,
    disableInWideLayout: Boolean,
): Float {
    if (disableInWideLayout && isWideAdaptiveLayout) {
        return 0f
    }
    val targetTop = toolbarBottomPx + toolbarGapPx
    return if (toolbarBottomPx.isFinite() && infoCardTopPx.isFinite() && initialInfoCardTopPx.isFinite()) {
        val travelDistance = (initialInfoCardTopPx - targetTop).coerceAtLeast(1f)
        ((initialInfoCardTopPx - infoCardTopPx) / travelDistance).coerceIn(0f, 1f)
    } else {
        val fallbackScroll = if (isWideAdaptiveLayout) landscapeScrollValue else scrollValue
        (fallbackScroll / 360f).coerceIn(0f, 1f)
    }
}

private fun easedOpacityProgress(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

@Composable
private fun resolveReadActionLabel(
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    isLoading: Boolean,
): String {
    val isChaptersLoading = isLoading && (historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
    val defaultReadRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string.play
        else -> R.string.read
    }
    val continueRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string._continue_play
        else -> R.string._continue
    }
    return stringResource(
        when {
            isChaptersLoading -> R.string.loading_
            historyInfo.isIncognitoMode -> R.string.incognito
            historyInfo.canContinue -> continueRes
            else -> defaultReadRes
        },
    )
}

@Composable
private fun DetailsOverflowMenu(
    contentTitle: String?,
    showTranslateAction: Boolean,
    hasTranslationCache: Boolean,
    isShowingTranslation: Boolean,
    isTranslating: Boolean,
    isStatsAvailable: Boolean,
    hasMetadataBrowserTarget: Boolean,
    hasLocalBrowserTarget: Boolean,
    localBrowserTitleRes: Int,
    hasOnlineVariant: Boolean,
    isReadingRecordAvailable: Boolean,
    isDeleteLocalAvailable: Boolean,
    isEditOverrideAvailable: Boolean,
    isShortcutSupported: Boolean,
    isNsfw: Boolean,
    onDeleteLocalRequest: () -> Unit,
    onActionClick: (DetailsAction) -> Unit,
) {
    val interfaceStyleTokens = LocalInterfaceStyleTokens.current
    var expanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
    ) {
        DetailsChromeButton(
            onClick = { expanded = true },
            modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more),
                modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = menuAnchorBounds,
        ) {
            if (showTranslateAction) {
                        CompactDropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (hasTranslationCache && isShowingTranslation) {
                                    R.string.details_show_original
                                } else if (hasTranslationCache) {
                                    R.string.details_show_translation
                                } else {
                                    R.string.details_translate_title_and_description_hint
                                },
                            ),
                        )
                    },
                    enabled = !isTranslating,
                    onClick = {
                        expanded = false
                        onActionClick(
                            if (hasTranslationCache) {
                                DetailsAction.ToggleTranslation
                            } else {
                                DetailsAction.Translate
                            },
                        )
                    },
                )
            }
            if (isReadingRecordAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.reading_record)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenReadingRecord)
                    },
                )
            }
            CompactDropdownMenuItem(
                text = { Text(stringResource(if (isNsfw) R.string.mark_as_safe else R.string.mark_as_nsfw)) },
                onClick = {
                    expanded = false
                    onActionClick(DetailsAction.ToggleSafe)
                },
            )
            if (isDeleteLocalAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        expanded = false
                        if (contentTitle != null) {
                            onDeleteLocalRequest()
                        }
                    },
                )
            }
            if (isEditOverrideAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.EditOverride)
                    },
                )
            }
            if (isShortcutSupported) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.create_shortcut)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.CreateShortcut)
                    },
                )
            }
            CompactDropdownMenuItem(
                text = { Text(stringResource(R.string.find_similar)) },
                onClick = {
                    expanded = false
                    onActionClick(DetailsAction.FindSimilar)
                },
            )
            if (hasOnlineVariant) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.online_variant)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenOnlineVariant)
                    },
                )
            }
            if (hasMetadataBrowserTarget) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.open_metadata_in_browser)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenMetadataInBrowser)
                    },
                )
            }
            if (hasLocalBrowserTarget) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(localBrowserTitleRes)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenLocalSourceInBrowser)
                    },
                )
            }
            if (isStatsAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.statistics)) },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenStatistics)
                    },
                )
            }
        }
    }
}

@Composable
private fun DeleteLocalDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.delete_manga)) },
        text = { Text(stringResource(R.string.text_delete_local_manga, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SearchTargetDialog(
    iconRes: Int,
    title: String,
    sourceTitle: String,
    onDismissRequest: () -> Unit,
    onSearchOnSource: () -> Unit,
    onSearchEverywhere: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
            )
        },
        title = { Text(text = title) },
        text = {
            Column {
                TextButton(
                    onClick = onSearchOnSource,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.search_on_s, sourceTitle),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onSearchEverywhere,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.search_everywhere),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.close))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ShareOptionsDialog(
    title: String,
    sourceTitle: String,
    onDismissRequest: () -> Unit,
    onShareAppLink: () -> Unit,
    onShareSourceLink: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
            )
        },
        title = { Text(text = stringResource(R.string.share)) },
        text = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                TextButton(
                    onClick = onShareAppLink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.link_to_manga_in_app),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onShareSourceLink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.link_to_manga_on_s, sourceTitle),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {},
    )
}


private data class PendingAuthorSearch(
    val author: String,
    val source: ContentSource,
)

private fun resolveAvailableDetailsTabIds(
    contentType: ContentType?,
    settings: AppSettings,
): List<Int> = buildList {
    add(DETAILS_TAB_CHAPTERS)
    val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
    val isVideo = contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO
    if (settings.isPagesTabEnabled && !isNovel && !isVideo) {
        add(DETAILS_TAB_PAGES)
    }
    if (!isVideo) {
        add(DETAILS_TAB_BOOKMARKS)
    }
}

private fun resolveDetailsTabSelection(
    requestedTabId: Int,
    availableTabs: List<Int>,
): Int {
    return if (requestedTabId in availableTabs) {
        requestedTabId
    } else {
        when {
            requestedTabId > DETAILS_TAB_CHAPTERS -> {
                availableTabs.getOrElse((requestedTabId - 1).coerceAtLeast(0)) { availableTabs.first() }
            }
            else -> availableTabs.first()
        }
    }
}

@Composable
fun DetailsChromeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        content()
    }
}


@Composable
fun DetailsRelationSections(
    sections: List<EntityRelationSection>,
    onItemClick: (EntityRelationItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        sections.forEach { section ->
            DetailsRelationSectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding),
            ) {
                EntityRelationSectionHeader(section = section)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = AppLayoutTokens.sectionHorizontalPadding),
                ) {
                    items(
                        items = section.items,
                        key = { it.stableKey },
                    ) { item ->
                        EntityRelationCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsRelatedContentSection(
    items: List<ContentListModel>,
    onItemClick: (ContentListModel) -> Unit,
) {
    val cardStyle = compactPosterRailCardStyle(gridScale = 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.details_related_works),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = AppLayoutTokens.sectionHorizontalPadding),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = AppLayoutTokens.sectionHorizontalPadding),
        ) {
            items(
                items = items,
                key = { "${it.source.name}:${it.id}:${it.manga.url}" },
            ) { item ->
                KototoroContentCard(
                    model = item,
                    sharedTransitionEnabled = false,
                    cardStyle = cardStyle,
                    onClick = { onItemClick(item) },
                    onLongClick = {},
                    modifier = Modifier.width(cardStyle.itemWidth + 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailsSupplementMetadataCard(
    properties: List<Pair<String, String>>,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.details_additional_metadata),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            properties.forEach { (label, value) ->
                if (value.isBlank()) {
                    return@forEach
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntityRelationSectionHeader(
    section: EntityRelationSection,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.titleRes?.let { titleRes ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Icon(
                    painter = rememberSafePainter(entityRelationSectionIconRes(titleRes)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(18.dp),
                )
            }
        }
        Text(
            text = section.titleRes?.let { stringResource(it) } ?: section.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Text(
                text = section.items.size.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun EntityRelationCard(
    item: EntityRelationItem,
    onClick: () -> Unit,
) {
    val type = item.type
    val typeLabel = type?.let { stringResource(entityRelationTypeLabelRes(it)) }
    val typeIconRes = type?.let { entityRelationTypeIconRes(it) }
    val opensExternalPage = type == null && item.trackingService == null && item.remoteId == null && !item.url.isNullOrBlank()
    DetailsRelationItemCard(
        width = if (type != null) 148.dp else 132.dp,
        title = item.name,
        subtitle = item.subtitle,
        supportingText = item.supportingText,
        onClick = onClick,
        footer = if (typeLabel != null && typeIconRes != null) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = rememberSafePainter(typeIconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else if (opensExternalPage) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_open_external),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.open_website),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            null
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (type != null) 0.76f else 0.72f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (item.coverUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = rememberSafePainter(typeIconRes ?: R.drawable.ic_placeholder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                } else {
                    val normalizedCoverUrl = item.coverUrl?.takeIfUsableImageUri()
                    AsyncImage(
                        model = normalizedCoverUrl,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f),
                                ),
                            ),
                        ),
                )
                if (typeLabel != null && typeIconRes != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = rememberSafePainter(typeIconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsRelationSectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = GlassDefaults.subtleStyle(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailsRelationItemCard(
    width: androidx.compose.ui.unit.Dp,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supportingText: String? = null,
    footer: (@Composable () -> Unit)? = null,
    cover: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cover()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                supportingText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                footer?.invoke()
            }
        }
    }
}

private fun entityRelationSectionIconRes(titleRes: Int): Int = when (titleRes) {
    R.string.entity_graph_section_characters -> R.drawable.ic_user
    R.string.entity_graph_section_creators -> R.drawable.ic_auto_fix
    R.string.entity_graph_section_parent_work -> R.drawable.ic_content_manga
    R.string.entity_graph_section_voice_actors -> R.drawable.ic_voice_input
    R.string.entity_graph_section_created_works -> R.drawable.ic_content_manga
    R.string.entity_graph_section_voiced_characters -> R.drawable.ic_user
    R.string.entity_graph_section_voiced_works -> R.drawable.ic_content_manga
    R.string.entity_graph_section_related_entities -> R.drawable.ic_select_group
    else -> R.drawable.ic_select_group
}

private fun entityRelationTypeLabelRes(type: org.skepsun.kototoro.entitygraph.domain.EntityType): Int = when (type) {
    org.skepsun.kototoro.entitygraph.domain.EntityType.WORK -> R.string.entity_graph_type_work
    org.skepsun.kototoro.entitygraph.domain.EntityType.CHARACTER -> R.string.entity_graph_type_character
    org.skepsun.kototoro.entitygraph.domain.EntityType.PERSON -> R.string.entity_graph_type_person
    org.skepsun.kototoro.entitygraph.domain.EntityType.ORGANIZATION -> R.string.entity_graph_type_organization
}

private fun entityRelationTypeIconRes(type: org.skepsun.kototoro.entitygraph.domain.EntityType): Int = when (type) {
    org.skepsun.kototoro.entitygraph.domain.EntityType.WORK -> R.drawable.ic_content_manga
    org.skepsun.kototoro.entitygraph.domain.EntityType.CHARACTER -> R.drawable.ic_user
    org.skepsun.kototoro.entitygraph.domain.EntityType.PERSON -> R.drawable.ic_user
    org.skepsun.kototoro.entitygraph.domain.EntityType.ORGANIZATION -> R.drawable.ic_select_group
}
