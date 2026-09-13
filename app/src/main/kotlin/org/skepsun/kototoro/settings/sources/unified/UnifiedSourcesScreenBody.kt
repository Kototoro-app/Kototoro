package org.skepsun.kototoro.settings.sources.unified


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.settings.compose.SettingsContentHorizontalPadding
import org.skepsun.kototoro.settings.compose.settingsContentTopInset
import org.skepsun.kototoro.parsers.model.ContentType

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
    onRepositoryFilterClick: (String?) -> Unit,
    onPackageStatusClick: (UnifiedPackageStatusFilter) -> Unit = {},
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
    onAddRecommendedRepository: (UnifiedRecommendedRepository) -> Unit,
    onPullRefresh: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberPagerState(pageCount = { UNIFIED_SOURCES_TAB_COUNT }),
    tabReselectTrigger: Pair<Int, Long>? = null,
) {
    val readyState = state as? UnifiedSourcesUiState.Ready
    val sourceListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val repositoryListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val packageListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val selectedTab = pagerState.currentPage.coerceIn(0, UNIFIED_SOURCES_TAB_COUNT - 1)

    LaunchedEffect(tabReselectTrigger) {
        val trigger = tabReselectTrigger ?: return@LaunchedEffect
        when (trigger.first) {
            UNIFIED_SOURCES_TAB_SOURCES -> sourceListState.animateScrollToItem(0)
            UNIFIED_SOURCES_TAB_REPOSITORIES -> repositoryListState.animateScrollToItem(0)
            UNIFIED_SOURCES_TAB_PACKAGES -> packageListState.animateScrollToItem(0)
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
                    UnifiedSourcesContextualFilterTabs(
                        tab = selectedTab,
                        state = readyState,
                        onContentTypeClick = onContentTypeClick,
                        onKindClick = onKindClick,
                        onRepositoryFilterClick = onRepositoryFilterClick,
                        onPackageStatusClick = onPackageStatusClick,
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
                    AnimatedVisibility(
                        visible = selectedTab == UNIFIED_SOURCES_TAB_SOURCES && activeSelectedSourceIds.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
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
                                    recommendedPackages = state.recommendedPackages,
                                    missingSourcesWithoutMatch = state.missingSourcesWithoutMatch,
                                    suggestedRepositoriesForMissing = state.suggestedRepositoriesForMissing,
                                    updateAllInProgress = updateAllInProgress,
                                    onUpdateAllPackages = onUpdateAllPackages,
                                    onPackagePrimaryAction = onPackagePrimaryAction,
                                    onPackageSystemInstall = onPackageSystemInstall,
                                    onPackageUninstall = onPackageUninstall,
                                    onPackageCancelInstall = onPackageCancelInstall,
                                    onImportLocalJar = onImportLocalJar,
                                    onAddRecommendedRepository = onAddRecommendedRepository,
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
private fun UnifiedSourcesContextualFilterTabs(
    tab: Int,
    state: UnifiedSourcesUiState.Ready,
    onContentTypeClick: (ContentType?) -> Unit,
    onKindClick: (UnifiedSourceKind?) -> Unit,
    onRepositoryFilterClick: (String?) -> Unit,
    onPackageStatusClick: (UnifiedPackageStatusFilter) -> Unit,
) {
    val packagesById = remember(state.allPackages) { state.allPackages.associateBy { it.id } }
    val repositoryOptions = remember(tab, state.allRepositories, state.allPackages, state.allSources) {
        val referencedRepositoryIds = when (tab) {
            UNIFIED_SOURCES_TAB_SOURCES -> state.allSources
                .mapNotNullTo(LinkedHashSet()) { it.effectiveRepositoryId(packagesById) }
            UNIFIED_SOURCES_TAB_PACKAGES -> state.allPackages
                .mapNotNullTo(LinkedHashSet()) { it.repositoryId }
            else -> emptySet()
        }
        state.allRepositories
            .filter { it.id in referencedRepositoryIds || it.id == state.filters.repositoryId }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (tab) {
            UNIFIED_SOURCES_TAB_SOURCES -> {
                if (repositoryOptions.isNotEmpty()) {
                    UnifiedRepositoryFilterRow(
                        repositories = repositoryOptions,
                        selectedRepositoryId = state.filters.repositoryId,
                        onRepositorySelected = onRepositoryFilterClick,
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
                ) {
                    item(key = "content_all") {
                        CompactFilterChip(
                            selected = state.filters.contentTypes.isEmpty(),
                            onClick = { onContentTypeClick(null) },
                            text = stringResource(R.string.all_content),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_filter_content_type),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    items(state.availableContentTypes, key = { it.name }) { type ->
                        CompactFilterChip(
                            selected = type in state.filters.contentTypes,
                            onClick = { onContentTypeClick(type) },
                            text = stringResource(type.titleResId),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(type.contentIconRes()),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
                ) {
                    item(key = "kind_all") {
                        CompactFilterChip(
                            selected = state.filters.kinds.isEmpty(),
                            onClick = { onKindClick(null) },
                            text = stringResource(R.string.all_sources),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_extension),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    items(state.availableKinds, key = { it.name }) { kind ->
                        CompactFilterChip(
                            selected = kind in state.filters.kinds,
                            onClick = { onKindClick(kind) },
                            text = kind.displayLabel(),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(kind.packageIconRes()),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
            UNIFIED_SOURCES_TAB_REPOSITORIES -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
                ) {
                    item(key = "repo_kind_all") {
                        CompactFilterChip(
                            selected = state.filters.kinds.isEmpty(),
                            onClick = { onKindClick(null) },
                            text = stringResource(R.string.all_sources),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_extension),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    items(state.availableKinds, key = { it.name }) { kind ->
                        CompactFilterChip(
                            selected = kind in state.filters.kinds,
                            onClick = { onKindClick(kind) },
                            text = kind.displayLabel(),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(kind.packageIconRes()),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
            UNIFIED_SOURCES_TAB_PACKAGES -> {
                if (repositoryOptions.isNotEmpty()) {
                    UnifiedRepositoryFilterRow(
                        repositories = repositoryOptions,
                        selectedRepositoryId = state.filters.repositoryId,
                        onRepositorySelected = onRepositoryFilterClick,
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
                ) {
                    item(key = "package_status_all") {
                        CompactFilterChip(
                            selected = state.filters.packageStatusFilter == UnifiedPackageStatusFilter.ALL,
                            onClick = { onPackageStatusClick(UnifiedPackageStatusFilter.ALL) },
                            text = stringResource(R.string.all),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_extension),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item(key = "package_status_updates") {
                        val isSelected = state.filters.packageStatusFilter == UnifiedPackageStatusFilter.UPDATE_AVAILABLE
                        CompactFilterChip(
                            selected = isSelected,
                            onClick = { onPackageStatusClick(UnifiedPackageStatusFilter.UPDATE_AVAILABLE) },
                            text = stringResource(R.string.package_filter_updates),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingBadge = if (state.packageUpdateCount > 0) {
                                {
                                    Badge(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                    ) {
                                        Text(state.packageUpdateCount.toString())
                                    }
                                }
                            } else null,
                        )
                    }
                    item(key = "package_status_installed") {
                        CompactFilterChip(
                            selected = state.filters.packageStatusFilter == UnifiedPackageStatusFilter.INSTALLED,
                            onClick = { onPackageStatusClick(UnifiedPackageStatusFilter.INSTALLED) },
                            text = stringResource(R.string.package_filter_installed),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item(key = "package_status_not_installed") {
                        CompactFilterChip(
                            selected = state.filters.packageStatusFilter == UnifiedPackageStatusFilter.NOT_INSTALLED,
                            onClick = { onPackageStatusClick(UnifiedPackageStatusFilter.NOT_INSTALLED) },
                            text = stringResource(R.string.package_filter_available),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_cloud_download),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
                ) {
                    item(key = "pkg_kind_all") {
                        CompactFilterChip(
                            selected = state.filters.kinds.isEmpty(),
                            onClick = { onKindClick(null) },
                            text = stringResource(R.string.all_sources),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_extension),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    items(state.availableKinds, key = { it.name }) { kind ->
                        CompactFilterChip(
                            selected = kind in state.filters.kinds,
                            onClick = { onKindClick(kind) },
                            text = kind.displayLabel(),
                            leadingIcon = {
                                Icon(
                                    painter = rememberSafePainter(kind.packageIconRes()),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedRepositoryFilterRow(
    repositories: List<UnifiedSourceRepositoryItem>,
    selectedRepositoryId: String?,
    onRepositorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
    ) {
        item(key = "repo_all") {
            CompactFilterChip(
                selected = selectedRepositoryId == null,
                onClick = { onRepositorySelected(null) },
                text = stringResource(R.string.all),
                leadingIcon = {
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_storage),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        items(repositories, key = { it.id }) { repository ->
            val isSelected = repository.id == selectedRepositoryId
            CompactFilterChip(
                selected = isSelected,
                onClick = {
                    onRepositorySelected(if (isSelected) null else repository.id)
                },
                text = repository.name,
                leadingIcon = {
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_storage),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = SettingsContentHorizontalPadding, vertical = 2.dp),
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
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingBadge: (@Composable () -> Unit)? = null,
) {
    val style = rememberUnifiedSourcesVisualStyle()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = 32.dp),
            shape = style.chipShape,
            leadingIcon = leadingIcon,
            trailingIcon = trailingBadge,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                borderWidth = 1.dp,
                selectedBorderWidth = 1.dp,
            ),
            label = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
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
