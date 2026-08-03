package org.skepsun.kototoro.search.ui.compose

import androidx.core.text.HtmlCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.clearFailedContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.resolveTopImmersiveAlpha
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.model.titleRes
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.parser.favicon.directFaviconUriOrNull
import org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.TopBarControlSurface
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.filter.ui.tags.TagsCatalogRoute
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.compose.KototoroContentListScreen
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceSwitcherIcon
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistStatus
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Locale
import java.util.TreeSet

private val SearchPinnedChipHeight = 32.dp
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

private fun lerpFloat(
    start: Float,
    endInclusive: Float,
    fraction: Float,
): Float = start + (endInclusive - start) * fraction.coerceIn(0f, 1f)

@Composable
private fun SearchFilterSheetSurface(
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = false,
    content: @Composable () -> Unit,
) {
    KototoroSheetSurface(
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showDragHandle) {
                SheetDragHandle(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
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
        shape = RoundedCornerShape(24.dp),
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
private fun SearchInputDialogSurface(
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
    val mainActivity = context as? MainActivity
    val configuration = LocalConfiguration.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val gridSize = settings.observeAsState(AppSettings.KEY_GRID_SIZE) { gridSize }.value
    val gridScale = gridSize / 100f
    val tabletUiMode by settings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
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
        ?: (context as? org.skepsun.kototoro.core.ui.BaseActivity<*>)?.exceptionResolver
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
    var showFilterPanel by rememberSaveable(isWideAdaptiveLayout) { mutableStateOf(isWideAdaptiveLayout) }
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

    LaunchedEffect(isWideAdaptiveLayout) {
        if (isWideAdaptiveLayout) {
            sidePaneMode = SearchSidePaneMode.Filter
            showFilterPanel = true
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
        if (error != null && exceptionResolver != null) {
            coroutineScope.launch {
                if (exceptionResolver.resolve(error)) {
                    viewModel.onRetry()
                }
            }
        } else {
            viewModel.onRetry()
        }
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
                                onItemClick = { item ->
                                    if (selectedItemsIds.isNotEmpty()) {
                                        hapticFeedback.performSelectionHapticFeedback()
                                        selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                                    } else {
                                        previewContent = item.toContentWithOverride()
                                        sidePaneMode = SearchSidePaneMode.Preview
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
                            onItemClick = { item ->
                                if (selectedItemsIds.isNotEmpty()) {
                                    hapticFeedback.performSelectionHapticFeedback()
                                    selectedItemsIds = if (item.id in selectedItemsIds) selectedItemsIds - item.id else selectedItemsIds + item.id
                                } else {
                                    val content = item.toContentWithOverride()
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
                ModalBottomSheet(
                    onDismissRequest = { showFilterPanel = false },
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    shape = RoundedCornerShape(0.dp),
                    dragHandle = null,
                ) {
                    SearchFilterSheetSurface(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        showDragHandle = true,
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
                            fillAvailableHeight = false,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPreviewPane(
    content: Content,
    onBackToFilters: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val context = LocalContext.current
    val coverRequest = remember(content.id, content.largeCoverUrl, content.coverUrl, content.source) {
        ImageRequest.Builder(context)
            .data(content.largeCoverUrl ?: content.coverUrl)
            .mangaSourceExtra(content.source)
            .build()
    }
    val description = remember(content.description) {
        HtmlCompat.fromHtml(content.description.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()
    }
    val primaryAuthor = content.authors.firstOrNull().orEmpty()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.details),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = sourceTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onBackToFilters) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.filter),
                )
            }
        }

        AsyncImage(
            model = coverRequest,
            contentDescription = content.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (primaryAuthor.isNotBlank()) {
                Text(
                    text = primaryAuthor,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content.state?.let { state ->
                Text(
                    text = stringResource(state.titleResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        OutlinedButton(
            onClick = onOpenDetails,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_expand),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.details),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (description.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.description),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (content.tags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.genres),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content.tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    text = tag.title,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchContentTopBar(
    searchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Unit,
    focusRequester: FocusRequester,
    sourceTitle: String,
    activeQuery: String?,
    currentSortLabel: String,
    isFilterApplied: Boolean,
    quickFilter: QuickFilter?,
    contentItems: List<ContentListModel>,
    selectedTags: Set<ContentTag>,
    availableTags: List<ContentTag>,
    listMode: ListMode,
    gridSize: Int,
    topActionsHeight: Dp,
    collapseOffsetPx: Float,
    isRandomLoading: Boolean,
    activeSpaceId: SpaceId?,
    onBackClick: () -> Unit,
    onSpaceSwitcherClick: () -> Unit,
    onRandomClick: () -> Unit,
    onFilterClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onListModeChange: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onClearActiveQuery: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    onToggleTag: (ContentTag, Boolean) -> Unit,
) {
    val extractedTags = remember(contentItems, selectedTags, availableTags) {
        buildSourcePinnedTags(
            contentItems = contentItems,
            selectedTags = selectedTags,
            availableTags = availableTags,
        )
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topActionsHeightPx = with(density) { topActionsHeight.toPx() }
    val topActionsCollapsedPx = collapseOffsetPx.coerceIn(0f, topActionsHeightPx)
    val topActionsVisibleHeight = with(density) { (topActionsHeightPx - topActionsCollapsedPx).coerceAtLeast(0f).toDp() }
    val compactTopBarAlpha = if (topActionsHeightPx == 0f) 1f else {
        ((topActionsHeightPx - topActionsCollapsedPx) / topActionsHeightPx).coerceIn(0f, 1f)
    }
    val topGradientAlpha = resolveTopImmersiveAlpha(
        contentScrollAlpha = (1f - compactTopBarAlpha).coerceIn(0f, 1f),
        chromeAlpha = compactTopBarAlpha,
    )
    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val statusBarTopPadding = statusBarPadding.calculateTopPadding()
    val glassPrefs = rememberGlassPrefsOrFallback()
    val immersiveStrength = (glassPrefs.immersiveStrengthPercent.coerceIn(0, 100)) / 100f
    val isDarkTheme = isSystemInDarkTheme()
    val immersiveBaseColor = if (isDarkTheme) Color.Black else Color.White
    val immersiveTopColors = listOf(
        immersiveBaseColor.copy(alpha = lerpFloat(0.72f, 0.98f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.56f, 0.82f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.32f, 0.52f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.12f, 0.22f, immersiveStrength)),
        Color.Transparent,
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarTopPadding + topActionsHeight + 6.dp)
                .graphicsLayer { alpha = topGradientAlpha }
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to immersiveTopColors[0],
                            0.38f to immersiveTopColors[1],
                            0.72f to immersiveTopColors[2],
                            0.92f to immersiveTopColors[3],
                            1f to immersiveTopColors[4],
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarTopPadding),
        ) {
            if (searchMode) {
                SearchInputRow(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    onClose = onSearchClose,
                    onSubmit = onSearchSubmit,
                    focusRequester = focusRequester,
                )
            } else {
                CollapsingBarSlot(
                    visibleHeight = topActionsVisibleHeight,
                    fullHeight = topActionsHeight,
                ) {
                    var showDisplayOptionsSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

                    SourceListTopActionsRow(
                        sourceTitle = sourceTitle,
                        currentSortLabel = currentSortLabel,
                        topBarAlpha = compactTopBarAlpha,
                        listMode = listMode,
                        gridSize = gridSize,
                        isFilterApplied = isFilterApplied,
                        isRandomLoading = isRandomLoading,
                        activeSpaceId = activeSpaceId,
                        onBackClick = onBackClick,
                        onSpaceSwitcherClick = onSpaceSwitcherClick,
                        onSearchClick = onSearchOpen,
                        onRandomClick = onRandomClick,
                        onFilterClick = onFilterClick,
                        onResetFilterClick = onResetFilterClick,
                        onSettingsClick = onSettingsClick,
                        onListModeChange = onListModeChange,
                        onGridSizeChange = onGridSizeChange,
                        onShowDisplayOptionsSheet = { showDisplayOptionsSheet = true }
                    )

                    if (showDisplayOptionsSheet) {
                        org.skepsun.kototoro.list.ui.compose.DisplayOptionsSheet(
                            supportsDisplayModeMenu = true,
                            currentListMode = listMode,
                            onListModeSelected = onListModeChange,
                            supportsGridSizeSlider = true,
                            gridSize = gridSize,
                            onGridSizeChange = onGridSizeChange,
                            onDismissRequest = { showDisplayOptionsSheet = false },
                        )
                    }
                }
            }

            if (!searchMode) {
                if (quickFilter != null) {
                    QuickFilterPinnedRow(
                        quickFilter = quickFilter,
                        activeQuery = activeQuery,
                        onClearActiveQuery = onClearActiveQuery,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                    )
                } else {
                    SourceTagsPinnedRow(
                        tags = extractedTags,
                        selectedTags = selectedTags,
                        activeQuery = activeQuery,
                        onClearActiveQuery = onClearActiveQuery,
                        onToggleTag = onToggleTag,
                    )
                }
            }
        }
    }
}

private fun buildSourcePinnedTags(
    contentItems: List<ContentListModel>,
    selectedTags: Set<ContentTag>,
    availableTags: List<ContentTag>,
    limit: Int = 16,
): List<ContentTag> {
    val counts = LinkedHashMap<ContentTag, Int>()
    contentItems.forEach { item ->
        item.manga.tags.forEach { tag ->
            counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    val frequencyOrdered = counts.entries
        .sortedWith(compareByDescending<Map.Entry<ContentTag, Int>> { it.value }.thenBy { it.key.title })
        .map { it.key }
    val fallbackTags = availableTags
        .asSequence()
        .distinct()
        .filterNot { counts.containsKey(it) }
        .sortedBy { it.title }
        .toList()
    return buildList(limit) {
        selectedTags
            .sortedBy { it.title }
            .forEach { tag ->
                if (tag !in this) {
                    add(tag)
                }
            }
        frequencyOrdered.forEach { tag ->
            if (size >= limit) return@forEach
            if (tag !in this) {
                add(tag)
            }
        }
        fallbackTags.forEach { tag ->
            if (size >= limit) return@forEach
            if (tag !in this) {
                add(tag)
            }
        }
    }
}

@Composable
private fun SearchInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.close),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(horizontal = 6.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.clear),
                )
            }
        }
    }
}

@Composable
private fun SourceListTopActionsRow(
    sourceTitle: String,
    currentSortLabel: String,
    topBarAlpha: Float,
    listMode: ListMode,
    gridSize: Int,
    isFilterApplied: Boolean,
    isRandomLoading: Boolean,
    activeSpaceId: SpaceId?,
    onBackClick: () -> Unit,
    onSpaceSwitcherClick: () -> Unit,
    onSearchClick: () -> Unit,
    onRandomClick: () -> Unit,
    onFilterClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onListModeChange: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onShowDisplayOptionsSheet: () -> Unit,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val controlSize = tokens.topBarButtonSize
    val iconSize = tokens.topBarIconSize
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val maxWidthDp = maxWidth.value
        val showRandomDirect = maxWidthDp >= 420f
        val showDisplayDirect = maxWidthDp >= 476f
        val showSettingsDirect = maxWidthDp >= 532f
        val shouldShowOverflow = !showRandomDirect || !showDisplayDirect || !showSettingsDirect || isFilterApplied

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.mainTopBarHeight)
                .padding(horizontal = CompactTopBarHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        ) {
            TopBarControlSurface(
                modifier = Modifier.wrapContentWidth(),
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(controlSize),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            Text(
                text = sourceTitle,
                modifier = Modifier
                    .weight(1f)
                    .alpha(topBarAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            TopBarControlSurface(
                modifier = Modifier.wrapContentWidth(),
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides controlSize) {
                    Row(
                        modifier = Modifier
                            .height(controlSize)
                            .padding(horizontal = 2.dp)
                            .alpha(topBarAlpha),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        activeSpaceId?.let { spaceId ->
                            IconButton(
                                onClick = onSpaceSwitcherClick,
                                modifier = Modifier.size(controlSize),
                            ) {
                                SpaceSwitcherIcon(
                                    activeSpaceId = spaceId,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }
                        BadgedBox(
                            badge = {
                                if (isFilterApplied) {
                                    Badge()
                                }
                            },
                        ) {
                            IconButton(onClick = onFilterClick, modifier = Modifier.size(controlSize)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_filter_menu),
                                    contentDescription = currentSortLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        IconButton(onClick = onSearchClick, modifier = Modifier.size(controlSize)) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(iconSize),
                            )
                        }

                        if (showRandomDirect) {
                            IconButton(
                                onClick = onRandomClick,
                                enabled = !isRandomLoading,
                                modifier = Modifier.size(controlSize),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_dice),
                                    contentDescription = stringResource(R.string.random),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (showDisplayDirect) {
                            IconButton(
                                onClick = onShowDisplayOptionsSheet,
                                modifier = Modifier.size(controlSize),
                            ) {
                                Icon(
                                    painter = painterResource(listMode.iconRes()),
                                    contentDescription = stringResource(R.string.list_options),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (showSettingsDirect) {
                            IconButton(onClick = onSettingsClick, modifier = Modifier.size(controlSize)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.settings),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }

                        if (shouldShowOverflow) {
                            MoreActionsButton(
                                showRandomAction = !showRandomDirect,
                                showDisplayActions = !showDisplayDirect,
                                showSettingsAction = !showSettingsDirect,
                                listMode = listMode,
                                gridSize = gridSize,
                                isFilterApplied = isFilterApplied,
                                isRandomLoading = isRandomLoading,
                                onRandomClick = onRandomClick,
                                onResetFilterClick = onResetFilterClick,
                                onSettingsClick = onSettingsClick,
                                onShowDisplayOptionsSheet = onShowDisplayOptionsSheet,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MoreActionsButton(
    showRandomAction: Boolean,
    showDisplayActions: Boolean,
    showSettingsAction: Boolean,
    listMode: ListMode,
    gridSize: Int,
    isFilterApplied: Boolean,
    isRandomLoading: Boolean,
    onRandomClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onShowDisplayOptionsSheet: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val tokens = LocalInterfaceStyleTokens.current

    Box(
        modifier = Modifier.onGloballyPositioned { anchorBounds = it.boundsInRoot() },
    ) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(tokens.topBarButtonSize)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more),
                modifier = Modifier.size(tokens.topBarIconSize),
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
            shape = RoundedCornerShape(28.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = anchorBounds,
        ) {
            if (showRandomAction) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.random)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dice),
                            contentDescription = null,
                        )
                    },
                    enabled = !isRandomLoading,
                    onClick = {
                        expanded = false
                        onRandomClick()
                    },
                )
            }

            if (showSettingsAction) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSettingsClick()
                    },
                )
            }

            if (showDisplayActions) {
                HorizontalDivider()
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.display_options)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_grid),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onShowDisplayOptionsSheet()
                    },
                )
            }

            if (isFilterApplied) {
                HorizontalDivider()
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.reset_filter)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onResetFilterClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun CollapsingBarSlot(
    visibleHeight: Dp,
    fullHeight: Dp,
    content: @Composable () -> Unit,
) {
    if (visibleHeight <= 0.dp) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(visibleHeight)
            .then(
                if (visibleHeight < fullHeight) {
                    Modifier.clipToBounds()
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight),
        ) {
            content()
        }
    }
}

@Composable
private fun QuickFilterPinnedRow(
    quickFilter: QuickFilter,
    activeQuery: String?,
    onClearActiveQuery: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        activeQuery?.takeIf { it.isNotBlank() }?.let { query ->
            item(key = "active_query") {
                ActiveQueryChip(query = query, onClear = onClearActiveQuery)
            }
        }
        items(quickFilter.items) { chip ->
            val isSelected = chip.isChecked
            val option = chip.data as? ListFilterOption
            PinnedRowPill(
                selected = isSelected,
                enabled = option != null,
                onClick = {
                    if (option != null) {
                        onQuickFilterOptionClick(option)
                    }
                },
                leading = if (chip.icon != 0) {
                    {
                        Icon(
                            painter = painterResource(chip.icon),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                } else {
                    null
                },
            ) {
                Text(
                    text = when {
                        chip.titleResId != 0 -> stringResource(chip.titleResId)
                        chip.title != null -> chip.title.toString()
                        else -> ""
                    }.let { title ->
                        if (chip.counter > 0) "$title ${chip.counter}" else title
                    },
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SourceTagsPinnedRow(
    tags: List<ContentTag>,
    selectedTags: Set<ContentTag>,
    activeQuery: String?,
    onClearActiveQuery: () -> Unit,
    onToggleTag: (ContentTag, Boolean) -> Unit,
) {
    if (tags.isEmpty() && activeQuery.isNullOrBlank()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = CompactTopBarHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        activeQuery?.takeIf { it.isNotBlank() }?.let { query ->
            item(key = "active_query") {
                ActiveQueryChip(query = query, onClear = onClearActiveQuery)
            }
        }
        itemsIndexed(
            items = tags,
            key = { index, tag -> sourceTagChipKey(tag, index) },
        ) { _, tag ->
            val isSelected = tag in selectedTags
            PinnedRowPill(
                selected = isSelected,
                onClick = { onToggleTag(tag, !isSelected) },
            ) {
                Text(
                    text = tag.title,
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ActiveQueryChip(
    query: String,
    onClear: () -> Unit,
) {
    PinnedRowPill(
        selected = true,
        onClick = onClear,
        leading = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = stringResource(R.string.clear),
                modifier = Modifier.size(13.dp),
            )
        },
    ) {
        Text(
            text = query,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PinnedRowPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val contentColor = (
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ).copy(alpha = if (enabled) 1f else 0.56f)
    val selectedOverlayColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
    } else {
        Color.Transparent
    }

    TopBarControlSurface(
        modifier = modifier
            .height(SearchPinnedChipHeight)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .height(SearchPinnedChipHeight)
                .background(selectedOverlayColor, CircleShape)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    leading?.invoke()
                    content()
                    trailing?.invoke()
                }
            }
        }
    }
}

private fun sourceTagChipKey(
    tag: ContentTag,
    index: Int,
): String = "${tag.source.name}:${tag.key}:${tag.title}:$index"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterPanel(
    sourceName: String,
    sortOrders: List<SortOrder>,
    selectedSortOrder: SortOrder?,
    tagGroups: List<UiTagGroup>,
    excludedTagGroups: List<UiTagGroup>,
    contentTypes: List<ContentType>,
    selectedContentTypes: Set<ContentType>,
    states: List<ContentState>,
    selectedStates: Set<ContentState>,
    locales: List<Locale?>,
    selectedLocale: Locale?,
    authors: List<String>,
    selectedAuthor: String?,
    blacklistedTagCount: Int,
    onOpenGlobalTagBlacklist: () -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onToggleContentType: (ContentType, Boolean) -> Unit,
    onToggleState: (ContentState, Boolean) -> Unit,
    onLocaleChange: (Locale?) -> Unit,
    onAuthorChange: (String?) -> Unit,
    onReset: () -> Unit,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onSetTextInputValue: (ContentTag, String) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    savedFilters: FilterProperty<PersistableFilter> = FilterProperty.EMPTY,
    isSaveEnabled: Boolean = false,
    onToggleSavedFilter: (PersistableFilter) -> Unit = {},
    onSaveFilter: (String) -> Unit = {},
    onRenameSavedFilter: (Int, String) -> Unit = { _, _ -> },
    onDeleteSavedFilter: (Int) -> Unit = {},
    onSetSavedFilterAutoEnabled: (Int, Boolean) -> Unit = { _, _ -> },
) {
    val scrollState = rememberScrollState()
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    var textInputDialog by remember { mutableStateOf<ContentTag?>(null) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteName by remember { mutableStateOf<String?>(null) }
    var pendingRenameFilter by remember { mutableStateOf<PersistableFilter?>(null) }
    var savedFilterMenuPreset by remember { mutableStateOf<PersistableFilter?>(null) }

    Column(
        modifier = modifier
            .then(if (fillAvailableHeight) Modifier.fillMaxHeight() else Modifier)
            .verticalScroll(scrollState)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { pendingSaveName = "" },
                    enabled = isSaveEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                    ),
                ) {
                    Text(stringResource(R.string.save))
                }
                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                    ),
                ) {
                    Text(stringResource(R.string.reset_filter))
                }
            }
        }

        GlobalTagBlacklistStatus(
            blacklistedTagCount = blacklistedTagCount,
            onClick = onOpenGlobalTagBlacklist,
        )

        FilterSection(title = stringResource(R.string.sort_order)) {
            SortOrderFilterSection(
                sourceName = sourceName,
                sortOrders = sortOrders,
                selectedSortOrder = selectedSortOrder,
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it },
                onSortOrderChange = onSortOrderChange,
            )
        }

        if (contentTypes.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.type)) {
                FilterChipFlow {
                    contentTypes.forEach { type ->
                        val isSelected = type in selectedContentTypes
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onToggleContentType(type, !isSelected) },
                            label = { Text(stringResource(type.titleResId)) },
                        )
                    }
                }
            }
        }

        if (states.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.state)) {
                FilterChipFlow {
                    states.forEach { state -> 
                        val isSelected = state in selectedStates
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onToggleState(state, !isSelected) },
                            label = { Text(stringResource(state.titleResId)) },
                        )
                    }
                }
            }
        }

        if (locales.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.language)) {
                FilterChipFlow {
                    locales.forEach { locale ->
                        val isSelected = locale == selectedLocale
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onLocaleChange(if (isSelected) null else locale) },
                            label = {
                                Text(
                                    if (locale == null) {
                                        stringResource(R.string.all)
                                    } else {
                                        locale.getDisplayName(locale).ifBlank { locale.toLanguageTag() }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

        if (authors.isNotEmpty()) {
            FilterSection(title = stringResource(R.string.author)) {
                OutlinedTextField(
                    value = selectedAuthor.orEmpty(),
                    onValueChange = { onAuthorChange(it.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.author)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilterChipFlow {
                    authors.take(12).forEach { author ->
                        val isSelected = author == selectedAuthor
                        SearchPanelChip(
                            selected = isSelected,
                            onClick = { onAuthorChange(if (isSelected) null else author) },
                            label = { Text(author) },
                        )
                    }
                }
            }
        }

        TagGroupsSection(
            title = stringResource(R.string.genres),
            tagGroups = tagGroups,
            excludeMode = false,
            isTextInputTag = isTextInputTag,
            textInputValue = textInputValue,
            textInputLabel = textInputLabel,
            onToggleTag = onToggleTag,
            onTextInputTagClick = { tag -> textInputDialog = tag },
            onOpenTagCatalog = onOpenTagCatalog,
        )

        if (excludedTagGroups.any { it.tags.isNotEmpty() }) {
            TagGroupsSection(
                title = stringResource(R.string.genres_exclude),
                tagGroups = excludedTagGroups,
                excludeMode = true,
                isTextInputTag = isTextInputTag,
                textInputValue = textInputValue,
                textInputLabel = textInputLabel,
                onToggleTag = onToggleTag,
                onTextInputTagClick = { tag -> textInputDialog = tag },
                onOpenTagCatalog = onOpenTagCatalog,
            )
        }

        if (!savedFilters.isEmpty() || savedFilters.isLoading) {
            FilterSection(title = stringResource(R.string.saved_filters)) {
                FilterChipFlow {
                    savedFilters.availableItems.forEach { preset ->
                        val selected = preset in savedFilters.selectedItems
                        Box {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                                FilterChip(
                                    selected = selected,
                                    onClick = { onToggleSavedFilter(preset) },
                                    modifier = Modifier.heightIn(min = 24.dp),
                                    label = {
                                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                            Text(
                                                text = preset.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { savedFilterMenuPreset = preset },
                                            modifier = Modifier.size(18.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.34f),
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                                        } else if (preset.autoEnabled) {
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.58f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                                        },
                                    ),
                                )
                            }
                            DropdownMenu(
                                expanded = savedFilterMenuPreset == preset,
                                onDismissRequest = { savedFilterMenuPreset = null },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (preset.autoEnabled) {
                                                stringResource(R.string.disable_auto_apply)
                                            } else {
                                                stringResource(R.string.enable_auto_apply)
                                            },
                                        )
                                    },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        onSetSavedFilterAutoEnabled(preset.id, !preset.autoEnabled)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        pendingRenameFilter = preset
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        savedFilterMenuPreset = null
                                        onDeleteSavedFilter(preset.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    textInputDialog?.let { tag ->
        TextInputTagDialog(
            title = textInputLabel(tag),
            initialValue = textInputValue(tag).orEmpty(),
            onConfirm = { value ->
                onSetTextInputValue(tag, value.trim())
                textInputDialog = null
            },
            onClear = {
                onSetTextInputValue(tag, "")
                textInputDialog = null
            },
            onDismissRequest = { textInputDialog = null },
        )
    }

    pendingSaveName?.let { initialName ->
        val existingNames = remember(savedFilters.availableItems) {
            savedFilters.availableItems.mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
        }
        SaveFilterNameDialog(
            initialValue = initialName,
            existingNames = existingNames,
            onDismiss = { pendingSaveName = null },
            onConfirm = { name ->
                pendingSaveName = null
                if (name in existingNames) {
                    pendingOverwriteName = name
                } else {
                    onSaveFilter(name)
                }
            },
        )
    }

    pendingOverwriteName?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteName = null },
            title = { Text(stringResource(R.string.save_filter)) },
            text = { Text(stringResource(R.string.filter_overwrite_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        onSaveFilter(name)
                    },
                ) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteName = null
                        pendingSaveName = name
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingRenameFilter?.let { preset ->
        val existingNames = remember(savedFilters.availableItems, preset.name) {
            savedFilters.availableItems
                .mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
                .apply { remove(preset.name) }
        }
        SaveFilterNameDialog(
            initialValue = preset.name,
            existingNames = existingNames,
            rejectExistingName = true,
            onDismiss = { pendingRenameFilter = null },
            onConfirm = { name ->
                pendingRenameFilter = null
                onRenameSavedFilter(preset.id, name)
            },
        )
    }
}

@Composable
private fun SaveFilterNameDialog(
    initialValue: String,
    existingNames: Set<String>,
    rejectExistingName: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val hasError = trimmed.isEmpty() || (rejectExistingName && trimmed in existingNames)

    SearchInputDialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.save_filter),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(MAX_TITLE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.filter_name)) },
                    isError = hasError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                )
                if (hasError) {
                    Text(
                        text = stringResource(R.string.invalid_value_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = !hasError,
                onClick = { onConfirm(trimmed) },
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@Composable
private fun TextInputTagDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    SearchInputDialogSurface(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = title) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            )
        },
        actions = {
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.clear))
            }
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { onConfirm(value) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun SortOrderFilterSection(
    sourceName: String,
    sortOrders: List<SortOrder>,
    selectedSortOrder: SortOrder?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
) {
    val selectedLabel = selectedSortOrder?.let { resolveSortOrderLabel(sourceName, it) }.orEmpty()
    Box {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            sortOrders.forEach { item ->
                val selected = item == selectedSortOrder
                CompactDropdownMenuItem(
                    text = { Text(resolveSortOrderLabel(sourceName, item)) },
                    onClick = {
                        onSortOrderChange(item)
                        onExpandedChange(false)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) R.drawable.ic_check else R.drawable.ic_sort,
                            ),
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun resolveSortOrderLabel(sourceName: String, order: SortOrder): String {
    return if (sourceName.startsWith("TRACKING_BANGUMI_")) {
        when (order) {
            SortOrder.RATING -> stringResource(R.string.sort_by_ranking)
            SortOrder.POPULARITY -> stringResource(R.string.sort_by_popularity_label)
            SortOrder.ADDED -> stringResource(R.string.sort_by_collection)
            SortOrder.NEWEST -> stringResource(R.string.sort_by_date_label)
            SortOrder.ALPHABETICAL -> stringResource(R.string.sort_by_name_label)
            else -> stringResource(order.titleRes)
        }
    } else {
        stringResource(order.titleRes)
    }
}

@Composable
private fun TagGroupsSection(
    title: String,
    tagGroups: List<UiTagGroup>,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val visibleGroups = tagGroups.filter { it.tags.isNotEmpty() }
    if (visibleGroups.isEmpty()) return
    FilterSection(title = title) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleGroups.forEach { group ->
                TagGroupContent(
                    group = group,
                    excludeMode = excludeMode,
                    isTextInputTag = isTextInputTag,
                    textInputValue = textInputValue,
                    textInputLabel = textInputLabel,
                    onToggleTag = onToggleTag,
                    onTextInputTagClick = onTextInputTagClick,
                    onOpenTagCatalog = onOpenTagCatalog,
                )
            }
        }
    }
}

@Composable
private fun TagGroupContent(
    group: UiTagGroup,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val orderedTags = remember(group) {
        (group.selected.toList() + group.tags.filterNot { it in group.selected }.sortedBy { it.title })
            .distinctBy { it.key }
    }
    val visibleTags = remember(orderedTags) { orderedTags.take(12) }
    val canExpand = orderedTags.size > visibleTags.size

    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (group.title.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (canExpand) {
                    IconButton(
                        onClick = { onOpenTagCatalog(group.title, excludeMode) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.show_more),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        FilterChipFlow {
            visibleTags.forEach { tag ->
                val value = textInputValue(tag)
                val textInput = isTextInputTag(tag) || value != null
                val selected = if (textInput) {
                    !value.isNullOrBlank()
                } else {
                    tag in group.selected
                }
                SearchPanelChip(
                    selected = selected,
                    onClick = {
                        if (textInput) {
                            onTextInputTagClick(tag)
                        } else {
                            onToggleTag(tag, !selected, excludeMode)
                        }
                    },
                    label = {
                        Text(
                            text = if (textInput && !value.isNullOrBlank()) {
                                "${textInputLabel(tag)}: $value"
                            } else if (textInput) {
                                textInputLabel(tag)
                            } else {
                                tag.title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    FilterPanelGroup(title = title) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipFlow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SearchPanelChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = modifier.heightIn(min = 36.dp),
            label = {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    label()
                }
            },
            leadingIcon = if (selected) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                null
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                },
            ),
        )
    }
}

private fun ListMode.iconRes(): Int = when (this) {
    ListMode.LIST -> R.drawable.ic_list
    ListMode.DETAILED_LIST -> R.drawable.ic_list_detailed
    ListMode.GRID,
    ListMode.COMPACT_GRID -> R.drawable.ic_grid
}
