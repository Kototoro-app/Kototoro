package org.skepsun.kototoro.search.ui.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.compose.KototoroContentCard
import org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.list.ui.compose.contentListSharedElementKey
import org.skepsun.kototoro.list.ui.model.ButtonFooter
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SEARCH_CONTENT_KIND_OPTIONS
import org.skepsun.kototoro.search.domain.SOURCE_TYPE_OPTIONS
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.main.ui.compose.SearchFilterSheet
import org.skepsun.kototoro.search.ui.multi.SearchResultsListModel
import org.skepsun.kototoro.search.ui.multi.SearchViewModel
import org.skepsun.kototoro.search.ui.multi.SearchResultsState
import org.skepsun.kototoro.search.ui.multi.sectionKey

private data class SearchPreparedItems(
    val sections: List<SearchResultsListModel>,
    val supplementaryItems: List<ListModel>,
)

private val SearchFixedCardWidth = 108.dp
private val SearchFixedCardHeight = SearchFixedCardWidth / 0.7f
private val SearchFixedCardCornerRadius = 14.dp

private fun fixedSearchPosterCardStyle(): CompactPosterCardStyle {
    return CompactPosterCardStyle(
        itemWidth = SearchFixedCardWidth,
        posterHeight = SearchFixedCardHeight,
        cornerRadius = SearchFixedCardCornerRadius,
    )
}

@Immutable
private data class SearchResultsScreenPrefs(
    val cardUiPrefs: ContentCardUiPrefs,
)

