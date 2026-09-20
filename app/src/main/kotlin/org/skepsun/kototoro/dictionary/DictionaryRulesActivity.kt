package org.skepsun.kototoro.dictionary

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class DictionaryRulesActivity : BaseComposeActivity() {

    private val viewModel by viewModels<DictionaryRuleSettingsViewModel>()
    private var exportJson: String? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launchWhenStarted {
            val raw = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!raw.isNullOrBlank()) viewModel.import(raw)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val raw = exportJson ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launchWhenStarted {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(raw) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val rules by viewModel.rules.collectAsStateWithLifecycle()
            DictionaryRulesScreen(
                rules = rules,
                onBack = ::finish,
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onExport = {
                    viewModel.export { json ->
                        exportJson = json
                        exportLauncher.launch("kototoro-dictionary-rules.json")
                    }
                },
                onSave = viewModel::save,
                onDelete = viewModel::delete,
                onToggle = viewModel::setEnabled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryRulesScreen(
    rules: List<DictionaryRule>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSave: (DictionaryRule) -> Unit,
    onDelete: (DictionaryRule) -> Unit,
    onToggle: (DictionaryRule, Boolean) -> Unit,
) {
    var editingRule by remember { mutableStateOf<DictionaryRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dictionary_rules)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.import_button))
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.export))
                    }
                },
            )
        },
        floatingActionButton = {
            SmallFloatingActionButton(onClick = {
                editingRule = null
                showEditor = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { paddingValues ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.dictionary_rules_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.name }) { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            editingRule = rule
                            showEditor = true
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = rule.urlRule,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { onToggle(rule, it) },
                            )
                            IconButton(onClick = { onDelete(rule) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        DictionaryRuleEditorDialog(
            initialRule = editingRule,
            onDismiss = { showEditor = false },
            onSave = {
                onSave(it)
                showEditor = false
            },
            onDelete = editingRule?.let { rule ->
                {
                    onDelete(rule)
                    showEditor = false
                }
            },
        )
    }
}

@Composable
private fun DictionaryRuleEditorDialog(
    initialRule: DictionaryRule?,
    onDismiss: () -> Unit,
    onSave: (DictionaryRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val defaultRule = remember(initialRule?.name) {
        initialRule ?: DictionaryRule(name = "", urlRule = "")
    }
    var name by remember(defaultRule.name) { mutableStateOf(defaultRule.name) }
    var urlRule by remember(defaultRule.name) { mutableStateOf(defaultRule.urlRule) }
    var showRule by remember(defaultRule.name) { mutableStateOf(defaultRule.showRule) }
    var enabled by remember(defaultRule.name) { mutableStateOf(defaultRule.enabled) }
    var sortNumber by remember(defaultRule.name) { mutableStateOf(defaultRule.sortNumber.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialRule == null) {
                    stringResource(R.string.dictionary_rule_add_title)
                } else {
                    stringResource(R.string.dictionary_rule_edit_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(
                    urlRule,
                    { urlRule = it },
                    label = { Text(stringResource(R.string.dictionary_rule_url_label)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    showRule,
                    { showRule = it },
                    label = { Text(stringResource(R.string.dictionary_rule_show_label)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    sortNumber,
                    { sortNumber = it },
                    label = { Text(stringResource(R.string.sort)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text(stringResource(R.string.enable))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        DictionaryRule(
                            name = name.trim(),
                            urlRule = urlRule.trim(),
                            showRule = showRule,
                            enabled = enabled,
                            sortNumber = sortNumber.toIntOrNull() ?: 0,
                        ),
                    )
                },
                enabled = name.isNotBlank() && urlRule.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
