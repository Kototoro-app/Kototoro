package org.skepsun.kototoro.list.ui.compose

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.main.ui.MainActivity
import androidx.compose.runtime.saveable.rememberSaveable
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.alternatives.ui.AutoFixService
import org.skepsun.kototoro.core.util.ShareHelper
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
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
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.details.ui.model.DetailsOrigin

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
    enableItemAnimations: Boolean = true,
) {
    val items by viewModel.content.collectAsStateWithLifecycle()
    val listMode by viewModel.listMode.collectAsStateWithLifecycle()
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isLoading.collectAsStateWithLifecycle()
    val hasMoreItems by viewModel.hasMoreItems.collectAsStateWithLifecycle()

    var composeSelectionIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val hapticFeedback = LocalHapticFeedback.current
    var pendingFixIds by remember { mutableStateOf<Set<Long>?>(null) }
    var pendingMarkAsCompletedItems by remember { mutableStateOf<List<ContentListModel>?>(null) }

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
    val exceptionResolver = when (activity) {
        is BaseActivity<*> -> activity.exceptionResolver
        is BaseComposeActivity -> activity.exceptionResolver
        else -> null
    }
    val selectionModels = remember(items, composeSelectionIds) {
        prepareContentSelectionModels(items, composeSelectionIds)
    }
    val selectedModels = selectionModels.selectedModels
    val quickFilter = remember(items) { items.firstOrNull { it is QuickFilter } as? QuickFilter }
    val gridState = rememberSaveable(viewModel, saver = LazyGridState.Saver) {
        LazyGridState()
    }
    val listState = rememberSaveable(viewModel, saver = LazyListState.Saver) {
        LazyListState()
    }
    val detailedListState = rememberSaveable(viewModel, saver = LazyListState.Saver) {
        LazyListState()
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

    BackHandler(enabled = composeSelectionIds.isNotEmpty()) {
        composeSelectionIds = emptySet()
    }

    if (composeSelectionIds.isNotEmpty()) {
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
                    selectedCount = composeSelectionIds.size,
                    isAllNonLocal = selectedModels.none { it.manga.isLocal },
                    isSingleSelection = composeSelectionIds.size == 1,
                    showRemoveOption = showRemoveOption,
                    supportedActions = supportedActions,
                    allPinned = selectedModels.all { it.isPinned },
                    preferredInlineActions = preferredSelectionInlineActions,
                    removeActionIconRes = removeSelectionActionIconRes,
                    removeActionTitleRes = removeSelectionActionTitleRes,
                    fixActionTitleRes = fixSelectionActionTitleRes,
                    onClearSelection = { composeSelectionIds = emptySet() },
                    onActionClick = { action ->
                        when (action) {
                            SelectionAction.SELECT_ALL -> {
                                hapticFeedback.performSelectionHapticFeedback()
                                composeSelectionIds = selectionModels.allContentIds
                            }

                            SelectionAction.REMOVE -> {
                                onRemoveSelection?.invoke(composeSelectionIds)
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.SHARE -> {
                                if (onShareSelection != null) {
                                    onShareSelection(composeSelectionIds)
                                } else {
                                    ShareHelper(context).shareContentLinks(selectedModels.map { it.manga })
                                }
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.FAVOURITE -> {
                                appRouter.showFavoriteDialog(selectedModels.map { it.manga })
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.SAVE -> {
                                appRouter.showDownloadDialog(selectedModels.map { it.manga })
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.EDIT_OVERRIDE -> {
                                selectedModels.singleOrNull()?.manga?.let(appRouter::openContentOverrideConfig)
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.FIX -> {
                                if (onFixSelection != null) {
                                    onFixSelection(composeSelectionIds)
                                    composeSelectionIds = emptySet()
                                } else {
                                    pendingFixIds = composeSelectionIds
                                }
                            }

                            SelectionAction.PIN -> {
                                onPinSelection?.invoke(composeSelectionIds)
                                composeSelectionIds = emptySet()
                            }

                            SelectionAction.MARK_AS_COMPLETED -> {
                                pendingMarkAsCompletedItems = selectedModels
                                composeSelectionIds = emptySet()
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

    if (emitFilterRailOverride) {
        SideEffect {
            onFilterRailOverrideChanged(
                if (composeSelectionIds.isEmpty()) {
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
                val resolver = when (activity) {
                    is BaseActivity<*> -> activity.exceptionResolver
                    is BaseComposeActivity -> activity.exceptionResolver
                    else -> null
                }
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
        val mainActivity = activity as? MainActivity
        val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
        val selectedSourceTags by viewModel.currentSourceTags.collectAsStateWithLifecycle()

        DisposableEffect(mainActivity, viewModel) {
            val callback = object : SearchBarFilterViewController.Callback {
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

            mainActivity?.setActiveFilterCallback(callback)
            onDispose {
                mainActivity?.clearActiveFilterCallback(callback)
            }
        }

        // 每次过滤状态变化时刷新胶囊栏的选中状态
        SideEffect {
            mainActivity?.refreshFilters()
        }
    }

    fun resolveCloudflareAndRetry() {
        val actionableError = items.filterIsInstance<ErrorState>().firstOrNull { item ->
            item.exception is CloudFlareProtectedException ||
                item.exception.findInteractiveActionRequiredException() != null
        }
        if (actionableError != null && exceptionResolver != null) {
            coroutineScope.launch {
                if (exceptionResolver.resolve(actionableError.exception)) {
                    viewModel.onRetry()
                }
            }
        } else {
            viewModel.onRetry()
        }
    }

    KototoroContentListScreen(
        contentPadding = contentPadding,
        items = items,
        listMode = listMode,
        isRefreshing = isRefreshing,
        pullRefreshEnabled = pullRefreshEnabled,
        showRemoveOption = showRemoveOption,
        sharedTransitionEnabled = sharedTransitionEnabled,
        sharedElementInstanceKey = sharedElementInstanceKey,
        onRefresh = { viewModel.onRefresh() },
        onLoadMore = onLoadMore,
        hasMoreItems = hasMoreItems,
        loadMoreVisibleThreshold = loadMoreVisibleThreshold,
        gridScale = gridScale,
        selectedItemsIds = composeSelectionIds,
        onPrepareItemTransition = { item, coverBounds ->
        },
        onItemClick = { item ->
            if (composeSelectionIds.isNotEmpty()) {
                hapticFeedback.performSelectionHapticFeedback()
                composeSelectionIds = if (item.id in composeSelectionIds) composeSelectionIds - item.id else composeSelectionIds + item.id
            } else {
                val content = item.toContentWithOverride()
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
            if (composeSelectionIds.isEmpty()) {
                composeSelectionIds = setOf(item.id)
            } else {
                composeSelectionIds = if (item.id in composeSelectionIds) composeSelectionIds - item.id else composeSelectionIds + item.id
            }
        },
        onClearSelection = { composeSelectionIds = emptySet() },
        onSelectionAction = { action ->
            when (action) {
                SelectionAction.SELECT_ALL -> {
                    hapticFeedback.performSelectionHapticFeedback()
                    val allIds = viewModel.content.value.mapNotNull { (it as? org.skepsun.kototoro.list.ui.model.ContentListModel)?.id }.toSet()
                    composeSelectionIds = allIds
                }
                SelectionAction.REMOVE -> {
                    onRemoveSelection?.invoke(composeSelectionIds)
                    composeSelectionIds = emptySet()
                }
                SelectionAction.SHARE -> {
                    onShareSelection?.invoke(composeSelectionIds)
                    composeSelectionIds = emptySet()
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
                resolveCloudflareAndRetry()
            }
        },
        onRetry = ::resolveCloudflareAndRetry,
        showInlineSelectionTopBar = false,
        listHeader = listHeader,
        showQuickFilterInline = showQuickFilterInline,
        enableItemAnimations = enableItemAnimations,
        gridState = gridState,
        listState = listState,
        detailedListState = detailedListState,
    )
}
