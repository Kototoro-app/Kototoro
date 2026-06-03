package org.skepsun.kototoro.favourites.ui.migration.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.MigrationItem
import org.skepsun.kototoro.favourites.domain.MigrationStatus
import org.skepsun.kototoro.favourites.ui.migration.SourceMigrationViewModel
import org.skepsun.kototoro.parsers.model.ContentSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SourceMigrationPanel(
    onDismiss: () -> Unit,
    viewModel: SourceMigrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = { if (!uiState.isExecuting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = { if (!uiState.isExecuting) onDismiss() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.88f)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                style = GlassDefaults.prominentStyle(),
                dialogSurface = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.source_migration_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { if (!uiState.isExecuting) onDismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    if (uiState.isExecuting || uiState.migrationProgress?.isFinished == true) {
                        MigrationProgressSection(uiState)
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            SourceFilterSection(
                                label = stringResource(R.string.source_migration_from_label),
                                sources = uiState.fromFilteredSources,
                                selectedSource = uiState.selectedFromSource,
                                onSourceSelected = { viewModel.selectFromSource(it) },
                                contentTypeFilter = uiState.fromContentTypeFilter,
                                onContentTypeToggle = { viewModel.toggleFromContentType(it) },
                                sourceTagFilter = uiState.fromSourceTagFilter,
                                onSourceTagToggle = { viewModel.toggleFromSourceTag(it) },
                                enabled = !uiState.isExecuting,
                            )
                        }

                        item {
                            SourceFilterSection(
                                label = stringResource(R.string.source_migration_to_label),
                                sources = uiState.toFilteredSources,
                                selectedSource = uiState.selectedToSource,
                                onSourceSelected = { viewModel.selectToSource(it) },
                                contentTypeFilter = uiState.toContentTypeFilter,
                                onContentTypeToggle = { viewModel.toggleToContentType(it) },
                                sourceTagFilter = uiState.toSourceTagFilter,
                                onSourceTagToggle = { viewModel.toggleToSourceTag(it) },
                                enabled = !uiState.isExecuting,
                            )
                        }

                        // Concurrency + action row
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ConcurrencyDropdown(
                                    value = uiState.concurrency,
                                    onSelect = { viewModel.setConcurrency(it) },
                                    enabled = !uiState.isExecuting,
                                    modifier = Modifier.weight(0.4f),
                                )

                                val canStart = uiState.selectedFromSource != null &&
                                    uiState.selectedToSource != null &&
                                    uiState.selectedFromSource?.name != uiState.selectedToSource?.name &&
                                    !uiState.isExecuting

                                Button(
                                    onClick = {
                                        if (uiState.isExecuting) viewModel.cancelMigration()
                                        else viewModel.startMigration()
                                    },
                                    modifier = Modifier.weight(0.6f),
                                    enabled = canStart || uiState.isExecuting,
                                    colors = if (uiState.isExecuting) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                ) {
                                    if (uiState.isExecuting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onError,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.source_migration_cancel))
                                    } else {
                                        Text(stringResource(R.string.source_migration_start))
                                    }
                                }
                            }

                            val sameSource = uiState.selectedFromSource != null &&
                                uiState.selectedToSource != null &&
                                uiState.selectedFromSource?.name == uiState.selectedToSource?.name
                            if (sameSource) {
                                Text(
                                    text = stringResource(R.string.migration_same_source_error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceFilterSection(
    label: String,
    sources: List<ContentSource>,
    selectedSource: ContentSource?,
    onSourceSelected: (ContentSource?) -> Unit,
    contentTypeFilter: Set<BrowseGroupTab>,
    onContentTypeToggle: (BrowseGroupTab) -> Unit,
    sourceTagFilter: Set<SourceTag>,
    onSourceTagToggle: (SourceTag) -> Unit,
    enabled: Boolean,
) {
    var showSelector by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled && sources.isNotEmpty()) Modifier.clickable { showSelector = true } else Modifier),
        ) {
            OutlinedTextField(
                value = selectedSource?.getTitle(androidx.compose.ui.platform.LocalContext.current) ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false, // disable interactions, let Box handle clicks
                placeholder = {
                    Text(
                        if (sources.isEmpty()) stringResource(R.string.migration_no_sources)
                        else "Select source...",
                    )
                },
                trailingIcon = {
                    if (selectedSource != null && enabled) {
                        IconButton(onClick = { onSourceSelected(null) }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // Filter dropdowns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterDropdown(
                label = stringResource(R.string.content_type_filter),
                summary = contentTypeFilter.takeIf { it.isNotEmpty() }
                    ?.joinToString { contentTypeLabel(it) }
                    ?: "All",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                listOf(BrowseGroupTab.Content, BrowseGroupTab.Novel, BrowseGroupTab.Video).forEach { tab ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tab.titleRes)) },
                        onClick = { onContentTypeToggle(tab) },
                        trailingIcon = if (tab in contentTypeFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }

            FilterDropdown(
                label = stringResource(R.string.source_type_filter),
                summary = sourceTagFilter.takeIf { it.isNotEmpty() }
                    ?.joinToString { it.id }
                    ?: "All",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                SourceTag.quickFilterEntries.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tag.titleRes)) },
                        onClick = { onSourceTagToggle(tag) },
                        trailingIcon = if (tag in sourceTagFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
        }
    }

    if (showSelector) {
        SourceSelectorDialog(
            title = label,
            sources = sources,
            onSelect = {
                onSourceSelected(it)
                showSelector = false
            },
            onDismiss = { showSelector = false },
        )
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    summary: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    menuItems: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 4.dp),
        ) {
            menuItems()
        }
    }
}

