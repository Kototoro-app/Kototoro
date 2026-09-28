package org.skepsun.kototoro.migration.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.migration.domain.MatchMode
import org.skepsun.kototoro.migration.domain.MigrationDataFlag

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MigrationConfigSheet(
    ids: LongArray,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: MigrationConfigViewModel = hiltViewModel(key = "migration-config-${ids.contentHashCode()}"),
) {
    LaunchedEffect(ids) { viewModel.load(ids) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingFamily by remember { mutableStateOf<ContentTypeFamily?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val family = editingFamily?.let { f -> state.families.firstOrNull { it.family == f } }
        if (family != null) {
            SourcePicker(
                family = family,
                onToggle = { viewModel.toggleSource(family.family, it) },
                onPreset = { viewModel.selectPreset(family.family, it) },
                onDone = { editingFamily = null },
            )
        } else {
            ConfigContent(
                state = state,
                viewModel = viewModel,
                onEdit = { editingFamily = it },
                onStart = {
                    viewModel.save()
                    onStart()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigContent(
    state: MigrationConfigState,
    viewModel: MigrationConfigViewModel,
    onEdit: (ContentTypeFamily) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(stringResource(R.string.migration_config_title, state.count), style = MaterialTheme.typography.titleLarge)
        if (state.originSourceNames.isNotEmpty()) {
            Text(
                text = state.originSourceNames.joinToString("、") { ContentSource(it).getTitle(context) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.families.forEach { family ->
            SectionLabel(stringResource(R.string.migration_config_sources)) {
                TextButton(onClick = { onEdit(family.family) }) { Text(stringResource(R.string.migration_config_edit)) }
            }
            if (family.selected.isEmpty()) {
                Text(stringResource(R.string.migration_pick_source), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                family.selected.take(MAX_VISIBLE_SOURCES).forEachIndexed { index, name ->
                    AssistChip(
                        onClick = { onEdit(family.family) },
                        label = { Text("${index + 1}  ${ContentSource(name).getTitle(context)}") },
                    )
                }
                val rest = family.selected.size - MAX_VISIBLE_SOURCES
                if (rest > 0) AssistChip(onClick = { onEdit(family.family) }, label = { Text("+$rest") })
            }
        }
        SectionLabel(stringResource(R.string.migration_config_data))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MigrationDataFlag.entries.forEach { flag ->
                FilterChip(
                    selected = flag in state.flags,
                    enabled = flag != MigrationDataFlag.CATEGORIES,
                    onClick = { viewModel.toggleFlag(flag) },
                    label = { Text(stringResource(flag.titleRes())) },
                )
            }
        }
        SectionLabel(stringResource(R.string.migration_config_match))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MatchMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.matchMode == mode,
                    onClick = { viewModel.setMatchMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, MatchMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            if (mode == MatchMode.FIRST_HIT) R.string.migration_match_first_hit else R.string.migration_match_most_chapters,
                        ),
                    )
                }
            }
        }
        TextButton(onClick = { showMore = !showMore }, modifier = Modifier.padding(top = 4.dp)) {
            Text((if (showMore) "▾ " else "▸ ") + stringResource(R.string.migration_config_more))
        }
        AnimatedVisibility(showMore) {
            Column {
                OutlinedTextField(
                    value = state.extraQuery,
                    onValueChange = viewModel::setExtraQuery,
                    singleLine = true,
                    label = { Text(stringResource(R.string.migration_extra_query)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.migration_deep_search), state.deepSearch, viewModel::setDeepSearch,
                    stringResource(R.string.migration_deep_search_summary))
                SwitchRow(stringResource(R.string.migration_hide_unmatched), state.hideUnmatched, viewModel::setHideUnmatched)
                SwitchRow(stringResource(R.string.migration_hide_without_updates), state.hideWithoutUpdates, viewModel::setHideWithoutUpdates)
                SwitchRow(stringResource(R.string.migration_duplicate_check), state.duplicateCheck, viewModel::setDuplicateCheck)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.migration_start))
        }
    }
}

@Composable
private fun SourcePicker(
    family: FamilySources,
    onToggle: (String) -> Unit,
    onPreset: (SourcePreset) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.migration_sources_picker_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.migration_sources_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourcePreset.entries.forEach { preset ->
                AssistChip(
                    onClick = { onPreset(preset) },
                    label = {
                        Text(
                            stringResource(
                                when (preset) {
                                    SourcePreset.ALL -> R.string.migration_select_all
                                    SourcePreset.PINNED -> R.string.migration_select_pinned
                                    SourcePreset.ENABLED -> R.string.migration_select_enabled
                                    SourcePreset.NONE -> R.string.migration_select_none
                                },
                            ),
                        )
                    },
                )
            }
        }
        LazyColumn {
            items(family.available, key = { it.name }) { source ->
                val order = family.selected.indexOf(source.name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(source.name) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = order >= 0, onCheckedChange = { onToggle(source.name) })
                    ContentSourceIcon(source = source, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(source.getTitle(context), modifier = Modifier.weight(1f))
                    if (order >= 0) Badge { Text("${order + 1}") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun MigrationDataFlag.titleRes(): Int = when (this) {
    MigrationDataFlag.CATEGORIES -> R.string.migration_flag_categories
    MigrationDataFlag.PROGRESS -> R.string.migration_flag_progress
    MigrationDataFlag.TRACKING -> R.string.migration_flag_tracking
    MigrationDataFlag.NOTES -> R.string.migration_flag_notes
    MigrationDataFlag.STATS -> R.string.migration_flag_stats
}

private const val MAX_VISIBLE_SOURCES = 3
