package org.skepsun.kototoro.explore.ui.preset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.toLocale
import org.skepsun.kototoro.explore.data.SourcePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePresetListScreen(
    presets: List<SourcePreset>,
    activePresetId: Long,
    sourceCount: (SourcePreset) -> Int,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (SourcePreset) -> Unit,
    onEdit: (SourcePreset) -> Unit,
    onDelete: (SourcePreset) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SourcePreset?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.source_presets)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(presets, key = { it.id }) { preset ->
                PresetRow(preset, activePresetId == preset.id, sourceCount(preset), onSelect, onEdit) {
                    pendingDelete = preset
                }
            }
        }
    }
    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(preset.title) },
            text = { Text(stringResource(R.string.remove)) },
            confirmButton = { TextButton(onClick = { onDelete(preset); pendingDelete = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun PresetRow(
    preset: SourcePreset,
    active: Boolean,
    sourceCount: Int,
    onSelect: (SourcePreset) -> Unit,
    onEdit: (SourcePreset) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val languages = preset.languages.map { it.toLocale().getDisplayName(context) }
    val count = pluralStringResource(R.plurals.source_count, sourceCount, sourceCount)
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(preset) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = { onSelect(preset) })
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(preset.title)
            Text(if (languages.isEmpty()) count else "${languages.joinToString()} — $count")
        }
        IconButton(onClick = { onEdit(preset) }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun SourcePresetListScreenPreview() {
    KototoroTheme {
        SourcePresetListScreen(emptyList(), 0, { 0 }, {}, {}, {}, {}, {})
    }
}