@Composable
private fun ConcurrencyDropdown(
    value: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(1, 2, 3, 5, 8, 10)

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    text = "Concurrency",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 4.dp),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text("$opt") },
                    onClick = { onSelect(opt); expanded = false },
                    trailingIcon = if (opt == value) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun SourceSelectorDialog(
    title: String,
    sources: List<ContentSource>,
    onSelect: (ContentSource) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(query) {
        delay(180)
        debouncedQuery = query
    }

    val sourceEntries = remember(sources, context) {
        sources.map { source ->
            val displayTitle = source.getTitle(context)
            SourceSearchEntry(
                source = source,
                displayTitle = displayTitle,
                normalizedName = source.name.lowercase(Locale.ROOT),
                normalizedTitle = displayTitle.lowercase(Locale.ROOT),
            )
        }
    }
    val normalizedQuery = remember(debouncedQuery) {
        debouncedQuery.trim().lowercase(Locale.ROOT)
    }
    val filtered by produceState(
        initialValue = sourceEntries,
        normalizedQuery,
        sourceEntries,
    ) {
        value = withContext(Dispatchers.Default) {
            if (normalizedQuery.isBlank()) {
                sourceEntries
            } else {
                sourceEntries.filter { entry ->
                    entry.normalizedName.contains(normalizedQuery) ||
                        entry.normalizedTitle.contains(normalizedQuery)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(28.dp),
            style = GlassDefaults.prominentStyle(),
            dialogSurface = true,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter sources…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.source.name },
                        contentType = { "source_option" },
                    ) { entry ->
                        androidx.compose.material3.TextButton(
                            onClick = { onSelect(entry.source) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = entry.displayTitle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("No matching sources", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SourceSearchEntry(
    val source: ContentSource,
    val displayTitle: String,
    val normalizedName: String,
    val normalizedTitle: String,
)

private fun contentTypeLabel(tab: BrowseGroupTab): String = when (tab) {
    BrowseGroupTab.Content -> "Manga"
    BrowseGroupTab.Novel -> "Novel"
    BrowseGroupTab.Video -> "Video"
    else -> tab.id
}

@Composable
private fun MigrationProgressSection(uiState: org.skepsun.kototoro.favourites.ui.migration.MigrationUiState) {
    val progress = uiState.migrationProgress ?: return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = (progress.completed + progress.failed + progress.notFound).toFloat() / progress.total,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (progress.currentItem != null) {
            Text(
                text = stringResource(R.string.migration_status_active, progress.currentItem.title),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (progress.isFinished) {
            Text(
                text = stringResource(
                    R.string.migration_completed_summary,
                    progress.completed,
                    progress.failed,
                    progress.notFound,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MigrationItemRow(item: MigrationItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.status) {
            MigrationStatus.SUCCESS -> Icon(
                Icons.Default.Check, null, Modifier.size(14.dp), tint = Color(0xFF4CAF50),
            )
            MigrationStatus.NOT_FOUND -> Icon(
                Icons.Default.Warning, null, Modifier.size(14.dp), tint = Color(0xFFFFC107),
            )
            MigrationStatus.ERROR -> Icon(
                Icons.Default.Error, null, Modifier.size(14.dp), tint = Color(0xFFF44336),
            )
            MigrationStatus.SEARCHING,
            MigrationStatus.MIGRATING -> CircularProgressIndicator(
                Modifier.size(14.dp), strokeWidth = 2.dp,
            )
            MigrationStatus.PENDING -> Spacer(Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text = item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        if (item.errorMessage != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = item.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}
