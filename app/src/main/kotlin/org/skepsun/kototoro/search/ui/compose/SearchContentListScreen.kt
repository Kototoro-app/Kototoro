package org.skepsun.kototoro.search.ui.compose


import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.clearFailedContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.model.titleRes
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.getCauseUrl
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.core.parser.favicon.directFaviconUriOrNull
import org.skepsun.kototoro.core.parser.tvbox.TVBoxActionHostActivity
import org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

import org.skepsun.kototoro.filter.ui.tags.TagsCatalogRoute
import org.skepsun.kototoro.list.ui.compose.KototoroContentListScreen
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import org.skepsun.kototoro.parsers.model.Content

internal val SearchPinnedChipHeight = 32.dp
private val SearchPinnedRowVisualHeight = SearchPinnedChipHeight + 8.dp
private val SearchFilterSheetLightMinAlpha = 0.88f
private val SearchFilterSheetLightMaxAlpha = 0.92f
private val SearchFilterSheetDarkMinAlpha = 0.82f
private val SearchFilterSheetDarkMaxAlpha = 0.88f

private enum class SearchSidePaneMode {
    Filter,
    Preview,
}

private data class SearchContentPreparedItems(
    val quickFilter: QuickFilter?,
    val contentItems: List<ListModel>,
    val contentListItems: List<ContentListModel>,
)

private fun prepareSearchContentItems(items: List<ListModel>): SearchContentPreparedItems {
    var quickFilter: QuickFilter? = null
    val contentItems = ArrayList<ListModel>()
    val contentListItems = ArrayList<ContentListModel>()
    items.forEach { item ->
        if (item is QuickFilter && quickFilter == null) {
            quickFilter = item
        } else {
            contentItems += item
            if (item is ContentListModel) {
                contentListItems += item
            }
        }
    }
    return SearchContentPreparedItems(
        quickFilter = quickFilter,
        contentItems = contentItems,
        contentListItems = contentListItems,
    )
}

internal fun lerpFloat(
    start: Float,
    endInclusive: Float,
    fraction: Float,
): Float = start + (endInclusive - start) * fraction.coerceIn(0f, 1f)

