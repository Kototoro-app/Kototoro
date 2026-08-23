package org.skepsun.kototoro.settings.sources.unified


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.settings.compose.settingsContentTopInset
import org.skepsun.kototoro.parsers.model.ContentType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSourcesScreen(
    state: UnifiedSourcesUiState,
    isLoading: Boolean,
    updateAllInProgress: Boolean,
    searchActive: Boolean,
    onLanguageFilterClick: () -> Unit,
    onMoreFiltersClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onKindClick: (UnifiedSourceKind?) -> Unit,
    onContentTypeClick: (ContentType?) -> Unit,
    onSourceEnabledChange: (String, Boolean) -> Unit,
    onEnableAllSources: () -> Unit,
    onDisableAllSources: () -> Unit,
    selectedSourceIds: Set<String>,
    onSourceSelectionChange: (Set<String>) -> Unit,
    onSelectAllVisibleSources: () -> Unit,
    onClearSourceSelection: () -> Unit,
    onEnableSelectedSources: () -> Unit,
    onDisableSelectedSources: () -> Unit,
    onTestSelectedSources: () -> Unit,
    onDeleteSelectedSources: () -> Unit,
    onToggleSelectedSourcesNsfw: () -> Unit,
    onSourcePinnedChange: (String, Boolean) -> Unit,
    onBrowseSource: (UnifiedSourceItem) -> Unit,
    onOpenSourceSettings: (UnifiedSourceItem) -> Unit,
    onAddRepository: (UnifiedSourceRepositoryItem?) -> Unit,
    onRefreshRepository: (UnifiedSourceRepositoryItem) -> Unit,
    onDeleteRepository: (UnifiedSourceRepositoryItem) -> Unit,
    onUpdateAllPackages: () -> Unit,
    onPackagePrimaryAction: (String) -> Unit,
    onPackageSystemInstall: (String) -> Unit,
    onPackageUninstall: (String) -> Unit,
    onPackageCancelInstall: (String) -> Unit,
    onImportLocalJar: () -> Unit,
    onPullRefresh: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val readyState = state as? UnifiedSourcesUiState.Ready
    val pagerState = rememberPagerState(pageCount = { UNIFIED_SOURCES_TAB_COUNT })
    val coroutineScope = rememberCoroutineScope()
    val sourceListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val repositoryListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val packageListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val selectedTab = pagerState.currentPage.coerceIn(0, UNIFIED_SOURCES_TAB_COUNT - 1)
    val onTabClick: (Int, LazyListState) -> Unit = { tab, listState ->
        if (tab != UNIFIED_SOURCES_TAB_SOURCES) {
            onClearSourceSelection()
        }
        coroutineScope.launch {
            if (selectedTab == tab) {
                listState.animateScrollToItem(0)
            } else {
                pagerState.animateScrollToPage(tab)
            }
        }
    }
    val activeSelectedSourceIds = remember(readyState?.sources, selectedSourceIds) {
        val visibleSourceIds = readyState?.sources.orEmpty().mapTo(LinkedHashSet()) { it.id }
        selectedSourceIds intersect visibleSourceIds
    }
    val selectedNsfwCount = remember(readyState?.sources, activeSelectedSourceIds) {
        readyState?.sources.orEmpty().count { it.id in activeSelectedSourceIds && it.isNsfw }
    }
    LaunchedEffect(activeSelectedSourceIds) {
        if (selectedSourceIds != activeSelectedSourceIds) {
            onSourceSelectionChange(activeSelectedSourceIds)
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != UNIFIED_SOURCES_TAB_SOURCES && selectedSourceIds.isNotEmpty()) {
            onClearSourceSelection()
        }
    }
    BackHandler(
        enabled = selectedTab == UNIFIED_SOURCES_TAB_SOURCES && selectedSourceIds.isNotEmpty(),
    ) {
        onClearSourceSelection()
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                Spacer(modifier = Modifier.height(settingsContentTopInset()))
                if (isLoading || state == UnifiedSourcesUiState.Loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (readyState != null) {
                    UnifiedSourcesFilterTabs(
                        state = readyState,
                        onContentTypeClick = onContentTypeClick,
                        onKindClick = onKindClick,
                    )
                }
            }
        },
    ) { innerPadding ->
        when (state) {
            UnifiedSourcesUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
            is UnifiedSourcesUiState.Ready -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorLayout { measurable, constraints, tabPositions ->
                                    if (tabPositions.isEmpty()) {
                                        val placeable = measurable.measure(constraints.copy(minWidth = 0))
                                        return@tabIndicatorLayout layout(constraints.maxWidth, placeable.height) {}
                                    }
                                    val currentPage = pagerState.currentPage.coerceIn(tabPositions.indices)
                                    val offset = pagerState.currentPageOffsetFraction
                                    val targetPage = (currentPage + sign(offset).toInt()).coerceIn(tabPositions.indices)
                                    val fraction = abs(offset)
                                    val left = lerp(tabPositions[currentPage].left, tabPositions[targetPage].left, fraction)
                                    val right = lerp(tabPositions[currentPage].right, tabPositions[targetPage].right, fraction)
                                    val width = (right - left).roundToPx()
                                    val placeable = measurable.measure(
                                        constraints.copy(minWidth = width, maxWidth = width),
                                    )
                                    layout(constraints.maxWidth, placeable.height) {
                                        placeable.placeRelative(left.roundToPx(), 0)
                                    }
                                },
                            )
                        },
                    ) {
                        Tab(
                            selected = selectedTab == UNIFIED_SOURCES_TAB_SOURCES,
                            onClick = { onTabClick(UNIFIED_SOURCES_TAB_SOURCES, sourceListState) },
                            text = { Text(stringResource(R.string.sources_tab_title, state.sources.size)) },
                        )
                        Tab(
                            selected = selectedTab == UNIFIED_SOURCES_TAB_REPOSITORIES,
                            onClick = { onTabClick(UNIFIED_SOURCES_TAB_REPOSITORIES, repositoryListState) },
                            text = { Text(stringResource(R.string.repositories_tab_title, state.repositories.size)) },
                        )
                        Tab(
                            selected = selectedTab == UNIFIED_SOURCES_TAB_PACKAGES,
                            onClick = { onTabClick(UNIFIED_SOURCES_TAB_PACKAGES, packageListState) },
                            text = { Text(stringResource(R.string.packages_tab_title, state.packages.size)) },
                        )
                    }
                    if (selectedTab == UNIFIED_SOURCES_TAB_SOURCES && activeSelectedSourceIds.isNotEmpty()) {
                        UnifiedSourceSelectionBar(
                            selectedCount = activeSelectedSourceIds.size,
                            allVisibleSelected = activeSelectedSourceIds.size == state.sources.size,
                            selectedNsfwCount = selectedNsfwCount,
                            selectedSfwCount = activeSelectedSourceIds.size - selectedNsfwCount,
                            onSelectAllVisibleSources = onSelectAllVisibleSources,
                            onClearSelection = onClearSourceSelection,
                            onEnableSelectedSources = onEnableSelectedSources,
                            onDisableSelectedSources = onDisableSelectedSources,
                            onToggleSelectedSourcesNsfw = onToggleSelectedSourcesNsfw,
                            onTestSelectedSources = onTestSelectedSources,
                            onDeleteSelectedSources = onDeleteSelectedSources,
                        )
                    }
                    KototoroPullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = { onPullRefresh(selectedTab) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (page) {
                                UNIFIED_SOURCES_TAB_SOURCES -> UnifiedSourceList(
                                    modifier = Modifier.fillMaxSize(),
                                    listState = sourceListState,
                                    sources = state.sources,
                                    onBrowseSource = onBrowseSource,
                                    onOpenSourceSettings = onOpenSourceSettings,
                                    onSourceEnabledChange = onSourceEnabledChange,
                                    onEnableAllSources = onEnableAllSources,
                                    onDisableAllSources = onDisableAllSources,
                                    selectedSourceIds = activeSelectedSourceIds,
                                    onSourceSelectionChange = onSourceSelectionChange,
                                    onSourcePinnedChange = onSourcePinnedChange,
                                )
                                UNIFIED_SOURCES_TAB_REPOSITORIES -> UnifiedRepositoryList(
                                    modifier = Modifier.fillMaxSize(),
                                    listState = repositoryListState,
                                    repositories = state.repositories,
                                    onAddRepository = onAddRepository,
                                    onRefreshRepository = onRefreshRepository,
                                    onDeleteRepository = onDeleteRepository,
                                )
                                UNIFIED_SOURCES_TAB_PACKAGES -> UnifiedPackageList(
                                    modifier = Modifier.fillMaxSize(),
                                    listState = packageListState,
                                    packages = state.packages,
                                    updateAllInProgress = updateAllInProgress,
                                    onUpdateAllPackages = onUpdateAllPackages,
                                    onPackagePrimaryAction = onPackagePrimaryAction,
                                    onPackageSystemInstall = onPackageSystemInstall,
                                    onPackageUninstall = onPackageUninstall,
                                    onPackageCancelInstall = onPackageCancelInstall,
                                    onImportLocalJar = onImportLocalJar,
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
private fun UnifiedSourcesFilterTabs(
    state: UnifiedSourcesUiState.Ready,
    onContentTypeClick: (ContentType?) -> Unit,
    onKindClick: (UnifiedSourceKind?) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            item(key = "content_all") {
                    CompactFilterChip(
                        selected = state.filters.contentTypes.isEmpty(),
                        onClick = { onContentTypeClick(null) },
                        text = stringResource(R.string.all_content),
                )
            }
            items(state.availableContentTypes, key = { it.name }) { type ->
                CompactFilterChip(
                    selected = type in state.filters.contentTypes,
                    onClick = { onContentTypeClick(type) },
                    text = stringResource(type.titleResId),
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            item(key = "kind_all") {
                    CompactFilterChip(
                        selected = state.filters.kinds.isEmpty(),
                        onClick = { onKindClick(null) },
                        text = stringResource(R.string.all_sources),
                )
            }
            items(state.availableKinds, key = { it.name }) { kind ->
                CompactFilterChip(
                    selected = kind in state.filters.kinds,
                    onClick = { onKindClick(kind) },
                    text = kind.displayLabel(),
                )
            }
        }
    }
}

@Composable
internal fun FilterSection(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 1.dp),
            content = content,
        )
    }
}

