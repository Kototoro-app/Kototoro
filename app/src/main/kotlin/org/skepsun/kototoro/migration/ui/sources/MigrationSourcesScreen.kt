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
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
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
                Text(health.source.migrationTitle(context), fontWeight = FontWeight.SemiBold)
                StatusTag(health.status)
            }
            statusSummary(health)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun statusSummary(health: SourceHealth): String? = when (health.status) {
    SourceHealthStatus.UNINSTALLED -> stringResource(R.string.migration_health_uninstalled_summary)
    SourceHealthStatus.BROKEN -> stringResource(R.string.migration_health_broken_summary)
    SourceHealthStatus.FAILING -> health.errorSummary
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
    var selected by remember(source.source.name) { mutableStateOf(source.contentIds.toSet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(source.source.migrationTitle(context), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Checkbox(
                checked = selected.size == source.contentIds.size,
                onCheckedChange = { selected = if (it) source.contentIds.toSet() else emptySet() },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(source.contentIds, key = { it }) { id ->
                val row = state.rowsById[id] ?: return@items
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (id in selected) selected - id else selected + id }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                    Text(row.title, modifier = Modifier.weight(1f))
                    Text("${row.chaptersCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = { onNext(selected.toLongArray()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(stringResource(R.string.migration_next)) }
    }
}
