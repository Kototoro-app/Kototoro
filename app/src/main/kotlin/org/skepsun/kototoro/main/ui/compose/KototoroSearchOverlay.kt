package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.search.domain.LocalEntitySuggestion
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity

private const val SearchOverlayAnimationDurationMillis = 260
private val SearchOverlayCollapsedHeight = 56.dp
private val SearchOverlayCollapsedHorizontalPadding = 10.dp
private val SearchOverlayCollapsedCornerRadius = 24.dp
private val SearchSuggestionCardWidth = 108.dp
private val SearchSuggestionCardCornerRadius = 8.dp
private val SearchSuggestionCardInnerCornerRadius = 6.dp
private val SearchSuggestionChipHeight = 28.dp

@Composable
fun KototoroSearchOverlay(
    visible: Boolean,
    query: String,
    suggestions: List<SearchSuggestionItem>,
    initialSearchKind: SearchKind,
    initialSourceTypes: Set<SourceType>,
    initialContentKinds: Set<SearchContentKind>,
    languagePresets: List<SourcePreset>,
    activeLanguagePresetId: Long,
    onQueryChanged: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchWithOptions: (
        query: String,
        kind: SearchKind,
        sourceTypes: Set<SourceType>,
        contentKinds: Set<SearchContentKind>,
        advancedQuery: AdvancedSearchParams?,
        pinnedOnly: Boolean,
        hideEmpty: Boolean,
    ) -> Unit,
    onDismissRequest: () -> Unit,
    onSourceTypesChange: (Set<SourceType>) -> Unit,
    onContentKindsChange: (Set<SearchContentKind>) -> Unit,
    onContentSuggestionClick: (Content) -> Unit,
    onLocalEntitySuggestionClick: (LocalEntitySuggestion) -> Unit,
    onTrackingEntitySuggestionClick: (TrackingEntity) -> Unit,
    onTagSuggestionClick: (ContentTag) -> Unit,
    onSourceSuggestionClick: (ContentSource) -> Unit,
    onAuthorSuggestionClick: (String) -> Unit,
    onDeleteQuery: (String) -> Unit,
    onLanguagePresetSelected: (Long) -> Unit,
    onManageLanguagePresets: () -> Unit,
    onVoiceInput: () -> Unit,
    onExitFinished: () -> Unit = {},
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val focusRequester = remember { FocusRequester() }
    var animatedVisible by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(initialSearchKind == SearchKind.ADVANCED) }
    var selectedSourceTypes by remember(initialSourceTypes) { mutableStateOf(initialSourceTypes.ifEmpty { ALL_SOURCE_TYPES }) }
    var selectedContentKinds by remember(initialContentKinds) { mutableStateOf(initialContentKinds.ifEmpty { ALL_SEARCH_CONTENT_KINDS }) }
    var pinnedOnly by remember { mutableStateOf(false) }
    var hideEmpty by remember { mutableStateOf(false) }
    var advancedTitle by remember { mutableStateOf("") }
    var advancedTags by remember { mutableStateOf("") }
    var advancedAuthor by remember { mutableStateOf("") }

    LaunchedEffect(visible) {
        animatedVisible = visible
        if (visible) {
            delay(90)
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            delay(SearchOverlayAnimationDurationMillis.toLong())
            onExitFinished()
        }
    }

    LaunchedEffect(selectedSourceTypes) {
        onSourceTypesChange(selectedSourceTypes)
    }

    LaunchedEffect(selectedContentKinds) {
        onContentKindsChange(selectedContentKinds)
    }

    fun submitSearch(searchQuery: String) {
        val kind = if (showAdvanced) SearchKind.ADVANCED else SearchKind.SIMPLE
        val advancedQuery = AdvancedSearchParams(
            query = searchQuery.trim(),
            title = advancedTitle.trim(),
            tags = advancedTags.trim(),
            author = advancedAuthor.trim(),
        ).takeIf {
            showAdvanced && (it.title.isNotBlank() || it.tags.isNotBlank() || it.author.isNotBlank())
        }
        onSearchWithOptions(
            searchQuery,
            kind,
            selectedSourceTypes,
            selectedContentKinds,
            advancedQuery,
            pinnedOnly,
            hideEmpty,
        )
    }

    // Always intercept back when this overlay is mounted (not just when visible),
    // otherwise disabling the handler on dismiss propagates the same back event
    // to the NavHost and pops the underlying screen.
    BackHandler {
        onDismissRequest()
    }

    val transition = updateTransition(
        targetState = animatedVisible,
        label = "search_overlay_transition",
    )
    val progress by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = SearchOverlayAnimationDurationMillis,
                easing = FastOutSlowInEasing,
            )
        },
        label = "search_overlay_progress",
    ) { isVisible -> if (isVisible) 1f else 0f }
    val horizontalPadding by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = SearchOverlayAnimationDurationMillis,
                easing = FastOutSlowInEasing,
            )
        },
        label = "search_overlay_horizontal_padding",
    ) { isVisible -> if (isVisible) 0.dp else SearchOverlayCollapsedHorizontalPadding }
    val cornerRadius by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = SearchOverlayAnimationDurationMillis,
                easing = FastOutSlowInEasing,
            )
        },
        label = "search_overlay_corner_radius",
    ) { isVisible -> if (isVisible) 0.dp else SearchOverlayCollapsedCornerRadius }
    val suggestionsAlpha = ((progress - 0.28f) / 0.72f).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val panelHeight by transition.animateDp(
            transitionSpec = {
                tween(
                    durationMillis = SearchOverlayAnimationDurationMillis,
                    easing = FastOutSlowInEasing,
                )
            },
            label = "search_overlay_panel_height",
        ) { isVisible ->
            if (isVisible) {
                maxHeight
            } else {
                statusBarPadding + SearchOverlayCollapsedHeight
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f * progress)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .height(panelHeight)
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    SearchInputField(
                        value = query,
                        onValueChange = onQueryChanged,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .height(48.dp)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_content),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = { onQueryChanged("") },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.clear),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                submitSearch(query)
                            },
                        ),
                    )
                    IconButton(
                        onClick = { showAdvanced = !showAdvanced },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (showAdvanced)
                                Icons.Filled.KeyboardArrowUp
                            else
                                Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (showAdvanced) R.string.collapse else R.string.expand
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = if (showAdvanced) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.size(40.dp),
                    ) {
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
                if (showAdvanced) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextField(
                            value = advancedTitle,
                            onValueChange = { advancedTitle = it },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.name),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        TextField(
                            value = advancedTags,
                            onValueChange = { advancedTags = it },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.genres),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        TextField(
                            value = advancedAuthor,
                            onValueChange = { advancedAuthor = it },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.author),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            HorizontalDivider()
            SuggestionList(
                suggestions = suggestions,
                bottomPadding = navigationBarPadding.calculateBottomPadding(),
                modifier = Modifier.graphicsLayer { alpha = suggestionsAlpha },
                onRecentQueryClick = { recentQuery ->
                    onQueryChanged(recentQuery)
                    submitSearch(recentQuery)
                },
                onHintClick = { hint ->
                    onQueryChanged(hint)
                    submitSearch(hint)
                },
                onAuthorSuggestionClick = { author ->
                    onQueryChanged(author)
                    submitSearch(author)
                },
                onContentSuggestionClick = onContentSuggestionClick,
                onLocalEntitySuggestionClick = onLocalEntitySuggestionClick,
                onTrackingEntitySuggestionClick = onTrackingEntitySuggestionClick,
                onTagSuggestionClick = { tag ->
                    onQueryChanged(tag.title)
                    onTagSuggestionClick(tag)
                },
                onSourceSuggestionClick = onSourceSuggestionClick,
                onDeleteQuery = onDeleteQuery,
            )
        }
    }

    if (showFilterSheet) {
        SearchFilterSheet(
            sourceTypes = selectedSourceTypes,
            contentKinds = selectedContentKinds,
            pinnedOnly = pinnedOnly,
            hideEmpty = hideEmpty,
            languagePresets = languagePresets,
            activeLanguagePresetId = activeLanguagePresetId,
            onSourceTypeToggle = { type ->
                selectedSourceTypes = selectedSourceTypes.toggleOrAll(type, ALL_SOURCE_TYPES)
            },
            onContentKindToggle = { kind ->
                selectedContentKinds = selectedContentKinds.toggleOrAll(kind, ALL_SEARCH_CONTENT_KINDS)
            },
            onPinnedOnlyChange = { pinnedOnly = it },
            onHideEmptyChange = { hideEmpty = it },
            onLanguagePresetSelected = onLanguagePresetSelected,
            onManageLanguagePresets = onManageLanguagePresets,
            onDismissRequest = { showFilterSheet = false },
        )
    }

}