private fun prepareSearchItems(items: List<ListModel>): SearchPreparedItems {
    val sections = ArrayList<SearchResultsListModel>()
    val supplementaryItems = ArrayList<ListModel>()
    items.forEach { item ->
        if (item is SearchResultsListModel) {
            sections += item
        } else {
            supplementaryItems += item
        }
    }
    return SearchPreparedItems(
        sections = sections,
        supplementaryItems = supplementaryItems,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsRoute(
    viewModel: SearchViewModel,
    onBackClick: () -> Unit,
    onOpenContent: (Content, String?) -> Unit,
    onPickContent: (Content) -> Unit,
    onOpenSourceResults: (SearchResultsListModel) -> Unit,
    onOpenFavouriteCategory: (org.skepsun.kototoro.favourites.domain.FavouriteSearchMatch, String) -> Unit,
    onManageLanguagePresets: () -> Unit,
    onOpenGlobalTagBlacklist: () -> Unit,
    onSubmitSearch: (
        query: String,
        kind: SearchKind,
        sourceTypes: Set<SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedQuery: AdvancedSearchParams?,
        pinnedOnly: Boolean,
        hideEmpty: Boolean,
    ) -> Unit,
    onShareSelection: (Set<Content>) -> Unit,
    onSaveSelection: (Set<Content>) -> Unit,
    onFavouriteSelection: (Set<Content>) -> Unit,
    isPickMode: Boolean,
) {
    val listModels by viewModel.list.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val languagePresets by viewModel.languagePresets.collectAsStateWithLifecycle()
    val activeLanguagePresetId by viewModel.activeLanguagePresetId.collectAsStateWithLifecycle()
    val globalTagBlacklist by viewModel.globalTagBlacklist.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val favouriteMatches by viewModel.favouriteMatches.collectAsStateWithLifecycle()
    LaunchedEffect(favouriteMatches) {
        favouriteMatches?.singleOrNull()?.let { match ->
            viewModel.dismissFavouriteCategories()
            onOpenFavouriteCategory(match, viewModel.query)
        }
    }
    favouriteMatches?.takeUnless { it.size == 1 }?.let { matches ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissFavouriteCategories,
            title = { Text(stringResource(R.string.favourites)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    if (matches.isEmpty()) item { Text(stringResource(R.string.nothing_found)) }
                    items(matches.size) { index ->
                        val match = matches[index]
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.dismissFavouriteCategories()
                            onOpenFavouriteCategory(match, viewModel.query)
                        }) {
                            Text(stringResource(R.string.favourite_search_category, match.category.title, match.mangaIds.size))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissFavouriteCategories) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_BADGES_TOP_LEFT,
        AppSettings.KEY_BADGES_TOP_RIGHT,
        AppSettings.KEY_BADGES_BOTTOM_LEFT,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
        AppSettings.KEY_CARD_PROGRESS_STYLE,
    ) {
        SearchResultsScreenPrefs(
            cardUiPrefs = ContentCardUiPrefs(
                badgesTopLeft = badgesTopLeft,
                badgesTopRight = badgesTopRight,
                badgesBottomLeft = badgesBottomLeft,
                badgesBottomRight = badgesBottomRight,
                cardProgressStyle = cardProgressStyle,
            ),
        )
    }
    val cardUiPrefs = screenPrefs.cardUiPrefs
    val posterStyle = remember { fixedSearchPosterCardStyle() }

    var query by rememberSaveable { mutableStateOf(viewModel.query) }
    var advancedTitle by rememberSaveable { mutableStateOf(viewModel.advancedQuery?.title.orEmpty()) }
    var advancedTags by rememberSaveable { mutableStateOf(viewModel.advancedQuery?.tags.orEmpty()) }
    var advancedAuthor by rememberSaveable { mutableStateOf(viewModel.advancedQuery?.author.orEmpty()) }
    var isAdvancedExpanded by rememberSaveable {
        mutableStateOf(
            viewModel.kind == SearchKind.ADVANCED ||
                advancedTitle.isNotBlank() ||
                advancedTags.isNotBlank() ||
                advancedAuthor.isNotBlank(),
        )
    }
    var showOptionsSheet by remember { mutableStateOf(false) }
    val selectedSourceTypes = filters.sourceTypes
    val selectedContentKinds = filters.contentKinds
    val pinnedOnly = filters.pinnedOnly
    val hideEmpty = filters.hideEmpty
    var selectedItemsIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val searchFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialSearchFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isTvPresentation, isPickMode) {
        if (isTvPresentation && !isPickMode && selectedItemsIds.isEmpty() && !hasRequestedInitialSearchFocus) {
            withFrameNanos { }
            if (searchFocusRequester.requestFocus()) {
                hasRequestedInitialSearchFocus = true
            }
        }
    }
    val hapticFeedback = LocalHapticFeedback.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val preparedItems = remember(listModels) { prepareSearchItems(listModels) }
    val sections = preparedItems.sections
    val supplementaryItems = preparedItems.supplementaryItems
    val selectedItems = remember(selectedItemsIds, listModels) {
        viewModel.getItems(selectedItemsIds)
    }
    val isAllNonLocal = selectedItems.none { it.isLocal }
    fun submitSearch() {
        val advancedQuery = AdvancedSearchParams(
            query = query.trim(),
            title = advancedTitle.trim(),
            tags = advancedTags.trim(),
            author = advancedAuthor.trim(),
        ).takeIf {
            isAdvancedExpanded && (it.title.isNotBlank() || it.tags.isNotBlank() || it.author.isNotBlank())
        }
        if (query.isBlank() && advancedQuery == null) {
            return
        }
        keyboardController?.hide()
        onSubmitSearch(
            query.trim(),
            if (advancedQuery != null) SearchKind.ADVANCED else viewModel.kind.takeUnless {
                it == SearchKind.ADVANCED
            } ?: SearchKind.SIMPLE,
            selectedSourceTypes,
            selectedContentKinds,
            advancedQuery,
            pinnedOnly,
            hideEmpty,
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            if (selectedItemsIds.isEmpty() || isPickMode) {
                SearchResultsTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    onBackClick = onBackClick,
                    onSearchClick = ::submitSearch,
                    onOptionsClick = {
                        keyboardController?.hide()
                        showOptionsSheet = true
                    },
                    selectedSourceTypes = selectedSourceTypes,
                    selectedContentKinds = selectedContentKinds,
                    pinnedOnly = pinnedOnly,
                    hideEmpty = hideEmpty,
                    languagePresetTitle = languagePresets.firstOrNull { it.id == filters.languagePresetId }?.title,
                    isAdvancedExpanded = isAdvancedExpanded,
                    onAdvancedExpandedChange = { isAdvancedExpanded = it },
                    advancedTitle = advancedTitle,
                    onAdvancedTitleChange = { advancedTitle = it },
                    advancedTags = advancedTags,
                    onAdvancedTagsChange = { advancedTags = it },
                    advancedAuthor = advancedAuthor,
                    onAdvancedAuthorChange = { advancedAuthor = it },
                    searchFocusRequester = searchFocusRequester.takeIf {
                        isTvPresentation && selectedItemsIds.isEmpty() && !isPickMode
                    },
                )
            } else {
                KototoroSelectionTopBar(
                    selectedCount = selectedItemsIds.size,
                    isAllNonLocal = isAllNonLocal,
                    isSingleSelection = selectedItemsIds.size == 1,
                    supportedActions = buildSet {
                        add(SelectionAction.SHARE)
                        add(SelectionAction.FAVOURITE)
                        if (isAllNonLocal) {
                            add(SelectionAction.SAVE)
                        }
                    },
                    onClearSelection = { selectedItemsIds = emptySet() },
                    onActionClick = { action ->
                        when (action) {
                            SelectionAction.SHARE -> {
                                onShareSelection(selectedItems)
                                selectedItemsIds = emptySet()
                            }

                            SelectionAction.FAVOURITE -> {
                                onFavouriteSelection(selectedItems)
                                selectedItemsIds = emptySet()
                            }

                            SelectionAction.SAVE -> {
                                if (isAllNonLocal) {
                                    onSaveSelection(selectedItems)
                                    selectedItemsIds = emptySet()
                                }
                            }

                            else -> Unit
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isTvPresentation) Modifier.focusGroup() else Modifier),
            contentPadding = PaddingValues(
                start = 0.dp,
                top = paddingValues.calculateTopPadding() + 12.dp,
                end = 0.dp,
                bottom = paddingValues.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searchState.isSearching || sections.isNotEmpty() || searchState.totalSources > 0 ||
                searchState.failedSources > 0
            ) {
                item(key = "search_progress", contentType = "search_progress") {
                    SearchResultsProgress(searchState, sections, viewModel::retryFailedSources)
                }
            }
            itemsIndexed(
                items = sections,
                key = { _, section -> section.sectionKey },
                contentType = { _, _ -> "search_section" },
            ) { _, section ->
                SearchResultsSection(
                    section = section,
                    posterStyle = posterStyle,
                    cardUiPrefs = cardUiPrefs,
                    selectedItemsIds = selectedItemsIds,
                    selectionEnabled = selectedItemsIds.isNotEmpty() && !isPickMode,
                    isSearching = searchState.isSearching,
                    isRetrying = section.sectionKey in searchState.retryingSectionKeys,
                    onRetryClick = { viewModel.retrySource(section) },
                    onSectionClick = {
                        if (section.titleResId == R.string.favourites) {
                            viewModel.showFavouriteCategories(section.list.map { it.id })
                        } else {
                            onOpenSourceResults(section)
                        }
                    },
                    onItemClick = { item ->
                        if (selectedItemsIds.isNotEmpty() && !isPickMode) {
                            hapticFeedback.performSelectionHapticFeedback()
                            selectedItemsIds = selectedItemsIds.toggle(item.id)
                        } else if (isPickMode) {
                            onPickContent(item.toContentWithOverride())
                        } else {
                            val content = item.toContentWithOverride()
                            onOpenContent(
                                content,
                                contentListSharedElementKey(item, null),
                            )
                        }
                    },
                    onItemLongClick = { item ->
                        if (!isPickMode) {
                            selectedItemsIds = selectedItemsIds.toggle(item.id)
                        }
                    },
                )
            }

            items(
                items = supplementaryItems,
                key = { item -> "extra_${item.javaClass.simpleName}_${item.hashCode()}" },
                contentType = { "search_supplementary" },
            ) { item ->
                SearchSupplementaryItem(
                    item = item,
                    onContinueSearch = viewModel::continueSearch,
                    onAdjustFilters = { showOptionsSheet = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showOptionsSheet) {
        SearchFilterSheet(
            sourceTypes = selectedSourceTypes,
            contentKinds = selectedContentKinds,
            pinnedOnly = pinnedOnly,
            hideEmpty = hideEmpty,
            languagePresets = languagePresets,
            activeLanguagePresetId = activeLanguagePresetId,
            blacklistedTagCount = globalTagBlacklist.size,
            onApply = viewModel::applyFilters,
            onManageLanguagePresets = onManageLanguagePresets,
            onOpenGlobalTagBlacklist = onOpenGlobalTagBlacklist,
            onDismissRequest = { showOptionsSheet = false },
        )
    }
}

@Composable
private fun SearchResultsProgress(
    state: SearchResultsState,
    sections: List<SearchResultsListModel>,
    onRetryFailed: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isRefreshing) stringResource(R.string.search_refreshing_results) else
                        stringResource(R.string.search_loaded_results, sections.sumOf { it.list.distinctBy { it.id }.size }),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.totalSources > 0) {
                    Text(
                        text = stringResource(R.string.search_sources_progress, state.completedSources, state.totalSources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.failedSources > 0) {
                    Text(
                        text = stringResource(R.string.search_sources_failed, state.failedSources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.failedSources > 0) {
                TextButton(onClick = onRetryFailed, enabled = !state.isSearching) {
                    Text(stringResource(R.string.search_retry_failed_sources))
                }
            }
        }
        if (state.isSearching) {
            if (state.totalSources > 0) {
                LinearProgressIndicator(
                    progress = { state.completedSources.toFloat() / state.totalSources },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        }
    }
}

@Composable
private fun SearchResultsTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit,
    onOptionsClick: () -> Unit,
    selectedSourceTypes: Set<SourceType>,
    selectedContentKinds: Set<SearchContentKind>,
    pinnedOnly: Boolean,
    hideEmpty: Boolean,
    languagePresetTitle: String?,
    isAdvancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    advancedTitle: String,
    onAdvancedTitleChange: (String) -> Unit,
    advancedTags: String,
    onAdvancedTagsChange: (String) -> Unit,
    advancedAuthor: String,
    onAdvancedAuthorChange: (String) -> Unit,
    searchFocusRequester: FocusRequester? = null,
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.search_results),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.search_results_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .then(searchFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_content)) },
                shape = RoundedCornerShape(18.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
            )
                FilledIconButton(
                    onClick = onSearchClick,
                    enabled = query.isNotBlank() || (isAdvancedExpanded &&
                        (advancedTitle.isNotBlank() || advancedTags.isNotBlank() || advancedAuthor.isNotBlank())),
                    modifier = Modifier.size(48.dp)
                        .tvFocusable(shape = RoundedCornerShape(24.dp), addFocusTarget = false),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
            }

            SearchToolsRow(
                advancedExpanded = isAdvancedExpanded,
                onAdvancedClick = { onAdvancedExpandedChange(!isAdvancedExpanded) },
                hasActiveFilters = selectedSourceTypes.size < ALL_SOURCE_TYPES.size ||
                    selectedContentKinds.size < ALL_SEARCH_CONTENT_KINDS.size || pinnedOnly || hideEmpty ||
                    languagePresetTitle != null,
                onFiltersClick = onOptionsClick,
                modifier = Modifier.fillMaxWidth(),
            )

            SearchResultsFilterSummary(
                sourceTypes = selectedSourceTypes,
                contentKinds = selectedContentKinds,
                pinnedOnly = pinnedOnly,
                hideEmpty = hideEmpty,
                languagePresetTitle = languagePresetTitle,
                onClick = onOptionsClick,
            )

            if (isAdvancedExpanded) {
                SearchAdvancedFields(
                    title = advancedTitle,
                    onTitleChange = onAdvancedTitleChange,
                    tags = advancedTags,
                    onTagsChange = onAdvancedTagsChange,
                    author = advancedAuthor,
                    onAuthorChange = onAdvancedAuthorChange,
                    onSearch = onSearchClick,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SearchResultsFilterSummary(
    sourceTypes: Set<SourceType>,
    contentKinds: Set<SearchContentKind>,
    pinnedOnly: Boolean,
    hideEmpty: Boolean,
    languagePresetTitle: String?,
    onClick: () -> Unit,
) {
    val labels = buildList {
        if (contentKinds.size < ALL_SEARCH_CONTENT_KINDS.size) {
            add(SEARCH_CONTENT_KIND_OPTIONS.filter { it.kind in contentKinds }
                .map { stringResource(it.titleRes) }.joinToString(" · "))
        }
        if (sourceTypes.size < ALL_SOURCE_TYPES.size) {
            val single = SOURCE_TYPE_OPTIONS.singleOrNull { it.type in sourceTypes }
            add(if (single != null) stringResource(single.titleRes) else
                stringResource(R.string.search_source_type_count, sourceTypes.size))
        }
        languagePresetTitle?.let { add(it) }
        if (pinnedOnly) add(stringResource(R.string.pinned_sources_only))
        if (hideEmpty) add(stringResource(R.string.hide_empty_sources))
    }
    if (labels.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(labels) { label ->
            AssistChip(
                onClick = onClick,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false),
            )
        }
    }
}

@Composable
private fun SearchResultsSection(
    section: SearchResultsListModel,
    posterStyle: CompactPosterCardStyle,
    cardUiPrefs: org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs,
    selectedItemsIds: Set<Long>,
    selectionEnabled: Boolean,
    isSearching: Boolean,
    isRetrying: Boolean,
    onRetryClick: () -> Unit,
    onSectionClick: () -> Unit,
    onItemClick: (ContentListModel) -> Unit,
    onItemLongClick: (ContentListModel) -> Unit,
) {
    val context = LocalContext.current
    val rowState = rememberLazyListState()
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)
    val uniqueItems = remember(section.list) { section.list.distinctBy { it.id } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.getTitle(context),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (uniqueItems.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.search_displayed_results, uniqueItems.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (section.titleResId == R.string.favourites) {
                IconButton(onClick = onSectionClick) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = stringResource(R.string.favourites),
                    )
                }
            } else if (section.source !== UnknownContentSource) {
                Button(
                    onClick = onSectionClick,
                    modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.show_all))
                }
            }
        }

        if (section.list.isNotEmpty()) {
            val railAnimationFactor = rememberRailAnimationFactor()
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.then(
                    if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier,
                ),
            ) {
                itemsIndexed(
                    items = uniqueItems,
                    key = { _, item ->
                        "${section.sectionKey}_${item.id}"
                    },
                    contentType = { _, _ -> "search_result_card" },
                ) { index, item ->
                    HorizontalRailAnimatedVisibility(
                        animationKey = "search_${section.source.name}_${item.id}",
                        index = index,
                        listState = rowState,
                        scrollIntensity = scrollIntensity,
                        animationFactor = railAnimationFactor,
                        enableScrollLinkedAnimation = false,
                    ) { animatedModifier ->
                        Box(
                            modifier = animatedModifier.width(posterStyle.itemWidth),
                        ) {
                            KototoroContentCard(
                                model = item,
                                isSelected = item.id in selectedItemsIds,
                                selectionModeActive = selectionEnabled,
                                sharedTransitionEnabled = true,
                                cardStyle = posterStyle,
                                uiPrefs = cardUiPrefs,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                            )
                        }
                    }
                }
            }
        } else if (section.error == null) {
            Text(
                text = stringResource(R.string.nothing_found),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        section.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error.getDisplayMessage(context.resources),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isRetrying) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp))
                } else {
                    TextButton(onClick = onRetryClick, enabled = !isSearching) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSupplementaryItem(
    item: ListModel,
    onContinueSearch: () -> Unit,
    onAdjustFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ButtonFooter -> {
            Button(
                onClick = onContinueSearch,
                modifier = modifier
                    .fillMaxWidth()
                    .tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
            ) {
                Text(stringResource(item.textResId))
            }
        }

        is EmptyState -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(item.textPrimary),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(item.textSecondary),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.actionStringRes != 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onContinueSearch,
                            modifier = Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false),
                        ) {
                            Text(stringResource(item.actionStringRes))
                        }
                    }
                    TextButton(onClick = onAdjustFilters) {
                        Text(stringResource(R.string.filter))
                    }
                }
            }
        }

        is LoadingFooter,
        LoadingState -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        else -> Unit
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> {
    return if (item in this) this - item else this + item
}
