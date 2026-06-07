package org.skepsun.kototoro.favourites.ui.migration.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.getStableIdentityKey
import java.util.Locale

@Composable
internal fun TrackingServiceSelectorDialog(
    services: List<ScrobblerService>,
    selectedServices: Set<ScrobblerService>,
    onToggle: (ScrobblerService) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.62f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.entity_organize_tracking_select),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(services, key = { it.id }) { service ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(service) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = service in selectedServices,
                                    onCheckedChange = { onToggle(service) },
                                )
                                Text(
                                    text = stringResource(service.titleResId),
                                    style = MaterialTheme.typography.bodyMedium,
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

internal fun SourceSelectorDialog(
    title: String,
    sources: List<ContentSource>,
    onSelect: (ContentSource) -> Unit,
    onDismiss: () -> Unit,
) {
    SourceSearchDialog(
        title = title,
        sources = sources,
        onDismiss = onDismiss,
    ) { entry ->
        androidx.compose.material3.TextButton(
            onClick = { onSelect(entry.source) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = entry.displayTitle, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun MultiSourceSelectorDialog(
    title: String,
    sources: List<ContentSource>,
    selectedSourceKeys: Set<String>,
    onToggle: (ContentSource) -> Unit,
    onDismiss: () -> Unit,
) {
    SourceSearchDialog(
        title = title,
        sources = sources,
        onDismiss = onDismiss,
    ) { entry ->
        androidx.compose.material3.TextButton(
            onClick = { onToggle(entry.source) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = entry.displayTitle, modifier = Modifier.weight(1f))
            if (entry.source.getStableIdentityKey() in selectedSourceKeys) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        }
    }
}

@Composable
internal fun SourceSearchDialog(
    title: String,
    sources: List<ContentSource>,
    onDismiss: () -> Unit,
    rowContent: @Composable (SourceSearchEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(query) {
        delay(180)
        debouncedQuery = query
    }

    val sourceEntries = remember(sources, context) {
        sources.mapIndexed { index, source ->
            val displayTitle = source.getEntityOrganizeDisplayTitle(context)
            SourceSearchEntry(
                stableKey = buildSourceEntryKey(source, displayTitle, index),
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
                    entry.normalizedName.contains(normalizedQuery) || entry.normalizedTitle.contains(normalizedQuery)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surface,
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

                SearchPillTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_sources),
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.stableKey },
                        contentType = { "source_option" },
                    ) { entry ->
                        rowContent(entry)
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.nothing_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

