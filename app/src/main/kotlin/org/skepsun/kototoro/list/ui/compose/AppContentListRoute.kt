package org.skepsun.kototoro.list.ui.compose

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.main.ui.SearchBarFilterCallback
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.main.ui.LocalMainChromeController
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.core.parser.tvbox.TVBoxActionHostActivity
import androidx.compose.runtime.saveable.rememberSaveable
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.alternatives.ui.AutoFixService
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.resolveSourceTitleForUi
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ErrorState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailItem
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.selectedFirst
import org.skepsun.kototoro.main.ui.compose.ContentSelectionTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.findInteractiveActionRequiredException
import org.skepsun.kototoro.core.util.ext.findCloudFlareException
import org.skepsun.kototoro.core.util.ext.getCauseUrl
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.details.ui.model.DetailsOrigin

private fun List<ListModel>.contentIndexOf(itemId: Long): Int {
    return indexOfFirst { model -> model is ContentListModel && model.id == itemId }
}

internal fun shouldUseRetainedPagingSnapshot(
    retentionEnabled: Boolean,
    hasPagingItems: Boolean,
    hasRetainedSnapshot: Boolean,
    returnTransitionSettled: Boolean,
    retainedAnchorPrefixIsReady: Boolean,
    pagingRefreshSettled: Boolean,
    retainedAnchorIsLoaded: Boolean,
): Boolean {
    val refreshedAnchorWasRemoved = returnTransitionSettled &&
        pagingRefreshSettled &&
        !retainedAnchorIsLoaded
    return retentionEnabled &&
        hasPagingItems &&
        hasRetainedSnapshot &&
        !refreshedAnchorWasRemoved &&
        (!retainedAnchorPrefixIsReady || !returnTransitionSettled)
}

/**
 * Lets a parent own the multi-select state instead of [AppContentListRoute]'s internal
 * `rememberSaveable` set. Used by top-level routes (e.g. the Updated page) that report
 * their own selection top bar to the main chrome directly, mirroring History/Feed —
 * and by extension it suppresses this route's own chrome reporting so there is exactly
 * one bar.
 */
class ContentSelectionControl(
    val selectedIds: State<Set<Long>>,
    val onSelectionChanged: (Set<Long>) -> Unit,
)

private fun <T> eventCollector(block: suspend (T) -> Unit): FlowCollector<T> = FlowCollector { value ->
    block(value)
}

private data class ContentSelectionModels(
    val allContentIds: Set<Long>,
    val selectedModels: List<ContentListModel>,
)

private fun prepareContentSelectionModels(
    items: List<ListModel>,
    selectedIds: Set<Long>,
): ContentSelectionModels {
    val allContentIds = linkedSetOf<Long>()
    val selectedModels = ArrayList<ContentListModel>()
    items.forEach { item ->
        if (item is ContentListModel) {
            allContentIds += item.id
            if (item.id in selectedIds) {
                selectedModels += item
            }
        }
    }
    return ContentSelectionModels(
        allContentIds = allContentIds,
        selectedModels = selectedModels,
    )
}

