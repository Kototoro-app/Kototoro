package org.skepsun.kototoro.dictionary

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.settings.DictionaryRuleEditorDialog
import org.skepsun.kototoro.core.ui.settings.DictionaryRuleUiModel
import org.skepsun.kototoro.core.ui.settings.DictionaryRulesContent

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
                    IconButton(onClick = onBack, modifier = Modifier.tvIconButtonRing()) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onImport, modifier = Modifier.tvIconButtonRing()) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.import_button))
                    }
                    IconButton(onClick = onExport, modifier = Modifier.tvIconButtonRing()) {
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
        DictionaryRulesContent(
            rules = rules.map(DictionaryRule::toUiModel),
            onEdit = { item -> editingRule = rules.first { it.name == item.name }; showEditor = true },
            onToggle = { item, enabled -> onToggle(rules.first { it.name == item.name }, enabled) },
            onDelete = { item -> onDelete(rules.first { it.name == item.name }) },
            modifier = Modifier.padding(paddingValues),
        )
    }

    if (showEditor) {
        DictionaryRuleEditorDialog(
            initial = editingRule?.toUiModel(),
            onDismiss = { showEditor = false },
            onSave = {
                onSave(DictionaryRule(it.name, it.urlRule, it.showRule, it.enabled, it.sortNumber.toIntOrNull() ?: 0))
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

private fun DictionaryRule.toUiModel() = DictionaryRuleUiModel(name, urlRule, showRule, enabled, sortNumber.toString())
