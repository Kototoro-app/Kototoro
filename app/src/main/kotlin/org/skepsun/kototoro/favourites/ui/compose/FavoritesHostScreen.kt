package org.skepsun.kototoro.favourites.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.nav.AppRouter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.container.FavouriteTabModel
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.main.ui.SearchBarFilterViewController
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTopBarTabItem
import org.skepsun.kototoro.main.ui.compose.ContentSelectionTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarOverrideState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroFavoritesHostRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    initialCategoryId: Long = NO_ID,
    initialCategoryTitle: String? = null,
    onOpenEntityOrganize: (Set<Long>) -> Unit = {},
    onNavigateToDetails: ((Content, String?) -> Unit)? = null,
    onNavigateToEntityDetails: ((DetailsOrigin, String?) -> Unit)? = null,
    registerFilterCallback: Boolean = true,
    refreshGeneration: Int = 0,
    consumeOrganizeMessages: Boolean = true,
    onTopBarOverrideChanged: (TopBarOverrideState?) -> Unit = {},
    viewModel: FavouritesContainerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mainActivity = LocalContext.current as? MainActivity
    val context = LocalContext.current
    val globalState = viewModel.globalFavoritesState
    val selectedGroupTab by viewModel.currentGroupTab.collectAsStateWithLifecycle()
    val selectedSourceTags by globalState.selectedSourceTags.collectAsStateWithLifecycle()
    val allFavoritesSortOrder by viewModel.allFavoritesSortOrder.collectAsStateWithLifecycle()

    DisposableEffect(mainActivity, globalState, selectedGroupTab, selectedSourceTags, registerFilterCallback) {
        if (!registerFilterCallback) {
            onDispose { }
        } else {
            val callback = object : SearchBarFilterViewController.Callback {
                override fun isSourceTagFilterVisible() = true

                override fun getSourceTagEntries() = SourceTag.quickFilterEntries

                override fun getSelectedContentType() = selectedGroupTab

                override fun onContentTypeSelected(tab: BrowseGroupTab) {
                    viewModel.setSelectedGroupTab(tab)
                }

                override fun getSelectedSourceTags() = selectedSourceTags

                override fun onSourceTagSelected(tag: SourceTag?) {
                    when {
                        tag == null -> globalState.clearSourceTags()
                        tag in selectedSourceTags -> globalState.setSelectedSourceTags(selectedSourceTags - tag)
                        else -> globalState.setSelectedSourceTags(selectedSourceTags + tag)
                    }
                }
            }
            mainActivity?.setActiveFilterCallback(callback)
            onDispose { mainActivity?.clearActiveFilterCallback(callback) }
        }
    }

    SideEffect {
        if (registerFilterCallback) {
            mainActivity?.refreshFilters()
        }
    }

    val displayCategories = remember(uiState.categories, initialCategoryId, initialCategoryTitle) {
        val categories = uiState.categories
        if (categories.any { it.id == initialCategoryId }) {
            categories
        } else if (initialCategoryId != NO_ID) {
            categories + FavouriteTabModel(id = initialCategoryId, title = initialCategoryTitle)
        } else {
            categories
        }
    }
    val initialPage = remember(displayCategories, initialCategoryId) {
        displayCategories.indexOfFirst { it.id == initialCategoryId }.takeIf { it >= 0 } ?: 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { displayCategories.size },
    )
    val coroutineScope = rememberCoroutineScope()
    var initialSelectionApplied by rememberSaveable(initialCategoryId) { mutableStateOf(false) }
    var childTopBarOverrideState by remember { mutableStateOf<TopBarOverrideState?>(null) }
    var childTopBarOverrideGeneration by remember { mutableIntStateOf(-1) }
    var activeChildOverrideGeneration by remember { mutableIntStateOf(0) }
    var lastActiveCategoryId by remember { mutableStateOf<Long?>(null) }
    val allFavouritesLabel = stringResource(R.string.all_favourites)
    val activePage = pagerState.settledPage.coerceIn(0, (displayCategories.size - 1).coerceAtLeast(0))
    val selectedTabsPage = pagerState.targetPage.coerceIn(0, (displayCategories.size - 1).coerceAtLeast(0))
    val activeCategoryId = displayCategories.getOrNull(activePage)?.id
    val activeCategory = displayCategories.getOrNull(activePage)
    val selectedSortOrder = if (activeCategoryId == NO_ID) {
        allFavoritesSortOrder
    } else {
        activeCategory?.order ?: allFavoritesSortOrder
    }

    LaunchedEffect(uiState.isLoading, displayCategories, initialCategoryId, initialSelectionApplied) {
        if (uiState.isLoading || initialSelectionApplied || displayCategories.isEmpty()) {
            return@LaunchedEffect
        }
        val targetPage = displayCategories.indexOfFirst { it.id == initialCategoryId }.takeIf { it >= 0 } ?: 0
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        initialSelectionApplied = true
    }

    LaunchedEffect(activeCategoryId) {
        val previousActiveCategoryId = lastActiveCategoryId
        if (previousActiveCategoryId == activeCategoryId) {
            return@LaunchedEffect
        }
        lastActiveCategoryId = activeCategoryId
        if (previousActiveCategoryId == null) {
            return@LaunchedEffect
        }
        activeChildOverrideGeneration += 1
        childTopBarOverrideState = null
    }

    val compactTabsState = remember(displayCategories, selectedTabsPage, allFavouritesLabel) {
        CompactTabsTopBarOverrideState(
            items = displayCategories.map {
                CompactTopBarTabItem(
                    id = it.id,
                    title = if (it.id == NO_ID) allFavouritesLabel else (it.title ?: ""),
                )
            },
            selectedItemId = displayCategories.getOrNull(selectedTabsPage)?.id ?: NO_ID,
            onItemSelected = { categoryId ->
                val targetPage = displayCategories.indexOfFirst { it.id == categoryId }
                if (targetPage >= 0) {
                    coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
                }
            },
        )
    }

    val effectiveChildTopBarOverrideState = childTopBarOverrideState.takeIf {
        !uiState.isLoading &&
            !uiState.isEmpty &&
            childTopBarOverrideGeneration == activeChildOverrideGeneration &&
            it is ContentSelectionTopBarOverrideState
    }

    val favoritesTopBarOverrideState = remember(
        compactTabsState,
        effectiveChildTopBarOverrideState,
        selectedSortOrder,
        activeCategoryId,
    ) {
        LayeredTopBarOverrideState(
            tabsState = compactTabsState,
            contextualOverrideState = effectiveChildTopBarOverrideState,
            keepTabsExpandedWhenCollapsed = true,
            sortOrders = ListSortOrder.FAVORITES.sortedBy { it.ordinal },
            selectedSortOrder = selectedSortOrder,
            onSortOrderSelected = { order ->
                viewModel.setSortOrder(activeCategoryId ?: NO_ID, order)
            },
        )
    }

    val innerPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
        end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )

    LaunchedEffect(uiState.isLoading, favoritesTopBarOverrideState) {
        if (!uiState.isLoading) {
            onTopBarOverrideChanged(favoritesTopBarOverrideState)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onTopBarOverrideChanged(null) }
    }

    if (consumeOrganizeMessages) {
        LaunchedEffect(viewModel.organizeMessages) {
            viewModel.organizeMessages.collect { event ->
                event?.consume(
                    FlowCollector { message ->
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (uiState.isEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_empty_favourites),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.text_empty_holder_primary),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.you_have_not_favourites_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page ->
                        val categoryId = displayCategories.getOrNull(page)?.id ?: page.toLong()
                        "${categoryId}_$refreshGeneration"
                    },
                ) { page ->
                    val category = displayCategories.getOrNull(page) ?: return@HorizontalPager
                    val enabled = page == activePage
                    KototoroFavoritesListScreen(
                        categoryId = category.id,
                        appRouter = appRouter,
                        contentPadding = innerPadding,
                        onNavigateToDetails = onNavigateToDetails,
                        onNavigateToEntityDetails = onNavigateToEntityDetails,
                        onEntityOrganizeSelection = onOpenEntityOrganize,
                        sharedTransitionEnabled = enabled,
                        isActivePage = enabled,
                        onTopBarOverrideChanged = { overrideState ->
                            if (enabled && category.id == activeCategoryId) {
                                childTopBarOverrideState = overrideState
                                childTopBarOverrideGeneration = activeChildOverrideGeneration
                            }
                        },
                        onFilterRailOverrideChanged = {},
                    )
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesFinderDialog(
    state: org.skepsun.kototoro.favourites.ui.container.DuplicatesFinderState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onQuickFix: () -> Unit,
    onFuzzyFix: (Int) -> Unit,
    onToggleGroupChecked: (Int) -> Unit,
    onAcceptFuzzy: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(if (state.isFuzzy) 1 else 0) }
    var tolerance by rememberSaveable { mutableIntStateOf(state.tolerance) }

    AlertDialog(
        onDismissRequest = {
            if (!state.isScanning) {
                onDismiss()
            }
        },
        title = {
            Text(
                text = stringResource(R.string.duplicates_finder),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.isScanning) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (state.isFinished && state.isFuzzy) {
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(state.groups) { index, group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleGroupChecked(index) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = group.isChecked,
                                    onCheckedChange = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.duplicates_group_keep_label,
                                            group.representative.title,
                                            group.representative.source.name
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    group.duplicates.forEach { dup ->
                                        Text(
                                            text = stringResource(
                                                R.string.duplicates_group_delete_label,
                                                dup.title,
                                                dup.source.name
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    androidx.compose.material3.TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        androidx.compose.material3.Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(R.string.quick_duplicates_fix)) }
                        )
                        androidx.compose.material3.Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(R.string.fuzzy_duplicates_fix)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedTab == 0) {
                        Text(
                            text = "Searches for duplicates with exact same name. Probes each source to keep the one with most chapters from active sources. Automatically deletes extra duplicates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Searches for duplicates with similar names based on tolerance. Probes sources, then lets you review and select which duplicate sets to resolve.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.duplicates_tolerance_label, tolerance),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            androidx.compose.material3.Slider(
                                value = tolerance.toFloat(),
                                onValueChange = { tolerance = it.toInt() },
                                valueRange = 0f..100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isScanning) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.duplicates_cancel))
                    }
                } else if (state.isFinished && state.isFuzzy) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.duplicates_cancel))
                    }
                    TextButton(onClick = onAcceptFuzzy) {
                        Text(stringResource(R.string.duplicates_accept))
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            if (selectedTab == 0) {
                                onQuickFix()
                            } else {
                                onFuzzyFix(tolerance)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.duplicates_scan))
                    }
                }
            }
        }
    )
}

@Composable
fun DuplicatesSummaryDialog(
    summary: org.skepsun.kototoro.favourites.ui.container.DuplicatesSummaryState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.duplicates_summary_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.duplicates_summary_text,
                    summary.totalDuplicatesFound,
                    summary.totalSeries,
                    summary.deduplicatedSeries
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}
