package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuDivider
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSourceDisplayStrings
import org.skepsun.kototoro.details.ui.model.DetailsSourceRole
import org.skepsun.kototoro.details.ui.model.EntityChapterSourceInfo
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.main.ui.compose.SearchFilterSheet
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSourceSheet(
    currentOptions: List<DetailsSourceOption>,
    selectedOption: DetailsSourceOption?,
    searchServices: List<ScrobblerService>,
    authorizedServices: Set<ScrobblerService>,
    searchQuery: String,
    searchSections: List<org.skepsun.kototoro.details.ui.MetadataSearchSectionUiState>,
    isLoading: Boolean,
    hasSearched: Boolean,
    currentContent: Content?,
    unavailableText: String,
    linkedTrackingItems: List<LinkedTrackingItemUiModel> = emptyList(),
    scrobblingStatuses: Array<String>,
    onDismissRequest: () -> Unit,
    onSelectOption: (DetailsSourceOption) -> Unit,
    onRemoveOption: (DetailsSourceOption) -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBindResult: (TrackingSiteItem) -> Unit,
    onOpenResult: (TrackingSiteItem) -> Unit,
    onOpenLinkedTracking: (LinkedTrackingItemUiModel) -> Unit = {},
    onUpdateLinkedTrackingStatus: (LinkedTrackingItemUiModel, ScrobblingStatus) -> Unit = { _, _ -> },
) {
    var pendingBindTarget by remember { mutableStateOf<TrackingSiteItem?>(null) }
    val visibleSections = remember(searchServices, searchSections) {
        if (searchSections.isNotEmpty()) {
            searchSections
        } else {
            searchServices.map { service ->
                org.skepsun.kototoro.details.ui.MetadataSearchSectionUiState(service = service)
            }
        }
    }
    val context = LocalContext.current
        DetailsSourceOverlayDialog(
            onDismissRequest = onDismissRequest,
        ) { panelDragModifier ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = panelDragModifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.details_entity_metadata),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.details_entity_metadata_sheet_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (currentOptions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(
                            items = currentOptions,
                            key = { index, option -> "${option.key}:$index" },
                        ) { _, option ->
                                val linked = option.trackingService?.let { svc ->
                                    linkedTrackingItems.firstOrNull { it.service == svc && it.remoteId == option.remoteId }
                                }
                            var showMenu by remember(option.key) { mutableStateOf(false) }
                            var menuAnchorBounds by remember(option.key) { mutableStateOf<Rect?>(null) }
                            Box(
                                modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
                            ) {
                                SourceOptionCard(
                                    displayModel = option.resolveDisplayModel(
                                        role = DetailsSourceRole.ENTITY_METADATA,
                                        currentContent = currentContent,
                                        linkedTrackingItem = linked,
                                        strings = DetailsSourceDisplayStrings(
                                            unavailableText = unavailableText,
                                            metadataBindingLabel = stringResource(R.string.details_entity_metadata_binding),
                                            currentProjectionLabel = stringResource(R.string.details_current_projection),
                                            switchableProjectionLabel = stringResource(R.string.details_switchable_projection),
                                        ),
                                        isSelected = option == selectedOption || option.isSelected,
                                    ),
                                    scrobblingStatuses = scrobblingStatuses,
                                    onTrackingStatusClick = onUpdateLinkedTrackingStatus,
                                    onClick = {
                                        onDismissRequest()
                                        onSelectOption(option)
                                    },
                                    onLongClick = {
                                        if (option.trackingService != null && option.remoteId != null) {
                                            showMenu = true
                                        }
                                    },
                                )
                                GlassDropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    // MetadataSourceSheet is rendered in its own Dialog window. The
                                    // app-level root overlay would be behind that window and the
                                    // menu would be occluded by the sheet's panel.
                                    useRootOverlay = false,
                                    anchorBounds = menuAnchorBounds,
                                ) {
                                    CompactDropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_remove_metadata_binding)) },
                                        onClick = {
                                            showMenu = false
                                            onRemoveOption(option)
                                        },
                                    )
                                }
                            }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    SourceSearchField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        onSearch = onSearch,
                    )
                    searchServices
                        .filter { it !in authorizedServices }
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            Text(
                                text = stringResource(R.string.details_metadata_source_login_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                }
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                when {
                    visibleSections.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = stringResource(R.string.nothing_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                                itemsIndexed(
                                    items = visibleSections,
                                    key = { index, section -> "metadata_section:${section.service.id}:$index" },
                                ) { _, section ->
                                MetadataSearchSection(
                                    section = section,
                                    isAuthorized = section.service in authorizedServices,
                                        hasSearched = hasSearched,
                                        onItemClick = { item ->
                                            onOpenResult(item)
                                        },
                                    onBindClick = { item -> pendingBindTarget = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pendingBindTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingBindTarget = null },
            title = { Text(stringResource(R.string.details_metadata_source)) },
            text = {
                Text(
                    stringResource(
                        R.string.migrate_confirmation,
                        currentContent?.title.orEmpty(),
                        currentContent?.source?.getTitle(context).orEmpty(),
                        target.title,
                        stringResource(target.service.titleResId),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBindTarget = null
                        onDismissRequest()
                        onBindResult(target)
                    },
                ) {
                    Text(stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBindTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun ReadingSourceSheet(
    currentOptions: List<DetailsSourceOption>,
    selectedOption: DetailsSourceOption?,
    searchSources: List<ContentSourceInfo>,
    searchQuery: String,
    searchSections: List<org.skepsun.kototoro.details.ui.ReadingSearchSectionUiState>,
    isLoading: Boolean,
    hasSearched: Boolean,
    scopeFilterUiState: org.skepsun.kototoro.details.ui.ReadingSearchScopeFilterUiState,
    languagePresets: List<SourcePreset>,
    activeLanguagePresetId: Long,
    currentContent: Content?,
    entityChapterSourceInfo: EntityChapterSourceInfo?,
    unavailableText: String,
    label: String,
    onDismissRequest: () -> Unit,
    onSelectOption: (DetailsSourceOption) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLanguagePresetSelected: (Long) -> Unit,
    onManageLanguagePresets: () -> Unit,
    onSourceTypeToggle: (org.skepsun.kototoro.core.jsonsource.SourceType) -> Unit,
    onContentKindToggle: (org.skepsun.kototoro.search.domain.SearchContentKind) -> Unit,
    onPinnedOnlyChange: (Boolean) -> Unit,
    onHideEmptyChange: (Boolean) -> Unit,
    onTemporaryOpenResult: (Content) -> Unit,
    onMigrateResult: (Content) -> Unit,
    onDeleteProjection: (DetailsSourceOption) -> Unit,
    onActivateProjection: (DetailsSourceOption) -> Unit,
) {
    val context = LocalContext.current
    var pendingMigrationTarget by remember { mutableStateOf<Content?>(null) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    val visibleSections = remember(searchSources, searchSections, hasSearched, isLoading) {
        when {
            searchSections.isNotEmpty() -> searchSections
            hasSearched || isLoading -> emptyList()
            else -> searchSources.map { source ->
                org.skepsun.kototoro.details.ui.ReadingSearchSectionUiState(source = source)
            }
        }
    }
    val isInitialSearchState = remember(searchSections, hasSearched, isLoading) {
        searchSections.isEmpty() && !hasSearched && !isLoading
    }
    val resultSections = remember(visibleSections, isInitialSearchState) {
        if (isInitialSearchState) {
            visibleSections
        } else {
            visibleSections.filter { section ->
                !section.isPending && (section.items.isNotEmpty() || section.isLoading)
            }
        }
    }
    val emptySections = remember(visibleSections, isInitialSearchState, scopeFilterUiState.hideEmpty) {
        if (scopeFilterUiState.hideEmpty || isInitialSearchState) {
            emptyList()
        } else {
            visibleSections.filter { !it.isPending && !it.isLoading && it.errorMessage == null && it.items.isEmpty() }
        }
    }
    val errorSections = remember(visibleSections, isInitialSearchState, scopeFilterUiState.hideEmpty) {
        if (scopeFilterUiState.hideEmpty || isInitialSearchState) {
            emptyList()
        } else {
            visibleSections.filter { !it.isPending && !it.isLoading && it.errorMessage != null && it.items.isEmpty() }
        }
    }
    var showEmptySources by rememberSaveable(emptySections.map { it.source.mangaSource.name }) {
        mutableStateOf(false)
    }
    var showUnavailableSources by rememberSaveable(errorSections.map { it.source.mangaSource.name }) {
        mutableStateOf(false)
    }
        DetailsSourceOverlayDialog(
            onDismissRequest = onDismissRequest,
        ) { panelDragModifier ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = panelDragModifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.details_current_projection),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = buildString {
                            append(stringResource(R.string.details_current_projection_sheet_hint, label))
                            entityChapterSourceInfo
                                ?.projectionCount
                                ?.takeIf { it > 0 }
                                ?.let { count ->
                                    append(' ')
                                    append(
                                        stringResource(
                                            R.string.entity_graph_chapter_source_projection_count,
                                            count,
                                        ),
                                    )
                                }
                            if (
                                entityChapterSourceInfo?.currentReadingProjectionMangaId != null &&
                                entityChapterSourceInfo.currentReadingProjectionMangaId != entityChapterSourceInfo.activeProjectionMangaId
                            ) {
                                append(' ')
                                append(stringResource(R.string.details_temporary_projection_sheet_hint))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (currentOptions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(
                            items = currentOptions,
                            key = { index, option -> "${option.key}:$index" },
                        ) { _, option ->
                            val isTemporaryProjection =
                                option.targetMangaId != null &&
                                    entityChapterSourceInfo?.currentReadingProjectionMangaId == option.targetMangaId &&
                                    entityChapterSourceInfo.activeProjectionMangaId != option.targetMangaId
                            val isActiveProjection =
                                option.targetMangaId != null &&
                                    entityChapterSourceInfo?.activeProjectionMangaId == option.targetMangaId
                            var showMenu by remember(option.key) { mutableStateOf(false) }
                            var menuAnchorBounds by remember(option.key) { mutableStateOf<Rect?>(null) }
                            Box(
                                modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
                            ) {
                                SourceOptionCard(
                                    displayModel = option.resolveDisplayModel(
                                        role = DetailsSourceRole.READING_PROJECTION,
                                        currentContent = currentContent,
                                        linkedTrackingItem = null,
                                        strings = DetailsSourceDisplayStrings(
                                            unavailableText = unavailableText,
                                            metadataBindingLabel = stringResource(R.string.details_entity_metadata_binding),
                                            currentProjectionLabel = label,
                                            switchableProjectionLabel = stringResource(R.string.details_switchable_projection),
                                        ),
                                        isSelected = option == selectedOption || option.isSelected,
                                    ).copy(
                                        badgeText = when {
                                            isActiveProjection -> stringResource(R.string.details_active_projection_badge)
                                            isTemporaryProjection -> stringResource(R.string.details_temporary_projection_badge)
                                            else -> null
                                        },
                                        isActiveProjection = isActiveProjection,
                                    ),
                                    scrobblingStatuses = emptyArray(),
                                    onClick = {
                                        onDismissRequest()
                                        onSelectOption(option)
                                        },
                                    onLongClick = {
                                        if (option.targetMangaId != null) {
                                            showMenu = true
                                        }
                                    },
                                )
                                GlassDropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    // ReadingSourceSheet is rendered in its own Dialog window. The
                                    // app-level root overlay would be behind that window.
                                    useRootOverlay = false,
                                    anchorBounds = menuAnchorBounds,
                                ) {
                                    CompactDropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_remove_projection)) },
                                        onClick = {
                                            showMenu = false
                                            onDeleteProjection(option)
                                        },
                                    )
                                    CompactDropdownMenuDivider()
                                    CompactDropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_activate_projection)) },
                                        onClick = {
                                            showMenu = false
                                            onActivateProjection(option)
                                            onDismissRequest()
                                        },
                                    )
                                }
                            }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceSearchField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            onSearch = onSearch,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = onSearch,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        FilledTonalButton(
                            onClick = { showFilterSheet = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_filter_menu),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            if (scopeFilterUiState.appliedFilterCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(scopeFilterUiState.appliedFilterCount.toString())
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                when {
                    resultSections.isEmpty() && emptySections.isEmpty() && errorSections.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (isLoading) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = stringResource(R.string.search),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            } else {
                                Text(
                                    text = if (hasSearched) {
                                        stringResource(R.string.details_source_search_no_visible_results)
                                    } else {
                                        stringResource(R.string.nothing_found)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(
                                items = resultSections,
                                key = { index, section -> "reading_section:${section.source.mangaSource.name}:$index" },
                            ) { _, section ->
                                ReadingSearchSection(
                                    section = section,
                                    hasSearched = hasSearched,
                                    onItemClick = { item ->
                                        onTemporaryOpenResult(item)
                                    },
                                    onMigrateClick = { item ->
                                        pendingMigrationTarget = item
                                    },
                                )
                            }
                            if (emptySections.isNotEmpty()) {
                                item(key = "reading_section_empty_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showEmptySources = !showEmptySources },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.details_source_search_no_results_group),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.graphicsLayer {
                                                rotationZ = if (showEmptySources) 180f else 0f
                                            },
                                        )
                                    }
                                }
                                if (showEmptySources) {
                                    itemsIndexed(
                                        items = emptySections,
                                        key = { index, section -> "reading_section_empty:${section.source.mangaSource.name}:$index" },
                                    ) { _, section ->
                                        ReadingSearchSection(
                                            section = section,
                                            hasSearched = true,
                                            onItemClick = { item ->
                                                onTemporaryOpenResult(item)
                                            },
                                            onMigrateClick = { item ->
                                                pendingMigrationTarget = item
                                            },
                                        )
                                    }
                                }
                            }
                            if (errorSections.isNotEmpty()) {
                                item(key = "reading_section_errors_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showUnavailableSources = !showUnavailableSources },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.unavailable),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.graphicsLayer {
                                                rotationZ = if (showUnavailableSources) 180f else 0f
                                            },
                                        )
                                    }
                                }
                                if (showUnavailableSources) {
                                    itemsIndexed(
                                        items = errorSections,
                                        key = { index, section -> "reading_section_error:${section.source.mangaSource.name}:$index" },
                                    ) { _, section ->
                                        ReadingSearchSection(
                                            section = section,
                                            hasSearched = hasSearched,
                                            onItemClick = { item ->
                                                onTemporaryOpenResult(item)
                                            },
                                            onMigrateClick = { item ->
                                                pendingMigrationTarget = item
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingMigrationTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingMigrationTarget = null },
            title = { Text(stringResource(R.string.manga_migration)) },
            text = {
                Text(
                    stringResource(
                        R.string.migrate_confirmation,
                        currentContent?.title.orEmpty(),
                        currentContent?.source?.getTitle(context).orEmpty(),
                        target.title,
                        target.source.getTitle(context),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMigrationTarget = null
                        onDismissRequest()
                        onMigrateResult(target)
                    },
                ) {
                    Text(stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMigrationTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showFilterSheet) {
        SearchFilterSheet(
            sourceTypes = scopeFilterUiState.sourceTypes,
            contentKinds = scopeFilterUiState.contentKinds,
            pinnedOnly = scopeFilterUiState.pinnedOnly,
            hideEmpty = scopeFilterUiState.hideEmpty,
            languagePresets = languagePresets,
            activeLanguagePresetId = activeLanguagePresetId,
            onSourceTypeToggle = onSourceTypeToggle,
            onContentKindToggle = onContentKindToggle,
            onPinnedOnlyChange = onPinnedOnlyChange,
            onHideEmptyChange = onHideEmptyChange,
            onLanguagePresetSelected = onLanguagePresetSelected,
            onManageLanguagePresets = onManageLanguagePresets,
            onDismissRequest = { showFilterSheet = false },
        )
    }
}