@Composable
internal fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
) {
    val style = rememberUnifiedSourcesVisualStyle()
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 30.dp),
        shape = style.chipShape,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun UnifiedSourceSelectionBar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    selectedNsfwCount: Int,
    selectedSfwCount: Int,
    onSelectAllVisibleSources: () -> Unit,
    onClearSelection: () -> Unit,
    onEnableSelectedSources: () -> Unit,
    onDisableSelectedSources: () -> Unit,
    onToggleSelectedSourcesNsfw: () -> Unit,
    onTestSelectedSources: () -> Unit,
    onDeleteSelectedSources: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "selected_count") {
            Text(
                text = stringResource(R.string.selected_count, selectedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "select_all") {
            CompactActionChip(
                onClick = if (allVisibleSelected) onClearSelection else onSelectAllVisibleSources,
                label = {
                    Text(stringResource(if (allVisibleSelected) R.string.deselect_all else R.string.select_all))
                },
            )
        }
        item(key = "enable_selected") {
            CompactActionChip(
                onClick = onEnableSelectedSources,
                label = { Text(stringResource(R.string.enable)) },
            )
        }
        item(key = "disable_selected") {
            CompactActionChip(
                onClick = onDisableSelectedSources,
                label = { Text(stringResource(R.string.disable)) },
            )
        }
        item(key = "toggle_nsfw_selected") {
            val mixedSelection = selectedNsfwCount > 0 && selectedSfwCount > 0
            val label = when {
                mixedSelection -> stringResource(R.string.unified_sources_set_nsfw)
                selectedNsfwCount > 0 -> stringResource(R.string.unified_sources_unmark_nsfw)
                else -> stringResource(R.string.unified_sources_mark_nsfw)
            }
            CompactActionChip(
                onClick = onToggleSelectedSourcesNsfw,
                label = { Text(label) },
            )
        }
        item(key = "test_selected") {
            CompactActionChip(
                onClick = onTestSelectedSources,
                label = { Text(stringResource(R.string.source_test_action)) },
            )
        }
        item(key = "delete_selected") {
            CompactActionChip(
                onClick = onDeleteSelectedSources,
                label = { Text(stringResource(R.string.delete)) },
            )
        }
        item(key = "clear_selection") {
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(android.R.string.cancel),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