@Composable
private fun SearchFilterSheetSurface(
    modifier: Modifier = Modifier,
    dragModifier: Modifier,
    content: @Composable () -> Unit,
) {
    KototoroSheetSurface(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            SheetDragHandle(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .then(dragModifier),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SearchDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedRectangle(24.dp),
        style = GlassDefaults.prominentStyle(),
        dialogSurface = true,
        componentRole = GlassComponentRole.Dialog,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SearchInputDialogSurface(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SearchDialogSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    content()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppSearchContentListRoute(
    appRouter: AppRouter,
    onBackClick: () -> Unit,
    onOpenDetails: ((Content, String?) -> Unit)? = null,
    activeSpaceId: SpaceId? = null,
    onSpaceSwitcherClick: () -> Unit = {},
    sharedTransitionEnabled: Boolean = true,
    viewModel: RemoteListViewModel = hiltViewModel(),
) {
    val items by viewModel.content.collectAsStateWithLifecycle(emptyList())
    val isRandomLoading by viewModel.isRandomLoading.collectAsStateWithLifecycle(false)
    val filterSnapshot by viewModel.filterCoordinator.observe()
        .collectAsStateWithLifecycle(viewModel.filterCoordinator.snapshot())
    val listMode by viewModel.listMode.collectAsStateWithLifecycle(ListMode.GRID)
    val resolvedSourceTitle = rememberResolvedSourceTitle(viewModel.source)
    val source = viewModel.source
    val sortOrderProperty by viewModel.filterCoordinator.sortOrder.collectAsStateWithLifecycle()
    val tagsProperty by viewModel.filterCoordinator.tags.collectAsStateWithLifecycle()
    val tagsExcludedProperty by viewModel.filterCoordinator.tagsExcluded.collectAsStateWithLifecycle()
    val contentTypesProperty by viewModel.filterCoordinator.contentTypes.collectAsStateWithLifecycle()
    val statesProperty by viewModel.filterCoordinator.states.collectAsStateWithLifecycle()
    val localeProperty by viewModel.filterCoordinator.locale.collectAsStateWithLifecycle()
    val authorsProperty by viewModel.filterCoordinator.authors.collectAsStateWithLifecycle()
    val savedFiltersProperty by viewModel.filterCoordinator.savedFilters.collectAsStateWithLifecycle()

    val isFilterSaveEnabled by derivedStateOf {
        filterSnapshot.listFilter.isNotEmpty() && savedFiltersProperty.selectedItems.isEmpty()
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val mainActivity = context as? MainActivity
    val configuration = LocalConfiguration.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val gridSize = settings.observeAsState(AppSettings.KEY_GRID_SIZE) { gridSize }.value
    val gridScale = gridSize / 100f
    val tabletUiMode by settings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
    val isTabletListPreviewEnabled by settings.observeAsState(AppSettings.KEY_TABLET_LIST_PREVIEW) {
        isTabletListPreviewEnabled
    }
    val isTabletListFilterPanelDefaultOpen by settings.observeAsState(
        AppSettings.KEY_TABLET_LIST_FILTER_PANEL_DEFAULT,
    ) {
        isTabletListFilterPanelDefaultOpen
    }
    val globalTagBlacklist by settings.observeAsState(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) {
        this.globalTagBlacklist
    }
    val isWideAdaptiveLayout = remember(context, configuration.orientation, configuration.screenWidthDp, tabletUiMode) {
        FoldableUtils.shouldUseTabletLayout(context, settings, configuration)
    }

    val preparedItems = remember(items) { prepareSearchContentItems(items) }
    val quickFilter = preparedItems.quickFilter
    val contentItems = preparedItems.contentItems
    val contentListItems = preparedItems.contentListItems
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val exceptionResolver = (context as? org.skepsun.kototoro.core.ui.BaseComposeActivity)?.exceptionResolver
    val openDetailsHandler = remember(appRouter, mainActivity, onOpenDetails) {
        onOpenDetails ?: { content: Content, sharedKey: String? ->
            mainActivity?.resolveDetailsOriginForContent(content) { origin ->
                when (origin) {
                    is DetailsOrigin.EntityGraph -> {
                        appRouter.openEntityDetails(
                            entityId = origin.entityId,
                            initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                            sharedElementKey = sharedKey,
                        )
                    }
                    else -> appRouter.openResolvedDetails(content, sharedElementKey = sharedKey)
                }
            } ?: appRouter.openResolvedDetails(content, sharedElementKey = sharedKey)
        }
    }

    LaunchedEffect(viewModel.source.name) {
        clearFailedContentSourceIcon(viewModel.source.name)
        val faviconUri = viewModel.source.directFaviconUriOrNull() ?: return@LaunchedEffect
        val cacheKey = "${viewModel.source.name}#${R.style.FaviconDrawable_Small}"
        val request = ImageRequest.Builder(context)
            .data(faviconUri)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .mangaSourceExtra(viewModel.source)
            .build()
        if (SingletonImageLoader.get(context).execute(request) is SuccessResult) {
            clearFailedContentSourceIcon(viewModel.source.name)
        }
    }

    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf(filterSnapshot.listFilter.query.orEmpty()) }
    var collapseOffsetPx by rememberSaveable { mutableStateOf(0f) }
    var showFilterPanel by rememberSaveable(isWideAdaptiveLayout) {
        mutableStateOf(isWideAdaptiveLayout && isTabletListFilterPanelDefaultOpen)
    }
    var sidePaneMode by rememberSaveable(isWideAdaptiveLayout) { mutableStateOf(SearchSidePaneMode.Filter) }
    var previewContent by remember { mutableStateOf<Content?>(null) }
    var showTagsCatalog by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    var selectedItemsIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val selectedItems: Set<Content> = remember(selectedItemsIds, contentListItems) {
        contentListItems
            .asSequence()
            .filter { it.id in selectedItemsIds }
            .map { it.manga }
            .toSet()
    }
    val isAllNonLocal = selectedItems.none { it.isLocal }

    BackHandler(enabled = selectedItemsIds.isNotEmpty()) {
        selectedItemsIds = emptySet()
    }

    BackHandler(enabled = searchMode) {
        searchMode = false
    }

    LaunchedEffect(filterSnapshot.listFilter.query, searchMode) {
        if (!searchMode) {
            searchQuery = filterSnapshot.listFilter.query.orEmpty()
        }
    }

    var autoApplyDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showFilterPanel, isWideAdaptiveLayout) {
        val shouldAutoApply = (isWideAdaptiveLayout || showFilterPanel) && !autoApplyDone
        if (shouldAutoApply) {
            autoApplyDone = true
            savedFiltersProperty.availableItems
                .filter { it.autoEnabled && it !in savedFiltersProperty.selectedItems }
                .forEach { viewModel.filterCoordinator.toggleSavedFilter(it) }
        }
    }

    LaunchedEffect(viewModel.onOpenContent) {
        viewModel.onOpenContent.collect { event ->
            event?.consume { content ->
                openDetailsHandler(
                    content,
                    contentCoverSharedKey(content, content.coverUrl),
                )
            }
        }
    }

    LaunchedEffect(viewModel.onContentMessage) {
        viewModel.onContentMessage.collect { event ->
            event?.consume { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(viewModel.onContentActionHostRequest) {
        viewModel.onContentActionHostRequest.collect { event ->
            event?.consume { request ->
                val hostActivity = activity ?: return@consume
                TVBoxActionHostActivity.start(hostActivity) { host ->
                    request.execute(host::complete)
                }
            }
        }
    }

    LaunchedEffect(isWideAdaptiveLayout, isTabletListFilterPanelDefaultOpen) {
        if (isWideAdaptiveLayout) {
            sidePaneMode = SearchSidePaneMode.Filter
            showFilterPanel = isTabletListFilterPanelDefaultOpen
        } else {
            previewContent = null
            sidePaneMode = SearchSidePaneMode.Filter
            showFilterPanel = false
        }
    }

    LaunchedEffect(contentItems) {
        val previewId = previewContent?.id ?: return@LaunchedEffect
        if (contentListItems.none { it.id == previewId }) {
            previewContent = null
            sidePaneMode = SearchSidePaneMode.Filter
        }
    }

    LaunchedEffect(contentListItems) {
        if (selectedItemsIds.isNotEmpty()) {
            val availableIds = contentListItems.asSequence().map { it.id }.toSet()
            val filteredSelection = selectedItemsIds.filterTo(mutableSetOf()) { it in availableIds }
            if (filteredSelection != selectedItemsIds) {
                selectedItemsIds = filteredSelection
            }
        }
    }

    val interfaceTokens = LocalInterfaceStyleTokens.current
    val topActionsHeight = interfaceTokens.mainTopBarHeight
    val topActionsHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        topActionsHeight.toPx()
    }
    val statusBarTopPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val maxCollapsePx = topActionsHeightPx
    val isWideSplitLayout = isWideAdaptiveLayout && showFilterPanel
    val showSelectionTopBar = selectedItemsIds.isNotEmpty()
    val extractedPinnedTags = remember(contentListItems, filterSnapshot.listFilter.tags, tagsProperty.availableItems) {
        buildSourcePinnedTags(
            contentItems = contentListItems,
            selectedTags = filterSnapshot.listFilter.tags,
            availableTags = tagsProperty.availableItems.flatMap { it.tags },
        )
    }
    val showPinnedRow = !searchMode && (quickFilter != null || extractedPinnedTags.isNotEmpty() || !filterSnapshot.listFilter.query.isNullOrBlank())
    val topOverlayHeight = statusBarTopPadding + topActionsHeight +
        if (showPinnedRow) SearchPinnedRowVisualHeight else 0.dp
    val wideGridState = remember { LazyGridState() }
    val wideListState = remember { LazyListState() }
    val wideDetailedListState = remember { LazyListState() }
    val providedLayerBackdrop = LocalLiquidGlassLayerBackdrop.current
    val backdropBackground = MaterialTheme.colorScheme.background
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val fallbackLayerBackdrop = if (isIosStyle && providedLayerBackdrop == null) {
        rememberLayerBackdrop {
            drawRect(backdropBackground)
            drawContent()
        }
    } else {
        null
    }
    val listLayerBackdrop = providedLayerBackdrop ?: fallbackLayerBackdrop
    val liquidGlassSourceModifier = if (isIosStyle && listLayerBackdrop != null) {
        Modifier.layerBackdrop(listLayerBackdrop)
    } else {
        Modifier
    }

    fun restoreFilterPane() {
        previewContent = null
        sidePaneMode = SearchSidePaneMode.Filter
        showFilterPanel = true
    }

    BackHandler(enabled = isWideSplitLayout && sidePaneMode == SearchSidePaneMode.Preview) {
        restoreFilterPane()
    }

    val nestedScrollConnection = remember(maxCollapsePx, searchMode) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (searchMode) return Offset.Zero
                val totalY = consumed.y
                if (totalY == 0f) return Offset.Zero
                val delta = -totalY
                val newOffset = (collapseOffsetPx + delta).coerceIn(0f, maxCollapsePx)
                collapseOffsetPx = newOffset
                return Offset.Zero
            }
        }
    }

    fun resolveErrorAndRetry() {
        val error = items
            .filterIsInstance<ErrorState>()
            .firstOrNull { ExceptionResolver.canResolve(it.exception) }
            ?.exception
        Log.i("SearchCfResolver", "retry clicked error=${error?.javaClass?.name} resolver=${exceptionResolver != null}")
        if (error != null && exceptionResolver != null) {
            coroutineScope.launch {
                Log.i("SearchCfResolver", "starting manual resolver")
                val cloudflare = error.findCloudFlareException()
                val resolverError = if (
                    cloudflare is CloudFlareProtectedException && cloudflare.source == UnknownContentSource
                ) {
                    CloudFlareProtectedException(cloudflare.url, viewModel.source, cloudflare.headers)
                } else {
                    error
                }
                if (exceptionResolver.resolve(resolverError, tryAutoResolve = false)) {
                    Log.i(
                        "SearchCfResolver",
                        "manual resolver succeeded, retrying source=${viewModel.source.name} " +
                            "challengeUrl=${cloudflare?.url}",
                    )
                    viewModel.onRetry()
                } else {
                    Log.w("SearchCfResolver", "manual resolver failed or was unavailable")
                }
            }
        } else {
            Log.w("SearchCfResolver", "no resolvable error or resolver, retrying directly")
            viewModel.onRetry()
        }
    }

    fun openErrorInBrowser(error: Throwable) {
        val url = error.findCloudFlareException()?.url ?: error.getCauseUrl() ?: return
        appRouter.openBrowser(
            url = CloudFlareHelper.getChallengeUrl(url),
            source = viewModel.source,
            title = null,
        )
    }

    val topBarContent: @Composable () -> Unit = {
        if (showSelectionTopBar) {
            KototoroSelectionTopBar(
                selectedCount = selectedItemsIds.size,
                isAllNonLocal = isAllNonLocal,
                isSingleSelection = selectedItemsIds.size == 1,
                supportedActions = buildSet {
                    add(SelectionAction.SHARE)
                    add(SelectionAction.FAVOURITE)
                    if (isAllNonLocal) add(SelectionAction.SAVE)
                },
                onClearSelection = { selectedItemsIds = emptySet() },
                onActionClick = { action ->
                    when (action) {
                        SelectionAction.SHARE -> {
                            ShareHelper(context).shareContentLinks(selectedItems)
                            selectedItemsIds = emptySet()
                        }
                        SelectionAction.FAVOURITE -> {
                            appRouter.showFavoriteDialog(selectedItems)
                            selectedItemsIds = emptySet()
                        }
                        SelectionAction.SAVE -> {
                            if (isAllNonLocal) {
                                appRouter.showDownloadDialog(selectedItems)
                                selectedItemsIds = emptySet()
                            }
                        }
                        else -> Unit
                    }
                },
            )
        } else {
                SearchContentTopBar(
                    searchMode = searchMode,
                    searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSearchOpen = { searchMode = true },
                    onSearchClose = { searchMode = false },
                    onSearchSubmit = {
                        viewModel.filterCoordinator.setQuery(searchQuery.takeIf { it.isNotBlank() })
                        searchMode = false
                    },
                    focusRequester = focusRequester,
                    sourceTitle = resolvedSourceTitle,
                activeQuery = filterSnapshot.listFilter.query,
                currentSortLabel = stringResource(filterSnapshot.sortOrder.titleRes),
                isFilterApplied = viewModel.filterCoordinator.isFilterApplied,
                quickFilter = quickFilter,
                contentItems = contentListItems,
                selectedTags = filterSnapshot.listFilter.tags,
                availableTags = tagsProperty.availableItems.flatMap { it.tags },
                listMode = listMode,
                gridSize = gridSize,
                topActionsHeight = topActionsHeight,
                collapseOffsetPx = collapseOffsetPx,
                isRandomLoading = isRandomLoading,
                activeSpaceId = activeSpaceId,
                onBackClick = onBackClick,
                onSpaceSwitcherClick = onSpaceSwitcherClick,
                onRandomClick = viewModel::openRandom,
                onFilterClick = {
                    if (isWideAdaptiveLayout) {
                        when {
                            sidePaneMode == SearchSidePaneMode.Preview -> {
                                restoreFilterPane()
                            }
                            else -> showFilterPanel = !showFilterPanel
                        }
                    } else {
                        showFilterPanel = !showFilterPanel
                    }
                },
                onResetFilterClick = viewModel.filterCoordinator::reset,
                onSettingsClick = { appRouter.openSourceSettings(viewModel.source) },
                onListModeChange = { settings.listMode = it },
                onGridSizeChange = { size ->
                    settings.gridSize = size.coerceIn(50, 150)
                },
                onClearActiveQuery = {
                    searchQuery = ""
                    viewModel.filterCoordinator.setQuery(null)
                },
                onQuickFilterOptionClick = { option ->
                    (viewModel as? org.skepsun.kototoro.list.domain.QuickFilterListener)?.toggleFilterOption(option)
                },
                onToggleTag = { tag, selected -> viewModel.filterCoordinator.toggleTag(tag, selected) },
            )
        }
    }

    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides listLayerBackdrop,
        LocalLiquidGlassLayerBackdrop provides listLayerBackdrop,
    ) {
        androidx.compose.material3.Scaffold(contentWindowInsets = WindowInsets.navigationBars) { paddingValues ->
            if (isWideSplitLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(liquidGlassSourceModifier),
                        ) {
                            KototoroContentListScreen(
                                items = contentItems,
                                gridScale = gridScale,
                                listMode = listMode,
                                isRefreshing = false,
                                contentPadding = PaddingValues(0.dp, topOverlayHeight, 0.dp, 0.dp),
                                sharedTransitionEnabled = sharedTransitionEnabled,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(nestedScrollConnection),
                                onPrepareItemTransition = { _, _ -> },
                                onItemClick = itemClick@{ item ->
                                    if (selectedItemsIds.isNotEmpty()) {
                                        hapticFeedback.performSelectionHapticFeedback()
                                        selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                                    } else {
                                        val content = item.toContentWithOverride()
                                        if (viewModel.onContentClick(content)) return@itemClick
                                        if (isTabletListPreviewEnabled) {
                                            previewContent = content
                                            sidePaneMode = SearchSidePaneMode.Preview
                                        } else {
                                            previewContent = null
                                            sidePaneMode = SearchSidePaneMode.Filter
                                            val sharedElementKey = contentCoverSharedKey(content, item.coverUrl)
                                            openDetailsHandler(
                                                content,
                                                sharedElementKey,
                                            )
                                        }
                                    }
                                },
                                onItemLongClick = { item ->
                                    selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                                },
                                onLoadMore = { viewModel.loadNextPage() },
                                onRefresh = { viewModel.onRefresh() },
                                onClearSelection = { selectedItemsIds = emptySet() },
                                onSelectionAction = { action ->
                                    when (action) {
                                        SelectionAction.SHARE -> {
                                            ShareHelper(context).shareContentLinks(selectedItems)
                                            selectedItemsIds = emptySet()
                                            true
                                        }

                                        SelectionAction.FAVOURITE -> {
                                            appRouter.showFavoriteDialog(selectedItems)
                                            selectedItemsIds = emptySet()
                                            true
                                        }

                                        SelectionAction.SAVE -> {
                                            if (isAllNonLocal) {
                                                appRouter.showDownloadDialog(selectedItems)
                                                selectedItemsIds = emptySet()
                                                true
                                            } else {
                                                false
                                            }
                                        }

                                        else -> false
                                    }
                                },
                                selectedItemsIds = selectedItemsIds,
                                showInlineSelectionTopBar = false,
                                onRetry = ::resolveErrorAndRetry,
                                onSecondaryAction = ::openErrorInBrowser,
                                gridState = if (listMode == ListMode.GRID || listMode == ListMode.COMPACT_GRID) {
                                    wideGridState
                                } else {
                                    null
                                },
                                listState = if (listMode == ListMode.LIST) wideListState else null,
                                detailedListState = if (listMode == ListMode.DETAILED_LIST) wideDetailedListState else null,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart),
                        ) {
                            topBarContent()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 12.dp)
                            .alpha(0.7f)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        if (sidePaneMode == SearchSidePaneMode.Preview && previewContent != null) {
                            SearchPreviewPane(
                                content = requireNotNull(previewContent),
                                onBackToFilters = ::restoreFilterPane,
                                onOpenDetails = {
                                    val content = requireNotNull(previewContent)
                                    val sharedElementKey = contentCoverSharedKey(content, content.coverUrl)
                                    openDetailsHandler(
                                        content,
                                        sharedElementKey,
                                    )
                                },
                            )
                        } else {
                            SearchFilterPanel(
                                sourceName = viewModel.source.name,
                                sortOrders = sortOrderProperty.availableItems,
                                selectedSortOrder = sortOrderProperty.selectedItems.firstOrNull(),
                                tagGroups = tagsProperty.availableItems,
                                excludedTagGroups = tagsExcludedProperty.availableItems,
                                contentTypes = contentTypesProperty.availableItems,
                                selectedContentTypes = contentTypesProperty.selectedItems,
                                states = statesProperty.availableItems,
                                selectedStates = statesProperty.selectedItems,
                                locales = localeProperty.availableItems,
                                selectedLocale = localeProperty.selectedItems.firstOrNull(),
                                authors = authorsProperty.availableItems,
                                selectedAuthor = authorsProperty.selectedItems.firstOrNull(),
                                blacklistedTagCount = globalTagBlacklist.size,
                                onOpenGlobalTagBlacklist = appRouter::openGlobalTagBlacklist,
                                onSortOrderChange = viewModel.filterCoordinator::setSortOrder,
                                onToggleTag = { tag, selected, excludeMode ->
                                    if (excludeMode) {
                                        viewModel.filterCoordinator.toggleTagExclude(tag, selected)
                                    } else {
                                        viewModel.filterCoordinator.toggleTag(tag, selected)
                                    }
                                },
                                onToggleContentType = { type, selected -> viewModel.filterCoordinator.toggleContentType(type, selected) },
                                onToggleState = { state, selected -> viewModel.filterCoordinator.toggleState(state, selected) },
                                onLocaleChange = viewModel.filterCoordinator::setLocale,
                                onAuthorChange = viewModel.filterCoordinator::setAuthor,
                                onReset = viewModel.filterCoordinator::reset,
                                isTextInputTag = viewModel.filterCoordinator::isTextInputTag,
                                textInputValue = viewModel.filterCoordinator::getTextInputValue,
                                textInputLabel = viewModel.filterCoordinator::getTextInputLabel,
                                onSetTextInputValue = viewModel.filterCoordinator::setTextInputValue,
                                onOpenTagCatalog = { groupTitle, excludeMode ->
                                    showTagsCatalog = groupTitle to excludeMode
                                },
                                modifier = Modifier.fillMaxHeight(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    top = statusBarTopPadding,
                                    end = 16.dp,
                                    bottom = 12.dp,
                                ),
                                savedFilters = savedFiltersProperty,
                                isSaveEnabled = isFilterSaveEnabled,
                                onToggleSavedFilter = viewModel.filterCoordinator::toggleSavedFilter,
                                onSaveFilter = viewModel.filterCoordinator::saveCurrentFilter,
                                onRenameSavedFilter = viewModel.filterCoordinator::renameSavedFilter,
                                onDeleteSavedFilter = viewModel.filterCoordinator::deleteSavedFilter,
                                onSetSavedFilterAutoEnabled = viewModel.filterCoordinator::setSavedFilterAutoEnabled,
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(liquidGlassSourceModifier),
                    ) {
                        KototoroContentListScreen(
                            items = contentItems,
                            gridScale = gridScale,
                            listMode = listMode,
                            isRefreshing = false,
                            contentPadding = PaddingValues(
                                top = topOverlayHeight,
                                bottom = paddingValues.calculateBottomPadding(),
                            ),
                            sharedTransitionEnabled = sharedTransitionEnabled,
                            modifier = Modifier.nestedScroll(nestedScrollConnection),
                            onPrepareItemTransition = { _, _ -> },
                            onItemClick = itemClick@{ item ->
                                if (selectedItemsIds.isNotEmpty()) {
                                    hapticFeedback.performSelectionHapticFeedback()
                                    selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                                } else {
                                    val content = item.toContentWithOverride()
                                    if (viewModel.onContentClick(content)) return@itemClick
                                    val sharedElementKey = contentCoverSharedKey(content, item.coverUrl)
                                    openDetailsHandler(
                                        content,
                                        sharedElementKey,
                                    )
                                }
                            },
                            onItemLongClick = { item ->
                                selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                            },
                            onLoadMore = { viewModel.loadNextPage() },
                            onRefresh = { viewModel.onRefresh() },
                            onClearSelection = { selectedItemsIds = emptySet() },
                            onSelectionAction = { action ->
                                when (action) {
                                    SelectionAction.SHARE -> {
                                        ShareHelper(context).shareContentLinks(selectedItems)
                                        selectedItemsIds = emptySet()
                                        true
                                    }

                                    SelectionAction.FAVOURITE -> {
                                        appRouter.showFavoriteDialog(selectedItems)
                                        selectedItemsIds = emptySet()
                                        true
                                    }

                                    SelectionAction.SAVE -> {
                                        if (isAllNonLocal) {
                                            appRouter.showDownloadDialog(selectedItems)
                                            selectedItemsIds = emptySet()
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    else -> false
                                }
                            },
                            selectedItemsIds = selectedItemsIds,
                            showInlineSelectionTopBar = false,
                            onRetry = ::resolveErrorAndRetry,
                            onSecondaryAction = ::openErrorInBrowser,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart),
                    ) {
                        topBarContent()
                    }
                }
            }

            if (!isWideAdaptiveLayout && showFilterPanel) {
                StableAnchoredBottomSheet(
                    onDismissRequest = { showFilterPanel = false },
                    shape = RectangleShape,
                    containerColor = Color.Transparent,
                    dragHandle = null,
                ) { sheetDragModifier ->
                    SearchFilterSheetSurface(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxSize(),
                        dragModifier = sheetDragModifier,
                    ) {
                        SearchFilterPanel(
                            sourceName = viewModel.source.name,
                            sortOrders = sortOrderProperty.availableItems,
                            selectedSortOrder = sortOrderProperty.selectedItems.firstOrNull(),
                            tagGroups = tagsProperty.availableItems,
                            excludedTagGroups = tagsExcludedProperty.availableItems,
                            contentTypes = contentTypesProperty.availableItems,
                            selectedContentTypes = contentTypesProperty.selectedItems,
                            states = statesProperty.availableItems,
                            selectedStates = statesProperty.selectedItems,
                            locales = localeProperty.availableItems,
                            selectedLocale = localeProperty.selectedItems.firstOrNull(),
                            authors = authorsProperty.availableItems,
                            selectedAuthor = authorsProperty.selectedItems.firstOrNull(),
                            blacklistedTagCount = globalTagBlacklist.size,
                            onOpenGlobalTagBlacklist = appRouter::openGlobalTagBlacklist,
                            onSortOrderChange = viewModel.filterCoordinator::setSortOrder,
                            onToggleTag = { tag, selected, excludeMode ->
                                if (excludeMode) {
                                    viewModel.filterCoordinator.toggleTagExclude(tag, selected)
                                } else {
                                    viewModel.filterCoordinator.toggleTag(tag, selected)
                                }
                            },
                            onToggleContentType = { type, selected -> viewModel.filterCoordinator.toggleContentType(type, selected) },
                            onToggleState = { state, selected -> viewModel.filterCoordinator.toggleState(state, selected) },
                            onLocaleChange = viewModel.filterCoordinator::setLocale,
                            onAuthorChange = viewModel.filterCoordinator::setAuthor,
                            onReset = viewModel.filterCoordinator::reset,
                            isTextInputTag = viewModel.filterCoordinator::isTextInputTag,
                            textInputValue = viewModel.filterCoordinator::getTextInputValue,
                            textInputLabel = viewModel.filterCoordinator::getTextInputLabel,
                            onSetTextInputValue = viewModel.filterCoordinator::setTextInputValue,
                            onOpenTagCatalog = { groupTitle, excludeMode ->
                                showTagsCatalog = groupTitle to excludeMode
                            },
                            modifier = Modifier.fillMaxWidth(),
                            fillAvailableHeight = true,
                            savedFilters = savedFiltersProperty,
                            isSaveEnabled = isFilterSaveEnabled,
                            onToggleSavedFilter = viewModel.filterCoordinator::toggleSavedFilter,
                            onSaveFilter = viewModel.filterCoordinator::saveCurrentFilter,
                            onRenameSavedFilter = viewModel.filterCoordinator::renameSavedFilter,
                            onDeleteSavedFilter = viewModel.filterCoordinator::deleteSavedFilter,
                        )
                    }
                }
            }
        }

        showTagsCatalog?.let { (groupTitle: String?, excludeMode: Boolean) ->
            TagsCatalogRoute(
                filter = viewModel.filterCoordinator,
                isExcludeTag = excludeMode,
                groupTitle = groupTitle,
                onDismiss = { showTagsCatalog = null },
            )
        }
    }
}

