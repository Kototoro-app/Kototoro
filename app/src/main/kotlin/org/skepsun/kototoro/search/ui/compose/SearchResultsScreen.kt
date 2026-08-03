package org.skepsun.kototoro.search.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
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
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.compose.KototoroContentCard
import org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar
import org.skepsun.kototoro.list.ui.compose.SelectionAction
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
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SEARCH_CONTENT_KIND_OPTIONS
import org.skepsun.kototoro.search.domain.SOURCE_TYPE_OPTIONS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.main.ui.compose.SearchFilterSheet
import org.skepsun.kototoro.main.ui.compose.toggleOrAll
import org.skepsun.kototoro.search.ui.multi.SearchResultsListModel
import org.skepsun.kototoro.search.ui.multi.SearchViewModel

private data class SearchPreparedItems(
    val sections: List<SearchResultsListModel>,
    val supplementaryItems: List<ListModel>,
)

private val SearchFixedCardWidth = 108.dp
private val SearchFixedCardHeight = SearchFixedCardWidth / 0.7f
private val SearchFixedCardCornerRadius = 8.dp

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

@Composable
private fun rememberSearchGridSpanCount(cardWidth: Dp): Int {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, cardWidth) {
        val availableWidth = configuration.screenWidthDp.dp - 32.dp
        (availableWidth / (cardWidth + 12.dp))
            .toInt()
            .coerceAtLeast(2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsRoute(
    viewModel: SearchViewModel,
    onBackClick: () -> Unit,
    onOpenContent: (Content, String?) -> Unit,
    onPickContent: (Content) -> Unit,
    onOpenSourceResults: (SearchResultsListModel) -> Unit,
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
    val languagePresets by viewModel.languagePresets.collectAsStateWithLifecycle()
    val activeLanguagePresetId by viewModel.activeLanguagePresetId.collectAsStateWithLifecycle()
    val globalTagBlacklist by viewModel.globalTagBlacklist.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val screenPrefs by settings.observeAsState(
        AppSettings.KEY_BADGES_TOP_LEFT,
        AppSettings.KEY_BADGES_TOP_RIGHT,
        AppSettings.KEY_BADGES_BOTTOM_LEFT,
        AppSettings.KEY_BADGES_BOTTOM_RIGHT,
    ) {
        SearchResultsScreenPrefs(
            cardUiPrefs = ContentCardUiPrefs(
                badgesTopLeft = badgesTopLeft,
                badgesTopRight = badgesTopRight,
                badgesBottomLeft = badgesBottomLeft,
                badgesBottomRight = badgesBottomRight,
            ),
        )
    }
    val cardUiPrefs = screenPrefs.cardUiPrefs
    val posterStyle = remember { fixedSearchPosterCardStyle() }
    val gridSpanCount = rememberSearchGridSpanCount(posterStyle.itemWidth)

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
    var selectedSourceTypes by remember { mutableStateOf(viewModel.getSourceTypes()) }
    var selectedContentKinds by remember { mutableStateOf(viewModel.getContentKinds()) }
    var pinnedOnly by remember { mutableStateOf(viewModel.isPinnedOnlySelected) }
    var hideEmpty by remember { mutableStateOf(viewModel.isHideEmptySelected) }
    var selectedItemsIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val hapticFeedback = LocalHapticFeedback.current

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
            it.title.isNotBlank() || it.tags.isNotBlank() || it.author.isNotBlank()
        }
        if (query.isBlank() && advancedQuery == null) {
            return
        }
        onSubmitSearch(
            query.trim(),
            if (advancedQuery != null) SearchKind.ADVANCED else viewModel.kind,
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
                    onOptionsClick = { showOptionsSheet = true },
                    selectedSourceTypes = selectedSourceTypes,
                    selectedContentKinds = selectedContentKinds,
                    pinnedOnly = pinnedOnly,
                    hideEmpty = hideEmpty,
                    isAdvancedExpanded = isAdvancedExpanded,
                    onAdvancedExpandedChange = { isAdvancedExpanded = it },
                    advancedTitle = advancedTitle,
                    onAdvancedTitleChange = { advancedTitle = it },
                    advancedTags = advancedTags,
                    onAdvancedTagsChange = { advancedTags = it },
                    advancedAuthor = advancedAuthor,
                    onAdvancedAuthorChange = { advancedAuthor = it },
                    onSourceTypesClick = { showOptionsSheet = true },
                    onContentKindsClick = { showOptionsSheet = true },
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 0.dp,
                top = paddingValues.calculateTopPadding(),
                end = 0.dp,
                bottom = paddingValues.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = sections,
                key = { index, section -> "${section.source.name}_${section.titleResId}_$index" },
                contentType = { _, _ -> "search_section" },
            ) { _, section ->
                SearchResultsSection(
                    section = section,
                    gridSpanCount = gridSpanCount,
                    posterStyle = posterStyle,
                    cardUiPrefs = cardUiPrefs,
                    selectedItemsIds = selectedItemsIds,
                    selectionEnabled = selectedItemsIds.isNotEmpty() && !isPickMode,
                    onSectionClick = { onOpenSourceResults(section) },
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
                                contentCoverSharedKey(content, item.coverUrl),
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
            onSourceTypeToggle = { type ->
                selectedSourceTypes = selectedSourceTypes.toggleOrAll(type, ALL_SOURCE_TYPES)
                viewModel.setSourceTypes(selectedSourceTypes)
            },
            onContentKindToggle = { kind ->
                selectedContentKinds = selectedContentKinds.toggleOrAll(kind, ALL_SEARCH_CONTENT_KINDS)
                viewModel.setContentKinds(selectedContentKinds)
            },
            onPinnedOnlyChange = {
                pinnedOnly = it
                viewModel.setPinnedOnly(it)
            },
            onHideEmptyChange = {
                hideEmpty = it
                viewModel.setHideEmpty(it)
            },
            onLanguagePresetSelected = viewModel::setActiveLanguagePreset,
            onManageLanguagePresets = onManageLanguagePresets,
            onOpenGlobalTagBlacklist = onOpenGlobalTagBlacklist,
            onDismissRequest = { showOptionsSheet = false },
        )
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
    isAdvancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    advancedTitle: String,
    onAdvancedTitleChange: (String) -> Unit,
    advancedTags: String,
    onAdvancedTagsChange: (String) -> Unit,
    advancedAuthor: String,
    onAdvancedAuthorChange: (String) -> Unit,
    onSourceTypesClick: () -> Unit,
    onContentKindsClick: () -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.search_results),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                        IconButton(
                            onClick = { onAdvancedExpandedChange(!isAdvancedExpanded) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (isAdvancedExpanded)
                                    Icons.Filled.KeyboardArrowUp
                                else
                                    Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    if (isAdvancedExpanded) R.string.collapse else R.string.expand
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = if (isAdvancedExpanded) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onOptionsClick, modifier = Modifier.size(40.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_filter_menu),
                                contentDescription = stringResource(R.string.display_options),
                                modifier = Modifier.size(20.dp),
                                tint = if (selectedSourceTypes.size < ALL_SOURCE_TYPES.size ||
                                          selectedContentKinds.size < ALL_SEARCH_CONTENT_KINDS.size ||
                                          pinnedOnly || hideEmpty)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
            )

            if (isAdvancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = advancedTitle,
                        onValueChange = onAdvancedTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.title)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
                    )
                    OutlinedTextField(
                        value = advancedTags,
                        onValueChange = onAdvancedTagsChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.tags)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
                    )
                    OutlinedTextField(
                        value = advancedAuthor,
                        onValueChange = onAdvancedAuthorChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.author)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchClick() }),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsSection(
    section: SearchResultsListModel,
    gridSpanCount: Int,
    posterStyle: CompactPosterCardStyle,
    cardUiPrefs: org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs,
    selectedItemsIds: Set<Long>,
    selectionEnabled: Boolean,
    onSectionClick: () -> Unit,
    onItemClick: (ContentListModel) -> Unit,
    onItemLongClick: (ContentListModel) -> Unit,
) {
    val context = LocalContext.current
    val rowState = rememberLazyListState()
    val scrollIntensity = rememberHorizontalRailScrollIntensity(rowState)

    Column(
        modifier = Modifier.fillMaxWidth(),
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
            if (section.source !== UnknownContentSource) {
                Button(
                    onClick = onSectionClick,
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
            ) {
                itemsIndexed(
                    items = section.list,
                    key = { index, item ->
                        "${section.source.name}_${section.titleResId}_${index}_${item.id}"
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
        }

        section.error?.let { error ->
            Text(
                text = error.getDisplayMessage(context.resources),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun SearchSupplementaryItem(
    item: ListModel,
    onContinueSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ButtonFooter -> {
            Button(
                onClick = onContinueSearch,
                modifier = modifier.fillMaxWidth(),
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
                        Button(onClick = onContinueSearch) {
                            Text(stringResource(item.actionStringRes))
                        }
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
