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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.skepsun.kototoro.core.ui.buttons.FilledTonalButton
import org.skepsun.kototoro.core.ui.compose.TrackingMetadataContent
import org.skepsun.kototoro.core.ui.compose.TrackingMetadataSlot
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.progress.CircularProgressIndicator
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsSourceDisplayStrings
import org.skepsun.kototoro.details.ui.model.DetailsSourceRole
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.main.ui.compose.SearchFilterSheet
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem

@Composable
fun MetadataSourceSheet(
    initialServiceId: Int?,
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
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onSelectOption: (DetailsSourceOption) -> Unit,
    onRemoveOption: (DetailsSourceOption) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (ScrobblerService) -> Unit,
    onBindResult: (TrackingSiteItem) -> Unit,
    onOpenResult: (TrackingSiteItem) -> Unit,
    onSaveTracking: (LinkedTrackingItemUiModel, Int, Float, ScrobblingStatus?) -> Unit,
) {
    var activeKey by rememberSaveable(initialServiceId) {
        mutableStateOf(initialServiceId?.toString() ?: selectedOption?.trackingService?.id?.toString() ?: "original")
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var pendingRemoveKey by remember { mutableStateOf<String?>(null) }
    val original = currentOptions.firstOrNull { it.trackingService == null }
    val services = (searchServices + currentOptions.mapNotNull { it.trackingService } +
        linkedTrackingItems.map { it.service }).distinct()
        .sortedBy { if (it in authorizedServices) 0 else 1 }
    val optionsByKey = buildMap {
        if (original != null) put("original", original)
        services.forEach { service ->
            val linked = linkedTrackingItems.firstOrNull { it.service == service }
            val option = currentOptions.firstOrNull { it.trackingService == service &&
                (linked == null || it.remoteId == linked.remoteId) }
                ?: linked?.let {
                    DetailsSourceOption(key = "${service.id}:${it.remoteId}", trackingService = service,
                        remoteId = it.remoteId, title = it.title, coverUrl = it.coverUrl, url = it.url)
                }
            if (option != null) put(service.id.toString(), option)
        }
    }
    val slots = buildList {
        if (original != null) add(TrackingMetadataSlot(
            key = "original", label = stringResource(R.string.details_tracking_original),
            title = original.title ?: currentContent?.title ?: unavailableText,
            usesMetadata = original.isSelected, isOriginal = true,
        ))
        services.forEach { service ->
            val linked = linkedTrackingItems.firstOrNull { it.service == service }
            val option = optionsByKey[service.id.toString()]
            add(TrackingMetadataSlot(
                key = service.id.toString(), label = stringResource(service.titleResId),
                title = linked?.title ?: option?.title,
                usesMetadata = option?.isSelected == true,
                authorized = service in authorizedServices,
                editable = linked?.hasScrobblingBinding == true && service in authorizedServices,
                communityScore = linked?.communityScore?.let { "%.1f".format(it) },
                status = linked?.status?.ordinal, rating = linked?.rating ?: 0f,
                progress = linked?.progress ?: 0,
            ))
        }
    }
    val activeService = services.firstOrNull { it.id.toString() == activeKey }
    fun search(service: ScrobblerService) {
        searching = true
        onSearch(service)
    }
    DetailsSourceOverlayDialog(
        onDismissRequest = onDismissRequest,
        heightFraction = when {
            searching -> 0.88f
            slots.firstOrNull { it.key == activeKey }?.editable == true -> 0.76f
            else -> 0.52f
        },
        maxWidth = 640.dp,
        floating = true,
    ) { panelDragModifier ->
        TrackingMetadataContent(
            slots = slots, selectedKey = activeKey, statuses = scrobblingStatuses.toList(),
            busy = busy, searching = searching && activeService != null,
            onSelectSlot = { slot ->
                activeKey = slot.key
                searching = false
                if (slot.title == null) services.firstOrNull { it.id.toString() == slot.key }?.let(::search)
            },
            onUseMetadata = { slot -> optionsByKey[slot.key]?.let(onSelectOption) },
            onSearch = { slot -> services.firstOrNull { it.id.toString() == slot.key }?.let(::search) },
            onRemove = { pendingRemoveKey = it.key },
            onSave = { slot, progress, rating, status ->
                linkedTrackingItems.firstOrNull { it.service.id.toString() == slot.key }?.let {
                    onSaveTracking(it, progress, rating, status?.let(ScrobblingStatus.entries::getOrNull))
                }
            },
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            headerModifier = panelDragModifier,
            searchContent = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceSearchField(value = searchQuery, onValueChange = onSearchQueryChange,
                        onSearch = { activeService?.let(::search) })
                    val section = searchSections.firstOrNull { it.service == activeService }
                    if (section != null) {
                        MetadataSearchSection(
                            section = section, isAuthorized = activeService in authorizedServices,
                            hasSearched = hasSearched, onItemClick = onOpenResult,
                            onBindClick = { item ->
                                onBindResult(item)
                                searching = false
                            },
                        )
                    } else if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            },
        )
    }
    pendingRemoveKey?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingRemoveKey = null },
            title = { Text(stringResource(R.string.details_tracking_unlink)) },
            text = { Text(stringResource(R.string.details_tracking_unlink_hint)) },
            confirmButton = {
                TvTextButton(onClick = {
                    pendingRemoveKey = null
                    optionsByKey[key]?.let(onRemoveOption)
                }) { Text(stringResource(R.string.details_tracking_unlink)) }
            },
            dismissButton = {
                TvTextButton(onClick = { pendingRemoveKey = null }) { Text(stringResource(android.R.string.cancel)) }
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
    /** The global browsing preset, used only to resolve what the panel highlights while it still follows it. */
    activeLanguagePresetId: Long,
    currentContent: Content?,
    unavailableText: String,
    label: String,
    blacklistedTagCount: Int = 0,
    onOpenGlobalTagBlacklist: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    /** Pins a preset for this panel only; null follows the global browsing preset. Never persisted. */
    onLanguagePresetSelected: (Long?) -> Unit,
    onManageLanguagePresets: () -> Unit,
    onSourceTypeToggle: (org.skepsun.kototoro.core.jsonsource.SourceType) -> Unit,
    onContentKindToggle: (org.skepsun.kototoro.search.domain.SearchContentKind) -> Unit,
    onPinnedOnlyChange: (Boolean) -> Unit,
    onHideEmptyChange: (Boolean) -> Unit,
    onTemporaryOpenResult: (Content) -> Unit,
    onMigrateResult: (Content) -> Unit,
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
                        text = label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.details_reading_source_sheet_hint, label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (currentOptions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(
                            items = currentOptions,
                            key = { index, option -> "${option.key}:$index" },
                        ) { _, option ->
                            SourceOptionCard(
                                displayModel = option.resolveDisplayModel(
                                    role = DetailsSourceRole.READING_SOURCE,
                                    currentContent = currentContent,
                                    linkedTrackingItem = null,
                                    strings = DetailsSourceDisplayStrings(
                                        unavailableText = unavailableText,
                                        metadataBindingLabel = stringResource(R.string.details_entity_metadata_binding),
                                        readingSourceLabel = label,
                                    ),
                                    isSelected = option == selectedOption || option.isSelected,
                                ),
                                scrobblingStatuses = emptyArray(),
                                onClick = onDismissRequest,
                                onLongClick = {},
                            )
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
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(999.dp),
                                addFocusTarget = false,
                            ),
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
                            modifier = Modifier.tvFocusable(
                                shape = RoundedCornerShape(999.dp),
                                addFocusTarget = false,
                            ),
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
                TvTextButton(
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
                TvTextButton(onClick = { pendingMigrationTarget = null }) {
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
            // The panel edits this work's own scope (tier 3): a null local preset follows the global browsing one.
            activeLanguagePresetId = scopeFilterUiState.languagePresetId,
            resolvedLanguagePresetId = activeLanguagePresetId,
            blacklistedTagCount = blacklistedTagCount,
            temporaryScope = true,
            onApply = { filters ->
                (filters.sourceTypes - scopeFilterUiState.sourceTypes).forEach(onSourceTypeToggle)
                (scopeFilterUiState.sourceTypes - filters.sourceTypes).forEach(onSourceTypeToggle)
                (filters.contentKinds - scopeFilterUiState.contentKinds).forEach(onContentKindToggle)
                (scopeFilterUiState.contentKinds - filters.contentKinds).forEach(onContentKindToggle)
                onPinnedOnlyChange(filters.pinnedOnly)
                onHideEmptyChange(filters.hideEmpty)
                onLanguagePresetSelected(filters.languagePresetId)
            },
            onManageLanguagePresets = onManageLanguagePresets,
            onOpenGlobalTagBlacklist = onOpenGlobalTagBlacklist,
            onDismissRequest = { showFilterSheet = false },
        )
    }
}
