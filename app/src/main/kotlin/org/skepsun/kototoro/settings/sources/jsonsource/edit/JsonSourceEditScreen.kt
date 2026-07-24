package org.skepsun.kototoro.settings.sources.jsonsource.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonSourceEditScreen(
    source: SourceEditData,
    isEdit: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSourceChange: (SourceEditData) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(if (isEdit) R.string.edit_source else R.string.add_source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(context.getString(R.string.basic_info))
            EditField(context.getString(R.string.source_name), source.name) {
                onSourceChange(source.copy(name = it))
            }
            EditField(context.getString(R.string.source_url), source.url) {
                onSourceChange(source.copy(url = it))
            }
            EditField(context.getString(R.string.source_group), source.group.orEmpty()) {
                onSourceChange(source.copy(group = it.takeIf(String::isNotBlank)))
            }
            RowSwitch(context.getString(R.string.enabled), source.enabled) {
                onSourceChange(source.copy(enabled = it))
            }
            Text(context.getString(R.string.rules))
            EditField(context.getString(R.string.search_url), source.searchUrl.orEmpty(), minLines = 2) {
                onSourceChange(source.copy(searchUrl = it.takeIf(String::isNotBlank)))
            }
            EditField(context.getString(R.string.explore_url), source.exploreUrl.orEmpty(), minLines = 2) {
                onSourceChange(source.copy(exploreUrl = it.takeIf(String::isNotBlank)))
            }
            TextButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(context.getString(R.string.save))
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        singleLine = minLines == 1,
    )
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
private fun JsonSourceEditScreenPreview() {
    KototoroTheme {
        JsonSourceEditScreen(
            source = SourceEditData("Example", "https://example.com", "小说", null, null, true),
            isEdit = true,
            onBack = {},
            onSave = {},
            onSourceChange = {},
        )
    }
}