@Composable
private fun SearchInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable (() -> Unit)?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingIcon()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            placeholder()
                        }
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        trailingIcon()
                    }
                }
            }
        },
    )
}

@Composable
private fun SuggestionList(
    suggestions: List<SearchSuggestionItem>,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    onRecentQueryClick: (String) -> Unit,
    onHintClick: (String) -> Unit,
    onAuthorSuggestionClick: (String) -> Unit,
    onContentSuggestionClick: (Content) -> Unit,
    onLocalEntitySuggestionClick: (LocalEntitySuggestion) -> Unit,
    onTrackingEntitySuggestionClick: (TrackingEntity) -> Unit,
    onTagSuggestionClick: (ContentTag) -> Unit,
    onSourceSuggestionClick: (ContentSource) -> Unit,
    onDeleteQuery: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 8.dp),
    ) {
        items(
            items = suggestions,
            key = { item ->
                when (item) {
                    is SearchSuggestionItem.RecentQuery -> "rq_${item.query}"
                    is SearchSuggestionItem.Hint -> "hint_${item.query}"
                    is SearchSuggestionItem.Author -> "author_${item.name}"
                    is SearchSuggestionItem.Source -> "src_${item.source.name}"
                    is SearchSuggestionItem.SourceTip -> "srctip_${item.source.name}"
                    is SearchSuggestionItem.Tags -> "tags"
                    is SearchSuggestionItem.ContentList -> "content"
                    is SearchSuggestionItem.LocalEntityList -> "local_entity"
                    is SearchSuggestionItem.TrackingEntityList -> "tracking_${item.service.name}"
                    is SearchSuggestionItem.Text -> "text_${item.textResId}"
                }
            },
            contentType = { item ->
                when (item) {
                    is SearchSuggestionItem.RecentQuery -> "recent_query"
                    is SearchSuggestionItem.Hint -> "hint"
                    is SearchSuggestionItem.Author -> "author"
                    is SearchSuggestionItem.Source -> "source"
                    is SearchSuggestionItem.SourceTip -> "source_tip"
                    is SearchSuggestionItem.Tags -> "tags"
                    is SearchSuggestionItem.ContentList -> "content_list"
                    is SearchSuggestionItem.LocalEntityList -> "local_entity_list"
                    is SearchSuggestionItem.TrackingEntityList -> "tracking_entity_list"
                    is SearchSuggestionItem.Text -> "text"
                }
            },
        ) { item ->
            when (item) {
                is SearchSuggestionItem.RecentQuery -> {
                    ListItem(
                        headlineContent = { Text(item.query) },
                        leadingContent = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_history),
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onDeleteQuery(item.query) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.remove),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onRecentQueryClick(item.query) },
                    )
                }

                is SearchSuggestionItem.Hint -> {
                    ListItem(
                        headlineContent = { Text(item.query) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onHintClick(item.query) },
                    )
                }

                is SearchSuggestionItem.Author -> {
                    ListItem(
                        headlineContent = { Text(item.name) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onAuthorSuggestionClick(item.name) },
                    )
                }

                is SearchSuggestionItem.Tags -> {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(item.tags, contentType = { "tag_chip" }) { chip ->
                            val tag = chip.data as? ContentTag
                            AssistChip(
                                onClick = { tag?.let(onTagSuggestionClick) },
                                label = { Text(chip.title?.toString().orEmpty(), maxLines = 1) },
                                modifier = Modifier.height(SearchSuggestionChipHeight),
                                shape = RoundedCornerShape(14.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            )
                        }
                    }
                }

                is SearchSuggestionItem.Source -> {
                    SourceSuggestionRow(
                        source = item.source,
                        onClick = { onSourceSuggestionClick(item.source) },
                    )
                }

                is SearchSuggestionItem.SourceTip -> {
                    SourceSuggestionRow(
                        source = item.source,
                        onClick = { onSourceSuggestionClick(item.source) },
                    )
                }

                is SearchSuggestionItem.ContentList -> {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = item.items,
                            key = { content -> content.id },
                            contentType = { "content_card" },
                        ) { content ->
                            ContentSuggestionCard(
                                content = content,
                                onClick = { onContentSuggestionClick(content) },
                            )
                        }
                    }
                }

                is SearchSuggestionItem.LocalEntityList -> {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = item.items,
                            key = { suggestion -> suggestion.entityId ?: suggestion.representative.id },
                            contentType = { "local_entity_card" },
                        ) { suggestion ->
                            LocalEntitySuggestionCard(
                                suggestion = suggestion,
                                onClick = { onLocalEntitySuggestionClick(suggestion) },
                            )
                        }
                    }
                }

                is SearchSuggestionItem.TrackingEntityList -> {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = item.items,
                            key = { entity -> "${entity.entityType.name}_${entity.remoteId}" },
                            contentType = { "tracking_entity_card" },
                        ) { entity ->
                            TrackingEntitySuggestionCard(
                                entity = entity,
                                onClick = { onTrackingEntitySuggestionClick(entity) },
                            )
                        }
                    }
                }

                is SearchSuggestionItem.Text -> {
                    if (item.textResId != 0) {
                        ListItem(
                            headlineContent = { Text(stringResource(item.textResId)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSuggestionRow(
    source: ContentSource,
    onClick: () -> Unit,
) {
    val sourceTitle = rememberResolvedSourceTitle(source)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            ContentSourceIcon(
                source = source,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
                Text(
                    text = sourceTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            Text(
                text = stringResource(source.contentType.titleResId),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackingEntitySuggestionCard(
    entity: TrackingEntity,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageRequest = remember(entity.service, entity.entityType, entity.remoteId, entity.coverUrl) {
        ImageRequest.Builder(context)
            .data(entity.coverUrl)
            .crossfade(true)
            .build()
    }
    val serviceTitle = stringResource(entity.service.titleResId)
    val typeTitle = stringResource(entity.entityType.titleResId())
    Surface(
        modifier = Modifier
            .width(SearchSuggestionCardWidth)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(SearchSuggestionCardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (entity.entityType == EntityType.WORK) 0.7f else 1f)
                    .clip(RoundedCornerShape(SearchSuggestionCardInnerCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (entity.coverUrl.isNullOrBlank()) {
                    Icon(
                        painter = painterResource(entity.entityType.iconResId()),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = entity.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$serviceTitle · $typeTitle",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun EntityType.titleResId(): Int = when (this) {
    EntityType.WORK -> R.string.entity_graph_type_work
    EntityType.CHARACTER -> R.string.entity_graph_type_character
    EntityType.PERSON -> R.string.entity_graph_type_person
    EntityType.ORGANIZATION -> R.string.entity_graph_type_organization
}

private fun EntityType.iconResId(): Int = when (this) {
    EntityType.WORK -> R.drawable.ic_content_manga
    EntityType.CHARACTER -> R.drawable.ic_user
    EntityType.PERSON -> R.drawable.ic_user
    EntityType.ORGANIZATION -> R.drawable.ic_select_group
}

@Composable
private fun ContentSuggestionCard(
    content: Content,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val imageRequest = remember(content.id, content.coverUrl) {
        ImageRequest.Builder(context)
            .data(content.coverUrl)
            .crossfade(true)
            .apply { mangaExtra(content) }
            .build()
    }

    Surface(
        modifier = Modifier
            .width(SearchSuggestionCardWidth)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(SearchSuggestionCardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
        ),
    ) {
        Column {
            AsyncImage(
                model = imageRequest,
                contentDescription = content.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = content.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = sourceTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LocalEntitySuggestionCard(
    suggestion: LocalEntitySuggestion,
    onClick: () -> Unit,
) {
    val representative = suggestion.representative
    val context = LocalContext.current
    val sourceTitle = rememberResolvedSourceTitle(representative.source)
    val imageRequest = remember(representative.id, representative.coverUrl) {
        ImageRequest.Builder(context)
            .data(representative.coverUrl)
            .crossfade(true)
            .apply { mangaExtra(representative) }
            .build()
    }

    Surface(
        modifier = Modifier
            .width(SearchSuggestionCardWidth)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(SearchSuggestionCardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
        ),
    ) {
        Column {
            AsyncImage(
                model = imageRequest,
                contentDescription = representative.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = representative.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = sourceTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.search_local_entity_suggestion_meta,
                        suggestion.projectionCount,
                        suggestion.sourceCount,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
