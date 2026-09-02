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

@Composable
internal fun DetailsScrollableContent(
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
            supplementalMetadataProperties = supplementalMetadataProperties,
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
internal fun TemporaryDetailsReadOnlyNotice(
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

internal fun shouldOpenTrackingRelationSheet(item: EntityRelationItem): Boolean {
    return item.trackingService == null &&
        item.remoteId == null &&
        !item.url.isNullOrBlank() &&
        (!item.subtitle.isNullOrBlank() || !item.supportingText.isNullOrBlank() || item.detailLines.isNotEmpty())
}
