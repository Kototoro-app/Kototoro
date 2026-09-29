package org.skepsun.kototoro.migration.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.migration.ui.rememberCoverRequest
import org.skepsun.kototoro.migration.ui.duplicates.readingLabel
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.migration.domain.RefreshError
import org.skepsun.kototoro.migration.domain.SourceHealth
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold

@Composable
fun MigrationSourcesScreen(
    state: MigrationSourcesState,
    onNavigateUp: () -> Unit,
    onMigrate: (LongArray) -> Unit,
) {
    var picking by remember { mutableStateOf<SourceHealth?>(null) }
    SettingsTopBarScaffold(title = stringResource(R.string.migration_sources_title), onNavigateUp = onNavigateUp) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SettingsTopBarScaffold
        }
        if (state.sources.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.migration_no_sources),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SettingsTopBarScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.attention.isNotEmpty()) {
                item {
                    AttentionBanner(
                        sourceCount = state.attention.size,
                        contentCount = state.attentionIds.size,
                        onMigrate = { onMigrate(state.attentionIds) },
                    )
                }
                item { GroupHeader(stringResource(R.string.migration_health_needs_attention)) }
                items(state.attention, key = { it.source.name }) { SourceRow(it) { picking = it } }
            }
            if (state.healthy.isNotEmpty()) {
                item { GroupHeader(stringResource(R.string.migration_health_ok)) }
                items(state.healthy, key = { it.source.name }) { SourceRow(it) { picking = it } }
            }
        }
    }
    picking?.let { source ->
        SourceContentPicker(
            source = source,
            state = state,
            onNext = { ids ->
                picking = null
                onMigrate(ids)
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun AttentionBanner(sourceCount: Int, contentCount: Int, onMigrate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                pluralStringResource(R.plurals.migration_health_banner, sourceCount, sourceCount, contentCount),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onMigrate,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.migration_health_banner_action, contentCount)) }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(health: SourceHealth, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContentSourceIcon(source = health.source, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    health.siteHint ?: health.source.migrationTitle(context),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusTag(health.status)
            }
            statusSummary(health)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("${health.favouriteCount}", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusTag(status: SourceHealthStatus) {
    val (label, bg, fg) = when (status) {
        SourceHealthStatus.UNINSTALLED -> Triple(R.string.migration_health_uninstalled, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SourceHealthStatus.BROKEN -> Triple(R.string.migration_health_broken, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SourceHealthStatus.FAILING -> Triple(R.string.migration_health_failing, Color(0xFFFFF1D6), Color(0xFF8A5A00))
        SourceHealthStatus.DISABLED -> Triple(R.string.migration_health_disabled, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        SourceHealthStatus.HEALTHY -> return
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 6.dp)) {
        Text(
            stringResource(label),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun refreshErrorText(error: RefreshError?): String? = when (error) {
    null -> null
    RefreshError.Challenge -> stringResource(R.string.migration_error_challenge)
    is RefreshError.Http -> stringResource(R.string.migration_error_http, error.code)
    RefreshError.Parse -> stringResource(R.string.migration_error_parse)
    RefreshError.Network -> stringResource(R.string.migration_error_network)
    is RefreshError.Other -> error.message
}

@Composable
private fun statusSummary(health: SourceHealth): String? = when (health.status) {
    SourceHealthStatus.UNINSTALLED -> stringResource(R.string.migration_health_uninstalled_summary) +
        (health.siteHint?.let { " · ${health.source.name}" } ?: "")
    SourceHealthStatus.BROKEN -> stringResource(R.string.migration_health_broken_summary)
    SourceHealthStatus.FAILING -> refreshErrorText(RefreshError.parse(health.errorSummary))
    SourceHealthStatus.DISABLED -> stringResource(R.string.migration_health_disabled_summary)
    SourceHealthStatus.HEALTHY -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceContentPicker(
    source: SourceHealth,
    state: MigrationSourcesState,
    onNext: (LongArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val total = source.contentIds.size
    var selected by remember(source.source.name) { mutableStateOf(source.contentIds.toSet()) }
    val allSelected = selected.size == total
    // Floating glass sheet sized to its content, like the migration config sheet.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        KototoroSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = GlassDefaults.prominentStyle().copy(containerAlpha = 0.8f, minimumContainerAlpha = 0.6f),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContentSourceIcon(source = source.source, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                source.siteHint ?: source.source.migrationTitle(context),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            StatusTag(source.status)
                        }
                        Text(
                            stringResource(R.string.migration_selected_of, selected.size, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { selected = if (allSelected) emptySet() else source.contentIds.toSet() }) {
                        Text(stringResource(if (allSelected) R.string.migration_select_none else R.string.migration_select_all))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(source.contentIds, key = { it }) { id ->
                        val row = state.rowsById[id] ?: return@items
                        val checked = id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (checked) selected - id else selected + id }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = rememberCoverRequest(row.coverUrl, source.source),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(40.dp).aspectRatio(13f / 18f).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    readingLabel(row),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + id else selected - id })
                        }
                    }
                }
                Button(
                    onClick = { onNext(selected.toLongArray()) },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(R.string.migration_next_with_count, selected.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
