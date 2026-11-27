package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.buttons.FilledTonalButton
import org.skepsun.kototoro.core.ui.buttons.IconButton
import org.skepsun.kototoro.core.ui.buttons.TextButton
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.parsers.model.ContentSource

private enum class DiscoveryFeedFilterMode {
    DEFAULT,
    SAVED_FILTER,
    KEYWORD,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryFeedManageRowsSheet(
    rows: List<DiscoveryFeedRow>,
    sourcesByName: Map<String, ContentSource>,
    onDismissRequest: () -> Unit,
    onAddRowClick: () -> Unit,
    onEditRowClick: (DiscoveryFeedRow) -> Unit,
    onMoveRowUp: (String) -> Unit,
    onMoveRowDown: (String) -> Unit,
    onToggleRowHidden: (String) -> Unit,
    onDeleteRow: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val savedFilterBadge = stringResource(R.string.discovery_feed_saved_filter_badge)

    TvModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.discovery_feed_manage_rows),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FilledTonalButton(
                    onClick = onAddRowClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.discovery_feed_add_row))
                }
            }

            if (rows.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = stringResource(R.string.discovery_feed_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, row -> row.id },
                    ) { index, row ->
                        val source = sourcesByName[row.sourceName]
                        val sourceTitle = remember(source, row.sourceName, context) {
                            source?.getTitle(context) ?: row.sourceName
                        }
                        val subtitle = remember(sourceTitle, row, savedFilterBadge) {
                            buildDiscoveryFeedRowSubtitle(
                                sourceTitle = sourceTitle,
                                row = row,
                                savedFilterBadge = savedFilterBadge,
                            ) ?: sourceTitle
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (row.isHidden) 0.62f else 1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (source != null) {
                                        ContentSourceIcon(
                                            source = source,
                                            modifier = Modifier.size(22.dp),
                                            contentDescription = null,
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = row.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (row.isHidden) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.discovery_feed_row_hidden_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { onMoveRowUp(row.id) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.move_up),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onMoveRowDown(row.id) },
                                        enabled = index < rows.lastIndex,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.move_down),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onToggleRowHidden(row.id) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if (row.isHidden) R.drawable.ic_eye else R.drawable.ic_eye_off,
                                            ),
                                            contentDescription = stringResource(
                                                if (row.isHidden) R.string.show else R.string.hide,
                                            ),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onEditRowClick(row) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_edit),
                                            contentDescription = stringResource(R.string.edit),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRow(row.id) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_delete),
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryFeedRowEditorSheet(
    editingRow: DiscoveryFeedRow?,
    availableSources: List<ContentSource>,
    loadSavedFilters: suspend (ContentSource) -> List<PersistableFilter>,
    onDismissRequest: () -> Unit,
    onSave: (
        sourceName: String,
        displayName: String,
        savedFilterId: Int?,
        query: String?,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val initialSource = remember(editingRow, availableSources) {
        if (editingRow != null) {
            availableSources.firstOrNull { it.name == editingRow.sourceName }
        } else {
            availableSources.firstOrNull()
        }
    }
    var selectedSourceName by remember(editingRow?.id) {
        mutableStateOf(editingRow?.sourceName ?: initialSource?.name.orEmpty())
    }
    val selectedSource = remember(selectedSourceName, availableSources) {
        availableSources.firstOrNull { it.name == selectedSourceName }
    }
    var isSourcePickerExpanded by remember(editingRow?.id) {
        mutableStateOf(editingRow == null && selectedSource == null)
    }
    var sourceSearchQuery by remember(editingRow?.id) { mutableStateOf("") }

    var filterMode by remember(editingRow?.id) {
        mutableStateOf(
            when {
                editingRow?.savedFilterId != null -> DiscoveryFeedFilterMode.SAVED_FILTER
                !editingRow?.normalizedQuery.isNullOrBlank() -> DiscoveryFeedFilterMode.KEYWORD
                else -> DiscoveryFeedFilterMode.DEFAULT
            },
        )
    }
    var savedFilters by remember(editingRow?.id) { mutableStateOf<List<PersistableFilter>>(emptyList()) }
    var isLoadingSavedFilters by remember(editingRow?.id) { mutableStateOf(false) }
    var selectedSavedFilterId by remember(editingRow?.id) { mutableStateOf(editingRow?.savedFilterId) }
    var keywordQuery by remember(editingRow?.id) { mutableStateOf(editingRow?.normalizedQuery.orEmpty()) }
    var hasUserEditedName by remember(editingRow?.id) { mutableStateOf(editingRow != null) }
    var displayName by remember(editingRow?.id) {
        mutableStateOf(
            editingRow?.displayName
                ?: initialSource?.getTitle(context).orEmpty(),
        )
    }

    LaunchedEffect(selectedSource) {
        if (selectedSource == null) {
            savedFilters = emptyList()
            isLoadingSavedFilters = false
            return@LaunchedEffect
        }
        isLoadingSavedFilters = true
        val loaded = runCatching { loadSavedFilters(selectedSource) }.getOrDefault(emptyList())
        savedFilters = loaded
        isLoadingSavedFilters = false
        if (selectedSavedFilterId != null && loaded.none { it.id == selectedSavedFilterId }) {
            selectedSavedFilterId = loaded.firstOrNull()?.id
        } else if (filterMode == DiscoveryFeedFilterMode.SAVED_FILTER && selectedSavedFilterId == null) {
            selectedSavedFilterId = loaded.firstOrNull()?.id
        }
    }

    val selectedSavedFilterName = remember(savedFilters, selectedSavedFilterId) {
        savedFilters.firstOrNull { it.id == selectedSavedFilterId }?.name
    }
    val selectedSourceTitle = remember(selectedSource, selectedSourceName, context) {
        selectedSource?.getTitle(context) ?: selectedSourceName
    }

    LaunchedEffect(selectedSourceTitle, filterMode, selectedSavedFilterName, keywordQuery, hasUserEditedName) {
        if (!hasUserEditedName) {
            displayName = suggestDiscoveryFeedDisplayName(
                sourceTitle = selectedSourceTitle,
                savedFilterName = if (filterMode == DiscoveryFeedFilterMode.SAVED_FILTER) selectedSavedFilterName else null,
                query = if (filterMode == DiscoveryFeedFilterMode.KEYWORD) keywordQuery else null,
            )
        }
    }

    val filteredSources = remember(availableSources, sourceSearchQuery, context) {
        val needle = sourceSearchQuery.trim()
        if (needle.isEmpty()) {
            availableSources
        } else {
            availableSources.filter { source ->
                source.getTitle(context).contains(needle, ignoreCase = true) ||
                    source.name.contains(needle, ignoreCase = true)
            }
        }
    }

    val canSave = selectedSourceName.isNotBlank() && when (filterMode) {
        DiscoveryFeedFilterMode.DEFAULT -> true
        DiscoveryFeedFilterMode.SAVED_FILTER -> selectedSavedFilterId != null
        DiscoveryFeedFilterMode.KEYWORD -> keywordQuery.isNotBlank()
    }

    TvModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (editingRow == null) R.string.discovery_feed_add_row else R.string.discovery_feed_edit_row,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 1. Source selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.discovery_feed_source_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { isSourcePickerExpanded = !isSourcePickerExpanded },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedSource != null) {
                            ContentSourceIcon(
                                source = selectedSource,
                                modifier = Modifier.size(22.dp),
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = selectedSourceTitle.ifBlank {
                                stringResource(R.string.discovery_feed_select_source)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedSourceTitle.isNotBlank()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (isSourcePickerExpanded) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isSourcePickerExpanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = sourceSearchQuery,
                                onValueChange = { sourceSearchQuery = it },
                                placeholder = {
                                    Text(stringResource(R.string.discovery_feed_search_sources_hint))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (filteredSources.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.discovery_feed_no_sources_available),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    items(
                                        items = filteredSources,
                                        key = { it.name },
                                    ) { source ->
                                        val isChosen = source.name == selectedSourceName
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    selectedSourceName = source.name
                                                    selectedSavedFilterId = null
                                                    isSourcePickerExpanded = false
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            ContentSourceIcon(
                                                source = source,
                                                modifier = Modifier.size(20.dp),
                                                contentDescription = null,
                                            )
                                            Text(
                                                text = source.getTitle(context),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isChosen) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (isChosen) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
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

            // 2. Content filter mode (Default / Saved filter / Keyword)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.discovery_feed_filter_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filterMode == DiscoveryFeedFilterMode.DEFAULT,
                        onClick = { filterMode = DiscoveryFeedFilterMode.DEFAULT },
                        label = { Text(stringResource(R.string.discovery_feed_mode_default)) },
                    )
                    FilterChip(
                        selected = filterMode == DiscoveryFeedFilterMode.SAVED_FILTER,
                        onClick = { filterMode = DiscoveryFeedFilterMode.SAVED_FILTER },
                        label = { Text(stringResource(R.string.discovery_feed_mode_saved_filter)) },
                    )
                    FilterChip(
                        selected = filterMode == DiscoveryFeedFilterMode.KEYWORD,
                        onClick = { filterMode = DiscoveryFeedFilterMode.KEYWORD },
                        label = { Text(stringResource(R.string.discovery_feed_mode_keyword)) },
                    )
                }

                when (filterMode) {
                    DiscoveryFeedFilterMode.DEFAULT -> {
                        Text(
                            text = stringResource(R.string.discovery_feed_mode_default_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DiscoveryFeedFilterMode.SAVED_FILTER -> {
                        if (isLoadingSavedFilters) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else if (savedFilters.isEmpty()) {
                            Text(
                                text = stringResource(R.string.discovery_feed_no_saved_filters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                savedFilters.forEach { filter ->
                                    FilterChip(
                                        selected = selectedSavedFilterId == filter.id,
                                        onClick = { selectedSavedFilterId = filter.id },
                                        label = { Text(filter.name) },
                                    )
                                }
                            }
                        }
                    }

                    DiscoveryFeedFilterMode.KEYWORD -> {
                        OutlinedTextField(
                            value = keywordQuery,
                            onValueChange = { keywordQuery = it },
                            label = { Text(stringResource(R.string.discovery_feed_keyword_label)) },
                            placeholder = { Text(stringResource(R.string.discovery_feed_keyword_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 3. Display Name
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    hasUserEditedName = true
                    displayName = it
                },
                label = { Text(stringResource(R.string.discovery_feed_display_name_label)) },
                placeholder = { Text(selectedSourceTitle) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        val effectiveFilterId = if (filterMode == DiscoveryFeedFilterMode.SAVED_FILTER) {
                            selectedSavedFilterId
                        } else {
                            null
                        }
                        val effectiveQuery = if (filterMode == DiscoveryFeedFilterMode.KEYWORD) {
                            keywordQuery.trim().takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                        val effectiveDisplayName = displayName.trim().ifEmpty {
                            suggestDiscoveryFeedDisplayName(
                                sourceTitle = selectedSourceTitle,
                                savedFilterName = if (filterMode == DiscoveryFeedFilterMode.SAVED_FILTER) {
                                    selectedSavedFilterName
                                } else {
                                    null
                                },
                                query = effectiveQuery,
                            )
                        }
                        onSave(
                            selectedSourceName,
                            effectiveDisplayName,
                            effectiveFilterId,
                            effectiveQuery,
                        )
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