@Composable
fun <VM : ContentListViewModel> AppContentListRoute(
    viewModel: VM,
    contentPadding: PaddingValues,
    appRouter: AppRouter,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    showRemoveOption: Boolean = false,
    showInlineSelectionTopBar: Boolean = false,
    inlineSelectionBarAnimated: Boolean = true,
    inlineSelectionSupportedActions: Set<SelectionAction>? = null,
    inlineSelectionIncludeContextualActions: Boolean = true,
    selectionControl: ContentSelectionControl? = null,
    sharedTransitionEnabled: Boolean = true,
    sharedElementInstanceKey: String? = null,
    isContentTypeFilterVisible: Boolean = true,
    isSourceTagFilterVisible: Boolean = true,
    registerFilterCallback: Boolean = true,
    onRemoveSelection: ((Set<Long>) -> Unit)? = null,
    onShareSelection: ((Set<Long>) -> Unit)? = null,
    onFixSelection: ((Set<Long>) -> Unit)? = null,
    onPinSelection: ((Set<Long>) -> Unit)? = null,
    onMarkAsCompletedSelection: ((List<ContentListModel>) -> Unit)? = null,
    preferredSelectionInlineActions: List<SelectionAction>? = null,
    removeSelectionActionIconRes: Int? = null,
    removeSelectionActionTitleRes: Int? = null,
    fixSelectionActionTitleRes: Int? = null,
    onEmptyActionClick: (() -> Unit)? = null,
    onFilterRailOverrideChanged: (CompactFilterRailOverrideState?) -> Unit = {},
    emitFilterRailOverride: Boolean = true,
    pullRefreshEnabled: Boolean = true,
    onLoadMore: () -> Unit = {},
    loadMoreVisibleThreshold: Int = 4,
    onNavigateToDetails: ((ContentListModel, org.skepsun.kototoro.parsers.model.Content, String?) -> Unit)? = null,
    onNavigateToEntityDetails: ((ContentListModel, org.skepsun.kototoro.parsers.model.Content, Long, Long?, String?) -> Unit)? = null,
    onAddMenuProvider: ((androidx.activity.ComponentActivity, VM, androidx.lifecycle.LifecycleOwner) -> androidx.core.view.MenuProvider?)? = null,
    listHeader: (@Composable () -> Unit)? = null,
    showQuickFilterInline: Boolean = true,
    quickFilterOverride: QuickFilter? = null,
    enableItemAnimations: Boolean = true,
    retainPagingSnapshotOnDetailsNavigation: Boolean = false,
) {
    val pagingFlow = viewModel.pagingContent
    val lazyPagingItems = pagingFlow?.collectAsLazyPagingItems()
    val sourceItems by if (pagingFlow == null) {
        viewModel.content.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val items = remember(sourceItems, quickFilterOverride) {
        if (quickFilterOverride == null) {
            sourceItems
        } else {
            buildList(sourceItems.size + 1) {
                var replaced = false
                sourceItems.forEach { item ->
                    if (item is QuickFilter) {
                        if (!replaced) {
                            add(quickFilterOverride)
                            replaced = true
                        }
                    } else {
                        add(item)
                    }
                }
                if (!replaced) {
                    add(0, quickFilterOverride)
                }
            }
        }
    }
    val listMode by viewModel.listMode.collectAsStateWithLifecycle()
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isLoading.collectAsStateWithLifecycle()
    val pagingIsRefreshing = lazyPagingItems?.loadState?.refresh is LoadState.Loading
    val hasMoreItems by viewModel.hasMoreItems.collectAsStateWithLifecycle()

    var composeSelectionIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val hapticFeedback = LocalHapticFeedback.current
    var pendingFixIds by remember { mutableStateOf<Set<Long>?>(null) }
    var pendingMarkAsCompletedItems by remember { mutableStateOf<List<ContentListModel>?>(null) }

    // When the parent supplies [selectionControl], the parent owns selection state and this
    // route's own chrome reporting is suppressed (exactly one selection bar in the app).
    val usesExternalSelection = selectionControl != null
    val currentSelectionIds = selectionControl?.selectedIds?.value ?: composeSelectionIds
    fun updateSelection(ids: Set<Long>) {
        if (usesExternalSelection) {
            selectionControl?.onSelectionChanged?.invoke(ids)
        } else {
            composeSelectionIds = ids
        }
    }

    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainActivity = activity as? MainActivity
    val entryPoint = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BaseApp.BaseAppEntryPoint::class.java,
            )
        }.getOrNull()
    }
    val coroutineScope = rememberCoroutineScope()
    val exceptionResolver = (activity as? BaseComposeActivity)?.exceptionResolver
    val loadedPagingItems = lazyPagingItems?.itemSnapshotList?.items.orEmpty()
    val initialRetainedPagingSnapshot = remember(viewModel, retainPagingSnapshotOnDetailsNavigation) {
        if (retainPagingSnapshotOnDetailsNavigation) {
            viewModel.peekRetainedPagingSnapshot()
        } else {
            null
        }
    }
    var retainedPagingSnapshot by remember(viewModel, retainPagingSnapshotOnDetailsNavigation) {
        mutableStateOf(initialRetainedPagingSnapshot)
    }
    val quickFilter = remember(items) { items.firstOrNull { it is QuickFilter } as? QuickFilter }
    // 首次 composition 渲染的是保留的快照（useRetainedPagingSnapshot=true 期间
    // displayedItems = items + snapshot.items），因此初始滚动位置必须取快照内记录
    // 的 firstVisibleItemIndex：metadata source 切换（如改为 AniList 追踪）会让收藏
    // 分页源失效重载，新旧数据内容/排序错位时，若用【新加载数据】里 anchor 的 index
    // 换算成布局位置去定位旧快照，会先显示错误页面、之后再被下方的
    // requestScrollToItem 兜底拉回（可见闪跳）。待切到真实新数据时，下方
    // LaunchedEffect 会用 anchor item 在新数据里重新对齐到目标位置。
    val restoredViewportIndex = initialRetainedPagingSnapshot?.let { retained ->
        retained.firstVisibleItemIndex
    } ?: 0
    val restoredViewportOffset = initialRetainedPagingSnapshot?.firstVisibleItemScrollOffset ?: 0
    val restoreGridViewport = initialRetainedPagingSnapshot?.listMode == ListMode.GRID ||
        initialRetainedPagingSnapshot?.listMode == ListMode.COMPACT_GRID
    val restoreListViewport = initialRetainedPagingSnapshot?.listMode == ListMode.LIST
    val restoreDetailedListViewport = initialRetainedPagingSnapshot?.listMode == ListMode.DETAILED_LIST
    val gridState = initialRetainedPagingSnapshot?.let { retained ->
        key("retained_paging_grid", retained.generation) {
            rememberSaveable(saver = LazyGridState.Saver) {
                LazyGridState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreGridViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreGridViewport } ?: 0,
                )
            }
        }
    } ?: rememberSaveable(viewModel, saver = LazyGridState.Saver) {
        LazyGridState()
    }
    val listState = initialRetainedPagingSnapshot?.let { retained ->
        key("retained_paging_list", retained.generation) {
            rememberSaveable(saver = LazyListState.Saver) {
                LazyListState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreListViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreListViewport } ?: 0,
                )
            }
        }
    } ?: rememberSaveable(viewModel, saver = LazyListState.Saver) {
        LazyListState()
    }
    val detailedListState = initialRetainedPagingSnapshot?.let { retained ->
        key("retained_paging_detailed_list", retained.generation) {
            rememberSaveable(saver = LazyListState.Saver) {
                LazyListState(
                    firstVisibleItemIndex = restoredViewportIndex.takeIf { restoreDetailedListViewport } ?: 0,
                    firstVisibleItemScrollOffset = restoredViewportOffset.takeIf { restoreDetailedListViewport } ?: 0,
                )
            }
        }
    } ?: rememberSaveable(viewModel, saver = LazyListState.Saver) {
        LazyListState()
    }
    val navigationTransition = LocalNavAnimatedVisibilityScope.current?.transition
    var returnTransitionSettled by remember(viewModel, initialRetainedPagingSnapshot?.generation) {
        mutableStateOf(initialRetainedPagingSnapshot == null)
    }
    LaunchedEffect(initialRetainedPagingSnapshot?.generation, navigationTransition) {
        val transition = navigationTransition
        if (initialRetainedPagingSnapshot == null || transition == null) {
            returnTransitionSettled = true
            return@LaunchedEffect
        }

        // The transition can still report idle during the first composition of the returning destination.
        // Keep the retained snapshot through that frame, then wait for the destination to become fully visible.
        withFrameNanos { }
        snapshotFlow {
            !transition.isRunning &&
                transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
        }.first { it }
        returnTransitionSettled = true
    }
    val retainedAnchorIndex = retainedPagingSnapshot?.let { retained ->
        retained.items.contentIndexOf(retained.anchorItemId)
    } ?: -1
    val liveAnchorIndex = retainedPagingSnapshot?.let { retained ->
        loadedPagingItems.contentIndexOf(retained.anchorItemId)
    } ?: -1
    val retainedAnchorIsLoaded = liveAnchorIndex >= 0
    val pagingPrependExhausted = (lazyPagingItems?.loadState?.prepend as? LoadState.NotLoading)
        ?.endOfPaginationReached == true
    val retainedAnchorPrefixIsReady = retainedAnchorIsLoaded &&
        (liveAnchorIndex >= retainedAnchorIndex || pagingPrependExhausted)
    val useRetainedPagingSnapshot = shouldUseRetainedPagingSnapshot(
        retentionEnabled = retainPagingSnapshotOnDetailsNavigation,
        hasPagingItems = lazyPagingItems != null,
        hasRetainedSnapshot = retainedPagingSnapshot != null,
        returnTransitionSettled = returnTransitionSettled,
        retainedAnchorPrefixIsReady = retainedAnchorPrefixIsReady,
        pagingRefreshSettled = lazyPagingItems?.loadState?.refresh is LoadState.NotLoading,
        retainedAnchorIsLoaded = retainedAnchorIsLoaded,
    )
    LaunchedEffect(
        useRetainedPagingSnapshot,
        returnTransitionSettled,
        retainedAnchorIndex,
        liveAnchorIndex,
        pagingPrependExhausted,
    ) {
        if (
            useRetainedPagingSnapshot &&
            returnTransitionSettled &&
            liveAnchorIndex in 0 until retainedAnchorIndex &&
            !pagingPrependExhausted
        ) {
            lazyPagingItems?.get(0)
        }
    }
    val displayedItems = remember(items, retainedPagingSnapshot, useRetainedPagingSnapshot) {
        if (useRetainedPagingSnapshot) items + retainedPagingSnapshot?.items.orEmpty() else items
    }
    val displayedPagingItems = if (useRetainedPagingSnapshot) null else lazyPagingItems
    val selectionModels = remember(
        displayedItems,
        loadedPagingItems,
        displayedPagingItems,
        currentSelectionIds,
    ) {
        prepareContentSelectionModels(
            if (displayedPagingItems == null) displayedItems else loadedPagingItems,
            currentSelectionIds,
        )
    }
    val selectedModels = selectionModels.selectedModels
    LaunchedEffect(useRetainedPagingSnapshot, loadedPagingItems.size) {
        if (!useRetainedPagingSnapshot && loadedPagingItems.isNotEmpty() && retainedPagingSnapshot != null) {
            val retained = checkNotNull(retainedPagingSnapshot)
            val liveAnchorLayoutIndex = liveAnchorIndex.takeIf { it >= 0 }?.let { items.size + it }
            liveAnchorLayoutIndex?.let { targetIndex ->
                when (listMode) {
                    ListMode.GRID, ListMode.COMPACT_GRID -> gridState.requestScrollToItem(
                        index = targetIndex,
                        scrollOffset = gridState.firstVisibleItemScrollOffset,
                    )
                    ListMode.LIST -> listState.requestScrollToItem(
                        index = targetIndex,
                        scrollOffset = listState.firstVisibleItemScrollOffset,
                    )
                    ListMode.DETAILED_LIST -> detailedListState.requestScrollToItem(
                        index = targetIndex,
                        scrollOffset = detailedListState.firstVisibleItemScrollOffset,
                    )
                }
            }
            viewModel.clearRetainedPagingSnapshot(retained.generation)
            retainedPagingSnapshot = null
        }
    }
    val quickFilterRailOverride = remember(quickFilter, context) {
        quickFilter?.let { filter ->
            CompactFilterRailOverrideState(
                items = filter.items.mapIndexedNotNull { index, chip ->
                    val option = chip.data as? org.skepsun.kototoro.list.domain.ListFilterOption ?: return@mapIndexedNotNull null
                    val sourceOption = option as? org.skepsun.kototoro.list.domain.ListFilterOption.Source
                    val title = when {
                        sourceOption != null -> resolveSourceTitleForUi(
                            context = context,
                            source = sourceOption.mangaSource,
                            entryPoint = entryPoint,
                        )
                        chip.titleResId != 0 -> context.getString(chip.titleResId)
                        !chip.title.isNullOrBlank() -> chip.title.toString()
                        else -> return@mapIndexedNotNull null
                    }
                    CompactFilterRailItem(
                        id = "${option::class.qualifiedName}:${option.hashCode()}:$index",
                        title = title,
                        isSelected = chip.isChecked,
                        source = sourceOption?.mangaSource,
                        onClick = { (viewModel as? org.skepsun.kototoro.list.domain.QuickFilterListener)?.toggleFilterOption(option) },
                    )
                }.selectedFirst(),
            )
        }
    }

    BackHandler(enabled = currentSelectionIds.isNotEmpty()) {
        updateSelection(emptySet())
    }

    if (!usesExternalSelection) {
        if (currentSelectionIds.isNotEmpty()) {
            SideEffect {
                val supportedActions = buildSet {
                    add(SelectionAction.SELECT_ALL)
                    add(SelectionAction.PIN)
                    add(SelectionAction.SHARE)
                    add(SelectionAction.SAVE)
                    if (showRemoveOption || onRemoveSelection != null) {
                        add(SelectionAction.REMOVE)
                    }
                    if (onPinSelection == null) {
                        remove(SelectionAction.PIN)
                    }
                    if (onMarkAsCompletedSelection != null) {
                        add(SelectionAction.MARK_AS_COMPLETED)
                    }
                    add(SelectionAction.FAVOURITE)
                }
                onTopBarOverrideChanged(
                    ContentSelectionTopBarOverrideState(
                        selectedCount = currentSelectionIds.size,
                        isAllNonLocal = selectedModels.none { it.manga.isLocal },
                        isSingleSelection = currentSelectionIds.size == 1,
                        showRemoveOption = showRemoveOption,
                        supportedActions = supportedActions,
                        allPinned = selectedModels.all { it.isPinned },
                        preferredInlineActions = preferredSelectionInlineActions,
                        removeActionIconRes = removeSelectionActionIconRes,
                        removeActionTitleRes = removeSelectionActionTitleRes,
                        fixActionTitleRes = fixSelectionActionTitleRes,
                        onClearSelection = { updateSelection(emptySet()) },
                        onActionClick = { action ->
                            when (action) {
                                SelectionAction.SELECT_ALL -> {
                                    hapticFeedback.performSelectionHapticFeedback()
                                    updateSelection(selectionModels.allContentIds)
                                }

                                SelectionAction.REMOVE -> {
                                    onRemoveSelection?.invoke(currentSelectionIds)
                                    updateSelection(emptySet())
                                }

                                SelectionAction.SHARE -> {
                                    if (onShareSelection != null) {
                                        onShareSelection(currentSelectionIds)
                                    } else {
                                        ShareHelper(context).shareContentLinks(selectedModels.map { it.manga })
                                    }
                                    updateSelection(emptySet())
                                }

                                SelectionAction.FAVOURITE -> {
                                    appRouter.showFavoriteDialog(selectedModels.map { it.manga })
                                    updateSelection(emptySet())
                                }

                                SelectionAction.SAVE -> {
                                    appRouter.showDownloadDialog(selectedModels.map { it.manga })
                                    updateSelection(emptySet())
                                }

                                SelectionAction.EDIT_OVERRIDE -> {
                                    selectedModels.singleOrNull()?.manga?.let(appRouter::openContentOverrideConfig)
                                    updateSelection(emptySet())
                                }

                                SelectionAction.FIX -> {
                                    if (onFixSelection != null) {
                                        onFixSelection(currentSelectionIds)
                                        updateSelection(emptySet())
                                    } else {
                                        pendingFixIds = currentSelectionIds
                                    }
                                }

                                SelectionAction.PIN -> {
                                    onPinSelection?.invoke(currentSelectionIds)
                                    updateSelection(emptySet())
                                }

                                SelectionAction.MARK_AS_COMPLETED -> {
                                    pendingMarkAsCompletedItems = selectedModels
                                    updateSelection(emptySet())
                                }
                            }
                        },
                    ),
                )
            }
        } else {
            LaunchedEffect(Unit) {
                onTopBarOverrideChanged(null)
            }
        }
    }

    if (emitFilterRailOverride) {
        SideEffect {
            onFilterRailOverrideChanged(
                if (currentSelectionIds.isEmpty()) {
                    quickFilterRailOverride
                } else {
                    null
                },
            )
        }
    }

    pendingFixIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingFixIds = null },
            title = { Text(text = stringResource(org.skepsun.kototoro.R.string.fix)) },
            text = { Text(text = stringResource(org.skepsun.kototoro.R.string.manga_fix_prompt)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AutoFixService.start(context, ids)
                        pendingFixIds = null
                    },
                ) {
                    Text(text = stringResource(org.skepsun.kototoro.R.string.fix))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFixIds = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingMarkAsCompletedItems?.let { itemsToMark ->
        AlertDialog(
            onDismissRequest = { pendingMarkAsCompletedItems = null },
            title = { Text(text = stringResource(org.skepsun.kototoro.R.string.mark_as_completed)) },
            text = { Text(text = stringResource(org.skepsun.kototoro.R.string.mark_as_completed_prompt)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMarkAsCompletedSelection?.invoke(itemsToMark)
                        pendingMarkAsCompletedItems = null
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMarkAsCompletedItems = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onTopBarOverrideChanged(null)
            if (emitFilterRailOverride) {
                onFilterRailOverrideChanged(null)
            }
        }
    }

    // Error observation
    LaunchedEffect(viewModel.onError) {
        viewModel.onError.collect { event ->
            event?.consume(eventCollector { error ->
                Toast.makeText(context, error.getDisplayMessage(context.resources), Toast.LENGTH_SHORT).show()
                val resolver = (activity as? BaseComposeActivity)?.exceptionResolver
                if (resolver != null && org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver.canResolve(error)) {
                    coroutineScope.launch {
                        if (resolver.resolve(error)) {
                            viewModel.onRetry()
                        }
                    }
                }
            })
        }
    }

    LaunchedEffect(viewModel.onContentMessage) {
        viewModel.onContentMessage.collect { event ->
            event?.consume(eventCollector { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            })
        }
    }

    LaunchedEffect(viewModel.onContentActionHostRequest) {
        viewModel.onContentActionHostRequest.collect { event ->
            event?.consume(eventCollector { request ->
                val hostActivity = activity ?: return@eventCollector
                TVBoxActionHostActivity.start(hostActivity) { host ->
                    request.execute(host::complete)
                }
            })
        }
    }

    // Menu Provider
    if (onAddMenuProvider != null) {
        DisposableEffect(viewModel, activity, lifecycleOwner) {
            val menuProvider = onAddMenuProvider(activity ?: return@DisposableEffect onDispose {}, viewModel, lifecycleOwner)
            if (menuProvider != null) {
                activity.addMenuProvider(menuProvider, lifecycleOwner, androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            onDispose {
                if (menuProvider != null) {
                    activity?.removeMenuProvider(menuProvider)
                }
            }
        }
    }

    // Filter Coordinator integration via MainActivity callback
    // When registerFilterCallback is false, the parent composable manages the callback
    // (e.g. FavoritesHostScreen centralizes it to avoid HorizontalPager contention)
    if (registerFilterCallback) {
        val mainChromeController = LocalMainChromeController.current
        val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
        val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()

        DisposableEffect(mainChromeController, viewModel) {
            val callback = object : SearchBarFilterCallback {
                override fun isContentTypeFilterVisible(): Boolean = isContentTypeFilterVisible
                override fun isSourceTagFilterVisible(): Boolean = isSourceTagFilterVisible

                override fun getSelectedContentType(): org.skepsun.kototoro.explore.ui.model.BrowseGroupTab {
                    return viewModel.currentGroupTab.value ?: org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.All
                }

                override fun onContentTypeSelected(tab: org.skepsun.kototoro.explore.ui.model.BrowseGroupTab) {
                    viewModel.setSelectedGroupTab(if (viewModel.currentGroupTab.value == tab) org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.All else tab)
                }

                override fun getSelectedSourceTags(): Set<org.skepsun.kototoro.explore.ui.model.SourceTag> {
                    return viewModel.currentSourceTags.value ?: emptySet()
                }

                override fun onSourceTagSelected(tag: org.skepsun.kototoro.explore.ui.model.SourceTag?) {
                    val current = viewModel.currentSourceTags.value ?: emptySet()
                    viewModel.setSelectedSourceTags(
                        if (tag == null) {
                            emptySet()
                        } else if (tag in current) {
                            current - tag
                        } else {
                            current + tag
                        }
                    )
                }

                override fun getSourceTagEntries(): List<org.skepsun.kototoro.explore.ui.model.SourceTag> {
                    return org.skepsun.kototoro.explore.ui.model.SourceTag.quickFilterEntries
                }
            }

            mainChromeController?.setActiveFilterCallback(callback)
            onDispose {
                mainChromeController?.clearActiveFilterCallback(callback)
            }
        }

        // 每次过滤状态变化时刷新胶囊栏的选中状态
        SideEffect {
            mainChromeController?.refreshFilters()
        }
    }

    fun resolveCloudflareAndRetry() {
        val actionableError = items.filterIsInstance<ErrorState>().firstOrNull { item ->
            item.exception.findCloudFlareException() is CloudFlareProtectedException ||
                item.exception.findInteractiveActionRequiredException() != null
        }
        if (actionableError != null && exceptionResolver != null) {
            coroutineScope.launch {
                if (exceptionResolver.resolve(actionableError.exception, tryAutoResolve = false)) {
                    viewModel.onRetry()
                }
            }
        } else {
            viewModel.onRetry()
        }
    }

    KototoroContentListScreen(
        contentPadding = contentPadding,
        items = displayedItems,
        pagingItems = displayedPagingItems,
        listMode = listMode,
        isRefreshing = isRefreshing || (pagingIsRefreshing && !useRetainedPagingSnapshot),
        pullRefreshEnabled = pullRefreshEnabled,
        showRemoveOption = showRemoveOption,
        sharedTransitionEnabled = sharedTransitionEnabled,
        sharedElementInstanceKey = sharedElementInstanceKey,
        onRefresh = {
            if (lazyPagingItems == null) {
                viewModel.onRefresh()
            } else {
                lazyPagingItems.refresh()
            }
        },
        onLoadMore = onLoadMore,
        hasMoreItems = hasMoreItems,
        loadMoreVisibleThreshold = loadMoreVisibleThreshold,
        gridScale = gridScale,
        selectedItemsIds = currentSelectionIds,
        onPrepareItemTransition = { item, coverBounds ->
        },
        onItemClick = itemClick@{ item ->
            if (currentSelectionIds.isNotEmpty()) {
                hapticFeedback.performSelectionHapticFeedback()
                updateSelection(if (item.id in currentSelectionIds) currentSelectionIds - item.id else currentSelectionIds + item.id)
            } else {
                val content = item.toContentWithOverride()
                if (viewModel.onContentClick(content)) return@itemClick
                if (retainPagingSnapshotOnDetailsNavigation && lazyPagingItems != null) {
                    val snapshotItems = retainedPagingSnapshot?.items
                        ?.takeIf { useRetainedPagingSnapshot }
                        ?: loadedPagingItems
                    val (firstVisibleIndex, firstVisibleScrollOffset) = when (listMode) {
                        ListMode.GRID, ListMode.COMPACT_GRID ->
                            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                        ListMode.LIST ->
                            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                        ListMode.DETAILED_LIST ->
                            detailedListState.firstVisibleItemIndex to detailedListState.firstVisibleItemScrollOffset
                    }
                    val firstVisiblePagingIndex = (firstVisibleIndex - items.size).coerceAtLeast(0)
                    val anchorItemId = (snapshotItems.getOrNull(firstVisiblePagingIndex) as? ContentListModel)?.id
                        ?: item.id
                    viewModel.retainPagingSnapshot(
                        items = snapshotItems,
                        anchorItemId = anchorItemId,
                        listMode = listMode,
                        firstVisibleItemIndex = firstVisibleIndex,
                        firstVisibleItemScrollOffset = firstVisibleScrollOffset,
                    )
                }
                val sharedElementKey = contentCoverSharedKey(
                    item.source.name,
                    item.coverUrl.orEmpty(),
                    sharedElementInstanceKey,
                )
                val entityId = viewModel.resolveEntityIdForUiItemId(item.id)
                if (entityId != null) {
                    val preferredLocalMangaId =
                        viewModel.resolvePreferredLocalMangaIdForUiItemId(item.id) ?: content.id
                    if (onNavigateToEntityDetails != null) {
                        onNavigateToEntityDetails(item, content, entityId, preferredLocalMangaId, sharedElementKey)
                    } else {
                        appRouter.openEntityDetails(
                            entityId = entityId,
                            preferredLocalMangaId = preferredLocalMangaId,
                            sharedElementKey = sharedElementKey,
                        )
                    }
                } else if (onNavigateToDetails != null) {
                    onNavigateToDetails(item, content, sharedElementKey)
                } else {
                    mainActivity?.resolveDetailsOriginForContent(content) { origin ->
                        when (origin) {
                            is DetailsOrigin.EntityGraph -> {
                                appRouter.openEntityDetails(
                                    entityId = origin.entityId,
                                    initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                    sharedElementKey = sharedElementKey,
                                )
                            }
                            else -> appRouter.openResolvedDetails(content, sharedElementKey = sharedElementKey)
                        }
                    } ?: appRouter.openResolvedDetails(content, sharedElementKey = sharedElementKey)
                }
            }
        },
        onItemLongClick = { item ->
            if (currentSelectionIds.isEmpty()) {
                updateSelection(setOf(item.id))
            } else {
                updateSelection(if (item.id in currentSelectionIds) currentSelectionIds - item.id else currentSelectionIds + item.id)
            }
        },
        onClearSelection = { updateSelection(emptySet()) },
        onSelectionAction = { action ->
            when (action) {
                SelectionAction.SELECT_ALL -> {
                    hapticFeedback.performSelectionHapticFeedback()
                    updateSelection(selectionModels.allContentIds)
                }
                SelectionAction.REMOVE -> {
                    onRemoveSelection?.invoke(currentSelectionIds)
                    updateSelection(emptySet())
                }
                SelectionAction.SHARE -> {
                    onShareSelection?.invoke(currentSelectionIds)
                    updateSelection(emptySet())
                }
                else -> {}
            }
        },
        onQuickFilterOptionClick = { option ->
            (viewModel as? org.skepsun.kototoro.list.domain.QuickFilterListener)?.toggleFilterOption(option)
        },
        onEmptyActionClick = {
            if (onEmptyActionClick != null) {
                onEmptyActionClick.invoke()
            } else {
                val quickFilterListener = viewModel as? org.skepsun.kototoro.list.domain.QuickFilterListener
                if (quickFilterListener != null) {
                    quickFilterListener.clearFilter()
                } else {
                    resolveCloudflareAndRetry()
                }
            }
        },
        onRetry = {
            if (lazyPagingItems == null) {
                resolveCloudflareAndRetry()
            } else {
                lazyPagingItems.retry()
            }
        },
        onSecondaryAction = { error ->
            error.getCauseUrl()?.let { url ->
                appRouter.openBrowser(url, null, null)
            }
        },
        showInlineSelectionTopBar = showInlineSelectionTopBar,
        inlineSelectionBarAnimated = inlineSelectionBarAnimated,
        inlineSelectionSupportedActions = inlineSelectionSupportedActions,
        inlineSelectionIncludeContextualActions = inlineSelectionIncludeContextualActions,
        listHeader = listHeader,
        showQuickFilterInline = showQuickFilterInline,
        enableItemAnimations = enableItemAnimations,
        gridState = gridState,
        listState = listState,
        detailedListState = detailedListState,
    )
}
